(ns is.galt.globo.ui.icons)

(def icon-classes
  {:cancel [:fa-solid :fa-circle-xmark]
   :settings [:fas :fa-solid :fa-user-gear]
   :pick-location [:fa-solid :fa-location-crosshairs]})

(defn icon
  [icon-type & [text]]
  [:<>
   [:span.icon.is-small
    [:span.icon
     [:i {:class (get icon-classes icon-type)}]]]
   (when text [:span text])])
