(ns com.yetanalytics.datasim.input.alignments
  "Alignment input specs and parsing."
  (:require [clojure.spec.alpha :as s]
            [clojure.walk       :as w]
            [xapi-schema.spec] ; need to bring in :statement/object
            [com.yetanalytics.datasim.protocols   :as p]
            [com.yetanalytics.datasim.iri         :as iri]
            [com.yetanalytics.datasim.xapi        :as xapi]
            [com.yetanalytics.datasim.util.errors :as errs]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Specs
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Alignment: Map of Component, Weight, and Object Override properties

;; Due to limitations of keywords, we cannot have IRI keys, limiting extensions
(defn- no-iri-keys?
  "Returns false if there exists a key made from an IRI, e.g.
   (name :https://foo.org) => \"/foo.org\""
  [obj]
  (cond
    (map? obj)
    (if-not (->> obj vals (map no-iri-keys?) (some false?))
      (->> obj keys (some (partial re-matches #".*/.*")) not)
      false)
    (vector? obj)
    (every? no-iri-keys? obj)
    :else
    true))

(s/def ::objectOverride
  (s/and (s/conformer w/stringify-keys w/keywordize-keys)
         no-iri-keys?
         :statement/object)) ; from xapi-schema

(s/def ::component
  iri/iri-spec)

(s/def ::weight
  (s/double-in :min -1.0 :max 1.0
               :infinite? false
               :NaN? false))

(s/def ::alignment
  (s/keys :req-un [::component
                   ::weight]
          :opt-un [::objectOverride]))

;; Actor-Alignment: Map of Actor ID, Actor Type, and collection of Alignments

(s/def ::id string?)

(s/def ::type #{"Agent" "Group" "Role"})

(s/def ::alignments (s/every ::alignment))

(defmulti actor-alignment? :type)

(defmethod actor-alignment? "Agent" [_]
  (fn [{agent-id :id}] (s/valid? ::xapi/agent-id agent-id)))

(defmethod actor-alignment? "Group" [_]
  (constantly true))

(defmethod actor-alignment? "Role" [_]
  (constantly true))

(s/def ::actor-alignment
  (s/and (s/keys :req-un [::id ::type ::alignments])
         (s/multi-spec actor-alignment? :type)))

;; Alignment-vector: Collection of Actor-Alignment

(s/def ::alignment-vector
  (s/every ::actor-alignment))

;; Alignment input
(s/def ::alignments-input
  (s/keys :req-un [::alignment-vector]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Validation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn validate-alignments
  [alignments]
  (some->> (s/explain-data ::alignments-input alignments)
           (errs/explain-to-map-coll ::alignments-input)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Record
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defrecord Alignments [alignment-vector]
  p/FromInput
  (validate [alignments]
    (validate-alignments alignments))

  p/JSONRepresentable
  (read-key-fn [_ k]
    (keyword (name k)))
  (read-body-fn [_ json-result]
    (map->Alignments {:alignment-vector (into [] json-result)}))
  (write-key-fn [_ k]
    (name k))
  (write-body-fn [_]
    alignment-vector))
