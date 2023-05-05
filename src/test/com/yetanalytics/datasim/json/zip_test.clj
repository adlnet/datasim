(ns com.yetanalytics.datasim.json.zip-test
  (:require [clojure.test :refer [deftest is are]]
            [clojure.zip :as z]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as stest]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [com.yetanalytics.datasim.json :as json]
            [com.yetanalytics.datasim.json.zip :as jzip]))

(deftest zip-functions-test
  (let [results (stest/check
                 `#{jzip/json-zip
                    jzip/internal?
                    jzip/el-key
                    jzip/k-path
                    jzip/get-child
                    jzip/get-child-in
                    jzip/loc-in
                    ;; jzip/stub-in ;; TODO: fix gen so it doesn't throw
                    jzip/json-locs
                    jzip/json->path-map
                    jzip/path-map->json
                    ;; jzip/prune ;; TODO: Fix gen so it doesn't get roots
                    })
        {:keys [total
                check-passed]} (stest/summarize-results results)]
    (is (= total check-passed))))

#_{:clj-kondo/ignore [:unresolved-symbol]}
(defspec k-path-is-valid-for-get-in
  (prop/for-all
   [json (s/gen ::json/any)]
   (let [locs (->> json
                   jzip/json-zip
                   (iterate z/next)
                   (take-while (complement z/end?))
                   ;; don't look at map entries
                   (remove jzip/internal?))]
     (every?
      (fn [loc]
        (let [key-path (jzip/k-path loc)]
          (= (z/node loc)
             (get-in json key-path)
             (z/node (jzip/loc-in json key-path)))))
      locs))))

(deftest stub-in-test
  (are [data key-path result-root result-node]
      (let [result-loc (jzip/stub-in (jzip/json-zip data) key-path)]
        (and (= (z/root result-loc)
                result-root)
             (= (z/node result-loc)
                result-node)))
    ;; simple use like get-in
    {"foo" {"bar" "baz"}}   ["foo" "bar"]
    {"foo" {"bar" "baz"}}   "baz"
    {"foo" {"bar" ["baz"]}} ["foo" "bar" 0]
    {"foo" {"bar" ["baz"]}} "baz"
    ;; stub out keys missing from a map
    {"foo" {"bar" "baz"}} ["foo" "quxx"]
    {"foo" {"bar" "baz"
            "quxx" ::jzip/stub}} ::jzip/stub
    ;; append a stub to a vector
    {"foo" {"bar" ["baz"]}} ["foo" "bar" 1]
    {"foo" {"bar" ["baz" ::jzip/stub]}} ::jzip/stub
    ;; with some extra items
    {"foo" {"bar" ["baz"]}} ["foo" "bar" 3]
    {"foo" {"bar" ["baz" ::jzip/stub ::jzip/stub ::jzip/stub]}} ::jzip/stub
    ;; build lotsa vectors
    [] [0 1 2 3]
    [[::jzip/stub
      [::jzip/stub
       ::jzip/stub
       [::jzip/stub
        ::jzip/stub
        ::jzip/stub
        ::jzip/stub]]]] ::jzip/stub
    ;; work on a stub to produce a map
    {"foo" {"bar" "baz"
            "quxx" ::jzip/stub}} ["foo" "quxx" "fizz"]
    {"foo" {"bar" "baz"
            "quxx" {"fizz" ::jzip/stub}}} ::jzip/stub
    ;; work on a stub to produce a vector
    {"foo" {"bar" "baz"
            "quxx" ::jzip/stub}} ["foo" "quxx" 0]
    {"foo" {"bar" "baz"
            "quxx" [::jzip/stub]}} ::jzip/stub))
