(ns onyx.health-test
  (:require [clojure.core.async :refer [chan >!! <!! close! sliding-buffer]]
            [clojure.test :refer [deftest is testing]]
            [onyx.plugin.core-async :refer [take-segments!]]
            [onyx.test-helper :refer [load-config with-test-env add-test-env-peers!]]
            [onyx.static.uuid :refer [random-uuid]]
            [onyx.lifecycle.metrics.metrics]
            [com.stuartsierra.component :as component]
            [onyx.monitoring.events :as monitoring] 
            [onyx.http-query]
            [clj-http.client :as client]
            [onyx.api])
  (:import [com.codahale.metrics JmxReporter]))

(def n-messages 100)

(def in-chan (atom nil))
(def in-buffer (atom nil))

(def out-chan (atom nil))

(defn inject-in-ch [event lifecycle]
  {:core.async/buffer in-buffer
   :core.async/chan @in-chan})

(defn inject-out-ch [event lifecycle]
  {:core.async/chan @out-chan})

(def in-calls
  {:lifecycle/before-task-start inject-in-ch})

(def out-calls
  {:lifecycle/before-task-start inject-out-ch})

(defn my-inc [{:keys [n] :as segment}]
  (assoc segment :n (inc n)))

(deftest health-test
  (let [id (random-uuid)
        env-config {:zookeeper/address "127.0.0.1:2188"
                    :zookeeper/server? true
                    :zookeeper.server/port 2188
                    :onyx.bookkeeper/server? true
                    :onyx.bookkeeper/delete-server-data? true
                    :onyx.bookkeeper/local-quorum? true
                    :onyx.bookkeeper/local-quorum-ports [3196 3197 3198]
                    :onyx/tenancy-id id} 
        monitoring-config (component/start (monitoring/new-monitoring))
        peer-config {:zookeeper/address "127.0.0.1:2188"
                     :onyx.peer/job-scheduler :onyx.job-scheduler/greedy
                     :onyx.peer/zookeeper-timeout 60000
                     :onyx.messaging.aeron/embedded-driver? true
                     :onyx.messaging/allow-short-circuit? false
                     :onyx.messaging/impl :aeron
                     :onyx.messaging/peer-port 40199
                     :onyx.messaging/bind-addr "localhost"
                     :onyx.monitoring/config monitoring-config
                     :onyx/tenancy-id id
                     :onyx.query/server? true
                     :onyx.query.server/ip "127.0.0.1"
                     :onyx.query.server/port 8091}]
    (with-test-env [test-env [3 env-config peer-config monitoring-config]]
      (let [batch-size 20
	    catalog [{:onyx/name :in
		      :onyx/plugin :onyx.plugin.core-async/input
		      :onyx/type :input
		      :onyx/medium :core.async
		      :onyx/batch-size batch-size
		      :onyx/max-peers 1
		      :onyx/doc "Reads segments from a core.async channel"}

		     {:onyx/name :inc
		      :onyx/fn :onyx.health-test/my-inc
		      :onyx/type :function
		      :onyx/batch-size batch-size}

		     {:onyx/name :out
		      :onyx/plugin :onyx.plugin.core-async/output
		      :onyx/type :output
		      :onyx/medium :core.async
		      :onyx/batch-size batch-size
		      :onyx/max-peers 1
		      :onyx/doc "Writes segments to a core.async channel"}]
	    workflow [[:in :inc] [:inc :out]]
	    lifecycles [{:lifecycle/task :in
			 :lifecycle/calls :onyx.health-test/in-calls}
			{:lifecycle/task :all
			 :lifecycle/calls :onyx.lifecycle.metrics.metrics/calls
			 :metrics/lifecycles #{:lifecycle/apply-fn 
					       :lifecycle/unblock-subscribers
					       :lifecycle/write-batch
					       :lifecycle/read-batch}
			 :lifecycle/doc "Instruments a task's metrics"}
			{:lifecycle/task :out
			 :lifecycle/calls :onyx.health-test/out-calls}]
	    _ (reset! in-chan (chan (inc n-messages)))
	    _ (reset! in-buffer {})
	    _ (reset! out-chan (chan (sliding-buffer (inc n-messages))))
	    _ (doseq [n (range n-messages)]
		(>!! @in-chan {:n n}))
	    _ (close! @in-chan)
	    job-id (:job-id (onyx.api/submit-job peer-config
						 {:catalog catalog
						  :workflow workflow
						  :lifecycles lifecycles
						  :task-scheduler :onyx.task-scheduler/balanced
						  :metadata {:job-name :click-stream}}))
	    _ (onyx.test-helper/feedback-exception! peer-config job-id)
	    results (take-segments! @out-chan 50)
	    peers (:result (clojure.edn/read-string (:body (client/get "http://127.0.0.1:8091/replica/peers"))))]
        (mapv (fn [[{:keys [uri]} {:keys [query-params-schema]}]]
                (if (= "/metrics" uri)
                  (do
                  (println (:body (client/get (str "http://127.0.0.1:8091" uri) 
                                                  {:query-params {}})))
                   (is (re-find #"replica_version" 
                               (:body (client/get (str "http://127.0.0.1:8091" uri) 
                                                  {:query-params {}})))))
                  (is (= :success 
                         (:status 
                          (doto 
                            (clojure.edn/read-string 
                             (:body (client/get (str "http://127.0.0.1:8091" uri) 
                                                {:query-params {"task-id" "out"
                                                                "peer-id" (first peers)
                                                                "job-id" (str job-id)}})))
                            println)))))) 
              onyx.http-query/endpoints)
        (is (= [job-id] (:result (clojure.edn/read-string (:body (client/get "http://127.0.0.1:8091/replica/completed-jobs"))))))
        (let [expected (set (map (fn [x] {:n (inc x)}) (range n-messages)))]
          (is (= expected (set results))))))))
