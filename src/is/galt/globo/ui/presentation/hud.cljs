(ns is.galt.globo.ui.presentation.hud
  (:require
   [is.galt.globo.ui.icons :refer [icon]]
   [clojure.string :as str]
   [reagent.core :as r]))

(defn hud-panel
  [opts & children]
  (into [:div.box
         {:class (into ["p-2" "hud-panel"] (:class opts))}]
        children))

(defn panel-row
  [e]
  [:div.panel-block.p-2 e])

(defn hud-details-layout
  "Standard open-HUD layout: a top bar above a padded content body.
   Both areas are hud-vstacks so the body flexes to fill the remaining
   HUD height and any inner hud-scroll/columns can manage overflow.

   Keys:
     header   - hiccup for the top bar (hud-header or hud-details-title-bar)
     body     - hiccup for the content area (a single element)"
  [{:keys [header body]}]
  [:div.hud-vstack
   header
   [:div.hud-vstack {:class ["px-3" "pb-3"]} body]])

(defn users-view
  [users]
  [hud-panel
   {}
   [:div.hud-scroll
    (for [u users]
      ^{:key (:id u)}
      [panel-row
       [:div {:class "level is-mobile p-0"}
        [:div {:class "level-left"}
         [:span {:class "icon-text"}
          [:span {:class "icon"} "🌍"]
          [:span.has-text-primary
           {:style {:white-space "nowrap"
                    :overflow "hidden"
                    :text-overflow "ellipsis"
                    :max-width "20ch"}}
           (:name u)]]]
        [:div {:class "level-right"}
         [:button.button
          {:class "is-small is-info is-rounded is-outlined"
           :on-click (fn [_] (println ">>> tell the globe to point the view to the user"))}
          "Focus"]]]])]])

(defn place-objects
  [placeable-map-objects place-object]
  [hud-panel
   {:class ["hud-panel-auto"]}
   [panel-row
    [:div.buttons.are-small
     (for [o placeable-map-objects]
       ^{:key (str "placeable-" (name (:model-id o)))}
       [:button {:class "button is-primary is-outlined"
                 :on-click #(place-object (o :model-id))}
        (str (:icon o) " " (:name o))])]]])

(defn favorite-places
  [{:keys [favorites go-to-favorite add-favorite]}]
  [hud-panel
   {}
   [:div.hud-scroll
    (for [s ["First" "Second" "Third" "Fourth" "Fifth" "Sixth"]]
      ^{:key s} [panel-row s])]])

(defn messages-view
  "Form-3 component using r/with-let so it can be called as a plain
   function (like users-view/places-view) while still holding local
   input state. The form-2 version returned the inner render fn when
   called directly, which left the desktop column empty."
  [{:keys [messages send-message]}]
  (r/with-let [text (r/atom "")]
    (let [send-fn #(when (not (str/blank? @text))
                     (send-message @text)
                     (reset! text ""))]
      [hud-panel
       {}
       [:div.hud-scroll
        {:ref (fn [el]
                (when el
                  (set! (.-scrollTop el) (.-scrollHeight el))))}
        (for [m messages]
          ^{:key (:id m)}
          [:div.has-text-primary-80
           (str (get-in m [:author :name] "?") ": " (:content m))])]
       [:div.send-message {:class "field has-addons"}
        [:div {:class "control is-expanded"}
         [:input {:class "input is-small"
                  :type "text"
                  :value @text
                  :placeholder "Send message to world..."
                  :on-change #(reset! text (.. % -target -value))
                  :on-key-down #(when (= (.-key %) "Enter")
                                  (send-fn))}]]
        [:div {:class "control"}
         [:button.button {:class "is-info is-small"
                          :on-click send-fn}
          "Send"]]]])))

(defn places-view
  [{:keys [placeable-map-objects place-object favorites go-to-favorite add-favorite]}]
  [:div.hud-vstack.places-layout
   [place-objects placeable-map-objects place-object]
   [favorite-places {:favorites favorites :go-to-favorite go-to-favorite :add-favorite add-favorite}]])

(defn status-dot
  "Connection status indicator. Sits opposite the close icon in the HUD top bar.
   status: :online | :reconnecting | :offline"
  [status]
  [:div.hud-status-dot
   {:class (case status
             :online "is-online"
             :reconnecting "is-reconnecting"
             :offline "is-offline"
             "is-offline")}])

(defn settings-button
  "Gear toggle that shows/hides the settings panel. Sits next to the
   status dot in the HUD top bar."
  [{:keys [settings-open? set-settings-open]}]
  [:button.button.is-small.is-light.is-inverted.ml-2.mb-2
   {:class (when settings-open? "is-active")
    :title "Settings"
    :on-click #(set-settings-open (not settings-open?))}
   [icon :settings]])

(defn settings-panel
  "Settings panel: name input and location picker. Fills the HUD body
   area below the header. Reuses hud-panel/panel-row so styling matches
   the other panels."
  [{:keys [user-name user-location picking-location?
           set-user-name start-pick-location cancel-pick-location]}]
  [hud-panel
   {:class ["hud-settings-panel"]}
   [panel-row
    [:div.field
     [:label.label.has-text-light-80 "Your name"]
     [:div.control
      [:input.input.is-small
       {:type "text"
        :value (or user-name "")
        :placeholder "Your name"
        :on-change #(set-user-name (.. % -target -value))}]]]]
   [panel-row
    [:div.field
     [:label.label.has-text-light-80 "Your location"]
     [:div.is-flex.is-align-items-center
      (if user-location
        [:span.has-text-primary-80
         (str (:lat user-location) ", " (:lng user-location))]
        [:span.has-text-grey-light "Not set yet"])
      [:button.button.is-small.is-info.is-outlined.ml-3
       {:on-click (if picking-location? cancel-pick-location start-pick-location)}
       (if picking-location?
         [icon :cancel "Cancel"]
         [icon :pick-location "Pick on map"])]]]]])

(defn panel-tabs
  [{:keys [active-panel set-active-panel]}]
  [:div.tabs.is-toggle.is-toggle-rounded.is-flex-shrink-0.mb-0
   [:ul
    (for [[key icon label] [[:users "👥" "Users"]
                            [:places "📌" "Places"]
                            [:messages "💬" "Messages"]]]
      ^{:key key}
      [:li {:class (when (= active-panel key) "is-active")}
       [:a {:on-click #(set-active-panel key)}
        [:span.icon.is-small icon]
        [:span label]]])]])

(defn hud-details-title-bar
  [{:keys [close-hud connection-status settings-open? set-settings-open] :as opts}]
  [:div.is-flex.is-align-items-center.pt-2.px-3.mb-2.is-flex-shrink-0
   [status-dot connection-status]
   [settings-button {:settings-open? settings-open? :set-settings-open set-settings-open}]
   [:div.is-flex-grow-1]
   [panel-tabs (select-keys opts [:active-panel :set-active-panel])]
   [:div.is-flex-grow-1.is-flex.is-justify-content-flex-end
    [:button.delete.is-medium {:on-click close-hud :aria-label "Close"}]]])

(defn mobile-hud-details
  [{:keys [active-panel users-online settings-open?] :as opts}]
  (let [body (if settings-open?
               [settings-panel (select-keys opts [:user-name :user-location :picking-location?
                                                  :set-user-name :start-pick-location :cancel-pick-location])]
               (case active-panel
                 :users [users-view users-online]
                 :places [places-view (select-keys opts [:placeable-map-objects :place-object :favorites :add-favorite :go-to-favorite])]
                 :messages [messages-view (select-keys opts [:messages :send-message])]))]
    [hud-details-layout
     {:header [hud-details-title-bar (select-keys opts [:close-hud :active-panel :set-active-panel
                                                        :connection-status :settings-open? :set-settings-open])]
      :body body}]))

(defn hud-desktop-column
  [contents]
  [:div.column.is-12-mobile.is-12-tablet.is-4-desktop.hud-column
   contents])

(defn hud-header
  [{:keys [close-hud connection-status settings-open? set-settings-open]}]
  [:div.is-flex.is-align-items-center.px-3.pt-2.is-flex-shrink-0
   [status-dot connection-status]
   [settings-button {:settings-open? settings-open? :set-settings-open set-settings-open}]
   [:div.is-flex-grow-1]
   [:div.hud-grab-handle {:on-click close-hud :role "button" :aria-label "Minimize HUD"}]
   [:div.is-flex-grow-1.is-flex.is-justify-content-flex-end
    [:button.delete.is-medium {:on-click close-hud :aria-label "Close"}]]])

(defn desktop-hud-details
  [{:keys [users-online close-hud connection-status settings-open?] :as opts}]
  (let [body (if settings-open?
               [settings-panel (select-keys opts [:user-name :user-location :picking-location?
                                                  :set-user-name :start-pick-location :cancel-pick-location])]
               [:div.columns.is-variable.is-2.hud-columns
                [hud-desktop-column (users-view users-online)]
                [hud-desktop-column (places-view (select-keys opts [:placeable-map-objects :place-object :favorites :add-favorite :go-to-favorite]))]
                [hud-desktop-column (messages-view (select-keys opts [:messages :send-message]))]])]
    [hud-details-layout
     {:header [hud-header {:close-hud close-hud
                           :connection-status connection-status
                           :settings-open? settings-open?
                           :set-settings-open (:set-settings-open opts)}]
      :body body}]))

(defn hud-details
  [{:keys [mobile?] :as opts}]
  (if mobile?
    (mobile-hud-details opts)
    (desktop-hud-details opts)))

(defn hud-summary
  [{:keys [open-hud users-online placeable-map-objects place-object connection-status]}]
  [:div
   {:style {:flex 1 :display "flex" :align-items "center"
            :padding "0 1rem" :justify-content "space-between"}}
   [:div.is-flex.is-align-items-center
    [status-dot connection-status]
    [:div.buttons.are-small.mb-0.ml-2
     (for [o (filter :show-in-summary? placeable-map-objects)]
       ^{:key (str "placeable-sum" (name (:model-id o)))}
       [:button {:class "button is-small is-primary is-outlined"
                 :title (:name o)
                 :on-click #(place-object (:model-id o))}
        (:icon o)])
     [:button {:class "button is-small is-info is-outlined" :title "Users online"}
      [:span {:class "icon-text"}
       [:span {:class "icon"} "👥"]
       [:span (count users-online)]]]]]
   [:button {:class "button is-small is-light"
             :on-click open-hud}
    [:span {:class "icon-text"}
     [:span {:class "icon"} "▲"]
     [:span "Open HUD"]]]])

(defn present
  [{:keys [open?] :as opts}]
  (let [hud-height (if open? "33vh" "3.75rem")]
    [:div#hud {:style {:height hud-height}}
     (if open?
       [hud-details opts]
       [hud-summary opts])]))
