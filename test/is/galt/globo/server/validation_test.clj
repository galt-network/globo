(ns is.galt.globo.server.validation-test
  (:require [clojure.test :refer [deftest is testing]]
            [is.galt.globo.server.validation :as validation]))

(def valid-user
  {:id "u1" :name "Alice" :location {:lat 10 :lng 20} :favorites []})

(def valid-message
  {:id "m1" :author {:id "u1" :name "Alice"}
   :type :world :target nil :content "hello"
   :viewport {:lat 1 :lng 2 :altitude 3}
   :sent-at "2026-01-01T00:00:00Z" :received-at nil :seen-at nil})

(def valid-object
  {:id "p1" :lat 1 :lng 2 :model-id "carrot" :scale 10})

(def valid-favorite
  {:id "f1" :label "Home" :lat nil :lng nil})

(deftest inbound-errors-test
  (testing "valid messages pass"
    (is (nil? (validation/inbound-errors {:type :new-message :content {:text "hello"}})))
    (is (nil? (validation/inbound-errors
               {:type :new-message
                :content {:text "hi" :viewport {:lat 1 :lng 2 :altitude 3}}})))
    (is (nil? (validation/inbound-errors
               {:type :update-object :content {:op :add :objects [valid-object]}})))
    (is (nil? (validation/inbound-errors {:type :update-object :content {:op :remove :objects []}})))
    (is (nil? (validation/inbound-errors {:type :update-user :content {:id "u1" :name "Alice"}})))
    (is (nil? (validation/inbound-errors {:type :update-user :content {:id "u1" :location {:lat 1 :lng 2}}})))
    (is (nil? (validation/inbound-errors
               {:type :update-favorite :content {:index 0 :partial {:label "Home"}}})))
    (is (nil? (validation/inbound-errors {:type :add-favorite :content {}})))
    (is (nil? (validation/inbound-errors {:type :add-favorite})))
    (is (nil? (validation/inbound-errors {:type :broadcast :content {}})))
    (is (nil? (validation/inbound-errors
               {:type :paint-hexhold :content {:hex-id "abc" :color "red"}})))
    (is (nil? (validation/inbound-errors
               {:type :paint-hexhold :content {:hex-id "abc" :color nil}})))
    (is (nil? (validation/inbound-errors
               (validation/system-notification :error {:type :x :content {}})))))
  (testing "invalid messages fail"
    (is (= {:content {:text ["should be a string"]}}
           (validation/inbound-errors {:type :new-message :content {:text 42}})))
    (is (= {:type ["invalid dispatch value"]}
           (validation/inbound-errors {:type :bogus :content {}})))
    (is (some? (validation/inbound-errors
                {:type :update-object :content {:op :bogus :objects []}})))
    (is (some? (validation/inbound-errors {:type :new-message :content {}})))
    (is (some? (validation/inbound-errors
                {:type :paint-hexhold :content {:hex-id "abc" :color "orange"}})))
    (is (some? (validation/inbound-errors
                {:type :paint-hexhold :content {:hex-id "abc" :color :red}})))
    (is (some? (validation/inbound-errors
                {:type :paint-hexhold :content {:hex-id 42 :color "red"}})))))

(deftest outbound-errors-test
  (testing "valid events pass"
    (is (nil? (validation/outbound-errors
               {:type :connected :content {:connection-id "c1" :user-id "u1"}})))
    (is (nil? (validation/outbound-errors
               {:type :connected
                :content {:connection-id "c1" :user-id "u1" :max-user-name-length 42}})))
    (is (nil? (validation/outbound-errors {:type :map-objects :content {:objects #{}}})))
    (is (nil? (validation/outbound-errors {:type :users-online :content {:users [valid-user]}})))
    (is (nil? (validation/outbound-errors {:type :messages :content {:messages [valid-message]}})))
    (is (nil? (validation/outbound-errors
               {:type :update-object :content {:op :add :objects [valid-object]}})))
    (is (nil? (validation/outbound-errors {:type :update-user :user-id "u1" :content valid-user})))
    (is (nil? (validation/outbound-errors {:type :user-online :content valid-user})))
    (is (nil? (validation/outbound-errors {:type :user-offline :content {:id "u1"}})))
    (is (nil? (validation/outbound-errors {:type :new-message :content valid-message})))
    (is (nil? (validation/outbound-errors
               {:type :favorite-added :user-id "u1" :content {:index 0 :favorite valid-favorite}})))
    (is (nil? (validation/outbound-errors
               {:type :favorite-updated :user-id "u1" :content {:index 0 :favorite valid-favorite}})))
    (is (nil? (validation/outbound-errors
               {:type :placeable-map-objects
                :content {:objects [{:model-id "carrot" :path "3d/carrot.glb" :scale 10}]}})))
    (is (nil? (validation/outbound-errors
               {:type :hexholds :content {:colors {"a" :red "b" :purple}}})))
    (is (nil? (validation/outbound-errors
               {:type :hexholds :content {:colors {}}})))
    (is (nil? (validation/outbound-errors
               {:type :hexholds-updated :content {:id "abc" :color :red :owner-id "u1"}})))
    (is (nil? (validation/outbound-errors
               {:type :hexholds-updated :content {:id "abc" :color nil :owner-id nil}})))
    (is (nil? (validation/outbound-errors
               (validation/system-notification :warning {:type :bogus :content {}})))))
  (testing "invalid events fail"
    (is (= {:type ["invalid dispatch value"]}
           (validation/outbound-errors {:type :bogus :content {}})))
    (is (some? (validation/outbound-errors {:type :update-user :content valid-user})))
    (is (some? (validation/outbound-errors
                {:type :favorite-added :content {:index 0 :favorite valid-favorite}})))
    (is (some? (validation/outbound-errors {:type :users-online :content {:users [42]}})))
    (is (some? (validation/outbound-errors
                {:type :hexholds :content {:colors {"a" :orange}}})))
    (is (some? (validation/outbound-errors
                {:type :hexholds-updated :content {:color :red}})))
    (is (some? (validation/outbound-errors
                {:type :hexholds-updated :content {:id 42 :color :red}})))))

(deftest system-notification-test
  (let [event {:type :map-objects :content {:objects #{}}}
        notification (validation/system-notification :error event)]
    (is (= :system-notification (:type notification)))
    (is (= "Something went wrong. Please try again." (get-in notification [:content :message])))
    (is (= :error (get-in notification [:content :severity])))
    (is (string? (get-in notification [:content :sent-at])))
    (is (= event (get-in notification [:content :event]))))
  (is (= "Something unexpected happened."
         (get-in (validation/system-notification :warning {:type :x}) [:content :message])))
  (is (= "Update received."
         (get-in (validation/system-notification :info {:type :x}) [:content :message]))))
