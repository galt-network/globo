(ns is.galt.globo.server.handlers-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [is.galt.globo.server.handlers :as handlers]))

(deftest users-online-test
  (testing "only users with open connections are returned"
    (let [storage {:users {"u1" {:id "u1" :name "Alice"}
                           "u2" {:id "u2" :name "Bob"}}
                   :user-connections {"u1" #{"c1"}
                                      "u2" #{}}}]
      (is (= [{:id "u1" :name "Alice"}]
             (handlers/users-online storage)))))
  (testing "empty when no one is connected"
    (is (= [] (handlers/users-online {:user-connections {} :users {}})))))
