(ns is.galt.globo.ui.presentation
  (:require
    [is.galt.globo.ui.presentation.map :as ui.map]
    [is.galt.globo.ui.presentation.hud :as ui.hud]
    [is.galt.globo.ui.events :as ui.events]
    [is.galt.globo.ui.subscriptions :as ui.subs]
    [is.galt.globo.ui.connection.subscriptions :as conn.subs]
    [re-frame.core :as rf]))

(defn present
  []
  (let [open-hud #(rf/dispatch [::ui.events/set-hud-open true])
        close-hud #(rf/dispatch [::ui.events/set-hud-open false])
        users-online @(rf/subscribe [::conn.subs/users-online])
        place-object #(rf/dispatch [::ui.events/start-place-object %])
        placeable-map-objects @(rf/subscribe [::ui.subs/placeable-map-objects])
        messages @(rf/subscribe [::ui.subs/messages])
        send-message #(rf/dispatch [::ui.events/send-chat-message %])
        open? @(rf/subscribe [::ui.subs/hud-open?])
        connection-status @(rf/subscribe [::conn.subs/status])
        mobile? @(rf/subscribe [::ui.subs/is-mobile?])
        active-panel @(rf/subscribe [::ui.subs/active-panel])
        set-active-panel #(rf/dispatch [::ui.events/set-active-panel %])
        settings-open? @(rf/subscribe [::ui.subs/settings-open?])
        picking-location? @(rf/subscribe [::ui.subs/picking-location?])
        current-user @(rf/subscribe [::ui.subs/current-user])
        set-settings-open #(rf/dispatch [::ui.events/set-settings-open %])
        set-user-name #(rf/dispatch [::ui.events/set-user-name %])
        start-pick-location #(rf/dispatch [::ui.events/start-pick-location])
        cancel-pick-location #(rf/dispatch [::ui.events/cancel-pick-location])
        map-classes @(rf/subscribe [::ui.subs/map-classes])
        map-params {:css-classes map-classes
                    :on-globe-click (fn [coords]
                                      (println ">>> app.ui on-globe-click" coords)
                                      (if picking-location?
                                        (rf/dispatch [::ui.events/set-user-location coords])
                                        (rf/dispatch [::ui.events/click-globe coords])))}
        hud-params {:open-hud open-hud
                    :close-hud close-hud
                    :users-online users-online
                    :place-object place-object
                    :placeable-map-objects placeable-map-objects
                    :messages messages
                    :send-message send-message
                    :open? open?
                    :connection-status connection-status
                    :active-panel active-panel
                    :set-active-panel set-active-panel
                    :mobile? mobile?
                    :settings-open? settings-open?
                    :set-settings-open set-settings-open
                    :user-name (:name current-user)
                    :user-location (:location current-user)
                    :picking-location? picking-location?
                    :set-user-name set-user-name
                    :start-pick-location start-pick-location
                    :cancel-pick-location cancel-pick-location}]
    [:div {:style {:position "fixed"
                   :inset 0
                   :overflow "hidden"
                   :background "#000011"}}
     [ui.map/present map-params]
     [ui.hud/present hud-params]]))
