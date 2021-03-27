(ns com.yetanalytics.datasim.onyx.main
  (:gen-class)
  (:require [onyx.plugin.http-output :as http]
            [onyx.plugin.s3-output]
            onyx.plugin.seq
            [cheshire.core]
            onyx.api
            com.yetanalytics.datasim.onyx.util
            [com.yetanalytics.datasim.onyx.job :as job]
            [com.yetanalytics.datasim.onyx.seq :as dseq]
            [com.yetanalytics.datasim.onyx.config :as config]
            [com.yetanalytics.datasim.onyx.peer :as peer]
            [com.yetanalytics.datasim.onyx.aeron-media-driver :as driver]
            [com.yetanalytics.datasim.onyx.repl :as repl]
            [clojure.string :as string]
            [clojure.tools.cli :refer [parse-opts]]
            [nrepl.server :as nrepl]
            [cider.nrepl :refer [cider-nrepl-handler]]
            [clojure.pprint :refer [pprint]]
            onyx.http-query)
  (:import [java.net InetAddress]))

(def cli-options
  [;; Peer
   ["-n" "--n-vpeers N_VPEERS" "Number of VPEERS to launch. Overrides config value."
    :parse-fn #(Integer/parseInt %)]
   ;; Peer + Submit
   ["-t" "--tenancy-id TENANCY_ID" "Onyx Tenancy ID"]
   ;; Submit
   ;; sim
   ["-i" "--input-loc INPUT_LOC" "DATASIM input location"]
   ["-m" "--override-max OVERRIDE_MAX" "Override max statements"
    :parse-fn #(Integer/parseInt %)]
   ;; Gen overrides
   [nil "--[no-]strip-ids" "Strip IDs from generated statements" :default false]
   [nil "--[no-]remove-refs" "Filter out statement references" :default false]

   ["-g" "--gen-concurrency GEN_CONCURRENCY" "Desired concurrency of generation."
    :default 1
    :parse-fn #(Integer/parseInt %)]
   [nil "--gen-batch-size GEN_BATCH_SIZE" "Generation Batch Size"
    :default 20
    :parse-fn #(Integer/parseInt %)]

   ["-o" "--out-concurrency OUT_CONCURRENCY" "Desired concurrency of output"
    :default 1
    :parse-fn #(Integer/parseInt %)]
   [nil "--out-batch-size OUT_BATCH_SIZE" "Batch Size of Output"
    :default 20
    :parse-fn #(Integer/parseInt %)]


   ;; LRS OUT ;; TODO: reintegrate
   #_#_#_#_#_#_#_
   ["-e" "--endpoint ENDPOINT" "xAPI LRS Endpoint like https://lrs.example.org/xapi"]
   ["-u" "--username USERNAME" "xAPI LRS BASIC Auth username"]
   ["-p" "--password PASSWORD" "xAPI LRS BASIC Auth password"]
   [nil "--x-api-key X_API_KEY" "API Gateway API key"]
   [nil "--retry-base-sleep-ms RETRY_BASE_SLEEP_MS" "Backoff retry sleep time"
    :default 500 ;; real cool about it
    :parse-fn #(Integer/parseInt %)]
   [nil "--retry-max-sleep-ms RETRY_MAX_SLEEP_MS" "Backoff retry sleep time max per retry."
    :default 30000 ;; every 30 sec max
    :parse-fn #(Integer/parseInt %)]
   [nil "--retry-max-total-sleep-ms RETRY_MAX_TOTAL_SLEEP_MS" "Backoff retry sleep time max total."
    :default 3600000 ;; an hour, why not
    :parse-fn #(Integer/parseInt %)]
   ;; S3 OUT
   [nil "--s3-bucket S3_BUCKET" "S3 out bucket"]
   [nil "--s3-prefix S3_PREFIX" "S3 out bucket base prefix"]
   [nil "--s3-prefix-separator S3_PREFIX_SEPARATOR" "S3 path separator, default is /"
    :default "/"]
   [nil "--s3-encryption S3_BUCKET_ENCRYPTION" "S3 Encryption scheme, :none (default) or :sse256"
    :parse-fn keyword
    :default :none]
   [nil "--s3-max-concurrent-uploads S3_MAX_CONCURRENT_UPLOADS" "S3 Max concurrent uploads per peer"
    :default 20]



   ;; Blocking (a little hard to predict)
   ["-b" "--[no-]block" "Block until the job is done" :default true]
   ;; Embedded REPL TODO: Use it!
   [nil "--nrepl-bind NREPL_BIND" "If provided on peer launch will start an nrepl server bound to this address"
    :default "0.0.0.0"]
   [nil "--nrepl-port NREPL_PORT" "If provided on peer launch will start an nrepl server on this port"
    :parse-fn #(Integer/parseInt %)]
   ["-h" "--help"]])

(defn usage [options-summary]
  (->> ["DATASIM Cluster CLI"
        ""
        "Usage: bin/onyx.sh [options] action"
        ""
        "Options:"
        options-summary
        ""
        "Actions:"
        "  start-peer            Start an onyx peer"
        "  start-driver          Start an aeron media driver"
        "  submit-job            Submit a datasim input for submission to an LRS"
        "  gc                    Trigger an onyx GC"
        "  gc-checkpoints JOB_ID Trigger a Checkpoint GC"
        "  repl                  Start a local repl"
        ]
       (string/join \newline)))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (string/join \newline errors)))

(defn validate-args
  "Validate command line arguments. Either return a map indicating the program
  should exit (with a error message, and optional ok status), or a map
  indicating the action the program should take and the options provided."
  [args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)]
    (cond
      (:help options) ; help => exit OK with usage summary
      {:exit-message (usage summary) :ok? true}
      errors ; errors => exit with description of errors
      {:exit-message (error-msg errors)}
      ;; custom validation on arguments
      (#{"start-peer"
         "start-driver"
         "submit-job"
         "gc"
         "gc-checkpoints"
         "repl"} (first arguments))
      {:action (first arguments) :action-args (rest arguments) :options options}
      :else ; failed custom validation => exit with usage summary
      {:exit-message (usage summary)})))

(defn exit [status msg]
  (println msg)
  (System/exit status))

(defn -main [& args]
  (let [{:keys [action action-args options exit-message ok?]} (validate-args args)]
    (if exit-message
      (exit (if ok? 0 1) exit-message)
      (case action
        "submit-job"
        (let [{:keys [tenancy-id

                      block

                      input-loc
                      override-max
                      strip-ids
                      remove-refs

                      #_#_#_#_#_#_#_
                      endpoint
                      username
                      password
                      x-api-key
                      retry-base-sleep-ms
                      retry-max-sleep-ms
                      retry-max-total-sleep-ms

                      gen-concurrency
                      gen-batch-size
                      out-concurrency
                      out-batch-size

                      s3-bucket
                      s3-prefix
                      s3-prefix-separator
                      s3-encryption
                      s3-max-concurrent-uploads]} options]
          (println "Starting job...")
          (let [{:keys [peer-config]} (cond-> (config/get-config)
                                        tenancy-id (assoc-in [:peer-config :onyx/tenancy-id] tenancy-id))
                job-config (job/config
                            {:input-json (slurp input-loc)
                             :strip-ids? strip-ids
                             :remove-refs? remove-refs
                             :override-max override-max

                             :gen-concurrency gen-concurrency
                             :gen-batch-size gen-batch-size
                             :out-concurrency out-concurrency
                             :out-batch-size out-batch-size

                             :s3-bucket s3-bucket
                             :s3-prefix s3-prefix
                             :s3-prefix-separator s3-prefix-separator
                             :s3-encryption s3-encryption
                             :s3-max-concurrent-uploads s3-max-concurrent-uploads
                             }
                            #_{:input-json (slurp input-loc)
                             :gen-concurrency gen-concurrency
                             :post-concurrency post-concurrency
                             :batch-size onyx-batch-size
                             :strip-ids? strip-ids
                             :remove-refs? remove-refs
                             :override-max override-max

                             :s3-bucket s3-bucket
                             :s3-prefix s3-prefix
                             :s3-prefix-separator s3-prefix-separator
                             :s3-encryption s3-encryption
                             :s3-max-concurrent-uploads s3-max-concurrent-uploads
                             ;; LRS POST
                             :retry-params
                             {:base-sleep-ms retry-base-sleep-ms
                              :max-sleep-ms retry-max-sleep-ms
                              :max-total-sleep-ms retry-max-total-sleep-ms}
                             :lrs {:endpoint endpoint
                                   :username username
                                   :password password
                                   :x-api-key x-api-key
                                   :batch-size lrs-batch-size}})
                _ (pprint {:job-config (update job-config
                                               :lifecycles
                                               (fn [ls]
                                                 (mapv (fn [lc]
                                                         (if (:com.yetanalytics.datasim.onyx.seq/input-json lc)
                                                           (assoc lc :com.yetanalytics.datasim.onyx.seq/input-json "<json>")
                                                           lc))
                                                       ls)))})
                submission (onyx.api/submit-job
                            peer-config
                            job-config)]
            (println "submitted!")
            (clojure.pprint/pprint submission)
            (when block
              (println "blocking...")
              (flush)
              (println "job complete!"
                       (onyx.api/await-job-completion peer-config (:job-id submission))))

            (exit 0 "OK")))
        "gc"
        (let [{:keys [tenancy-id]} options]
          (println "Performing gc...")
          (let [{:keys [peer-config]} (cond-> (config/get-config)
                                        tenancy-id (assoc-in [:peer-config :onyx/tenancy-id] tenancy-id))
                gc-ret (onyx.api/gc peer-config)]
            (println "gc result: " gc-ret)

            (if (true? gc-ret)
              (exit 0 "OK")
              (exit 1 "GC FAIL"))))

        "gc-checkpoints"
        (let [{:keys [tenancy-id]} options]
          (println "Performing gc...")
          (if-let [job-id (some-> action-args
                                  first
                                  not-empty
                                  (java.util.UUID/fromString))]
            (let [{:keys [peer-config]} (cond-> (config/get-config)
                                          tenancy-id (assoc-in [:peer-config :onyx/tenancy-id] tenancy-id))
                  gcc-ret (onyx.api/gc-checkpoints peer-config job-id)]
              (println "gc checkpoints result: " gcc-ret)

              (exit 0 "OK"))
            (exit 1 "Job ID Required")))

        "start-peer"
        (let [{:keys [tenancy-id
                      n-vpeers
                      nrepl-port
                      nrepl-bind]} options]
          (println "Preparing to start peers...")
          (when n-vpeers
            (println "Overriding number of virtual peers to " n-vpeers))
          (when nrepl-port
            (println "Starting Nrepl on port " nrepl-port)
            (nrepl/start-server
             :bind nrepl-bind
             :port nrepl-port
             :handler cider-nrepl-handler))
          (peer/start-peer!
           ;; Config overrides
           (cond-> (config/get-config)
             tenancy-id
             (->
              (assoc-in [:env-config :onyx/tenancy-id] tenancy-id)
              (assoc-in [:peer-config :onyx/tenancy-id] tenancy-id))
             n-vpeers
             (assoc-in [:launch-config :n-vpeers] n-vpeers))))
        "start-driver"
        (driver/start-driver!)
        "repl"
        (repl/repl!)))))
