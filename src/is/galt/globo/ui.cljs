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
  (fn [{:keys [is-mobile?]} [_ {:keys [globo-api-base-url]}]]
    {:db {:system-state {:is-mobile? is-mobile?}
          :config {:globo-api-base-url globo-api-base-url
                   :connection-url (str globo-api-base-url "/connection")
                   :send-message-url (str globo-api-base-url "/send-message")}
          :users {} ; A map of user-id => user data
          :connection {:status :offline
                       :connection-id nil
                       :user-id nil
                       :users-online #{} ; A set of user-ids that are online
                       }
          :ui {:active-panel :users}
          :messages (list)
          :map-objects #{}
          :placeable-map-objects (reduce (fn [acc c] (assoc acc (:model-id c) c)) {} map-objects/config)
          :place-object nil ; A map to track the user placing an object on the map
          :hud-open? true
          ;; Set to true once all GLTF models have finished preloading.
          ;; Until true, ::place-objects only updates the db; the actual
          ;; globe.gl customLayerData push is deferred so that
          ;; create-3d-object never falls back to the green-sphere
          ;; placeholder (which would then be cached by three-globe's
          ;; data-bind-mapper and never replaced).
          :models-ready? false}
     :fx [[:dispatch [:is.galt.globo.ui.connection.events/initialize]]]}))


(defn ^:export init
  [^js raw-params]
  (let [params (js->clj raw-params :keywordize-keys true)
        config (select-keys params [:globo-api-base-url])]
    (println "Globo re-frame app init" config)
    (reset! app-root (rdc/create-root (js/document.getElementById "app")))
    (rf/dispatch-sync [:is.galt.globo.ui.db/initialize config])
    (render! @app-root {})))

(defn start!
  []
  (rf/clear-subscription-cache!)
  (render! @app-root {}))

(defn stop!
  []
  )
