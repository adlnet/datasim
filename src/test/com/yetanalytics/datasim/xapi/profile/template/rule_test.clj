(ns com.yetanalytics.datasim.xapi.profile.template.rule-test
  (:require [clojure.test :refer [deftest testing is are]]
            [clojure.test.check.generators :as stest]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [clojure.spec.alpha :as s]
            [xapi-schema.spec :as xs]
            [com.yetanalytics.datasim.json.schema :as jschema]
            [com.yetanalytics.datasim.xapi.profile.template.rule :as r]
            [com.yetanalytics.datasim.test-constants :as const])
  (:import [clojure.lang ExceptionInfo]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Constants
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def gen-seed 100)

(def short-statement
  {"id"        "59de1b06-bb6c-4708-a51a-b3d403c491db"
   "actor"     {"name" "Alice Faux"
                "mbox" "mailto:alice@example.org"}
   "verb"      {"id" "https://adlnet.gov/expapi/verbs/launched"}
   "object"    {"id"         "https://example.org/career/1054719918"
                "definition" {"type" "https://w3id.org/xapi/tla/activity-types/career"}
                "objectType" "Activity"}
   "context"   {"registration" "d7acfddb-f4c2-49f4-a081-ad1fb8490448"}
   "timestamp" "2021-03-18T17:36:22.131Z"})

(def long-statement
  (with-open
   [r (io/reader const/long-statement-filepath)]
    (json/parse-stream r)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Rule Parse/Follow/Match Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def example-rules
  [;; included presence
   {:location  "$.id"
    :presence  "included"
    :scopeNote {:en "included presence, no value requirement"}}
   {:location  "$.timestamp"
    :presence  "included"
    :scopeNote {:en "included presence, no value requirement, non-ID value"}}
   {:location  "$.verb.id"
    :presence  "included"
    :any       ["http://adlnet.gov/expapi/verbs/launched"
                "http://adlnet.gov/expapi/verbs/attended"]
    :scopeNote {:en "included presence, any values"}}
   {:location  "$.actor.member[*].objectType"
    :presence  "included"
    :any       ["Agent" "Group"]
    :scopeNote {:en "included presence, any values, non-ID value"}}
   {:location  "$.verb.id"
    :presence  "included"
    :all       ["http://adlnet.gov/expapi/verbs/attended"]
    :scopeNote {:en "included presence, any values"}}
   {:location  "$.actor.member[*].name"
    :presence  "included"
    :all       ["Andrew Downes" "Toby Nichols" "Ena Hills"]
    :scopeNote {:en "included presence, all values, non-ID value"}}
   {:location  "$.verb.id"
    :presence  "included"
    :none      ["http://adlnet.gov/expapi/verbs/launched"]
    :scopeNote {:en "included presence, none values"}}
   {:location  "$.version"
    :presence  "included"
    :none      ["1.0.1" "1.0.2" "1.0.3"]
    :scopeNote {:en "included presence, none values, non-ID value"}}
   ;; excluded presence
   {:location  "$.context.contextActivities.grouping"
    :presence  "excluded"
    :scopeNote {:en "excluded presence, no value requirement"}}
   {:location  "$.context.contextActivities.grouping[*].id"
    :presence  "excluded"
    :any       ["http://www.example.com/non-existent-activity/1"
                "http://www.example.com/non-existent-activity/2"
                "http://www.example.com/non-existent-activity/3"]
    :scopeNote {:en "excluded presence, any values (values don't matter)"}}
   {:location  "$.context.contextActivities.grouping[*].objectType"
    :presence  "excluded"
    :all       ["Actor" "Group"]
    :scopeNote {:en "excluded presence, all values (values don't matter)"}}
   {:location  "$.context.contextActivities.group[*].definition.name"
    :presence  "excluded"
    :none      ["bad activity name 1" "bad activity name 2"]
    :scopeNote {:en "excluded presence, none values (values don't matter)"}}
   ;; recommended presence
   {:location  "$.context.contextActivities.parent"
    :presence  "recommended"
    :scopeNote {:en "recommended presence, no value req, exists in long statement"}}
   {:location  "$.context.contextActivities.grouping"
    :presence  "recommended"
    :scopeNote {:en "recommended presence, no value req, does not exist in long statement"}}
   {:location  "$.verb.id"
    :presence  "recommended"
    :any       ["http://adlnet.gov/expapi/verbs/launched"
                "http://adlnet.gov/expapi/verbs/attended"]
    :scopeNote {:en "recommended presence, any values"}}
   {:location  "$.actor.member[*].name"
    :presence  "recommended"
    :all       ["Andrew Downes" "Toby Nichols" "Ena Hills"]
    :scopeNote {:en "recommended presence, all values"}}
   {:location  "$.version"
    :presence  "recommended"
    :none      ["1.0.1" "1.0.2" "1.0.3"]
    :scopeNote {:en "recommended presence, none values"}}
   ;; no presence
   {:location  "$.verb.id"
    :any       ["http://adlnet.gov/expapi/verbs/launched"
                "http://adlnet.gov/expapi/verbs/attended"]
    :scopeNote {:en "no presence, any values"}}
   {:location  "$.actor.member[*].name"
    :all       ["Andrew Downes" "Toby Nichols" "Ena Hills"]
    :scopeNote {:en "no presence, all values"}}
   {:location  "$.version"
    :none      ["1.0.1" "1.0.2" "1.0.3"]
    :scopeNote {:en "no presence, none values"}}
   ;; selector JSONPath
   {:location  "$.context.contextActivities.parent.*"
    :selector  "$.id"
    :any       ["http://www.example.com/meetings/series/266"
                "http://www.example.com/meetings/series/267"
                "http://www.example.com/meetings/series/268"]
    :scopeNote {:en "selector path, any values"}}
   {:location  "$.context.contextActivities.category.*"
    :selector  "$.id"
    :all       ["http://www.example.com/meetings/categories/teammeeting"]
    :scopeNote {:en "selector path, all values"}}
   {:location  "$.context.contextActivities.other.*"
    :selector  "$.id"
    :none      ["http://www.example.com/meetings/occurances/0"
                "http://www.example.com/meetings/occurances/1"
                "http://www.example.com/meetings/occurances/2"]
    :scopeNote {:en "selector path, none values"}}
   {:location  "$.context.contextActivities.other[0,1]"
    :selector  "$.id"
    :none      ["http://www.example.com/meetings/occurances/0"
                "http://www.example.com/meetings/occurances/1"
                "http://www.example.com/meetings/occurances/2"]
    :scopeNote {:en "selector path, none values, array"}}
   {:location  "$.context.contextActivities.other[0:1]"
    :selector  "$.id"
    :none      ["http://www.example.com/meetings/occurances/0"
                "http://www.example.com/meetings/occurances/1"
                "http://www.example.com/meetings/occurances/2"]
    :scopeNote {:en "selector path, none values, splice"}}
   ;; object values
   {:location  "$.result.extensions"
    :any       [{"http://example.com/profiles/meetings/resultextensions/minuteslocation" "X:\\meetings\\minutes\\examplemeeting.one"}
                {"http://example.com/profiles/meetings/resultextensions/minuteslocation" "X:\\meetings\\minutes\\examplemeeting.two"}]
    :scopeNote {:en "any value that is a JSON object"}}
   {:location  "$.result.extensions"
    :all       [{"http://example.com/profiles/meetings/resultextensions/minuteslocation" "X:\\meetings\\minutes\\examplemeeting.one"}]
    :scopeNote {:en "all value that is a JSON object"}}
   {:location  "$.result.extensions"
    :none      [{"http://example.com/profiles/meetings/resultextensions/minuteslocation" "X:\\meetings\\minutes\\examplemeeting.two"}]
    :scopeNote {:en "none value that is a JSON object"}}])

(defmacro is-parsed [parsed-rules rules]
  `(is (= ~parsed-rules (r/parse-rules ~rules))))

(deftest parse-rules-test
  (testing "included presence, no value requirement"
    (is-parsed [{:location [[["id"]]]
                 :presence :included
                 :path     ["id"]}]
               [{:location  "$.id"
                 :presence  "included"}]))
  (testing "included presence, no value requirement, with scope note"
    (is-parsed [{:location [[["timestamp"]]]
                 :presence :included
                 :path     ["timestamp"]}]
               [{:location  "$.timestamp"
                 :presence  "included"
                 :scopeNote {:en-US "This is a scope note"}}]))
  (testing "included presence, any values"
    (is-parsed [{:location [[["verb"] ["id"]]]
                 :presence :included
                 :valueset #{"http://adlnet.gov/expapi/verbs/launched"
                             "http://adlnet.gov/expapi/verbs/attended"}
                 :any      #{"http://adlnet.gov/expapi/verbs/launched"
                             "http://adlnet.gov/expapi/verbs/attended"}
                 :path     ["verb" "id"]}]
               [{:location  "$.verb.id"
                 :presence  "included"
                 :any       ["http://adlnet.gov/expapi/verbs/launched"
                             "http://adlnet.gov/expapi/verbs/attended"]}]))
  (testing "included presence, all values"
    (is-parsed [{:location [[["verb"] ["id"]]]
                 :presence :included
                 :valueset #{"http://adlnet.gov/expapi/verbs/launched"
                             "http://adlnet.gov/expapi/verbs/attended"}
                 :all      #{"http://adlnet.gov/expapi/verbs/launched"
                             "http://adlnet.gov/expapi/verbs/attended"}
                 :path     ["verb" "id"]}]
               [{:location  "$.verb.id"
                 :presence  "included"
                 :all       ["http://adlnet.gov/expapi/verbs/launched"
                             "http://adlnet.gov/expapi/verbs/attended"]}]))
  (testing "included presence, none values"
    (is-parsed [{:location [[["verb"] ["id"]]]
                 :presence :included
                 :none     #{"http://adlnet.gov/expapi/verbs/launched"}
                 :path     ["verb" "id"]}]
               [{:location  "$.verb.id"
                 :presence  "included"
                 :none      ["http://adlnet.gov/expapi/verbs/launched"]}]))
  (testing "included presence, any and all values"
    (is-parsed [{:location [[["verb"] ["id"]]]
                 :presence :included
                 :valueset  #{"http://adlnet.gov/expapi/verbs/launched"}
                 :any       #{"http://adlnet.gov/expapi/verbs/launched"
                              "http://adlnet.gov/expapi/verbs/attended"}
                 :all       #{"http://adlnet.gov/expapi/verbs/launched"}
                 :path      ["verb" "id"]}]
               [{:location  "$.verb.id"
                 :presence  "included"
                 :any       ["http://adlnet.gov/expapi/verbs/launched"
                             "http://adlnet.gov/expapi/verbs/attended"]
                 :all       ["http://adlnet.gov/expapi/verbs/launched"]}]))
  (testing "included presence, any and none values"
    (is-parsed [{:location [[["verb"] ["id"]]]
                 :presence :included
                 :valueset  #{"http://adlnet.gov/expapi/verbs/launched"}
                 :any       #{"http://adlnet.gov/expapi/verbs/launched"
                              "http://adlnet.gov/expapi/verbs/attended"}
                 :none      #{"http://adlnet.gov/expapi/verbs/attended"}
                 :path      ["verb" "id"]}]
               [{:location  "$.verb.id"
                 :presence  "included"
                 :any       ["http://adlnet.gov/expapi/verbs/launched"
                             "http://adlnet.gov/expapi/verbs/attended"]
                 :none      ["http://adlnet.gov/expapi/verbs/attended"]}]))
  (testing "included presence, all and none values"
    (is-parsed [{:location [[["verb"] ["id"]]]
                 :presence :included
                 :valueset  #{"http://adlnet.gov/expapi/verbs/launched"}
                 :all       #{"http://adlnet.gov/expapi/verbs/launched"
                              "http://adlnet.gov/expapi/verbs/attended"}
                 :none      #{"http://adlnet.gov/expapi/verbs/attended"}
                 :path      ["verb" "id"]}]
               [{:location  "$.verb.id"
                 :presence  "included"
                 :all       ["http://adlnet.gov/expapi/verbs/launched"
                             "http://adlnet.gov/expapi/verbs/attended"]
                 :none      ["http://adlnet.gov/expapi/verbs/attended"]}]))
  (testing "included presence, any, all and none values"
    (is-parsed [{:location [[["verb"] ["id"]]]
                 :presence :included
                 :valueset  #{"http://adlnet.gov/expapi/verbs/launched"}
                 :any       #{"http://adlnet.gov/expapi/verbs/launched"
                              "http://adlnet.gov/expapi/verbs/initialized"
                              "http://adlnet.gov/expapi/verbs/passed"}
                 :all       #{"http://adlnet.gov/expapi/verbs/launched"
                              "http://adlnet.gov/expapi/verbs/initialized"
                              "http://adlnet.gov/expapi/verbs/completed"}
                 :none      #{"http://adlnet.gov/expapi/verbs/initialized"}
                 :path      ["verb" "id"]}]
               [{:location  "$.verb.id"
                 :presence  "included"
                 :any       ["http://adlnet.gov/expapi/verbs/launched"
                             "http://adlnet.gov/expapi/verbs/initialized"
                             "http://adlnet.gov/expapi/verbs/passed"]
                 :all       ["http://adlnet.gov/expapi/verbs/launched"
                             "http://adlnet.gov/expapi/verbs/initialized"
                             "http://adlnet.gov/expapi/verbs/completed"]
                 :none      ["http://adlnet.gov/expapi/verbs/initialized"]}]))
  (testing "included presence, path with wildcards"
    (is-parsed [{:location [[["actor"] ["member"] '* ["objectType"]]]
                 :presence :included
                 :all      #{"Agent" "Group"}
                 :valueset #{"Agent" "Group"}
                 :path     ["actor" "member" '* "objectType"]}]
               [{:location  "$.actor.member[*].objectType"
                 :presence  "included"
                 :all       ["Agent" "Group"]}]))
  (testing "excluded presence, no value requirement"
    (is-parsed [{:location [[["context"] ["contextActivities"] ["grouping"]]]
                 :presence :excluded
                 :path     ["context" "contextActivities" "grouping"]}]
               [{:location  "$.context.contextActivities.grouping"
                 :presence  "excluded"}]))
  (testing "recommended presence, no value req, exists in long statement"
    (is-parsed [{:location [[["context"] ["contextActivities"] ["parent"]]]
                 :presence :recommended
                 :path     ["context" "contextActivities" "parent"]}]
               [{:location  "$.context.contextActivities.parent"
                 :presence  "recommended"}]))
  ;; Rule separation now applies
  (testing "selector path with array indices"
    (is-parsed
     [{:location [[["context"] ["contextActivities"] ["other"] [0 1] ["id"]]]
       :presence :included
       :path     ["context" "contextActivities" "other" '* "id"]}]
     [{:location  "$.context.contextActivities.other[0,1]"
       :selector  "$.id"
       :presence  "included"}]))
  (testing "selector path with multiple string keys"
    (is-parsed
     [{:location  [[["context"] ["contextActivities"] ["category"] [0 1]]]
       :presence  :included
       :path      ["context" "contextActivities" "category" '*]}
      {:location  [[["context"] ["contextActivities"] ["grouping"] [0 1]]]
       :presence  :included
       :path      ["context" "contextActivities" "grouping" '*]}]
     [{:location  "$.context.contextActivities['category','grouping']"
       :selector  "$[0,1]"
       :presence  "included"}]))
  (testing "selector path with and multiple keys and wildcard"
    (is-parsed
     [{:location  [[["context"] ["contextActivities"] ["parent"] '*]]
       :presence  :included
       :path      ["context" "contextActivities" "parent" '*]}
      {:location  [[["context"] ["contextActivities"] ["other"] '*]]
       :presence  :included
       :path      ["context" "contextActivities" "other" '*]}]
     [{:location  "$.context.contextActivities['parent','other']"
       :selector  "$.*"
       :presence  "included"}]))
  (testing "path with pipe operator"
    (is-parsed
     [{:location  [[["object"] ["id"]]]
       :presence  :included
       :path      ["object" "id"]}
      {:location  [[["object"] ["object"] ["id"]]]
       :presence  :included
       :path      ["object" "object" "id"]}]
     [{:location  "$.object.id | $.object.object.id"
       :presence  "included"}])))

(defmacro is-obj-types [object-types rules]
  `(is (= ~object-types (r/rules->spec-hints (r/parse-rules ~rules)))))

(deftest spec-object-types-test
  (testing "Statement object based on objectType"
    (is-obj-types {["object"] #{"activity"}}
                  [{:location "$.object.objectType"
                    :all      ["Activity"]}])
    (is-obj-types {["object"] #{"agent"}}
                  [{:location "$.object.objectType"
                    :all      ["Agent"]}])
    (is-obj-types {["object"] #{"group"}}
                  [{:location "$.object.objectType"
                    :all      ["Group"]}])
    (is-obj-types {["object"] #{"statement-ref"}}
                  [{:location "$.object.objectType"
                    :all      ["StatementRef"]}])
    (is-obj-types {["object"] #{"sub-statement"}}
                  [{:location "$.object.objectType"
                    :all      ["SubStatement"]}])
    (is-obj-types {["object"] #{"agent" "group"}}
                  [{:location "$.object.objectType"
                    :all      ["Agent" "Group"]}]))
  (testing "SubStatement object based on objectType"
    (is-obj-types {["object"] #{"sub-statement"}
                   ["object" "object"] #{"activity"}}
                  [{:location "$.object.object.objectType"
                    :all      ["Activity"]}])
    (is-obj-types {["object"] #{"sub-statement"}
                   ["object" "object"] #{"activity"}}
                  [{:location "$.object.object.objectType"
                    :all      ["Activity"]}])
    (is-obj-types {["object"] #{"sub-statement"}
                   ["object" "object"] #{"agent"}}
                  [{:location "$.object.object.objectType"
                    :all      ["Agent"]}])
    (is-obj-types {["object"] #{"sub-statement"}
                   ["object" "object"] #{"group"}}
                  [{:location "$.object.object.objectType"
                    :all      ["Group"]}])
    (is-obj-types {["object"] #{"sub-statement"}
                   ["object" "object"] #{"statement-ref"}}
                  [{:location "$.object.object.objectType"
                    :all      ["StatementRef"]}])
    (is-obj-types {["object"] #{"sub-statement"}
                   ["object" "object"] #{"agent" "group"}}
                  [{:location "$.object.object.objectType"
                    :all      ["Agent" "Group"]}]))
  (testing "Actors based on objectType"
    (is-obj-types {["actor"] #{"agent"}
                   ["authority"] #{"agent"}
                   ["context" "instructor"] #{"agent"}}
                  [{:location "$.actor.objectType"
                    :all      ["Agent"]}
                   {:location "$.authority.objectType"
                    :all      ["Agent"]}
                   {:location "$.context.instructor.objectType"
                    :all      ["Agent"]}])
    (is-obj-types {["actor"] #{"group"}
                   ["authority"] #{"group"}
                   ["context" "instructor"] #{"group"}}
                  [{:location "$.actor.objectType"
                    :all      ["Group"]}
                   {:location "$.authority.objectType"
                    :all      ["Group"]}
                   {:location "$.context.instructor.objectType"
                    :all      ["Group"]}])
    (is-obj-types {["actor"] #{"agent" "group"}
                   ["authority"] #{"agent" "group"}
                   ["context" "instructor"] #{"agent" "group"}}
                  [{:location "$.actor.objectType"
                    :all      ["Agent" "Group"]}
                   {:location "$.authority.objectType"
                    :all      ["Agent" "Group"]}
                   {:location "$.context.instructor.objectType"
                    :all      ["Agent" "Group"]}])
    (is-obj-types {["object"] #{"sub-statement"}
                   ["object" "actor"] #{"agent" "group"}
                   ["object" "context" "instructor"] #{"agent" "group"}}
                  [{:location "$.object.actor.objectType"
                    :all      ["Agent" "Group"]}
                   {:location "$.object.context.instructor.objectType"
                    :all      ["Agent" "Group"]}]))
  (testing "Objects based on properties"
    (is-obj-types {["object"] #{"activity" "agent" "group" "statement-ref" "sub-statement"}}
                  [{:location "$.object"
                    :presence "included"}])
    (is-obj-types {["object"] #{"activity" "agent" "group" "statement-ref" "sub-statement"}}
                  [{:location "$.object.objectType"
                    :presence "included"}])
    (is-obj-types {["object"] #{"activity" "statement-ref" "sub-statement"}}
                  [{:location "$.object.id"
                    :presence "included"}])
    (is-obj-types {["object"] #{"activity"}}
                  [{:location "$.object.definition"
                    :presence "included"}])
    (is-obj-types {["object"] #{"activity"}}
                  [{:location "$.object.id"
                    :presence "included"}
                   {:location "$.object.definition"
                    :presence "included"}])
    (is-obj-types {["object"] #{"agent" "group"}}
                  [{:location "$.object.name"
                    :presence "included"}])
    (is-obj-types {["object"] #{"agent" "group"}}
                  [{:location "$.object.mbox"
                    :presence "included"}])
    (is-obj-types {["object"] #{"agent" "group"}}
                  [{:location "$.object.mbox_sha1sum"
                    :presence "included"}])
    (is-obj-types {["object"] #{"agent" "group"}}
                  [{:location "$.object.openid"
                    :presence "included"}])
    (is-obj-types {["object"] #{"agent" "group"}}
                  [{:location "$.object.account"
                    :presence "included"}])
    (is-obj-types {["object"] #{"agent" "group"}}
                  [{:location "$.object.account.name"
                    :presence "included"}])
    (is-obj-types {["object"] #{"agent" "group"}}
                  [{:location "$.object.account.homePage"
                    :presence "included"}])
    (is-obj-types {["object"] #{"group"}}
                  [{:location "$.object.member"
                    :presence "included"}])
    (is-obj-types {["object"] #{"group"}}
                  [{:location "$.object.name"
                    :presence "included"}
                   {:location "$.object.member"
                    :presence "included"}])
    (is-obj-types {["object"] #{"sub-statement"}
                   ["object" "actor"] #{"agent" "group"}}
                  [{:location "$.object.actor"
                    :presence "included"}])
    (is-obj-types {["object"] #{"sub-statement"}
                   ["object" "object"] #{"activity" "agent" "group" "statement-ref"}}
                  [{:location "$.object.object"
                    :presence "included"}])
    (is-obj-types {["object"] #{"sub-statement"}}
                  [{:location "$.object.verb"
                    :presence "included"}])
    (is-obj-types {["object"] #{"sub-statement"}}
                  [{:location "$.object.context"
                    :presence "included"}])
    (is-obj-types {["object"] #{"sub-statement"}}
                  [{:location "$.object.result"
                    :presence "included"}])
    (is-obj-types {["object"] #{"sub-statement"}}
                  [{:location "$.object.timestamp"
                    :presence "included"}]))
  (testing "Contradictory types"
    (is (= ::r/invalid-object-types
           (try (-> [{:location "$.object.objectType"
                      :any      ["BadType"]}]
                    r/parse-rules
                    r/rules->spec-hints)
                (catch Exception e (-> e ex-data :type)))))
    (is (= ::r/invalid-object-types
           (try (-> [{:location "$.object.id"
                      :presence "included"}
                     {:location "$.object.objectType"
                      :any      ["Agent" "Group"]}]
                    r/parse-rules
                    r/rules->spec-hints)
                (catch Exception e (-> e ex-data :type)))))
    (is (= ::r/invalid-object-types
           (try (-> [{:location "$.object.id"
                      :presence "included"}
                     {:location "$.object.name"
                      :presence "included"}]
                    r/parse-rules
                    r/rules->spec-hints)
                (catch Exception e (-> e ex-data :type)))))))

(def valuegen-iri-map
  {"http://foo.org/verb"
   {:id   "http://foo.org/verb"
    :type "Verb"}
   "http://foo.org/activity"
   {:id   "http://foo.org/activity"
    :type "Activity"
    :definition {:type "http://foo.org/activity-type"}}
   "http://foo.org/activity-type"
   {:id   "http://foo.org/activity-type"
    :type "ActivityType"}
   "http://foo.org/activity-extension"
   {:id   "http://foo.org/activity-extension"
    :type "ActivityExtension"
    :inlineSchema "{\"type\":\"string\"}"}
   "http://foo.org/context-extension"
   {:id   "http://foo.org/context-extension"
    :type "ContextExtension"
    :inlineSchema "{\"type\":\"integer\"}"}
   "http://foo.org/result-extension"
   {:id   "http://foo.org/result-extension"
    :type "ResultExtension"
    :inlineSchema "{\"type\":\"boolean\"}"}})

(def valuegen-object-types
  {["object"] #{"activity"}
   ["actor"] #{"agent" "group"}})

(def valuegen-object-types-2
  {["object"] #{"sub-statement"}
   ["object" "object"] #{"activity"}
   ["actor"] #{"agent" "group"}
   ["object" "actor"] #{"agent" "group"}})

(def valuegen-valuesets
  {:verbs          #{{:id   "http://foo.org/verb"
                      :type "Verb"}}
   :verb-ids       #{"http://foo.org/verb"}
   :activities     #{{:id         "http://foo.org/activity"
                      :type       "Activity"
                      :definition {:type "http://foo.org/activity-type"}}}
   :activity-ids   #{"http://foo.org/activity"}
   :activity-types #{"http://foo.org/activity-type"}})

(defn- parse-rule-valuegen [rule]
  (r/add-rule-valuegen valuegen-iri-map
                       valuegen-object-types
                       valuegen-valuesets
                       (first (r/parse-rules [rule]))))

(defn- parse-rule-valuegen-2 [rule]
  (r/add-rule-valuegen valuegen-iri-map
                       valuegen-object-types-2
                       valuegen-valuesets
                       (first (r/parse-rules [rule]))))

(deftest valuegen-test
  (testing "Ignore if valueset is already present"
    (is (= {:location [[["actor"] ["name"]]]
            :valueset #{"Andrew Downes" "Toby Nichols" "Ena Hills"}
            :all      #{"Andrew Downes" "Toby Nichols" "Ena Hills"}
            :path     ["actor" "name"]}
           (parse-rule-valuegen
            {:location "$.actor.name"
             :all      ["Andrew Downes" "Toby Nichols" "Ena Hills"]})))
    (is (= {:location [[["verb"] ["id"]]]
            :valueset #{"http://example.org/verb" "http://example.org/verb-2"}
            :any      #{"http://example.org/verb" "http://example.org/verb-2"}
            :path     ["verb" "id"]}
           (parse-rule-valuegen
            {:location "$.verb.id"
             :any      ["http://example.org/verb"
                        "http://example.org/verb-2"]})))
    (is (= {:location [[["object"] ["verb"] ["id"]]]
            :valueset #{"http://example.org/verb" "http://example.org/verb-2"}
            :any      #{"http://example.org/verb" "http://example.org/verb-2"}
            :path     ["object" "verb" "id"]}
           (parse-rule-valuegen-2
            {:location "$.object.verb.id"
             :any      ["http://example.org/verb"
                        "http://example.org/verb-2"]}))))
  (testing "Add valuesets"
    (is (= {:location [[["verb"]]]
            :presence :included
            :path     ["verb"]
            :valueset #{{:id "http://foo.org/verb" :type "Verb"}}
            :all      #{{:id "http://foo.org/verb" :type "Verb"}}}
           (parse-rule-valuegen
            {:location "$.verb"
             :presence "included"})))
    (is (= {:location [[["verb"] ["id"]]]
            :presence :included
            :path     ["verb" "id"]
            :valueset #{"http://foo.org/verb"}
            :all      #{"http://foo.org/verb"}}
           (parse-rule-valuegen
            {:location "$.verb.id"
             :presence "included"})))
    (is (= {:location [[["object"]]]
            :presence :included
            :path     ["object"]
            :valueset #{{:id   "http://foo.org/activity"
                         :type "Activity"
                         :definition {:type "http://foo.org/activity-type"}}}
            :all      #{{:id   "http://foo.org/activity"
                         :type "Activity"
                         :definition {:type "http://foo.org/activity-type"}}}}
           (parse-rule-valuegen
            {:location "$.object"
             :presence "included"})))
    (is (= {:location [[["object"] ["id"]]]
            :presence :included
            :path     ["object" "id"]
            :valueset #{"http://foo.org/activity"}
            :all      #{"http://foo.org/activity"}}
           (parse-rule-valuegen
            {:location "$.object.id"
             :presence "included"})))
    (is (= {:location [[["object"] ["definition"] ["type"]]]
            :presence :included
            :path     ["object" "definition" "type"]
            :valueset #{"http://foo.org/activity-type"}
            :all      #{"http://foo.org/activity-type"}}
           (parse-rule-valuegen
            {:location "$.object.definition.type"
             :presence "included"}))))
  (testing "Add valuesets (substatements)"
    (is (= {:location [[["object"] ["verb"]]]
            :presence :included
            :path     ["object" "verb"]
            :valueset #{{:id "http://foo.org/verb" :type "Verb"}}
            :all      #{{:id "http://foo.org/verb" :type "Verb"}}}
           (parse-rule-valuegen-2
            {:location "$.object.verb"
             :presence "included"})))
    (is (= {:location [[["object"] ["verb"] ["id"]]]
            :presence :included
            :path     ["object" "verb" "id"]
            :valueset #{"http://foo.org/verb"}
            :all      #{"http://foo.org/verb"}}
           (parse-rule-valuegen-2
            {:location "$.object.verb.id"
             :presence "included"})))
    (is (= {:location [[["object"] ["object"]]]
            :presence :included
            :path     ["object" "object"]
            :valueset #{{:id   "http://foo.org/activity"
                         :type "Activity"
                         :definition {:type "http://foo.org/activity-type"}}}
            :all      #{{:id   "http://foo.org/activity"
                         :type "Activity"
                         :definition {:type "http://foo.org/activity-type"}}}}
           (parse-rule-valuegen-2
            {:location "$.object.object"
             :presence "included"})))
    (is (= {:location [[["object"] ["object"] ["id"]]]
            :presence :included
            :path     ["object" "object" "id"]
            :valueset #{"http://foo.org/activity"}
            :all      #{"http://foo.org/activity"}}
           (parse-rule-valuegen-2
            {:location "$.object.object.id"
             :presence "included"})))
    (is (= {:location [[["object"] ["object"] ["definition"] ["type"]]]
            :presence :included
            :path     ["object" "object" "definition" "type"]
            :valueset #{"http://foo.org/activity-type"}
            :all      #{"http://foo.org/activity-type"}}
           (parse-rule-valuegen-2
            {:location "$.object.object.definition.type"
             :presence "included"})
           (r/add-rule-valuegen valuegen-iri-map
                                valuegen-object-types-2
                                valuegen-valuesets
                                {:location [[["object"] ["object"] ["definition"] ["type"]]]
                                 :presence :included
                                 :path     ["object" "object" "definition" "type"]}))))
  (testing "Add spec and generator"
    (is (= :statement/result
           (:spec (parse-rule-valuegen {:location "$.result"
                                        :presence "included"}))))
    (is (= :sub-statement/result
           (:spec (parse-rule-valuegen-2 {:location "$.object.result"
                                          :presence "included"}))))
    (is (string?
         (stest/generate
          (:generator (parse-rule-valuegen {:location "$.actor.name"
                                            :presence "included"})))))
    (is (string?
         (stest/generate
          (:generator (parse-rule-valuegen-2 {:location "$.object.actor.name"
                                              :presence "included"}))))))
  (testing "Add spec and generator (extensions)"
    (is (= ::jschema/string
           (:spec (parse-rule-valuegen
                   {:location "$.object.definition.extensions['http://foo.org/activity-extension']"
                    :presence "included"}))))
    (is (= ::jschema/integer
           (:spec (parse-rule-valuegen
                   {:location "$.context.extensions['http://foo.org/context-extension']"
                    :presence "included"}))))
    (is (= ::jschema/boolean
           (:spec (parse-rule-valuegen
                   {:location "$.result.extensions['http://foo.org/result-extension']"
                    :presence "included"}))))
    (is (string?
         (stest/generate
          (:generator (parse-rule-valuegen
                       {:location "$.object.definition.extensions['http://foo.org/activity-extension']"
                        :presence "included"})))))
    (is (int?
         (stest/generate
          (:generator (parse-rule-valuegen
                       {:location "$.context.extensions['http://foo.org/context-extension']"
                        :presence "included"})))))
    (is (boolean?
         (stest/generate
          (:generator (parse-rule-valuegen
                       {:location "$.result.extensions['http://foo.org/result-extension']"
                        :presence "included"})))))))

(deftest example-rules-test
  (let [rule-tuples (map (fn [{:keys [scopeNote] :as rule}]
                           [scopeNote (r/parse-rule rule)])
                         example-rules)]
    (testing "Parse rule test:"
      (doseq [[rule-name parsed-rule] rule-tuples]
        (testing rule-name
          (is (nil? (s/explain-data ::r/parsed-rule parsed-rule))))))
    (testing "Follows rule test:"
      (doseq [[rule-name parsed-rule] rule-tuples]
        (testing rule-name
          (is (r/follows-rule? long-statement parsed-rule)))))))

(deftest not-follows-rule-test
  (testing "Does not follow rules"
    (are [statement rule]
         (->> rule r/parse-rule (r/follows-rule? statement) not)
      ;; No ID
      (dissoc short-statement "id")
      (get example-rules 0)
      ;; Bad Verb ID
      (assoc-in short-statement
                ["verb" "id"]
                "http://foo.org")
      (get example-rules 2)
      ;; Bad Verb ID 2
      (assoc-in short-statement
                ["verb" "id"]
                "http://adlnet.gov/expapi/verbs/launched")
      (get example-rules 6)
      ;; Out-of-place grouping contextActivity
      (assoc-in short-statement
                ["context" "contextActivities" "grouping"]
                [{"id" "https://w3id.org/xapi/tla/v0.13"}])
      (get example-rules 8)))
  (testing "Not following rules w/ recommended presence"
    (are [statement rule]
         (->> rule r/parse-rule (r/follows-rule? statement) not)
      ;; Bad Verb ID - recommended presence
      (assoc-in short-statement
                ["verb" "id"]
                "http://foo.org")
      (get example-rules 14)
      ;; Bad Actor Name - recommended presence
      (assoc-in short-statement
                ["actor" "member"]
                [{"name" "Foo Bar" "mbox" "mailto:foo@example.com"}])
      (get example-rules 15)
      ;; Version Present - recommended presence
      (assoc-in short-statement
                ["version"]
                "1.0.2")
      (get example-rules 16))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Rule Application Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- apply-rule-gen [statement rule]
  (r/apply-rules-gen statement [rule] :seed gen-seed))

(comment
  (apply-rule-gen
   long-statement
   {:location "$.context.contextActivities.other[0] | $.context.contextActivities.other[1]"
    :selector "$.definition.type"
    :all      ["http://www.example.com/activity-type-1"
               "http://www.example.com/activity-type-2"
               "http://www.example.com/activity-type-3"]}))

;; Apply one rule at a time
(deftest apply-rule-gen-test
  ;; Actors
  (testing "apply-rules-gen for Actors in short statement"
    (are [new-actor rule]
         (= new-actor
            (-> (apply-rule-gen short-statement rule) (get "actor")))
      ;; Replace actor
      {"name" "Bob Fakename"
       "mbox" "mailto:bob@example.org"}
      {:location "$.actor"
       :all      [{"name" "Bob Fakename"
                   "mbox" "mailto:bob@example.org"}]}
      ;; Replace actor type
      {"name" "Alice Faux"
       "mbox" "mailto:alice@example.org"
       "objectType" "Group"}
      {:location "$.actor.objectType"
       :presence "included"
       :all      ["Group"]}
      ;; Replace actor name
      {"name" "Bob Fakename"
       "mbox" "mailto:alice@example.org"}
      {:location "$.actor.name"
       :all      ["Bob Fakename"]}
      ;; Replace actor name via "any"
      {"name" "Bob Fakename"
       "mbox" "mailto:alice@example.org"}
      {:location "$.actor.name"
       :any      ["Bob Fakename"]}
      ;; Replace actor name - not applied since name is already included
      {"name" "Alice Faux"
       "mbox" "mailto:alice@example.org"}
      {:location "$.actor.name"
       :any      ["Alice Faux" "Bob Fakename"]}
      ;; Replace actor name - not applied due to already matching
      {"name" "Alice Faux"
       "mbox" "mailto:alice@example.org"}
      {:location "$.actor.name"
       :all      ["Alice Faux" "Bob Fakename"]}
      ;; Remove actor name via "none"
      {"name" "g0940tWy7k3GA49j871LLl4W0" ; randomly generated
       "mbox" "mailto:alice@example.org"}
      {:location "$.actor.name"
       :none     ["Alice Faux" "Bob Fakename"]}
      ;; Remove actor name via "excluded" ("any" values)
      {"mbox" "mailto:alice@example.org"}
      {:location "$.actor.name"
       :presence "excluded"
       :any     ["Alice Faux" "Bob Fakename"]}
      ;; Remove actor name via "excluded" ("all" values)
      {"mbox" "mailto:alice@example.org"}
      {:location "$.actor.name"
       :presence "excluded"
       :none     ["Alice Faux" "Bob Fakename"]}
      ;; Remove actor name via "excluded" ("none" values)
      {"mbox" "mailto:alice@example.org"}
      {:location "$.actor.name"
       :presence "excluded"
       :none     ["Alice Faux" "Bob Fakename"]}
      ;; Remove actor name using both location and selector
      {"mbox" "mailto:alice@example.org"}
      {:location "$.actor"
       :selector "$.name"
       :presence "excluded"}
      ;; Remove actor via "excluded"
      nil
      {:location "$.actor"
       :presence "excluded"}
      ;; Remove actor properties
      nil
      {:location "$.actor.*"
       :presence "excluded"}
      ;; Both `any` and `all` - former is superset of latter
      {"name" "Bob Fakename"
       "mbox" "mailto:alice@example.org"}
      {:location "$.actor.name"
       :any      ["Alice Faux" "Bob Fakename"]
       :all      ["Bob Fakename"]}
      ;; Both `any` and `all` - former is subset of latter
      {"name" "Bob Fakename"
       "mbox" "mailto:alice@example.org"}
      {:location "$.actor.name"
       :any      ["Bob Fakename"]
       :all      ["Alice Faux" "Bob Fakename"]}
      ;; Both `any` and `none`
      {"name" "Bob Fakename"
       "mbox" "mailto:alice@example.org"}
      {:location "$.actor.name"
       :any      ["Bob Fakename"]
       :none     ["Alice Faux"]}
      ;; Both `all` and `none`
      {"name" "Bob Fakename"
       "mbox" "mailto:alice@example.org"}
      {:location "$.actor.name"
       :all      ["Bob Fakename"]
       :none     ["Alice Faux"]}
      ;; Everything: `any`, `all`, and `none`
      {"name" "Bob Fakename"
       "mbox" "mailto:alice@example.org"}
      {:location "$.actor.name"
       :all      ["Alice Faux" "Bob Fakename" "Fred Ersatz"]
       :any      ["Alice Faux" "Bob Fakename"]
       :none     ["Alice Faux"]}))
  ;; Verbs
  (testing "apply-rules-gen for Verbs in short statement"
    (are [new-verb rule]
         (= new-verb
            (-> (apply-rule-gen short-statement rule) (get "verb")))
      ;; Replace verb ID using "any"
      {"id" "https://adlnet.gov/expapi/verbs/launched"}
      {:location "$.verb.id"
       :presence "included"
       :any      ["https://adlnet.gov/expapi/verbs/launched"
                  "https://adlnet.gov/expapi/verbs/initialized"]}
      ;; Replace verb ID using "all"
      {"id" "https://adlnet.gov/expapi/verbs/launched"}
      {:location "$.verb.id"
       :presence "included"
       :all      ["https://adlnet.gov/expapi/verbs/launched"]}
      ;; Remove verb ID using "none"
      {"id" "tnwjfgj://dtiwcirffy.mkkt.efbk/xeozmjldyx"} ; randomly generated
      {:location "$.verb.id"
       :presence "included"
       :none     ["https://adlnet.gov/expapi/verbs/launched"]}
      ;; Remove verb using "excluded"
      nil
      {:location "$.verb.id"
       :presence "excluded"}
      ;; Insert verb description ("any")
      {"id" "https://adlnet.gov/expapi/verbs/launched"
       "display" {"en-US" "Launched"}}
      {:location "$.verb.display"
       :any      [{"en-US" "Launched"}]}
      ;; Insert verb description ("all")
      {"id" "https://adlnet.gov/expapi/verbs/launched"
       "display" {"en-US" "Launched"}}
      {:location "$.verb.display.en-US"
       :all      ["Launched"]}))
  ;; Context Activities
  (testing "apply-rules-gen for Context activities in long statement"
    (are [activity-property new-context rule]
         (= new-context
            (-> (apply-rule-gen long-statement rule)
                (get-in ["context" "contextActivities" activity-property])))
      ;; Increment one single ID
      "parent" [{"id" "http://www.example.com/meetings/series/268"
                 "objectType" "Activity"}]
      {:location "$.context.contextActivities.parent[0].id"
       :all      ["http://www.example.com/meetings/series/268"]}
      ;; Increment potentially multiple IDs using wildcard
      ;; Values are randomly chosen
      "parent" [{"id" "http://www.example.com/meetings/series/268"
                 "objectType" "Activity"}]
      {:location "$.context.contextActivities.parent[*].id"
       :all      ["http://www.example.com/meetings/series/268"]}
      ;; Replace ID
      "category" [{"id" "tnwjfgj://dtiwcirffy.mkkt.efbk/xeozmjldyx" ; randomly generated
                   "objectType" "Activity"
                   "definition" {"name" {"en" "team meeting"}
                                 "description" {"en" "A category of meeting used for regular team meetings."}
                                 "type" "http://example.com/expapi/activities/meetingcategory"}}]
      {:location "$.context.contextActivities.category[0].id"
       :none     ["http://www.example.com/meetings/categories/teammeeting"]}
      ;; Replace two different IDs
      "other" [{"id" "http://www.example.com/meetings/occurances/bar"
                "objectType" "Activity"}
               {"id" "http://www.example.com/meetings/occurances/foo"
                "objectType" "Activity"}]
      {:location "$.context.contextActivities.other[0,1].id"
       :all      ["http://www.example.com/meetings/occurances/foo"
                  "http://www.example.com/meetings/occurances/bar"]}
      ;; Replace and insert IDs using wildcard
      ;; Values are randomly chosen
      "other" [{"id" "http://www.example.com/meetings/occurances/qux"
                "objectType" "Activity"}
               {"id" "http://www.example.com/meetings/occurances/foo"
                "objectType" "Activity"}
               {"id" "http://www.example.com/meetings/occurances/baz"}]
      {:location "$.context.contextActivities.other[*].id"
       :all      ["http://www.example.com/meetings/occurances/foo"
                  "http://www.example.com/meetings/occurances/bar"
                  "http://www.example.com/meetings/occurances/baz"
                  "http://www.example.com/meetings/occurances/qux"]}
      ;; Replace and insert multiple activity types
      ;; Activity types are selected randomly
      "other" [{"id" "http://www.example.com/meetings/occurances/34257"
                "objectType" "Activity"
                "definition" {"type" "http://www.example.com/activity-type-3"}}
               {"id" "http://www.example.com/meetings/occurances/3425567"
                "objectType" "Activity"
                "definition" {"type" "http://www.example.com/activity-type-2"}}
               {"definition" {"type" "http://www.example.com/activity-type-2"}}
               {"definition" {"type" "http://www.example.com/activity-type-2"}}
               {"definition" {"type" "http://www.example.com/activity-type-3"}}]
      {:location "$.context.contextActivities.other[0,1,2,3,4].definition.type"
       :all      ["http://www.example.com/activity-type-1"
                  "http://www.example.com/activity-type-2"
                  "http://www.example.com/activity-type-3"]}
      ;; Replace one ID with "any"
      "other" [{"id" "http://www.example.com/meetings/occurances/34257"
                "objectType" "Activity"}
               ;; selected via deteministed seeded rng
               {"id" "http://www.example.com/meetings/occurances/bar"
                "objectType" "Activity"}]
      {:location "$.context.contextActivities.other[1].id"
       :any      ["http://www.example.com/meetings/occurances/foo"
                  "http://www.example.com/meetings/occurances/bar"]}
      ;; Replace Activity definitions
      "category" [{"id"         "http://www.example.com/meetings/categories/teammeeting"
                   "objectType" "Activity"
                   "definition" {"name"        {"en" "team meeting"}
                                 "description" {"en" "foo"}
                                 "type"        "http://example.com/expapi/activities/meetingcategory"}}
                  {"definition" {"description" {"en" "foo"}}}]
      {:location "$.context.contextActivities.category[0,1].definition.description"
       :any      [{"en" "foo"}]}
      ;; Try creating multiple IDs
      ;; Collection is randomly shuffled
      "grouping" [{"id" "http://www.example.com/id-3"}
                  {"id" "http://www.example.com/id-1"}
                  {"id" "http://www.example.com/id-2"}]
      {:location "$.context.contextActivities.grouping[0,1,2].id"
       :presence "included"
       :all      ["http://www.example.com/id-1"
                  "http://www.example.com/id-2"
                  "http://www.example.com/id-3"]}
      ;; Try creating multiple IDs, but only one ID is available
      ;; IDs should be distinct, but since the profile says otherwise
      "grouping" [{"id" "http://www.example.com/only-id"}
                  {"id" "http://www.example.com/only-id"}
                  {"id" "http://www.example.com/only-id"}]
      {:location "$.context.contextActivities.grouping[0,1,2].id"
       :presence "included"
       :all      ["http://www.example.com/only-id"]}
      ;; Same thing as above but skipping an entry
      "grouping" [{"id" "http://www.example.com/only-id"}
                  nil
                  {"id" "http://www.example.com/only-id"}]
      {:location "$.context.contextActivities.grouping[0,2].id"
       :presence "included"
       :all      ["http://www.example.com/only-id"]}
      ;; Assoc an entry out of bounds
      ;; (this was the error Cliff encountered when trying to craft a Profile)
      "grouping" [nil {"definition" {"type" "https://xapinet.com/xapi/blooms/activitytypes/cognitive-process-dimension"}}]
      {:location "$.context.contextActivities.grouping[1].definition.type"
       :presence "included"
       :all      ["https://xapinet.com/xapi/blooms/activitytypes/cognitive-process-dimension"]}
      ;; Use both location and selector
      ;; Activity types are chosen randomly
      "other" [{"id" "http://www.example.com/meetings/occurances/34257"
                "objectType" "Activity"
                "definition" {"type" "http://www.example.com/activity-type-3"}}
               {"id" "http://www.example.com/meetings/occurances/3425567"
                "objectType" "Activity"
                "definition" {"type" "http://www.example.com/activity-type-2"}}
               {"definition" {"type" "http://www.example.com/activity-type-2"}}
               {"definition" {"type" "http://www.example.com/activity-type-2"}}
               {"definition" {"type" "http://www.example.com/activity-type-3"}}]
      {:location "$.context.contextActivities.other[0,1,2,3,4]"
       :selector "$.definition.type"
       :all      ["http://www.example.com/activity-type-1"
                  "http://www.example.com/activity-type-2"
                  "http://www.example.com/activity-type-3"]}
      ;; Use the pipe operator on location
      ;; Activity types are chosen randomly
      "other" [{"id" "http://www.example.com/meetings/occurances/34257"
                "objectType" "Activity"
                "definition" {"type" "http://www.example.com/activity-type-1"}}
               {"id" "http://www.example.com/meetings/occurances/3425567"
                "objectType" "Activity"
                "definition" {"type" "http://www.example.com/activity-type-2"}}]
      {:location "$.context.contextActivities.other[0] | $.context.contextActivities.other[1]"
       :selector "$.definition.type"
       :all      ["http://www.example.com/activity-type-1"
                  "http://www.example.com/activity-type-2"
                  "http://www.example.com/activity-type-3"]}
      ;; Use the pipe operator on selector
      ;; Activity types are chosen randomly
      "other" [{"id" "http://www.example.com/meetings/occurances/34257"
                "objectType" "Activity"
                "definition" {"type" "http://www.example.com/activity-type-1"}}
               {"id" "http://www.example.com/meetings/occurances/3425567"
                "objectType" "Activity"
                "definition" {"type" "http://www.example.com/activity-type-2"}}]
      {:location "$.context.contextActivities"
       :selector "$.other[0].definition.type | $.other[1].definition.type"
       :all      ["http://www.example.com/activity-type-1"
                  "http://www.example.com/activity-type-2"
                  "http://www.example.com/activity-type-3"]})))

;; Apply a collection of rules
(deftest apply-rule-coll-gen-test
  (testing "apply-rules-gen with multiple rules for Actors"
    ;; Turn the Actor into a Group using repeated rule applications
    (is (= {"name" "Alice Faux"
            "mbox" "mailto:alice@example.org"
            "objectType" "Group"
            "member" [{"mbox" "mailto:milt@yetanalytics.com"
                       "name" "milt"
                       "objectType" "Agent"}]}
           (-> short-statement
               (r/apply-rules-gen
                [{:location "$.actor.objectType"
                  :presence "included"
                  :all      ["Group"]}
                 {:location "$.actor.member[0]"
                  :presence "included"
                  :all      [{"mbox"       "mailto:milt@yetanalytics.com"
                              "name"       "milt"
                              "objectType" "Agent"}]}]
                :seed gen-seed)
               (get "actor")))))
  (testing "apply-rules-gen with multiple rules for Verbs"
    ;; Add two lang map entires at once
    (is (= {"id" "https://adlnet.gov/expapi/verbs/launched"
            "display" {"en-US" "Launched"
                       "zh-CN" "展开"}}
           (-> short-statement
               (r/apply-rules-gen
                [{:location "$.verb.display.en-US"
                  :all      ["Launched"]}
                 {:location "$.verb.display.zh-CN"
                  :all      ["展开"]}]
                :seed gen-seed)
               (get "verb"))))
    ;; Add, then try to remove, lang map entries
    (is (= {"id" "https://adlnet.gov/expapi/verbs/launched"
            "display" {"en-US" "Launched"
                       "zh-CN" "3csI6sZq6uxukVZ964BE5GDrqBoLJ7"}} ; randomly gen
           (-> short-statement
               (r/apply-rules-gen
                [{:location "$.verb.display.en-US"
                  :all      ["Launched"]}
                 {:location "$.verb.display.zh-CN"
                  :all      ["展开"]}
                 {:location "$.verb.display.zh-CN"
                  :none     ["展开"]}]
                :seed gen-seed)
               (get "verb")))))
  ;; TODO: Right now only one value can be replaced in the following
  ;; test cases since we are dealing with an `id` property and there is
  ;; only one value in the `any` and `all` colls. We need to discuss if
  ;; this behavior should be changed in Pathetic.
  (testing "apply-rules-gen with multiple rules for Context Activities"
    ;; two "any" rules
    ;; FIXME: This is technically wrong, as two `any` rules at the same
    ;; location would be valid (and in fact this result is wrong, since
    ;; the intersection w/ the "foo" coll is empty).
    (is (= [{"id" "http://www.example.com/meetings/occurances/bar"
             "objectType" "Activity"}
            {"id" "http://www.example.com/meetings/occurances/3425567"
             "objectType" "Activity"}]
           (-> long-statement
               (r/apply-rules-gen
                [{:location "$.context.contextActivities.other[*].id"
                  :any ["http://www.example.com/meetings/occurances/foo"]}
                 {:location "$.context.contextActivities.other[*].id"
                  :any ["http://www.example.com/meetings/occurances/bar"]}]
                :seed gen-seed)
               (get-in ["context" "contextActivities" "other"]))))
    ;; "all" followed by "any" - "any" overwrites "all"
    (is (= [{"id" "http://www.example.com/meetings/occurances/bar"
             "objectType" "Activity"}
            {"id" "http://www.example.com/meetings/occurances/3425567"
             "objectType" "Activity"}]
           (-> long-statement
               (r/apply-rules-gen
                [{:location "$.context.contextActivities.other[*].id"
                  :all ["http://www.example.com/meetings/occurances/foo"]}
                 {:location "$.context.contextActivities.other[*].id"
                  :any ["http://www.example.com/meetings/occurances/bar"]}]
                :seed gen-seed)
               (get-in ["context" "contextActivities" "other"]))))
    ;; "any" followed by "all" - "all" overwrites "any"
    (is (= [{"id" "http://www.example.com/meetings/occurances/bar"
             "objectType" "Activity"}
            {"id" "http://www.example.com/meetings/occurances/3425567"
             "objectType" "Activity"}]
           (-> long-statement
               (r/apply-rules-gen
                [{:location "$.context.contextActivities.other[*].id"
                  :any ["http://www.example.com/meetings/occurances/foo"]}
                 {:location "$.context.contextActivities.other[*].id"
                  :all ["http://www.example.com/meetings/occurances/bar"]}]
                :seed gen-seed)
               (get-in ["context" "contextActivities" "other"]))))
    ;; two "all" rules - second "all" overwrites first
    (is (= [{"id" "http://www.example.com/meetings/occurances/bar"
             "objectType" "Activity"}
            {"id" "http://www.example.com/meetings/occurances/3425567"
             "objectType" "Activity"}]
           (-> long-statement
               (r/apply-rules-gen
                [{:location "$.context.contextActivities.other[*].id"
                  :all ["http://www.example.com/meetings/occurances/foo"]}
                 {:location "$.context.contextActivities.other[*].id"
                  :all ["http://www.example.com/meetings/occurances/bar"]}]
                :seed gen-seed)
               (get-in ["context" "contextActivities" "other"]))))
    ;; assoc the 1st entry in an array before the 0th entry
    (is (= [{"definition" {"type" "https://xapinet.com/xapi/blooms/activities/objectives/procedural"}}
            {"definition" {"type" "https://xapinet.com/xapi/blooms/activitytypes/cognitive-process-dimension"}}]
           (-> long-statement
               (r/apply-rules-gen
                [{:location "$.context.contextActivities.grouping[1].definition.type"
                  :presence "included"
                  :all      ["https://xapinet.com/xapi/blooms/activitytypes/cognitive-process-dimension"]}
                 {:location "$.context.contextActivities.grouping[0].definition.type"
                  :presence "included"
                  :all      ["https://xapinet.com/xapi/blooms/activities/objectives/procedural"]}]
                :seed gen-seed)
               (get-in ["context" "contextActivities" "grouping"]))))))

(deftest apply-rule-gen-distinct-test
  (testing "apply-rules-gen uses all 3 distinct `all` values for 3 locations"
   (let [rule     {:location "$.context.contextActivities.grouping[0,1,2].id"
                   :presence "included"
                   :all      ["http://www.example.com/id-1"
                              "http://www.example.com/id-2"
                              "http://www.example.com/id-3"]}
         expected #{{"id" "http://www.example.com/id-1"}
                    {"id" "http://www.example.com/id-2"}
                    {"id" "http://www.example.com/id-3"}}
         actuals  (repeatedly
                   30
                   #(-> long-statement
                        (r/apply-rules-gen [rule] :seed gen-seed)
                        (get-in ["context" "contextActivities" "grouping"])
                        set))]
     (is (every? #(= expected %) actuals))))
  (testing "apply-rules-gen uses 2 of 3 distinct `all` values for 2 locations"
    (let [rule     {:location "$.context.contextActivities.grouping[0,1].id"
                    :presence "included"
                    :all      ["http://www.example.com/id-1"
                               "http://www.example.com/id-2"
                               "http://www.example.com/id-3"]}
          expect-1 #{{"id" "http://www.example.com/id-1"}
                     {"id" "http://www.example.com/id-2"}}
          expect-2 #{{"id" "http://www.example.com/id-1"}
                     {"id" "http://www.example.com/id-3"}}
          expect-3 #{{"id" "http://www.example.com/id-2"}
                     {"id" "http://www.example.com/id-3"}}
          actuals  (repeatedly
                    30
                    #(-> long-statement
                         (r/apply-rules-gen [rule] :seed gen-seed)
                         (get-in ["context" "contextActivities" "grouping"])
                         set))]
      (is (every? #(#{expect-1 expect-2 expect-3} %) actuals)))))

(deftest apply-rules-gen-exception-test
  (testing "apply-rules-gen throws exceptions if rules are invalid"
    ;; Flat-out invalid Statement property
    (is (= ::r/undefined-path
           (try (r/apply-rules-gen
                 short-statement
                 [{:location "$.object.zooWeeMama"
                   :presence "included"}]
                 :seed gen-seed)
                nil
                (catch ExceptionInfo e (-> e ex-data :type)))))
    ;; Actor name in an Activity object
    (is (= ::r/undefined-path
           (try (r/apply-rules-gen
                 short-statement
                 [{:location "$.object.name"
                   :presence "included"}]
                 :seed gen-seed)
                nil
                (catch ExceptionInfo e (-> e ex-data :type)))))
    ;; Activity definition in an Actor object
    (is (= ::r/undefined-path
           (try (r/apply-rules-gen
                 short-statement
                 [;; First replace the Activity object with an Actor object
                  {:location "$.object"
                   :all      [{"objectType" "Agent"
                               "name"       "Owen Overrider"
                               "mbox"       "mailto:owoverrider@example.com"}]}
                  {:location "$.object.definition.type"
                   :presence "included"}]
                 :seed gen-seed)
                nil
                (catch ExceptionInfo e (-> e ex-data :type)))))
    ;; Rule value pool is empty
    (is (= ::r/invalid-rule-values
           (try (r/apply-rules-gen
                 short-statement
                 [{:location "$.actor.name"
                   :any      ["Alice Faux"]
                   :all      ["Bob Fakename"]}]
                 :seed gen-seed)
                nil
                (catch ExceptionInfo e (-> e ex-data :type)))))
    (is (= ::r/invalid-rule-values
           (try (r/apply-rules-gen
                 short-statement
                 [{:location "$.actor.name"
                   :any      ["Alice Faux"]
                   :none     ["Alice Faux"]}]
                 :seed gen-seed)
                nil
                (catch ExceptionInfo e (-> e ex-data :type)))))
    (is (= ::r/invalid-rule-values
           (try (r/apply-rules-gen
                 short-statement
                 [{:location "$.actor.name"
                   :all      ["Bob Fakename"]
                   :none     ["Bob Fakename"]}]
                 :seed gen-seed)
                nil
                (catch ExceptionInfo e (-> e ex-data :type)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CMI5 Rule Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; We can pull some actual rules from cmi5

(def cmi5-templates
  (:templates const/cmi5-profile))

(def cmi5-template-rules
  (mapcat :rules cmi5-templates))

(def cmi5-statement ; simple statement used for cmi5 tests
  (with-open [r (io/reader const/simple-statement-filepath)]
    (json/parse-stream r)))

(deftest cmi5-rule-tests
  ;; Individual Rule Parsing
  (testing "parse cmi5 rules"
    (is (every?
         (comp nil? (partial s/explain-data ::r/parsed-rule))
         (map r/parse-rule cmi5-template-rules))))
  ;; Individual Rule Application
  (testing "apply cmi5 Rule:"
    (doseq [{:keys [rules]} cmi5-templates
            rule rules]
      (testing (format "Rule: %s" rule)
        (let [processed (r/apply-rules-gen cmi5-statement [rule] :seed gen-seed)]
          (is (r/follows-rule? processed (r/parse-rule rule)))
          (is (nil? (s/explain-data ::xs/statement processed)))))))
  ;; Collected Rules Application
  (testing "apply cmi5 Template:"
    (doseq [{:keys [id rules]} cmi5-templates]
      (testing (format "Template: %s" id)
        (let [processed (r/apply-rules-gen cmi5-statement rules :seed gen-seed)]
          (is (every? (partial r/follows-rule? processed)
                      (map r/parse-rule rules)))
          (is (nil? (s/explain-data ::xs/statement processed))))))))

(comment
  (r/apply-rules-gen
   {"id" "fd41c918-b88b-4b20-a0a5-a4c32391aaa0",
    "timestamp" "2015-11-18T12:17:00+00:00",
    "actor"
    {"objectType" "Agent",
     "name" "Project Tin Can API",
     "mbox" "mailto:user@example.com"},
    "verb"
    {"id" "http://example.com/xapi/verbs#sent-a-statement",
     "display" {"en-US" "sent"}},
    "object"
    {"id" "http://example.com/xapi/activity/simplestatement",
     "definition"
     {"name" {"en-US" "simple statement"},
      "description"
      {"en-US"
       "A simple Experience API statement. Note that the LRS \n\t\t\t\tdoes not need to have any prior information about the Actor (learner), the \n\t\t\t\tverb, or the Activity/object."}}}}
   [{:location "$.object.definition.type"}]
   #_[{:location "$.verb.display.en-US", :all ["Launched"]}]
    {:location "$.verb.display.zh-CN", :all ["展开"]}
    {:location "$.verb.display.zh-CN", :none ["展开"]}
   #_[{:location "$.context.extensions['https://w3id.org/xapi/cmi5/context/extensions/launchmode']"
       :presence "included"
       :all      ["Review" "Normal" "Browse"]}]
   :seed gen-seed 

   (r/follows-rule?
    {"id" "fd41c918-b88b-4b20-a0a5-a4c32391aaa0",
     "timestamp" "2015-11-18T12:17:00+00:00",
     "actor"
     {"objectType" "Agent",
      "name" "Project Tin Can API",
      "mbox" "mailto:user@example.com"},
     "verb"
     {"id" "http://example.com/xapi/verbs#sent-a-statement",
      "display" {"en-US" "sent"}},
     "object"
     {"objectType" "Agent"
      "name"       "Owen Overrider"
      "mbox"       "mailto:owoverrider@example.com"}}
    (r/parse-rule {:location "$.object.definition.type"
                   :presence "included"}))

   (r/apply-rules-gen
    {"id" "fd41c918-b88b-4b20-a0a5-a4c32391aaa0",
     "timestamp" "2015-11-18T12:17:00+00:00",
     "actor"
     {"objectType" "Agent",
      "name" "Project Tin Can API",
      "mbox" "mailto:user@example.com"},
     "verb"
     {"id" "http://example.com/xapi/verbs#sent-a-statement",
      "display" {"en-US" "sent"}},
     "object"
     {"objectType" "Agent"
      "name"       "Owen Overrider"
      "mbox"       "mailto:owoverrider@example.com"}}
    [{:location "$.object.definition.type"
      :presence "included"}]
    #_[{:location "$.context.extensions['https://w3id.org/xapi/cmi5/context/extensions/launchmode']"
        :presence "included"
        :all      ["Review" "Normal" "Browse"]}]
    :seed gen-seed)))
