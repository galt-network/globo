(ns is.galt.globo.ui.connection.events
  (:require
   [is.galt.globo.ui.connection :as ui.connection]
   [clojure.set :as set]
   [re-frame.core :as rf]))

(defn- log-success
  [result]
  (js/console.log "::send-message SUCCESS" result))

(defn- log-failure
  [result]
  (js/console.error "::send-message FAILURE" result))

(defn dispatch-sse->re-frame
  [message]
  (println ">>> DISPATCHING" message)
  (let [msg-type (keyword (:type message))
        msg-content (:content message)]
    (rf/dispatch
      (case msg-type
        :update-user [::update-user msg-content]
        :update-object [:is.galt.globo.ui.events/place-objects msg-content]
        :map-objects [::update-map-objects msg-content]
        :connected [::connected msg-content]
        :disconnected [::disconnected msg-content]
        :users-online [::users-online msg-content]
        :user-online [::user-online msg-content]
        :user-offline [::user-offline msg-content]
        :messages [::receive-initial-messages msg-content]
        :new-message [::receive-new-message msg-content]))))

(rf/reg-event-fx
  ::initialize
  (fn [{:keys [db]} _]
    (ui.connection/setup-sse-events
      {:connection-url (get-in db [:config :connection-url])
       :on-open #(js/console.log ">> is.galt.globo.ui.connection.events OPEN received" %)
       :on-error (fn [e]
                   (rf/dispatch [:app.connection.events/disconnected])
                   (js/console.log ">> app.connection: ERROR received" e))
       :on-message dispatch-sse->re-frame})))

(rf/reg-event-fx
  ::update-map-objects
  (fn [{:keys [db]} [_ content]]
    (println ">>> ::update-map-objects FROM SERVER" content)
    (let [our-objects (get-in db [:map-objects])
          ;; TODO: refactor so that every client before placing an object
          ;; registers or requests it from the server, to avoid concurrency issues
          ;; e.g. same object add and remove events arrive at different sequences
          message-objects (into #{} (get-in content [:objects]))
          new-objects (set/difference message-objects our-objects)]
      {:fx [[:dispatch [:is.galt.globo.ui.events/place-objects {:op :add :objects new-objects}]]]})))

(rf/reg-event-db
  ::update-user
  (fn [db [_ user]]
    (assoc-in db [:users (:id user)] user)))

(rf/reg-event-db
  ::connected
  (fn [db [_ message]]
    (-> db
        (assoc-in ,,, [:connection :connection-id] (:connection-id message))
        (assoc-in ,,, [:connection :user-id] (:user-id message))
        (assoc-in ,,, [:connection :status] :online))))

(rf/reg-event-db
  ::disconnected
  (fn [db [_ _]]
    (assoc-in db [:connection :status] :offline)))

(rf/reg-event-db
  ::users-online
  (fn [db [_ {:keys [users] :as msg}]]
    (println ">>> USERS-ONLINE" msg)
    (-> db
        (update-in ,,, [:connection :users-online] into (map :id users))
        (update-in ,,, [:users] merge (reduce (fn [acc u] (assoc acc (:id u) u)) {} users)))
    ))

(rf/reg-event-db
  ::user-online
  (fn [db [_ user]]
    (println ">>> user ONLINE" user)
    (-> db
        (update-in ,,, [:connection :users-online] conj (:id user))
        (assoc-in ,,, [:users (:id user)] user))
    ))

(rf/reg-event-db
  ::user-offline
  (fn [db [_ user]]
    (println ">>> user OFFLINE" user)
    (update-in db [:connection :users-online] disj (:id user))))

(rf/reg-event-db
  ::receive-initial-messages
  (fn [db [_ {:keys [messages]}]]
    (println ">>> RECEIVED INITIAL MESSAGES" messages)
    (assoc db :messages (vec messages))))

(rf/reg-event-db
  ::receive-new-message
  (fn [db [_ message]]
    (update db :messages #(conj (vec %) message))))

(rf/reg-event-db
  ::send-message-success
  (fn [db [_ result]]
    (js/console.log "::send-message-success" result)))

(rf/reg-event-db
  ::send-message-failure
  (fn [db [_ result]]
    (js/console.error "::send-message-failure" result)))

(rf/reg-event-fx
  ::send-message
  (fn [{:keys [db]} [_ message]]
    (println ">>> ::connection/send-message" message)
    {:fetch {:method :post
             :url (get-in db [:config :send-message-url])
             :body (assoc
                     message
                     :connection-id (get-in db [:connection :connection-id])
                     :user-id (get-in db [:connection :user-id]))
             :request-content-type :json
             :response-content-types {#"application/.*json" :json}
             :on-success [::send-message-success]
             :on-failure [::send-message-failure]}}))
