(ns is.galt.globo.ui.icons
  "FontAwesome icon map and helper for rendering icon + optional text.")

(def icon-classes
  {:cancel [:fa-solid :fa-circle-xmark]
   :settings [:fas :fa-solid :fa-user-gear]
   :pick-location [:fa-solid :fa-location-crosshairs]
   :edit [:fa-solid :fa-pen-to-square]
   :set-location [:fa-solid :fa-location-dot]
   :hexholds [:fa-solid :fa-hexagon]})

(defn icon
  [icon-type & [text]]
  [:<>
   [:span.icon.is-small
    [:span.icon
     [:i {:class (get icon-classes icon-type)}]]]
   (when text [:span text])])
