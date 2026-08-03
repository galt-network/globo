(ns is.galt.globo.ui.presentation.hud
  (:require
   [is.galt.globo.ui.icons :refer [icon]]
   [is.galt.globo.ui.subscriptions :as ui.subs]
   [clojure.string :as str]
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
  [placeable-map-objects set-mouse-action mouse-action]
  (let [active-model (when (= :place-object (:type mouse-action))
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
                         (set-mouse-action nil)
                         (set-mouse-action
                          {:type :place-object :model-id (:model-id o)})))}
          (str (:icon o) " " (:name o))])]]]))

(defn favorite-row
  [{:keys [index favorite set-mouse-action clear-mouse-action
           mouse-action go-to-favorite rename-favorite]}]
  (r/with-let [editing? (r/atom false)
               draft (r/atom (or (:label favorite) ""))]
    (let [set? (and (some? (:lat favorite)) (some? (:lng favorite)))
          label (:label favorite)
          blank? (str/blank? label)
          shorten (fn [float] (when float (.toFixed float 3)))
          shortened-coordinates (str (shorten (:lat favorite)) ":" (shorten (:lng favorite)))
          display (cond
                    (not blank?) label
                    set? (str "Unnamed (" shortened-coordinates  ")")
                    :else "Unnamed")
          active? (and (= :set-favorite (:type mouse-action))
                       (= (:index mouse-action) index))
          busy? (and (:type mouse-action) (not active?))]
      [panel-row
       [:div.level.is-mobile.p-0
        {:class (when set? "is-clickable")
         :on-click (fn [_] (when set? (go-to-favorite index)))
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
                         (rename-favorite index @draft))
              :on-key-down
              (fn [e]
                (case (.-key e)
                  "Enter" (do (reset! editing? false)
                              (rename-favorite index @draft))
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
                         (clear-mouse-action)
                         (set-mouse-action
                          {:type :set-favorite :index index})))}
          (if active? (icon :cancel) (icon :set-location))]]]])))

(defn add-favorite-row
  [{:keys [add-favorite mouse-action]}]
  (let [busy? (some? (:type mouse-action))]
    [panel-row
     [:div.level.is-mobile.p-0
      [:div.level-left
       [:span.has-text-grey-light.is-size-7 ""]]
      [:div.level-right
       [:button.button.is-small.is-primary
        {:disabled busy?
         :on-click (fn [_] (add-favorite))}
        "Add"]]]]))

(defn favorite-places
  [{:keys [favorites max-favorite-places mouse-action set-mouse-action clear-mouse-action
           go-to-favorite rename-favorite add-favorite]}]
  (let [rows (mapv (fn [f i] {:index i :favorite f})
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
          [add-favorite-row
           {:add-favorite add-favorite
            :mouse-action mouse-action}]
          ^{:key (str "favorite-" (:index slot))}
          [favorite-row
           {:index (:index slot)
            :favorite (:favorite slot)
            :set-mouse-action set-mouse-action
            :clear-mouse-action clear-mouse-action
            :mouse-action mouse-action
            :go-to-favorite go-to-favorite
            :rename-favorite rename-favorite}]))]]))

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
  [{:keys [placeable-map-objects mouse-action favorites
           set-mouse-action clear-mouse-action
           go-to-favorite rename-favorite add-favorite]}]
  (let [max-favorite-places @(rf/subscribe [::ui.subs/max-favorite-places])]
    [:div.hud-vstack.places-layout
     [place-objects placeable-map-objects set-mouse-action mouse-action]
     [favorite-places {:favorites favorites
                       :max-favorite-places max-favorite-places
                       :mouse-action mouse-action
                       :set-mouse-action set-mouse-action
                       :clear-mouse-action clear-mouse-action
                       :go-to-favorite go-to-favorite
                       :rename-favorite rename-favorite
                       :add-favorite add-favorite}]]))

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
  [{:keys [user-name user-location mouse-action
           set-user-name set-mouse-action clear-mouse-action]}]
  (let [picking? (= :pick-user-location (:type mouse-action))]
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
           (str (Math/round (:lat user-location)) ", "
                (Math/round (:lng user-location)))]
          [:span.has-text-grey-light "Not set yet"])
        [:button.button.is-small.is-info.is-outlined.ml-3
         {:on-click (fn [_]
                      (if picking?
                        (clear-mouse-action)
                        (set-mouse-action {:type :pick-user-location})))}
         (if picking?
           [icon :cancel "Cancel"]
           [icon :pick-location "Pick on map"])]]]]]))

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
               [settings-panel (select-keys opts [:user-name :user-location :mouse-action
                                                  :set-user-name :set-mouse-action :clear-mouse-action])]
               (case active-panel
                 :users [users-view users-online]
                 :places [places-view (select-keys opts [:placeable-map-objects :mouse-action :favorites
                                                         :set-mouse-action :clear-mouse-action
                                                         :go-to-favorite :rename-favorite
                                                         :add-favorite])]
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
               [settings-panel (select-keys opts [:user-name :user-location :mouse-action
                                                  :set-user-name :set-mouse-action :clear-mouse-action])]
               [:div.columns.is-variable.is-2.hud-columns
                [hud-desktop-column (users-view users-online)]
                [hud-desktop-column (places-view (select-keys opts [:placeable-map-objects :mouse-action :favorites
                                                                    :set-mouse-action :clear-mouse-action
                                                                    :go-to-favorite :rename-favorite
                                                                    :add-favorite]))]
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
  [{:keys [open-hud users-online placeable-map-objects
           set-mouse-action mouse-action connection-status]}]
  (let [active-model (when (= :place-object (:type mouse-action))
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
                         (set-mouse-action nil)
                         (set-mouse-action
                          {:type :place-object :model-id (:model-id o)})))}
          (:icon o)])
       [:button {:class "button is-small is-info is-outlined" :title "Users online"}
        [:span {:class "icon-text"}
         [:span {:class "icon"} "👥"]
         [:span (count users-online)]]]]]
     [:button {:class "button is-small is-light"
               :on-click open-hud}
      [:span {:class "icon-text"}
       [:span {:class "icon"} "▲"]
       [:span "Open HUD"]]]]))

(defn present
  [{:keys [open?] :as opts}]
  (let [hud-height (if open? "33vh" "3.75rem")]
    [:div#hud {:style {:height hud-height}}
     (if open?
       [hud-details opts]
       [hud-summary opts])]))
