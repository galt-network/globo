(ns is.galt.globo.server.storage-test
  (:require [clojure.test :refer [deftest is testing]]
            [is.galt.globo.protocols :as protocols]
            [is.galt.globo.server.storage :as storage]))

(deftest user-round-trip-test
  (let [s (storage/in-memory-globo-storage)]
    (is (nil? (protocols/get-user s "u1")))
    (protocols/update-user! s "u1" #(assoc % :id "u1" :name "Alice"))
    (is (= "Alice" (:name (protocols/get-user s "u1"))))
    (is (= [] (protocols/user-favorites s "u1")))
    (is (= {} (protocols/users-map (storage/in-memory-globo-storage))))))

(deftest favorites-test
  (let [s (storage/in-memory-globo-storage)
        favorite {:id "f1" :label "" :lat nil :lng nil}]
    (protocols/update-user! s "u1" (fnil identity {}))
    (protocols/add-favorite! s "u1" favorite)
    (is (= [favorite] (protocols/user-favorites s "u1")))
    (testing "update-favorite! returns the merged favorite"
      (is (= {:id "f1" :label "Home" :lat nil :lng nil}
             (protocols/update-favorite! s "u1" 0 {:label "Home"})))
      (is (= [{:id "f1" :label "Home" :lat nil :lng nil}]
             (protocols/user-favorites s "u1"))))))

(deftest map-objects-test
  (let [s (storage/in-memory-globo-storage)]
    (is (= #{} (protocols/get-map-objects s)))
    (protocols/set-map-objects! s #{{:id "p1"}})
    (is (= #{{:id "p1"}} (protocols/get-map-objects s)))))

(deftest messages-test
  (let [s (storage/in-memory-globo-storage)]
    (is (= [] (protocols/latest-messages s 20)))
    (doseq [m (mapv (fn [i] {:id i}) (range 25))]
      (protocols/append-message! s m))
    (is (= 20 (count (protocols/latest-messages s 20))))
    (is (= [22 23 24] (map :id (protocols/latest-messages s 3))))))

(deftest user-connections-test
  (let [s (storage/in-memory-globo-storage)]
    (is (= #{} (protocols/connection-ids-for-user s "u1")))
    (protocols/add-user-connection! s "u1" "c1")
    (protocols/add-user-connection! s "u1" "c2")
    (is (= #{"c1" "c2"} (protocols/connection-ids-for-user s "u1")))
    (protocols/remove-user-connection! s "u1" "c1")
    (is (= #{"c2"} (protocols/connection-ids-for-user s "u1")))))

(deftest constructor-test
  (testing "wraps an existing atom (back-compat)"
    (let [existing (atom {:users {} :map-objects #{} :user-connections {} :messages []})
          s (storage/in-memory-globo-storage existing)]
      (protocols/update-user! s "u1" (fnil identity {}))
      (is (= {:users {"u1" {}} :map-objects #{} :user-connections {} :messages []}
             @existing)))))
