(ns com.yetanalytics.datasim.math.random
  "Random number generation and probabilistic operations."
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as sgen]
            [com.yetanalytics.datasim.util.maths :as maths])
  (:refer-clojure :exclude
                  [rand rand-int rand-nth random-sample random-uuid shuffle])
  (:import [java.util UUID Random]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Specs
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::seed int?)

(s/def ::rng
  (s/with-gen #(instance? Random %)
    (fn [] (sgen/fmap (fn [s] (Random. s)) (s/gen ::seed)))))

(s/def ::sd
  (s/double-in :min 0.0 :infinite? false :NaN? false))

(s/def ::prob
  (s/double-in :min 0.0 :max 1.0))

(s/def ::weight
  (s/double-in :min -1.0 :max 1.0))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; RNG Creation ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/fdef rng
  :args (s/cat)
  :ret ::rng)

(defn rng
  "Create a pseudorandom RNG using an arbitrary seed value."
  ^Random []
  (Random.))

(s/fdef seed-rng
  :args (s/cat :seed ::seed)
  :ret ::rng)

(defn seed-rng
  "Create a seeded deterministic, pseudorandom RNG, in which two RNGs created
   using the same value of `seed` will output the same results."
  ^Random [^Long seed]
  (Random. seed))

;; Random Number Generation ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/fdef rand
  :args (s/cat :rng ::rng
               :n (s/? number?))
  :ret double?)

(defn rand
  "Generate a pseudorando, uniformly distributed double value between 0 and
   `n` (both inclusive).
   
   See also: `clojure.core/rand`"
  (^Double [^Random rng]
   (.nextDouble rng))
  (^Double [^Random rng n]
   (* n (rand rng))))

(s/fdef rand-int
  :args (s/cat :rng ::rng
               :n (s/int-in Integer/MIN_VALUE Integer/MAX_VALUE))
  :ret int?)

(defn rand-int
  "Generate a pseudorandom, uniformly distributed integer value between 0
   (inclusive) and `n` (exclusive).
   
   See also: `clojure.core/rand-int`"
  [rng n]
  (long (rand rng n)))

(s/fdef rand-unbound-int
  :args (s/cat :rng ::rng)
  :ret int?)

(defn rand-unbound-int
  "Generate a pseudorandom, uniformly distributed integer value in the
   entire range of possible Java long/Clojure integer values."
  [^Random rng]
  (.nextLong rng))

(s/fdef rand-gaussian
  :args (s/cat :rng  ::rng
               :mean double?
               :sd   ::sd)
  :ret double?)

(defn rand-gaussian
  "Generate a pseudorandom, normally distributed double value with mean
   `mean` and standard deviation `sd`."
  [^Random rng mean sd]
  (+ mean (* sd (.nextGaussian rng))))

;; A boolean is just a Bernoulli-distribued random number (1 = true, 0 = false)

(s/fdef rand-boolean
  :args (s/cat :rng  ::rng
               :prob ::prob)
  :ret boolean?)

(defn rand-boolean
  "Generate a pseudorandom boolean value where `true` is returned with
   probability `prob` and `false` with probablility `1 - prob`. This
   function can be used to realize `prob`, and is equivalent to generating
   a Bernoulli-distributed value."
  [rng prob]
  (< (rand rng) prob))

;; Technically a UUID is also a number, right?

(s/fdef rand-uuid
  :args (s/cat :rng ::rng)
  :ret string?)

(defn rand-uuid
  "Generate a pseudorandom UUID (as a string)."
  [rng]
  ;; Derived from `clojure.test.check.generators/uuid`
  ;; We use decimal representations of bitmasks to avoid having to
  ;; coerce to unchecked longs
  (let [x1 (-> (rand-unbound-int rng)
               (bit-and -45057)                 ; 0xffffffffffff4fff
               (bit-or 0x4000))                 ; 0x0000000000004000
        x2 (-> (rand-unbound-int rng)
               (bit-or -9223372036854775808)    ; 0x8000000000000000
               (bit-and -4611686018427387905))] ; 0xbfffffffffffffff
    (.toString (UUID. x1 x2))))

;; Collection Functions ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- validate-not-empty
  [coll]
  (when (empty? coll)
    (throw (ex-info "Attempted to select elements from an empty collection!"
                    {:type ::empty-coll}))))

(s/fdef rand-nth
  :args (s/cat :rng  ::rng
               :coll (s/every any? :min-count 1))
  :ret any?)

(defn rand-nth
  "Randomly select an element from `coll`. Each element has an equal
   probability of being selected.
   
   Will throw an `::empty-coll` exception on an empty `coll`.
   
   See also: `clojure.core/rand-nth`"
  [rng coll]
  (validate-not-empty coll)
  (nth coll (rand-int rng (count coll))))

(s/fdef shuffle
  :args (s/cat :rng  ::rng
               :coll (s/every any?))
  :ret coll?
  :fn (fn [{{:keys [coll]} :args ret :ret}]
        (= (set coll) (set ret))))

(defn shuffle
  "Randomly shuffle `coll` and return a lazy sequence as the result.
   
   See also: `clojure.core/shuffle`"
  ([rng coll]
   (shuffle rng coll (count coll)))
  ([rng coll cnt]
   (lazy-seq
    (when (< 0 cnt)
      (let [[head [x & tail]] (split-at (rand-int rng cnt) coll)]
        (cons x
              (shuffle rng (concat head tail) (dec cnt))))))))

(s/fdef random-sample
  :args (s/cat :rng     ::rng
               :prob    ::prob
               :coll    (s/every any? :min-count 1)
               :weights (s/? (s/map-of any? ::weight)))
  :ret coll?)

(defn random-sample
  "Probabilistically sample elements from `coll`, where each element has
   `prob` probability of being selected. If `weights` are provided, then
   the element associated with a weight has `(+ prob weight)` probability
   (up to 1.0) of being selected. Returns a transducer when `coll` is not
   provided.
   
   See also: `clojure.core/random-sample`"
  ([rng prob]
   (filter (fn [_] (rand-boolean rng prob))))
  ([rng prob coll]
   (filter (fn [_] (rand-boolean rng prob)) coll))
  ([rng prob coll weights]
   (filter (fn [x]
             (let [prob* (maths/bound-probability (+ prob (get weights x 0.0)))]
               (rand-boolean rng prob*)))
           coll)))

(s/fdef choose
  :args (s/cat :rng     ::rng
               :weights (s/map-of any? (s/keys :req-un [::weight]))
               :coll    (s/every any? :min-count 1)
               :options (s/keys* :opt-un [::sd])))

(defn choose
  "Probabilistically select one element from `coll`. The `weights` map
   should be a map of `coll` elements to map with a `:weight` value."
  [rng weights coll & {:keys [sd] :or {sd 0.25}}]
  (validate-not-empty coll)
  (let [rand-gauss
        (fn [x]
          (let [weight (get-in weights [x :weight] 0.0)]
            (if (<= weight -1.0)
              -1.0
              (rand-gaussian rng (* sd weight) sd))))]
    (apply max-key rand-gauss coll)))

(s/fdef choose-map
  :args (s/cat :rng     ::rng
               :weights (s/map-of any? (s/keys :req-un [::weight]))
               :coll    (s/map-of any? any? :min-count 1)
               :options (s/keys* :opt-un [::sd])))

(defn choose-map
  "Probabilistically select one value from the map `m`. The `weights` map
   should be a map from the keys of `m` to their `:weight` maps."
  [rng weights m & {:keys [sd] :or {sd 0.25}}]
  (get m (choose rng weights (keys m) :sd sd)))
