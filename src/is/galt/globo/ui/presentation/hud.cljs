(ns is.galt.globo.ui.presentation.hud
  "HUD overlay: users, places, messages panels plus the collapsed summary bar."
  (:require
   [clojure.string :as str]
   [is.galt.globo.ui.connection.subscriptions :as conn.subs]
   [is.galt.globo.ui.events :as ui.events]
   [is.galt.globo.ui.icons :refer [icon]]
   [is.galt.globo.ui.subscriptions :as ui.subs]
   [re-frame.core :as rf]
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
  []
  (let [users @(rf/subscribe [::conn.subs/users-online])]
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
             :disabled (nil? (:location u))
             :on-click (fn [_]
                         (when-let [loc (:location u)]
                           (rf/dispatch
                            [::ui.events/focus-globe (select-keys loc [:lat :lng])])))}
            "Focus"]]]])]]))

(defn place-objects
  []
  (let [placeable-map-objects @(rf/subscribe [::ui.subs/placeable-map-objects])
        mouse-action @(rf/subscribe [::ui.subs/mouse-action])
        active-model (when (= :place-object (:type mouse-action))
                       (:model-id mouse-action))
        other-busy? (and (:type mouse-action)
                         (not= :place-object (:type mouse-action)))]
    [hud-panel
     {:class ["hud-panel-auto"]}
     [panel-row
      [:div.buttons.are-small
       (for [o placeable-map-objects]
         ^{:key (str "placeable-" (name (:model-id o)))}
         [:button
          {:class (cond-> "button is-outlined"
                    (= active-model (:model-id o)) (str " is-warning")
                    (and (not active-model) other-busy?) (str " is-light"))
           :on-click (fn [_]
                       (if (= active-model (:model-id o))
                         (rf/dispatch [::ui.events/set-mouse-action nil])
                         (rf/dispatch
                          [::ui.events/set-mouse-action
                           {:type :place-object :model-id (:model-id o)}])))}
          (str (:icon o) " " (:name o))])]]]))

(defn favorite-row
  [{:keys [index favorite]}]
  (let [mouse-action @(rf/subscribe [::ui.subs/mouse-action])]
    (r/with-let [editing? (r/atom false)
                 draft (r/atom (or (:label favorite) ""))]
      (let [set? (and (some? (:lat favorite)) (some? (:lng favorite)))
            label (:label favorite)
            blank? (str/blank? label)
            shorten (fn [float] (when float (.toFixed float 3)))
            shortened-coordinates (str (shorten (:lat favorite)) ":" (shorten (:lng favorite)))
            display (cond
                      (not blank?) label
                      set? (str "Unnamed (" shortened-coordinates ")")
                      :else "Unnamed")
            active? (and (= :set-favorite (:type mouse-action))
                         (= (:index mouse-action) index))
            busy? (and (:type mouse-action) (not active?))]
        [panel-row
         [:div.level.is-mobile.p-0
          {:class (when set? "is-clickable")
           :on-click (fn [_] (when set? (rf/dispatch [::ui.events/go-to-favorite index])))
           :style (when set? {:cursor "pointer"})}
          [:div.level-left
           [:div
            (if @editing?
              [:input.input.is-small
               {:type "text"
                :auto-focus true
                :value @draft
                :on-change #(reset! draft (.. % -target -value))
                :on-blur (fn [_]
                           (reset! editing? false)
                           (rf/dispatch [::ui.events/rename-favorite index @draft]))
                :on-key-down
                (fn [e]
                  (case (.-key e)
                    "Enter" (do (reset! editing? false)
                                (rf/dispatch [::ui.events/rename-favorite index @draft]))
                    "Escape" (do (reset! editing? false)
                                 (reset! draft (or label "")))
                    nil))
                :on-click (fn [e] (.stopPropagation e))}]
              [:span
               {:class ["has-text-primary-80" "is-size-6"]
                :style {:white-space "nowrap"
                        :overflow "hidden"
                        :text-overflow "ellipsis"
                        :max-width "20ch"}}
               display])]]
          [:div.level-right.buttons.are-small.mb-0
           (when set?
             [:button.button.is-info.is-outlined
              {:title "Rename"
               :on-click (fn [e]
                           (.stopPropagation e)
                           (reset! draft (or label ""))
                           (reset! editing? true))}
              (icon :edit)])
           [:button.button.is-small.is-outlined
            {:class (if active? "is-warning" "is-primary")
             :disabled busy?
             :on-click (fn [e]
                         (.stopPropagation e)
                         (if active?
                           (rf/dispatch [::ui.events/clear-mouse-action])
                           (rf/dispatch
                            [::ui.events/set-mouse-action
                             {:type :set-favorite :index index}])))}
            (if active? (icon :cancel) (icon :set-location))]]]]))))

(defn add-favorite-row
  []
  (let [mouse-action @(rf/subscribe [::ui.subs/mouse-action])
        busy? (some? (:type mouse-action))]
    [panel-row
     [:div.level.is-mobile.p-0
      [:div.level-left
       [:span.has-text-grey-light.is-size-7 ""]]
      [:div.level-right
       [:button.button.is-small.is-primary
        {:disabled busy?
         :on-click (fn [_] (rf/dispatch [::ui.events/add-favorite]))}
        "Add"]]]]))

(defn favorite-places
  []
  (let [favorites @(rf/subscribe [::ui.subs/favorites])
        max-favorite-places @(rf/subscribe [::ui.subs/max-favorite-places])
        rows (mapv (fn [f i] {:index i :favorite f})
                   favorites
                   (range))
        slots (if (< (count favorites) max-favorite-places)
                (conj rows {:add? true})
                rows)]
    [hud-panel
     {}
     [:div.hud-scroll
      (for [slot slots]
        (if (:add? slot)
          ^{:key "add-favorite"}
          [add-favorite-row]
          ^{:key (str "favorite-" (:index slot))}
          [favorite-row {:index (:index slot)
                         :favorite (:favorite slot)}]))]]))

(defn messages-view
  "Form-3 component using r/with-let so it can be called as a plain
   function (like users-view/places-view) while still holding local
   input state. The form-2 version returned the inner render fn when
   called directly, which left the desktop column empty."
  []
  (let [messages @(rf/subscribe [::ui.subs/messages])]
    (r/with-let [text (r/atom "")]
      (let [send-fn #(when (not (str/blank? @text))
                       (rf/dispatch [::ui.events/send-chat-message @text])
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
            "Send"]]]]))))

(defn places-view
  []
  [:div.hud-vstack.places-layout
   [place-objects]
   [favorite-places]])

(defn status-dot
  "Connection status indicator. Sits opposite the close icon in the HUD top bar.
   status: :online | :reconnecting | :offline"
  [status]
  [:div.hud-status-dot
   {:title (str/capitalize (or (name status) "offline"))
    :class (case status
             :online "is-online"
             :reconnecting "is-reconnecting"
             :offline "is-offline"
             "is-offline")}])

(defn settings-button
  "Gear toggle that shows/hides the settings panel. Sits next to the
   status dot in the HUD top bar."
  []
  (let [settings-open? @(rf/subscribe [::ui.subs/settings-open?])]
    [:button.button.is-small.is-light.is-inverted.ml-2.mb-2
     {:class (when settings-open? "is-active")
      :title "Settings"
      :on-click #(rf/dispatch [::ui.events/set-settings-open (not settings-open?)])}
     [icon :settings]]))

(defn settings-panel
  "Settings panel: name input and location picker. Fills the HUD body
   area below the header. Reuses hud-panel/panel-row so styling matches
   the other panels."
  []
  (let [current-user @(rf/subscribe [::ui.subs/current-user])
        mouse-action @(rf/subscribe [::ui.subs/mouse-action])
        user-name (:name current-user)
        user-location (:location current-user)
        picking? (= :pick-user-location (:type mouse-action))]
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
          :on-change #(rf/dispatch [::ui.events/set-user-name (.. % -target -value)])}]]]]
     [panel-row
      [:div.field
       [:label.label.has-text-light-80 "Your location"]
       [:div.is-flex.is-align-items-center
        (if user-location
          [:span.has-text-primary-80
           (str (Math/round (:lat user-location)) ", "
                (Math/round (:lng user-location)))]
          [:span.has-text-grey-light "Not set yet"])
        [:button.button.is-small.is-info.is-outlined.ml-3
         {:on-click (fn [_]
                      (if picking?
                        (rf/dispatch [::ui.events/clear-mouse-action])
                        (rf/dispatch [::ui.events/set-mouse-action {:type :pick-user-location}])))}
         (if picking?
           [icon :cancel "Cancel"]
           [icon :pick-location "Pick on map"])]]]]]))

(defn panel-tabs
  []
  (let [active-panel @(rf/subscribe [::ui.subs/active-panel])]
    [:div.tabs.is-toggle.is-toggle-rounded.is-flex-shrink-0.mb-0
     [:ul
      (for [[key icon label] [[:users "👥" "Users"]
                              [:places "📌" "Places"]
                              [:messages "💬" "Messages"]]]
        ^{:key key}
        [:li {:class (when (= active-panel key) "is-active")}
         [:a {:on-click #(rf/dispatch [::ui.events/set-active-panel key])}
          [:span.icon.is-small icon]
          [:span label]]])]]))

(defn hud-details-title-bar
  []
  (let [connection-status @(rf/subscribe [::conn.subs/status])]
    [:div.is-flex.is-align-items-center.pt-2.px-3.mb-2.is-flex-shrink-0
     [status-dot connection-status]
     [settings-button]
     [:div.is-flex-grow-1]
     [panel-tabs]
     [:div.is-flex-grow-1.is-flex.is-justify-content-flex-end
      [:button.delete.is-medium
       {:on-click #(rf/dispatch [::ui.events/set-hud-open false])
        :aria-label "Close"}]]]))

(defn mobile-hud-details
  []
  (let [settings-open? @(rf/subscribe [::ui.subs/settings-open?])
        active-panel @(rf/subscribe [::ui.subs/active-panel])
        body (if settings-open?
               [settings-panel]
               (case active-panel
                 :users [users-view]
                 :places [places-view]
                 :messages [messages-view]))]
    [hud-details-layout
     {:header [hud-details-title-bar]
      :body body}]))

(defn hud-desktop-column
  [contents]
  [:div.column.is-12-mobile.is-12-tablet.is-4-desktop.hud-column
   contents])

(defn hud-header
  []
  (let [connection-status @(rf/subscribe [::conn.subs/status])]
    [:div.is-flex.is-align-items-center.px-3.pt-2.is-flex-shrink-0
     [status-dot connection-status]
     [settings-button]
     [:div.is-flex-grow-1]
     [:div.hud-grab-handle
      {:on-click #(rf/dispatch [::ui.events/set-hud-open false])
       :role "button" :aria-label "Minimize HUD"}]
     [:div.is-flex-grow-1.is-flex.is-justify-content-flex-end
      [:button.delete.is-medium
       {:on-click #(rf/dispatch [::ui.events/set-hud-open false])
        :aria-label "Close"}]]]))

(defn desktop-hud-details
  []
  (let [settings-open? @(rf/subscribe [::ui.subs/settings-open?])
        body (if settings-open?
               [settings-panel]
               [:div.columns.is-variable.is-2.hud-columns
                [hud-desktop-column (users-view)]
                [hud-desktop-column (places-view)]
                [hud-desktop-column (messages-view)]])]
    [hud-details-layout
     {:header [hud-header]
      :body body}]))

(defn hud-details
  []
  (if @(rf/subscribe [::ui.subs/is-mobile?])
    [mobile-hud-details]
    [desktop-hud-details]))

(defn hud-summary
  []
  (let [users-online @(rf/subscribe [::conn.subs/users-online])
        placeable-map-objects @(rf/subscribe [::ui.subs/placeable-map-objects])
        mouse-action @(rf/subscribe [::ui.subs/mouse-action])
        connection-status @(rf/subscribe [::conn.subs/status])
        active-model (when (= :place-object (:type mouse-action))
                       (:model-id mouse-action))]
    [:div
     {:style {:flex 1 :display "flex" :align-items "center"
              :padding "0 1rem" :justify-content "space-between"}}
     [:div.is-flex.is-align-items-center
      [status-dot connection-status]
      [:div.buttons.are-small.mb-0.ml-2
       (for [o (filter :show-in-summary? placeable-map-objects)]
         ^{:key (str "placeable-sum" (name (:model-id o)))}
         [:button
          {:class (cond-> "button is-small is-primary is-outlined"
                    (= active-model (:model-id o)) (str " is-warning"))
           :title (:name o)
           :on-click (fn [_]
                       (if (= active-model (:model-id o))
                         (rf/dispatch [::ui.events/set-mouse-action nil])
                         (rf/dispatch
                          [::ui.events/set-mouse-action
                           {:type :place-object :model-id (:model-id o)}])))}
          (:icon o)])
       [:button {:class "button is-small is-info is-outlined" :title "Users online"}
        [:span {:class "icon-text"}
         [:span {:class "icon"} "👥"]
         [:span (count users-online)]]]]]
     [:button {:class "button is-small is-light"
               :on-click #(rf/dispatch [::ui.events/set-hud-open true])}
      [:span {:class "icon-text"}
       [:span {:class "icon"} "▲"]
       [:span "Open HUD"]]]]))

(defn system-notifications-view
  []
  (let [notifications @(rf/subscribe [::ui.subs/system-notifications])]
    (when (seq notifications)
      (into [:div.system-notifications]
            (map (fn [{:keys [id message severity]}]
                   [:div {:key id
                          :class (str "notification is-light "
                                      (case severity
                                        :error "is-danger"
                                        :warning "is-warning"
                                        "is-info"))}
                    message]))
            notifications))))

(defn present
  []
  (let [open? @(rf/subscribe [::ui.subs/hud-open?])
        hud-height (if open? "33vh" "3.75rem")]
    [:div#hud {:style {:height hud-height}}
     (if open?
       [hud-details]
       [hud-summary])
     [system-notifications-view]]))
