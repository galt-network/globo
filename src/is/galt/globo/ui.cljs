(ns is.galt.globo.ui
  "UI entry point for the globo app. Defines the app-db initial schema,
  creates the React root, and renders the presentation tree."
  (:require
   [is.galt.globo.ui.connection]
   [is.galt.globo.ui.connection.events]
   [is.galt.globo.ui.connection.subscriptions]
   [is.galt.globo.ui.events :as ui.events]
   [is.galt.globo.ui.presentation :as ui.presentation]
   [re-frame.core :as rf]
   [reagent.dom.client :as rdc]))

(defonce app-root
  (atom nil))

(def default-db
  "Initial app-db schema for the globo application."
  {:system-state {:is-mobile? false}
   :config {:max-user-name-length 42}
   :users {}
   :connection {:status :offline
                :connection-id nil
                :user-id nil
                :users-online #{}}
   :ui {:active-panel :users
        :active-view :user-communication
        :user-name-save-error nil
        :user-name-draft nil}
   :messages []
   :map-objects #{}
   :placeable-map-objects {}
   :mouse-action nil
   :favorites []
   :rings {}
   :message-arcs {}
   :hexholds {:colors {}
              :visible []
              :hover-id nil
              :selected-id nil
              :info nil
              :messages {}}
   :system-notifications []
   :hud-open? true
   :models-ready? false})

(defn render!
  [container]
  (rdc/render container [ui.presentation/present]))

(rf/reg-event-fx
 :is.galt.globo.ui.db/initialize
 [(rf/inject-cofx ::ui.events/is-mobile?)]
 (fn [{:keys [is-mobile?]} [_ {:keys [globo-api-base-url assets-base-url]}]]
   (let [assets-base-url (or assets-base-url (str globo-api-base-url "/assets"))]
      {:db (-> default-db
               (assoc-in [:system-state :is-mobile?] is-mobile?)
                (assoc :config {:globo-api-base-url globo-api-base-url
                                :assets-base-url assets-base-url
                                :connection-url (str globo-api-base-url "/connection")
                                :send-message-url (str globo-api-base-url "/send-message")
                                 :hexholds-query-url (str globo-api-base-url "/hexholds/query")
                                 :hexholds-messages-url (str globo-api-base-url "/hexholds/messages")
                                :max-favorite-places 3}))
      :fx [[:dispatch [:is.galt.globo.ui.connection.events/initialize]]]})))

(defn ^:export init
  [^js raw-params]
  (let [params (js->clj raw-params :keywordize-keys true)
        config (select-keys params [:globo-api-base-url :assets-base-url])]
    (ui.events/setup-mobile-detection!)
    (when (nil? @app-root)
      (reset! app-root (rdc/create-root (js/document.getElementById "app"))))
    (rf/dispatch-sync [:is.galt.globo.ui.db/initialize config])
    (render! @app-root)))

(defn start!
  []
  (rf/clear-subscription-cache!)
  (render! @app-root))

(defn stop!
  [])

(comment
  (require '[re-frame.db :refer [app-db]])
  (keys @app-db))
