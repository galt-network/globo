(ns is.galt.globo.server.handlers-test
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
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
    (is (= [:connected :map-objects :users-online :messages :placeable-map-objects :hexholds]
           (mapv :type events)))
    (is (= {:connection-id "c-new" :user-id "u1" :max-user-name-length 42}
           (:content (first events))))
    (is (= {:objects #{}} (:content (second events))))
    (is (= {:users []} (:content (nth events 2))))
    (is (= {:messages []} (:content (nth events 3))))
    (is (= {:objects placeables/default-config} (:content (nth events 4))))
    (is (= {:colors {}} (:content (last events))))))

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
    (is (= 6 (count (re-seq #"data: " body))))))

(deftest send-message-handler-json-test
  (testing "string :op from a JSON body is keywordized and accepted"
    (let [g (make-globo)
          object {:id "p1" :lat 1 :lng 2 :model-id "carrot" :scale 10}]
      (handlers/register-user! g "u1")
      (handlers/register-user! g "u2")
      (protocols/add-user-connection! (:storage g) "u2" "conn-2")
      (protocols/add-connection! (:connections g) "conn-2" :channel-2)
      (with-redefs [org.httpkit.server/send! (fn [ch data & _] true)]
        (let [body (json/generate-string {:type "update-object" :connection-id "conn-1"
                                          :content {:op "add" :objects [object]}})
              resp (handlers/send-message-handler g
                                                  {:user-id "u1"
                                                   :body (io/input-stream (.getBytes body))})]
          (is (= 200 (:status resp)))
          (is (= #{object} (protocols/get-map-objects (:storage g)))))))))

(deftest send-message-handler-single-user-test
  (testing "placement by a lone connected user returns 200 (echo to sender)"
    (let [g (make-globo)
          object {:id "p1" :lat 1 :lng 2 :model-id "carrot" :scale 10}]
      (handlers/register-user! g "u1")
      (protocols/add-user-connection! (:storage g) "u1" "conn-1")
      (protocols/add-connection! (:connections g) "conn-1" :channel-1)
      (with-redefs [org.httpkit.server/send! (fn [ch data & _] true)]
        (let [body (json/generate-string {:type "update-object" :connection-id "conn-1"
                                          :content {:op "add" :objects [object]}})
              resp (handlers/send-message-handler g
                                                  {:user-id "u1"
                                                   :body (io/input-stream (.getBytes body))})]
          (is (= 200 (:status resp)))
          (is (= #{object} (protocols/get-map-objects (:storage g)))))))))

(deftest send-message-handler-add-favorite-test
  (testing "content-less add-favorite JSON body returns 200 and appends a favorite"
    (let [g (make-globo)]
      (handlers/register-user! g "u1")
      (protocols/add-user-connection! (:storage g) "u1" "conn-1")
      (protocols/add-connection! (:connections g) "conn-1" :channel-1)
      (with-redefs [org.httpkit.server/send! (fn [ch data & _] true)]
        (let [body (json/generate-string {:type "add-favorite" :connection-id "conn-1"})
              resp (handlers/send-message-handler g
                                                  {:user-id "u1"
                                                   :body (io/input-stream (.getBytes body))})
              favorites (protocols/user-favorites (:storage g) "u1")]
          (is (= 200 (:status resp)))
          (is (= 1 (count favorites)))
          (is (= "" (:label (first favorites)))))))))

(deftest send-message-handler-update-user-rejection-test
  (testing "over-limit name returns 409 with error and details, storage untouched"
    (let [g (make-globo)
          long-name (apply str (repeat 43 "x"))]
      (handlers/register-user! g "u1")
      (protocols/update-user! (:storage g) "u1" #(assoc % :name "Me"))
      (protocols/add-user-connection! (:storage g) "u1" "conn-1")
      (protocols/add-connection! (:connections g) "conn-1" :channel-1)
      (with-redefs [org.httpkit.server/send! (fn [ch data & _] true)]
        (let [body (json/generate-string {:type "update-user" :connection-id "conn-1"
                                          :content {:id "u1" :name long-name}})
              resp (handlers/send-message-handler g
                                                  {:user-id "u1"
                                                   :body (io/input-stream (.getBytes body))})
              parsed (-> resp :body json/parse-string)]
          (is (= 409 (:status resp)))
          (is (= "error" (get parsed "status")))
          (is (= 42 (get-in parsed ["details" "max"])))
          (is (= 43 (get-in parsed ["details" "actual"])))
          (is (= "Me" (:name (protocols/get-user (:storage g) "u1"))))))))
  (testing "valid rename returns 200"
    (let [g (make-globo)]
      (handlers/register-user! g "u1")
      (protocols/update-user! (:storage g) "u1" #(assoc % :name "Me"))
      (protocols/add-user-connection! (:storage g) "u1" "conn-1")
      (protocols/add-connection! (:connections g) "conn-1" :channel-1)
      (with-redefs [org.httpkit.server/send! (fn [ch data & _] true)]
        (let [body (json/generate-string {:type "update-user" :connection-id "conn-1"
                                          :content {:id "u1" :name "Alice"}})
              resp (handlers/send-message-handler g
                                                  {:user-id "u1"
                                                   :body (io/input-stream (.getBytes body))})]
          (is (= 200 (:status resp)))
          (is (= "Alice" (:name (protocols/get-user (:storage g) "u1")))))))))

(deftest hexholds-query-handler-test
  (testing "valid request returns 200 with land cells joined with colors"
    (let [g (globo/create-globo {:hexholds (is.galt.globo.server.hexholds/in-memory-hexhold-store
                                            #{"a" "b"})})
          _ (protocols/paint-hexhold! (:hexholds g) "b" :red "u1")
          body (json/generate-string {:cells ["a" "b" "ocean"]})
          resp (handlers/hexholds-query-handler g
                                                {:body (io/input-stream (.getBytes body))})]
      (is (= 200 (:status resp)))
      (is (= [{"id" "a" "color" nil "owner-id" nil} {"id" "b" "color" "red" "owner-id" "u1"}]
             (->> (-> resp :body json/parse-string (get "hexholds"))
                  (sort-by #(get % "id")))))))
  (testing "malformed body returns 400"
    (let [g (make-globo)
          resp (handlers/hexholds-query-handler g
                                                {:body (io/input-stream (.getBytes "not json"))})]
      (is (= 400 (:status resp)))))
  (testing "non-string cells return 400"
    (let [g (make-globo)
          body (json/generate-string {:cells [1 2]})
          resp (handlers/hexholds-query-handler g
                                                {:body (io/input-stream (.getBytes body))})]
      (is (= 400 (:status resp))))))

(deftest hexhold-messages-handler-test
  (testing "valid request returns 200 with the hex's messages"
    (let [g (make-globo)
          _ (protocols/add-hexhold-message! (:hexholds g) "a" {:id "u1" :name "Me"} "hello")
          body (json/generate-string {:hex-id "a"})
          resp (handlers/hexhold-messages-handler g
                                                  {:body (io/input-stream (.getBytes body))})]
      (is (= 200 (:status resp)))
      (is (= 1 (count (-> resp :body json/parse-string (get "messages")))))))
  (testing "unknown hex returns an empty messages list"
    (let [g (make-globo)
          body (json/generate-string {:hex-id "nope"})
          resp (handlers/hexhold-messages-handler g
                                                  {:body (io/input-stream (.getBytes body))})]
      (is (= 200 (:status resp)))
      (is (= [] (-> resp :body json/parse-string (get "messages"))))))
  (testing "malformed body returns 400"
    (let [g (make-globo)
          resp (handlers/hexhold-messages-handler g
                                                  {:body (io/input-stream (.getBytes "not json"))})]
      (is (= 400 (:status resp)))))
  (testing "missing hex-id returns 400"
    (let [g (make-globo)
          body (json/generate-string {})
          resp (handlers/hexhold-messages-handler g
                                                  {:body (io/input-stream (.getBytes body))})]
      (is (= 400 (:status resp))))))
