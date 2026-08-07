(ns is.galt.globo.server.handlers-test
  (:require [clojure.test :refer [deftest is testing]]
            [is.galt.globo.protocols :as protocols]
            [is.galt.globo.server :as globo]
            [is.galt.globo.server.handlers :as handlers]
            [is.galt.globo.server.placeables :as placeables]))

(defn make-globo
  []
  (globo/create-globo))

(deftest users-online-test
  (testing "only users with connections are online"
    (let [g (make-globo)]
      (handlers/register-user! g "u1")
      (handlers/register-user! g "u2")
      (protocols/add-user-connection! (:storage g) "u1" "c1")
      (is (= ["u1"] (map :id (handlers/users-online g))))))
  (testing "empty when nobody is online"
    (is (= [] (handlers/users-online (make-globo))))))

(deftest register-user!-test
  (let [g (make-globo)]
    (handlers/register-user! g "u1")
    (let [user (protocols/get-user (:storage g) "u1")]
      (is (= "u1" (:id user)))
      (is (instance? java.time.Instant (:last-seen-at user)))
      (is (= [] (:favorites user))))))

(deftest initial-burst-events-test
  (let [g (make-globo)
        events (handlers/initial-burst-events g "c-new" "u1")]
    (is (= [:connected :map-objects :users-online :messages :placeable-map-objects]
           (mapv :type events)))
    (is (= {:connection-id "c-new" :user-id "u1"} (:content (first events))))
    (is (= {:objects #{}} (:content (second events))))
    (is (= {:users []} (:content (nth events 2))))
    (is (= {:messages []} (:content (nth events 3))))
    (is (= {:objects placeables/default-config} (:content (last events))))))

(deftest safe-sse-event-test
  (let [g (make-globo)]
    (testing "valid events pass through unchanged"
      (let [event {:type :connected :content {:connection-id "c1" :user-id "u1"}}]
        (is (= event (handlers/safe-sse-event g event)))))
    (testing "invalid events become system-notifications"
      (let [bad {:type :bogus :content {:x 1}}
            replacement (handlers/safe-sse-event g bad)]
        (is (= :system-notification (:type replacement)))
        (is (= :error (get-in replacement [:content :severity])))
        (is (= bad (get-in replacement [:content :event])))))))

(deftest initial-burst-body-test
  (let [g (make-globo)
        body (handlers/initial-burst-body g "c-new" "u1")]
    (is (string? body))
    (is (= 5 (count (re-seq #"data: " body))))))
