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
        placeable-map-objects @(rf/subscribe [::ui.subs/placeable-map-objects])
        messages @(rf/subscribe [::ui.subs/messages])
        send-message #(rf/dispatch [::ui.events/send-chat-message %])
        open? @(rf/subscribe [::ui.subs/hud-open?])
        connection-status @(rf/subscribe [::conn.subs/status])
        mobile? @(rf/subscribe [::ui.subs/is-mobile?])
        active-panel @(rf/subscribe [::ui.subs/active-panel])
        set-active-panel #(rf/dispatch [::ui.events/set-active-panel %])
        settings-open? @(rf/subscribe [::ui.subs/settings-open?])
        current-user @(rf/subscribe [::ui.subs/current-user])
        set-settings-open #(rf/dispatch [::ui.events/set-settings-open %])
        set-user-name #(rf/dispatch [::ui.events/set-user-name %])
        set-mouse-action #(rf/dispatch [::ui.events/set-mouse-action %])
        clear-mouse-action #(rf/dispatch [::ui.events/clear-mouse-action])
        mouse-action @(rf/subscribe [::ui.subs/mouse-action])
        favorites @(rf/subscribe [::ui.subs/favorites])
        go-to-favorite #(rf/dispatch [::ui.events/go-to-favorite %])
        rename-favorite (fn [slot new-name]
                          (rf/dispatch [::ui.events/rename-favorite slot new-name]))
        add-favorite #(rf/dispatch [::ui.events/add-favorite])
        map-classes @(rf/subscribe [::ui.subs/map-classes])
        map-params {:css-classes map-classes
                    :on-globe-click (fn [coords]
                                      (rf/dispatch [::ui.events/click-globe coords]))}
        hud-params {:open-hud open-hud
                    :close-hud close-hud
                    :users-online users-online
                    :placeable-map-objects placeable-map-objects
                    :mouse-action mouse-action
                    :set-mouse-action set-mouse-action
                    :clear-mouse-action clear-mouse-action
                    :favorites favorites
                    :go-to-favorite go-to-favorite
                    :rename-favorite rename-favorite
                    :add-favorite add-favorite
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
                    :set-user-name set-user-name}]
    [:div {:style {:position "fixed"
                   :inset 0
                   :overflow "hidden"
                   :background "#000011"}}
     [ui.map/present map-params]
     [ui.hud/present hud-params]]))
