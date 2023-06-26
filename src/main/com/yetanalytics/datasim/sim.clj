(ns com.yetanalytics.datasim.sim
  "Given input, compose a simulation model"
  (:require
   [clojure.spec.alpha :as s]
   [clojure.core.async :as a]
   [clojure.core.async.impl.protocols :as ap]
   [java-time :as t]
   [xapi-schema.spec :as xs]
   [com.yetanalytics.datasim.timeseries :as ts]
   [com.yetanalytics.datasim.xapi :as xapi]
   [com.yetanalytics.datasim.xapi.profile :as p]
   [com.yetanalytics.datasim.xapi.activity :as activity]
   [com.yetanalytics.datasim.xapi.statement :as statement]
   [com.yetanalytics.datasim.util.xapi :as xapiu]
   [com.yetanalytics.datasim.util.maths :as maths]
   [com.yetanalytics.datasim.util.sequence :as su]
   [com.yetanalytics.datasim.util.async :as au]
   [com.yetanalytics.datasim.random :as random])
  (:import [java.time ZoneRegion]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Specs
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; "skeleton" is a map of agent ids to maps with setup info for the agent.

;; A tuple of [timestamp probability]
(def prob-seq-moment-spec
  (s/tuple pos-int?
           (s/double-in
            :min 0.0
            :max 1.0
            :infinite? false
            :NaN? false)))

(s/def ::probability-seq
  (s/every prob-seq-moment-spec))

(s/def ::seed
  int?)

(s/def ::registration-seq
  (s/every ::p/registration-map))

;; Based on the probability of activity at a given minute, and an infinite seq
;; of profile walks, emit statements for one actor
(s/def :skeleton/statement-seq
  (s/every ::xs/statement :kind #(instance? clojure.lang.LazySeq %)))

(s/def ::skeleton
  (s/map-of ::xapi/agent-id
            :skeleton/statement-seq))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Statement Sequence
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def min-ms 60000) ; The amount of milliseconds in one minute

(defn- actual-start-time
  "Set the actual time of the generated Statement to a time somwhere between
   `time-ms` and `time-ms + 1 min`, rather than always at `time-ms`.
   
   This is for additional spiciness :)"
  [time-ms rng]
  (long (+ time-ms (random/rand-int* rng min-ms))))

(defn- drop-time-probs
  "Given `prob-seq` consisting of `[time-ms prob]` pairs, drop the first couple
   of pairs; `prob` is the probability that on a given pair the dropping
   stops and `[[time-ms prob] & rest]` is returned."
  [prob-seq rng]
  (->> prob-seq
       (drop-while
        (fn [[_time-ms prob]]
          (or
           ;; micro-optimization - don't bother with rng if `prob` is 0
           (zero? prob)
           ;; choose `minutes` with probability `prob`
           (>= (random/rand* rng) prob))))
       not-empty))

(defn- drop-past-time-probs
  "Drop all `[time prob]` pairs where `time` occurs before `end-ms`."
  [prob-seq end-ms]
  (drop-while
   (fn [[time-ms _prob]] (< time-ms end-ms))
   prob-seq))

(s/fdef statement-seq
  :args (s/cat :inputs (s/keys :req-un [::statement/type-iri-map
                                        ::statement/activity-map
                                        ::statement/statement-base-map
                                        ::statement/parsed-rules-map
                                        ::statement/actor
                                        ::statement/alignment])
               :probability-seq  ::probability-seq
               :registration-seq ::registration-seq
               :seed             ::seed)
  :ret :skeleton/statement-seq)

(defn- statement-seq
  "Return a lazy sequence of generated Statements; generation ends once
   `probability-seq` is exhausted."
  [inputs probability-seq registration-seq seed]
  (let [time-rng (random/seed-rng seed)
        ;; time-ms -> start-ms -> <statement generator> -> end-ms
        ;; the sequence should resume after end-ms
        statement-seq*
        (fn statement-seq* [prob-seq reg-seq]
          (lazy-seq
           (when-some [[[time-ms _] & rest-prob-seq]
                       (drop-time-probs prob-seq time-rng)]
             (let [start-ms  (actual-start-time time-ms time-rng)
                   input-map (merge inputs
                                    (first reg-seq)
                                    {:sim-t start-ms})
                   statement (statement/generate-statement input-map)
                   end-ms    (:end-ms (meta statement))]
               (cons statement
                     (statement-seq*
                      (drop-past-time-probs rest-prob-seq end-ms)
                      (rest reg-seq)))))))]
    (statement-seq* probability-seq registration-seq)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Skeleton
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Data structure helpers

(defn- personaes->group-actor-id-map
  "Convert `personae-array` into a map from group IDs, which represent
   each personae in the array, to actor IDs, representing each group member."
  [personae-array]
  (reduce
   (fn [m {actors :member :as personae}]
     (let [group-id (xapiu/agent-id personae)]
       (reduce
        (fn [m* actor]
          (assoc m* (xapiu/agent-id actor) group-id))
        m
        actors)))
   {}
   personae-array))

(defn- update-alignment
  [{existing-count  :count
    existing-weight :weight}
   {new-weight   :weight
    obj-override :objectOverride}]
  (let [count  (inc existing-count)
        weight (-> (* existing-count existing-weight)
                   (+ new-weight)
                   (/ count))]
    {:weight          weight
     :count           count
     :object-override obj-override}))

(defn- get-actor-alignments
  "Return `alignments` as a map from the component IDs to their alignment
   data, i.e. a map of `:weight`, `:count`, and `:object-override`. Only
   alignments that contain `actor-id`, `group-id`, or `role` will be
   included in the returned map."
  [alignments actor-id group-id role]
  (let [actor-alignment-ids (set [actor-id group-id role])]
    (reduce (fn [alignment-map {component-iri :component :as alignment}]
              (update alignment-map
                      component-iri
                      (fnil update-alignment {:weight 0.0 :count 0})
                      alignment))
            {}
            (for [{alignment-maps :alignments
                   alignment-id   :id} alignments
                  :when (actor-alignment-ids alignment-id)
                  alignment alignment-maps]
              alignment))))

;; Timestamp helpers

(defn- timezone->region ^ZoneRegion [tz]
  (t/zone-id tz))

(defn- timestamp->millis [ts]
  (.toEpochMilli (t/instant ts)))

(defn- drop-statements-from-time
  "Drop any `statements` whose `:timestamp-ms` metadata comes after
   `from-ms`."
  [from-ms statements]
  (drop-while
   (fn [statement]
     (>= from-ms (-> statement meta :timestamp-ms)))
   statements))

;; Time/probability sequence helpers

;; Right now we are using common ARMA settings, this may change
(def common-arma
  {:phi   [0.5 0.2]
   :theta []
   :std   0.25
   :c     0.0})

(defn- arma-seq [seed]
  (ts/arma-seq (assoc common-arma :seed seed)))

(defn- lunch-hour-seq
  "Map `minute-of-day-seq` into a sequence of `1.0`, if the corresponding
   minute of the day is between 12:00 and 13:00 (the \"lunch hour\"),
   and `-1.0`, if it is any other time."
  [minute-of-day-seq]
  (map (fn [min-of-day]
         (if (<= 720 min-of-day 780) 1.0 -1.0))
       minute-of-day-seq))

(defn- arma-time-seqs->prob-mask-seq
  "Derive a lazy sequence of probability mask values for actor events.
   
   The mask seq can be plotted as a time series. One will see a
   generally-sinusoidal value, as `day-night-seq` is sinusoidal,
   with the highest y-value during the night and lowest during the day;
   random variation is introduced by `group-arma-seq` and the y-value is
   fixed at `1.0` during the \"lunch hour\" (12:00 to 13:00)
   thanks to `(lunch-hour-seq min-of-day-seq)`.
   
   These values will be inverted since each mask value will be subtracted
   from each value in `actor-arma-seq` in order to form each actor
   event probability."
  [arma-seq day-night-seq min-of-day-seq]
  (map max
       arma-seq
       day-night-seq
       (lunch-hour-seq min-of-day-seq)))

(defn- arma-mask-seqs->prob-seq
  "Subtract each value of `arma-seq` by its respective `prob-mask-seq`
   value, then use that value to derive a probability value in `[0,1]`.
   Note that a higher `prob-mask-seq` value will result in a lower probability."
  [arma-seq prob-mask-seq]
  (let [clamp-probability #(maths/min-max 0.0 % 1.0)]
    (map (fn [arma-val prob-mask-val]
           (-> (- arma-val prob-mask-val) ; higher mask val -> lower prob
               (/ 2) ; decrease general range from [-1, 1] to [-0.5, 0.5]
               clamp-probability
               double))
         arma-seq
         prob-mask-seq)))

(s/fdef build-skeleton
  :args (s/cat :input :com.yetanalytics.datasim/input)
  :ret ::skeleton)

(defn build-skeleton
  "Given simulation input, return a skeleton with statement sequences per
   actor from `start` of sim. Should be run once (in a single thread).
  
   Spooky."
  [{:keys [profiles personae-array parameters alignments]
    :as   input}]
  (let [;; Input parameters and alignments
        {:keys [start end timezone seed] ?from-stamp :from} parameters
        {alignments :alignment-vector} alignments
        ;; RNG for generating the rest of the seeds
        ;; TODO: Switch `.nextLong` calls to `random/rand-int` or other fn
        sim-rng     (random/seed-rng seed)
        ;; Set timezone region and timestamps
        zone-region (timezone->region timezone)
        t-start     (timestamp->millis start)
        ?t-from     (some-> ?from-stamp timestamp->millis)
        ?t-end      (some-> end timestamp->millis)
        ?sample-ms  (some-> ?t-end (- t-start))
        ;; Derive the actor event probability mask sequence.
        {:keys
         [minute-ms-seq
          minute-of-day-seq
          minute-day-night-seq]} (ts/time-seqs :t-zero t-start
                                               :sample-ms ?sample-ms
                                               :zone zone-region)
        mask-arma-seed  (.nextLong sim-rng)
        mask-arma-seq   (arma-seq mask-arma-seed)
        prob-mask-seq   (arma-time-seqs->prob-mask-seq mask-arma-seq
                                                       minute-day-night-seq
                                                       minute-of-day-seq)
        ;; Derive actor, activity, and profile object colls and maps
        actor-seq       (apply concat (map :member personae-array))
        actor-group-map (personaes->group-actor-id-map personae-array)
        activity-seed   (.nextLong sim-rng)
        activity-map    (activity/derive-cosmos input activity-seed)
        type-iri-map    (-> (p/profiles->type-iri-map profiles)
                            (p/select-primary-patterns parameters))
        ;; Pre-parse templates into statement bases and rules, as a
        ;; form of optimization
        template-base-m (p/profiles->base-statement-map profiles)
        template-rule-m (p/profiles->parsed-rule-map profiles
                                                     type-iri-map
                                                     activity-map)

        ;; Generate statement base inputs
        base-input-map  {:type-iri-map       type-iri-map
                         :activity-map       activity-map
                         :statement-base-map template-base-m
                         :parsed-rules-map   template-rule-m}]
    ;; Now, for each actor we initialize what is needed for the sim
    (->> actor-seq
         (sort-by xapiu/agent-id)
         (reduce
          (fn [m actor]
            (let [;; Actor basics + alignment
                  actor-id        (xapiu/agent-id actor)
                  actor-role      (:role actor)
                  actor-group-id  (get actor-group-map actor-id)
                  actor-alignment (get-actor-alignments alignments
                                                        actor-id
                                                        actor-group-id
                                                        actor-role)
                  ;; Actor probability seq
                  actor-arma-seed (.nextLong sim-rng)
                  actor-arma-seq  (arma-seq actor-arma-seed)
                  actor-prob-seq* (arma-mask-seqs->prob-seq actor-arma-seq
                                                            prob-mask-seq)
                  actor-prob-seq  (map vector minute-ms-seq actor-prob-seq*)
                  ;; Actor registration seq
                  actor-reg-seed  (.nextLong sim-rng)
                  actor-reg-seq   (p/registration-seq type-iri-map
                                                      actor-alignment
                                                      actor-reg-seed)
                  ;; Additional seed for further gen
                  actor-seed      (.nextLong sim-rng)
                  ;; Dissoc `:role` since it is not an xAPI property
                  actor-xapi      (dissoc actor :role)
                  ;; Statement seq
                  actor-input     (merge base-input-map
                                         {:actor     actor-xapi
                                          :alignment actor-alignment})
                  actor-stmt-seq  (cond->> (statement-seq
                                            actor-input
                                            actor-prob-seq
                                            actor-reg-seq
                                            actor-seed)
                                    ?t-from
                                    (drop-statements-from-time ?t-from))]
              (assoc m actor-id actor-stmt-seq)))
          {}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Statement Sequence Simulation (Sync)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::select-agents
  (s/every ::xapi/agent-id))

(s/fdef sim-seq
  :args (s/cat :input :com.yetanalytics.datasim/input
               :options (s/keys*
                         :opt-un [::select-agents]))
  :ret :skeleton/statement-seq)

(defn sim-seq
  "Given input, build a skeleton and produce a seq of statements."
  [{{?max-statements :max} :parameters :as input}
   & {:keys [select-agents]}]
  (let [skeleton (cond-> (build-skeleton input)
                   select-agents
                   (select-keys select-agents))]
    (cond->> (->> skeleton vals (su/seq-sort (comp :timestamp-ms meta)))
      ?max-statements (take ?max-statements))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Statement Sequence Simulation (Async)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- chan?
  [x]
  (satisfies? ap/Channel x))

;; simulate multiple channels

(s/def ::pad-chan-max
  pos-int?)

(s/fdef sim-chans
  :args (s/cat :input :com.yetanalytics.datasim/input
               :options (s/keys*
                         :opt-un [::select-agents
                                  ::pad-chan-max]))
  :ret (s/map-of ::xapi/agent-id
                 chan?))

(defn sim-chans
  "Given input, build a skeleton and produce a map from agent IFIs to
   agent statement simulation channels.

   Uses the `core.async` thread pool for concurrency.

   Note that the `:max` parameter is used as a quotient and may
   have unexpected results if it is zero. The `:end` parameter is preferable.
   
   The `:max` parameter is divided by the number of agents in the simulation.
   Thus `pad-chan-max` is provided as a kwarg so we can add that amount to
   the length of each channel's statement seq - either a little bit to get over
   `:max`, or a lot to account for an imbalance in activity at the tail end
   of the simulation."
  [{{?max-statements :max} :parameters :as input}
   & {:keys [select-agents
             pad-chan-max]
      :or {pad-chan-max 1}}]
  (let [skeleton (cond-> (build-skeleton input)
                   select-agents
                   (select-keys select-agents))
        ?take-n  (when ?max-statements ; TODO: Handle division by zero error
                   (->> (count skeleton)
                        (quot ?max-statements)
                        (+ pad-chan-max)))]
    ;; TODO: Use reduce-vals after updating to Clojure 1.11
    (reduce-kv
     (fn [m k agent-seq]
       (assoc m k (cond->> (a/to-chan! agent-seq)
                    ?take-n
                    (a/take ?take-n))))
     (empty skeleton)
     skeleton)))

;; simulate single channel

(defn- compare-timestamp-ms-meta
  [stmt-1 stmt-2]
  (compare
   (-> stmt-1 meta :timestamp-ms)
   (-> stmt-2 meta :timestamp-ms)))

(s/def ::sort boolean?)
(s/def ::buffer-size pos-int?)

(s/fdef sim-chan
  :args (s/cat :input :com.yetanalytics.datasim/input
               :options (s/keys*
                         :opt-un [::select-agents
                                  ::pad-chan-max
                                  ::sort
                                  ::buffer-size]))
  :ret chan?)

(defn sim-chan
  "Merged output of `sim-chans` for parallel generation."
  [input
   & {:keys [sort buffer-size]
      :or {sort true
           buffer-size 100}
      :as kwargs}]
  (let [chan-map (apply sim-chans
                        input
                        (mapcat identity kwargs))
        chans    (vals chan-map)]
    (if sort
      (->> chans
           (au/sequence-messages (a/chan buffer-size)
                                 compare-timestamp-ms-meta))
      (-> chans
          (a/merge buffer-size)))))

(comment
  (require '[clojure.pprint :as pprint]
           '[com.yetanalytics.datasim.input :as input])
  
  (def input-1
    (input/from-location :input :json "dev-resources/input/simple.json")) 
  
  (->> input-1 :parameters pprint/pprint)

  ;; Build and examine skeleton output
  
  (def skel
    (time
     (build-skeleton input-1)))
  
  (->> skel (s/explain ::skeleton))
  (->> skel first second (take 10) pprint/pprint)

  ;; Perform and inspect parallel statement generation

  (let [agent-mbox "mbox::mailto:alicefaux@example.org"
        agent-chan (-> input-1 sim-chans (get agent-mbox))]
    (a/go-loop [cnt 0]
      (when-let [s (a/<! agent-chan)]
        (when (= 0
                 (mod cnt 10))
          (printf "\n%d statements\n\n" cnt)
          (println s))
        (recur (inc cnt)))))

  (def input-2
    (assoc-in input-1 [:parameters :end] "2021-01-01T00:00:00.000000Z"))

  (time
   (->> input-2 sim-chan (a/into []) a/<!! count))

  (time
   (->> input-2 sim-seq count)))

(comment
  (get-actor-alignments
   [{:id         "mbox::mailto:bob@example.org"
     :type       "Agent"
     :alignments [{:component "https://example.org/activity/a"
                   :weight    0.5}
                  {:component "https://example.org/activity/c"
                   :weight    -0.2}]}]
   "mbox::mailto:bob@example.org"
   "trainee"
   "Lead Developer")

  (reduce
   (fn [m {actors :member :as personae}]
     (let [group-id (:name personae)]
       (reduce
        (fn [m' actor] (assoc m' (xapiu/agent-id actor) group-id))
        m
        actors)))
   {}
   [{:name "trainee"
     :objectType "Group"
     :member [{:name "Bob Fakename"
               :mbox "mailto:bob@example.org"
               :role "Lead Developer"}
              {:name "Alice Faux"
               :mbox "mailto:alice@example.org"
               :role "Lead Developer"}]}])

  (xapiu/agent-id {:name "Bob Fakename"
                   :mbox "mailto:bob@example.org"
                   :role "Lead Developer"}))
