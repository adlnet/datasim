(ns com.yetanalytics.datasim.input.personae-test
  (:require [clojure.test :refer [deftest testing is]]
            [com.yetanalytics.datasim.io :as dio]
            [com.yetanalytics.datasim.protocols :as p]
            [com.yetanalytics.datasim.input.personae :as personae :refer [map->Personae]]
            [com.yetanalytics.datasim.test-constants :as const]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Constants
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def simple-personae
  (dio/read-loc-json (map->Personae {}) const/simple-personae-filepath))

(def tc3-personae
  (dio/read-loc-json (map->Personae {}) const/tc3-personae-filepath))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest personae-test
  (testing "personae without roles"
    (is (nil? (p/validate simple-personae)))
    (is (nil? (p/validate tc3-personae))))
  (testing "personae with roles"
    (is (nil? (-> simple-personae
                  (assoc-in [:member 0 :role] "Lead Developer")
                  (assoc-in [:member 1 :role] "Data Engineer")
                  (assoc-in [:member 2 :role] "CEO")
                  p/validate)))
    (is (nil? (-> tc3-personae
                  (assoc-in [:member 0 :role] "Avatar")
                  (assoc-in [:member 1 :role] "Water Tribe Chief")
                  (assoc-in [:member 2 :role] "Earth Queen")
                  (assoc-in [:member 3 :role] "Fire Lord")
                  (assoc-in [:member 4 :role] "Air Nomand")
                  (assoc-in [:member 5 :role] "Cabbage Merchant")
                  p/validate)))))

(deftest personae-array-validation-test
  (testing "personae-array spec"
    (is (nil? (personae/validate-personae-array
               [const/simple-personae const/tc3-personae])))
    (is (some? (personae/validate-personae-array
                [(-> const/simple-personae
                     (assoc-in [:member 0 :mbox] "not-an-email"))
                 const/tc3-personae])))
    (is (some? (personae/validate-personae-array []))))
  (testing "duplicate member ids across different groups"
    (is (some? (personae/validate-personae-array
                [(-> const/simple-personae
                     (assoc-in [:member 0 :mbox] "mailto:bob@example.org")
                     (assoc-in [:member 1 :mbox] "mailto:alice@example.org")
                     (assoc-in [:member 2 :mbox] "mailto:fred@example.org"))
                 const/tc3-personae])))))
