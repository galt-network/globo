(ns is.galt.globo.ui.connection-test
  "Tests for SSE event parsing."
  (:require
   [cljs.test :refer-macros [deftest is testing]]
   [is.galt.globo.ui.connection :as connection]))

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
