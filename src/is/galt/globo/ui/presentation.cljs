(ns is.galt.globo.ui.presentation
  "Top-level component: wires the map and HUD to re-frame subscriptions."
  (:require
   [is.galt.globo.ui.events :as ui.events]
   [is.galt.globo.ui.presentation.hud :as ui.hud]
   [is.galt.globo.ui.presentation.map :as ui.map]
   [is.galt.globo.ui.subscriptions :as ui.subs]
   [re-frame.core :as rf]))

(defn present
  []
  (let [map-classes @(rf/subscribe [::ui.subs/map-classes])]
    [:div {:style {:position "fixed"
                   :inset 0
                   :overflow "hidden"
                   :background "#000011"}}
     [ui.map/present {:css-classes map-classes
                      :on-globe-click #(rf/dispatch [::ui.events/click-globe %])}]
     [ui.hud/present]]))
