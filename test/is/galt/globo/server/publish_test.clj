(ns is.galt.globo.server.publish-test
  (:require [clojure.test :refer [deftest is testing]]
            [cheshire.core :as json]
            [clojure.walk :as walk]
            [is.galt.globo.protocols :as protocols]
            [is.galt.globo.server :as globo]
            [is.galt.globo.server.handlers :as handlers]
            [is.galt.globo.server.publish :as publish]))

(defn make-globo
  "Globo with u1 on conn-1 and u2 on conn-2; returns [globo sent logged]."
  []
  (let [sent (atom [])
        logged (atom [])
        g (globo/create-globo {:log-fn (fn [& args] (swap! logged conj args))})]
    (handlers/register-user! g "u1")
    (handlers/register-user! g "u2")
    (protocols/update-user! (:storage g) "u1" #(assoc % :name "Me"))
    (protocols/update-user! (:storage g) "u2" #(assoc % :name "Other"))
    (protocols/add-user-connection! (:storage g) "u1" "conn-1")
    (protocols/add-user-connection! (:storage g) "u2" "conn-2")
    (protocols/add-connection! (:connections g) "conn-1" :channel-1)
    (protocols/add-connection! (:connections g) "conn-2" :channel-2)
    [g sent logged]))

(defn with-recording-send!
  [sent f]
  (with-redefs [org.httpkit.server/send! (fn [ch data & _]
                                            (swap! sent conj [ch data])
                                            true)]
    (f)))

(defn recorded-event
  [sent]
  (-> (second (first @sent)) (subs 6) json/parse-string (walk/keywordize-keys)))

(deftest resolve-target-ids-test
  (let [[g _ _] (make-globo)
        ctx {:globo g :user-id "u1"}]
    (is (= #{"conn-1" "conn-2"} (publish/resolve-target-ids ctx :everybody)))
    (is (= #{"conn-1"} (publish/resolve-target-ids ctx :sender)))
    (is (= #{"conn-2"} (publish/resolve-target-ids ctx :all-but-sender)))
    (is (= #{"conn-1" "conn-2"}
           (publish/resolve-target-ids ctx (fn [registry] (keys registry)))))
    (is (= #{"conn-2"} (publish/resolve-target-ids ctx ["conn-2"])))
    (is (= #{} (publish/resolve-target-ids {:globo g} :sender)))
    (is (= #{} (publish/resolve-target-ids ctx (fn [_] []))))))

(deftest publish-valid-event-test
  (let [[g sent _] (make-globo)]
    (with-recording-send! sent
      #(is (true? (publish/publish! g :everybody
                                    {:type :map-objects :content {:objects #{}}}))))
    (is (= #{:channel-1 :channel-2} (set (map first @sent))))
    (let [event (recorded-event sent)]
      (is (= "map-objects" (:type event)))
      (is (= [] (get-in event [:content :objects]))))))

(deftest publish-invalid-event-test
  (let [[g sent logged] (make-globo)
        bad {:type :bogus :content {:x 1}}]
    (with-recording-send! sent
      #(is (true? (publish/publish! g :everybody bad))))
    (is (= #{:channel-1 :channel-2} (set (map first @sent))))
    (let [event (recorded-event sent)]
      (is (= "system-notification" (:type event)))
      (is (= {:type "bogus" :content {:x 1}} (get-in event [:content :event]))))
    (is (some #(re-find #"invalid SSE event dropped" (pr-str %)) @logged))))

(deftest publish-invalid-system-notification-test
  (testing "an invalid system-notification is logged but never replaced (no recursion)"
    (let [[g sent logged] (make-globo)]
      (with-recording-send! sent
        #(is (false? (publish/publish! g :everybody {:type :system-notification :content {:bad 1}}))))
      (is (empty? @sent))
      (is (seq @logged)))))

(deftest publish-empty-target-test
  (let [[g sent _] (make-globo)]
    (with-recording-send! sent
      #(is (false? (publish/publish! g [] {:type :map-objects :content {:objects #{}}}))))
    (with-recording-send! sent
      #(is (false? (publish/publish! g :sender {:type :map-objects :content {:objects #{}}}))))
    (is (empty? @sent))))
