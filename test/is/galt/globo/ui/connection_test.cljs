(ns is.galt.globo.ui.connection-test
  "Tests for SSE event parsing."
  (:require
   [cljs.test :refer-macros [deftest is testing]]
   [is.galt.globo.ui.connection :as connection]
   [is.galt.globo.ui.connection.events :as conn-events]))

(deftest parse-event-keywordizes-test
  (testing "top-level :type and :op are keywordized"
    (let [parsed (connection/parse-event
                  "{\"type\":\"update-object\",\"op\":\"add\",\"objects\":[]}")]
      (is (= :update-object (:type parsed)))
      (is (= :add (:op parsed)))))

  (testing "nested objects keep :type and :op keywordized"
    (let [parsed (connection/parse-event
                  "{\"type\":\"new-message\",\"content\":{\"type\":\"world\"}}")]
      (is (= :world (get-in parsed [:content :type]))))))

(deftest parse-event-data-test
  (testing "non-type keys survive parsing"
    (let [parsed (connection/parse-event
                  "{\"type\":\"users-online\",\"content\":{\"users\":[{\"id\":\"u1\",\"name\":\"alice\"}]}}")]
      (is (= "u1" (get-in parsed [:content :users 0 :id])))
      (is (= "alice" (get-in parsed [:content :users 0 :name]))))))

(deftest sse-type->event-test
  (testing "placeable-map-objects dispatches to update-placeable-map-objects"
    (let [content {:objects [{:model-id "carrot" :path "3d/carrot.glb"}]}]
      (is (= [::conn-events/update-placeable-map-objects content]
             (conn-events/sse-type->event {:type :placeable-map-objects
                                           :content content})))))

  (testing "system-notification dispatches to system-notification event"
    (let [content {:message "Something went wrong."
                   :severity :error
                   :sent-at "2026-01-01T00:00:00Z"
                   :event {:type :bogus}}]
      (is (= [::conn-events/system-notification content]
             (conn-events/sse-type->event {:type :system-notification
                                           :content content})))))

  (testing "hexhold-message dispatches to receive-hexhold-message"
    (let [content {:hex-id "abc"
                   :message {:id "m1"
                             :author {:id "u1" :name "Me"}
                             :content "hi"
                             :sent-at "2026-01-01T00:00:00Z"}}]
      (is (= [:is.galt.globo.ui.events/receive-hexhold-message content]
             (conn-events/sse-type->event {:type :hexhold-message
                                           :content content})))))

  (testing "unknown types return nil"
    (is (nil? (conn-events/sse-type->event {:type :totally-unknown
                                            :content {}})))))
