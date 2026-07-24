(ns is.galt.globo.ui
  (:require
   [is.galt.globo.ui.connection]
   [is.galt.globo.ui.events :as ui.events]
   [is.galt.globo.ui.presentation :as ui.presentation]
   [is.galt.globo.ui.map-objects :as map-objects]
   [is.galt.globo.ui.connection.events]
   [is.galt.globo.ui.connection.subscriptions]
   [re-frame.core :as rf]
   [reagent.dom.client :as rdc]))

(defonce app-root
  (atom nil))

(defn render!
  [container deps]
  (rdc/render container [ui.presentation/present]))

(rf/reg-event-fx
 :is.galt.globo.ui.db/initialize
 [(rf/inject-cofx ::ui.events/is-mobile?)]
 (fn [{:keys [is-mobile?]} [_ {:keys [globo-api-base-url assets-base-url]}]]
   (let [assets-base-url (or assets-base-url (str globo-api-base-url "/assets"))]
     {:db {:system-state {:is-mobile? is-mobile?}
           :config {:globo-api-base-url globo-api-base-url
                    :assets-base-url assets-base-url
                    :connection-url (str globo-api-base-url "/connection")
                    :send-message-url (str globo-api-base-url "/send-message")}
           :users {}
           :connection {:status :offline
                        :connection-id nil
                        :user-id nil
                        :users-online #{}}
           :ui {:active-panel :users}
           :messages []
           :map-objects #{}
           :placeable-map-objects (reduce (fn [acc c] (assoc acc (:model-id c) c)) {} map-objects/config)
           :place-object nil
           :hud-open? true
           :models-ready? false}
      :fx [[:dispatch [:is.galt.globo.ui.connection.events/initialize]]]})))

(defn ^:export init
  [^js raw-params]
  (let [params (js->clj raw-params :keywordize-keys true)
        config (select-keys params [:globo-api-base-url :assets-base-url])]
    (println "Globo re-frame app init" config)
    (when (nil? @app-root)
      (reset! app-root (rdc/create-root (js/document.getElementById "app"))))
    (rf/dispatch-sync [:is.galt.globo.ui.db/initialize config])
    (render! @app-root {})))

(defn start!
  []
  (rf/clear-subscription-cache!)
  (render! @app-root {}))

(defn stop!
  [])
