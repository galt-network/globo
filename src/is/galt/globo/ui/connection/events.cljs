(ns is.galt.globo.ui.connection.events
  "Re-frame events for the SSE connection lifecycle and server message sync."
  (:require
   [clojure.set :as set]
   [is.galt.globo.ui.connection :as ui.connection]
   [is.galt.globo.ui.message-arcs :as message-arcs]
   [re-frame.core :as rf]))

(defn- log-success
  [result]
  (js/console.log "::send-message SUCCESS" result))

(defn- log-failure
  [result]
  (js/console.error "::send-message FAILURE" result))

(defn sse-type->event
  "Map a parsed SSE message to its re-frame event vector (or nil when the
   message type is unknown). Pure — testable without a running re-frame."
  [{:keys [type content]}]
  (let [msg-type (keyword type)]
    (case msg-type
      :update-user [::update-user content]
      :update-object [:is.galt.globo.ui.events/place-objects content]
      :map-objects [::update-map-objects content]
      :connected [::connected content]
      :disconnected [::disconnected content]
      :users-online [::users-online content]
      :user-online [::user-online content]
      :user-offline [::user-offline content]
      :messages [::receive-initial-messages content]
      :new-message [::receive-new-message content]
      :favorite-added [::favorite-added content]
      :favorite-updated [::favorite-updated content]
      :placeable-map-objects [::update-placeable-map-objects content]
      :hexholds [:is.galt.globo.ui.events/hexholds-colors content]
      :hexholds-updated [:is.galt.globo.ui.events/hexhold-updated content]
      :hexhold-message [:is.galt.globo.ui.events/receive-hexhold-message content]
      :system-notification [::system-notification content]
      nil)))

(defn dispatch-sse->re-frame
  "Dispatch a parsed SSE message to the matching re-frame event."
  [message]
  (let [event (sse-type->event message)]
    (if event
      (rf/dispatch event)
      (js/console.warn "Unknown SSE message type" (:type message)))))

(rf/reg-event-fx
 ::initialize
 (fn [{:keys [db]} _]
   (ui.connection/setup-sse-events
    {:connection-url (get-in db [:config :connection-url])
     :on-open #(js/console.log "SSE connection open")
     :on-error (fn [_]
                 (rf/dispatch [:is.galt.globo.ui.connection.events/disconnected]))
     :on-message dispatch-sse->re-frame})
   {}))

(rf/reg-event-fx
 ::update-map-objects
 (fn [{:keys [db]} [_ content]]
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

(rf/reg-event-fx
 ::update-placeable-map-objects
 (fn [{:keys [db]} [_ {:keys [objects]}]]
   (let [by-model-id (reduce (fn [acc o] (assoc acc (:model-id o) o)) {} objects)]
     {:db (assoc db :placeable-map-objects by-model-id)
      :fx [[:is.galt.globo.ui.events/preload-models
            {:assets-base-url (get-in db [:config :assets-base-url])
             :placeables objects}]]})))

(def system-notification-dismiss-ms 6000)
(def max-system-notifications 5)

(rf/reg-event-fx
 ::system-notification
 (fn [{:keys [db]} [_ notification]]
   (let [content (:content notification)
         entry {:id (str (random-uuid))
                :message (:message content)
                :severity (:severity content)
                :received-at (:sent-at content)}
         current (get-in db [:system-notifications] [])
         db' (assoc db :system-notifications
                    (vec (take max-system-notifications
                               (conj current entry))))]
     {:db db'
      :fx [[:is.galt.globo.ui.events/schedule-dispatch
            {:delay system-notification-dismiss-ms
             :event [::dismiss-system-notification (:id entry)]}]]})))

(rf/reg-event-db
 ::dismiss-system-notification
 (fn [db [_ id]]
   (update db :system-notifications
           (fn [notifications]
             (vec (remove #(= id (:id %)) notifications))))))

(rf/reg-event-db
 ::favorite-added
 (fn [db [_ {:keys [index favorite]}]]
   (let [current (vec (get-in db [:favorites] []))
         updated (if (< index (count current))
                   (assoc current index favorite)
                   (conj current favorite))]
     (assoc db :favorites updated))))

(rf/reg-event-db
 ::favorite-updated
 (fn [db [_ {:keys [index favorite]}]]
   (assoc-in db [:favorites index] favorite)))

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
   (let [users-map (reduce (fn [acc u] (assoc acc (:id u) u)) {} users)
         self-id (get-in db [:connection :user-id])
         self-favs (get-in users-map [self-id :favorites])]
     (cond-> (-> db
                 (update-in ,,, [:connection :users-online] into (map :id users))
                 (update-in ,,, [:users] merge users-map))
       self-favs (assoc :favorites self-favs)))))

(rf/reg-event-db
 ::user-online
 (fn [db [_ user]]
   (-> db
       (update-in ,,, [:connection :users-online] conj (:id user))
       (assoc-in ,,, [:users (:id user)] user))))

(rf/reg-event-db
 ::user-offline
 (fn [db [_ user]]
   (update-in db [:connection :users-online] disj (:id user))))

(rf/reg-event-db
 ::receive-initial-messages
 (fn [db [_ {:keys [messages]}]]
   (assoc db :messages (vec messages))))

(rf/reg-event-fx
 ::receive-new-message
 [(rf/inject-cofx :is.galt.globo.ui.events/globe-viewpoint)]
 (fn [{:keys [db globe-viewpoint]} [_ message]]
   (let [db' (update db :messages conj message)
         author-id (get-in message [:author :id])
         self-id (get-in db [:connection :user-id])
         origin (when-not (= author-id self-id)
                  (message-arcs/origin-location
                   (get-in db [:users author-id]) nil))
         endpoint (message-arcs/origin-location
                   (get-in db [:users self-id]) globe-viewpoint)]
     (cond-> {:db db'}
       (and origin endpoint)
       (assoc :fx [[:dispatch
                    [:is.galt.globo.ui.events/show-message-arcs
                     origin [endpoint]]]])))))

(rf/reg-event-db
 ::send-message-success
 (fn [db [_ result]]
   (log-success result)
   db))

(rf/reg-event-db
 ::send-message-failure
 (fn [db [_ result]]
   (log-failure result)
   (cond-> db
     (not (:status result)) (assoc-in [:connection :status] :offline))))

(rf/reg-event-fx
 ::send-message
 (fn [{:keys [db]} [_ message]]
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
