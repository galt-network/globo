(ns is.galt.globo.server.messages-test
  (:require [clojure.test :refer [deftest is testing]]
            [cheshire.core :as json]
            [clojure.walk :as walk]
            [is.galt.globo.protocols :as protocols]
            [is.galt.globo.server :as globo]
            [is.galt.globo.server.handlers :as handlers]
            [is.galt.globo.server.messages :as messages]
            [is.galt.globo.server.storage :as storage]))

(defn make-globo
  "Globo with u1 (Me, conn-1) and u2 (Other, conn-2)."
  []
  (let [g (globo/create-globo)]
    (handlers/register-user! g "u1")
    (handlers/register-user! g "u2")
    (protocols/update-user! (:storage g) "u1" #(assoc % :name "Me"))
    (protocols/update-user! (:storage g) "u2" #(assoc % :name "Other"))
    (protocols/add-user-connection! (:storage g) "u1" "conn-1")
    (protocols/add-user-connection! (:storage g) "u2" "conn-2")
    (protocols/add-connection! (:connections g) "conn-1" :channel-1)
    (protocols/add-connection! (:connections g) "conn-2" :channel-2)
    g))

(defn with-recording-send!
  "Runs f with org.httpkit.server/send! redefined to record [ch data] into sent."
  [sent f]
  (with-redefs [org.httpkit.server/send! (fn [ch data & _]
                                           (swap! sent conj [ch data])
                                           true)]
    (f)))

(defn recorded-event
  "First recorded SSE payload parsed as Clojure data (JSON round-trip
  loses set-ness, so compare content with seq semantics)."
  [sent]
  (-> (second (first @sent)) (subs 6) json/parse-string (walk/keywordize-keys)))

(def valid-object
  {:id "p1" :lat 1 :lng 2 :model-id "carrot" :scale 10})

(deftest process-test
  (testing "world message broadcasts to everybody"
    (let [g (make-globo) sent (atom [])]
      (with-recording-send! sent
        #(is (true? (messages/process {:globo g :user-id "u1"}
                                      {:type :new-message :content {:text "hello world"}}))))
      (is (= 2 (count @sent)))
      (is (= #{:channel-1 :channel-2} (set (map first @sent))))
      (let [event (recorded-event sent)]
        (is (= "new-message" (:type event)))
        (is (= "world" (get-in event [:content :type])))
        (is (nil? (get-in event [:content :target])))
        (is (= "hello world" (get-in event [:content :content]))))))
  (testing "direct message @user goes to sender and target"
    (let [g (make-globo) sent (atom [])]
      (with-recording-send! sent
        #(messages/process {:globo g :user-id "u1"}
                           {:type :new-message :content {:text "@other hi there"}}))
      (is (= #{:channel-1 :channel-2} (set (map first @sent))))
      (let [event (recorded-event sent)]
        (is (= "direct" (get-in event [:content :type])))
        (is (= #{"u2"} (set (get-in event [:content :target])))))))
  (testing "unknown @recipient falls back to world"
    (let [g (make-globo) sent (atom [])]
      (with-recording-send! sent
        #(messages/process {:globo g :user-id "u1"}
                           {:type :new-message :content {:text "@ghost hi"}}))
      (is (= "world" (get-in (recorded-event sent) [:content :type])))))
  (testing "update-object :add updates storage and broadcasts to everybody"
    (let [g (make-globo) sent (atom [])]
      (with-recording-send! sent
        #(messages/process {:globo g :user-id "u1"}
                           {:type :update-object :content {:op :add :objects [valid-object]}}))
      (is (= #{valid-object} (protocols/get-map-objects (:storage g))))
      (is (= #{:channel-1 :channel-2} (set (map first @sent))))
      (let [event (recorded-event sent)]
        (is (= "update-object" (:type event)))
        (is (= "add" (get-in event [:content :op]))))))
  (testing "update-object :remove"
    (let [g (make-globo) sent (atom [])]
      (protocols/set-map-objects! (:storage g) #{valid-object})
      (with-recording-send! sent
        #(messages/process {:globo g :user-id "u1"}
                           {:type :update-object :content {:op :remove :objects [valid-object]}}))
      (is (= #{} (protocols/get-map-objects (:storage g))))
      (is (= #{:channel-1 :channel-2} (set (map first @sent))))))
  (testing "update-object :add with a single connected user succeeds (echo to sender)"
    (let [g (globo/create-globo) sent (atom [])]
      (handlers/register-user! g "u1")
      (protocols/add-user-connection! (:storage g) "u1" "conn-1")
      (protocols/add-connection! (:connections g) "conn-1" :channel-1)
      (with-recording-send! sent
        #(is (true? (messages/process {:globo g :user-id "u1"}
                                      {:type :update-object
                                       :content {:op :add :objects [valid-object]}}))))
      (is (= [:channel-1] (map first @sent)))
      (is (= #{valid-object} (protocols/get-map-objects (:storage g))))))
  (testing "update-object with unrecognized :op throws"
    (let [g (make-globo)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (messages/process {:globo g :user-id "u1"}
                                     {:type :update-object :content {:op :bogus :objects []}})))))
  (testing "update-user merges name/location and broadcasts to everybody"
    (let [g (make-globo) sent (atom [])]
      (with-recording-send! sent
        #(messages/process {:globo g :user-id "u1"}
                           {:type :update-user :content {:id "u1" :name "Renamed"}}))
      (is (= "Renamed" (:name (protocols/get-user (:storage g) "u1"))))
      (is (= #{:channel-1 :channel-2} (set (map first @sent))))
      (let [event (recorded-event sent)]
        (is (= "update-user" (:type event)))
        (is (= "u1" (:user-id event)))
        (is (= "Renamed" (get-in event [:content :name]))))))
  (testing "update-favorite notifies own connections"
    (let [g (make-globo) sent (atom [])]
      (protocols/update-user! (:storage g) "u1"
                              #(assoc % :favorites [{:id "f1" :label "Old" :lat nil :lng nil}]))
      (with-recording-send! sent
        #(messages/process {:globo g :user-id "u1"}
                           {:type :update-favorite :content {:index 0 :partial {:label "Home"}}}))
      (is (= [{:id "f1" :label "Home" :lat nil :lng nil}]
             (protocols/user-favorites (:storage g) "u1")))
      (is (= [:channel-1] (map first @sent)))
      (let [event (recorded-event sent)]
        (is (= "favorite-updated" (:type event)))
        (is (= 0 (get-in event [:content :index])))
        (is (= {:id "f1" :label "Home" :lat nil :lng nil}
               (get-in event [:content :favorite]))))))
  (testing "add-favorite notifies own connections"
    (let [g (make-globo) sent (atom [])]
      (with-recording-send! sent
        #(messages/process {:globo g :user-id "u1"} {:type :add-favorite :content {}}))
      (is (= 1 (count (protocols/user-favorites (:storage g) "u1"))))
      (is (= [:channel-1] (map first @sent)))
      (let [event (recorded-event sent)]
        (is (= "favorite-added" (:type event)))
        (is (= 0 (get-in event [:content :index])))
        (is (= "" (get-in event [:content :favorite :label])))
        (is (string? (get-in event [:content :favorite :id]))))))
  (testing "user-offline announces when no connections remain"
    (let [g (make-globo) sent (atom [])]
      (protocols/remove-user-connection! (:storage g) "u1" "conn-1")
      (with-recording-send! sent
        #(messages/process {:globo g :user-id "u1"}
                           {:type :user-offline :connection-id "conn-1" :content {:id "u1"}}))
      (is (= #{:channel-1 :channel-2} (set (map first @sent))))
      (is (= "u1" (get-in (recorded-event sent) [:content :id])))))
  (testing "user-offline stays silent while connections remain"
    (let [g (make-globo) sent (atom [])]
      (with-recording-send! sent
        #(messages/process {:globo g :user-id "u1"}
                           {:type :user-offline :connection-id "conn-1" :content {:id "u1"}}))
      (is (empty? @sent))))
  (testing "paint-hexhold stores the color and broadcasts to everybody"
    (let [g (make-globo) sent (atom [])]
      (with-recording-send! sent
        #(messages/process {:globo g :user-id "u1"}
                           {:type :paint-hexhold :content {:hex-id "abc" :color :red}}))
      (is (= {"abc" :red} (protocols/hexhold-colors (:hexholds g))))
      (is (= #{:channel-1 :channel-2} (set (map first @sent))))
      (let [event (recorded-event sent)]
        (is (= "hexholds-updated" (:type event)))
        (is (= "abc" (get-in event [:content :id])))
        (is (= "red" (get-in event [:content :color]))))))
  (testing "paint-hexhold with nil color clears the cell"
    (let [g (make-globo) sent (atom [])]
      (protocols/paint-hexhold! (:hexholds g) "abc" :blue "u1")
      (with-recording-send! sent
        #(messages/process {:globo g :user-id "u1"}
                           {:type :paint-hexhold :content {:hex-id "abc" :color nil}}))
      (is (= {} (protocols/hexhold-colors (:hexholds g))))
      (let [event (recorded-event sent)]
        (is (= "hexholds-updated" (:type event)))
        (is (= "abc" (get-in event [:content :id])))
        (is (nil? (get-in event [:content :color]))))))
  (testing "paint-hexhold by a non-owner is rejected with a warning to the sender only"
    (let [g (make-globo) sent (atom [])]
      (with-recording-send! sent
        (fn []
          (messages/process {:globo g :user-id "u1"}
                            {:type :paint-hexhold :content {:hex-id "abc" :color :red}})
          (reset! sent [])
          (messages/process {:globo g :user-id "u2"}
                            {:type :paint-hexhold :content {:hex-id "abc" :color :blue}})))
      (is (= {"abc" :red} (protocols/hexhold-colors (:hexholds g))))
      (is (= [:channel-2] (map first @sent)))
      (let [event (recorded-event sent)]
        (is (= "system-notification" (:type event)))
        (is (= "warning" (get-in event [:content :severity]))))))
  (testing "hexhold-message appends and broadcasts to everybody"
    (let [g (make-globo) sent (atom [])]
      (with-recording-send! sent
        #(messages/process {:globo g :user-id "u1"}
                           {:type :hexhold-message :content {:hex-id "abc" :text "hi there"}}))
      (is (= 1 (count (protocols/hexhold-messages (:hexholds g) "abc"))))
      (is (= #{:channel-1 :channel-2} (set (map first @sent))))
      (let [event (recorded-event sent)]
        (is (= "hexhold-message" (:type event)))
        (is (= "abc" (get-in event [:content :hex-id])))
        (is (= "u1" (get-in event [:content :message :author :id])))
        (is (= "Me" (get-in event [:content :message :author :name])))
        (is (= "hi there" (get-in event [:content :message :content])))
        (is (string? (get-in event [:content :message :sent-at]))))))
  (testing "unknown :type throws"
    (let [g (make-globo)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (messages/process {:globo g :user-id "u1"} {:type :bogus :content {}}))))))

(deftest latest-messages-test
  (let [s (storage/in-memory-globo-storage)
        msgs (mapv (fn [i] {:id i}) (range 25))]
    (doseq [m msgs]
      (protocols/append-message! s m))
    (is (= 20 (count (protocols/latest-messages s 20))))
    (is (= [22 23 24] (map :id (protocols/latest-messages s 3))))
    (is (= [] (protocols/latest-messages (storage/in-memory-globo-storage) 20)))))
