(ns is.galt.globo.server.messages-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [is.galt.globo.server.messages :as messages]))

(defn- make-send!
  "Returns a send! fn that records [channels data] calls into `recording`
  and reports that the event was delivered."
  [recording]
  (fn [channels data]
    (swap! recording conj [channels data])
    (boolean (seq channels))))

(defn- make-deps
  "Builds a process deps map with a fresh storage atom, a recording send!,
  and a user registered with one open connection."
  [user-id]
  (let [storage (atom {:users {}
                       :map-objects #{}
                       :user-connections {}
                       :messages []})
        sse-clients (atom {"conn-1" :channel-1 "conn-2" :channel-2})
        recording (atom [])]
    (swap! storage assoc-in [:users user-id]
           {:id user-id :name "Me" :favorites []})
    (swap! storage assoc-in [:user-connections user-id] #{"conn-1"})
    {:storage storage
     :sse-clients sse-clients
     :recording recording
     :send! (make-send! recording)}))

(deftest process-test
  (testing "world message broadcasts to everybody"
    (let [{:keys [storage sse-clients recording send!]} (make-deps "u1")]
      (swap! storage assoc-in [:users "u2"] {:id "u2" :name "Other"})
      (swap! storage assoc-in [:user-connections "u2"] #{"conn-2"})
      (let [result (messages/process
                    {:send! send! :storage storage :sse-clients sse-clients :user-id "u1"}
                    {:type :new-message
                     :content {:text "hello world" :viewport {}}})]
        (is result)
        (is (= 1 (count @recording)))
        (let [[channels message] (first @recording)]
          (is (= #{:channel-1 :channel-2} (set channels)))
          (is (= :world (get-in message [:content :type])))))))

  (testing "@username message is direct to sender + target only"
    (let [{:keys [storage sse-clients recording send!]} (make-deps "u1")]
      (swap! storage assoc-in [:users "u2"] {:id "u2" :name "Other"})
      (swap! storage assoc-in [:user-connections "u2"] #{"conn-2"})
      (messages/process
       {:send! send! :storage storage :sse-clients sse-clients :user-id "u1"}
       {:type :new-message
        :content {:text "@other hi there" :viewport {}}})
      (let [[channels message] (first @recording)]
        (is (= #{:channel-1 :channel-2} (set channels)))
        (is (= :direct (get-in message [:content :type])))
        (is (= #{"u2"} (get-in message [:content :target]))))))

  (testing ":update-object :add merges into map-objects and skips sender"
    (let [{:keys [storage sse-clients recording send!]} (make-deps "u1")]
      (swap! storage assoc-in [:users "u2"] {:id "u2" :name "Other"})
      (swap! storage assoc-in [:user-connections "u2"] #{"conn-2"})
      (let [result (messages/process
                    {:send! send! :storage storage :sse-clients sse-clients :user-id "u1"}
                    {:type :update-object
                     :content {:op :add :objects [{:id "p1" :lat 1 :lng 2}]}})]
        (is result)
        (is (= #{{:id "p1" :lat 1 :lng 2}} (:map-objects @storage)))
        (let [[channels message] (first @recording)]
          (is (= [:channel-2] channels))
          (is (= :update-object (:type message)))))))

  (testing "unknown :type throws ex-info"
    (let [{:keys [storage sse-clients send!]} (make-deps "u1")]
      (is (thrown? clojure.lang.ExceptionInfo
                   (messages/process
                    {:send! send! :storage storage :sse-clients sse-clients :user-id "u1"}
                    {:type :bogus-type :content {}}))))))

(deftest latest-messages-test
  (testing "returns up to limit most recent messages"
    (let [storage {:messages (vec (map (fn [i] {:id i}) (range 25)))}]
      (is (= 20 (count (messages/latest-messages storage))))
      (is (= [22 23 24] (map :id (messages/latest-messages storage 3))))
      (is (= [] (messages/latest-messages {:messages []})))))
  (testing "returns all messages when fewer than limit"
    (let [storage {:messages [{:id 1} {:id 2}]}]
      (is (= [1 2] (map :id (messages/latest-messages storage)))))))
