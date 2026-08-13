(ns is.galt.globo.ui.models)

(defn flush-layer-action
  [objects]
  (when (seq objects)
    {:op :replace :objects objects}))

(defn placeables-fx
  [assets-base-url objects source]
  (if (seq objects)
    [[:is.galt.globo.ui.events/preload-models
      {:assets-base-url assets-base-url :placeables objects}]]
    (when (= source :server)
      [[:dispatch [:is.galt.globo.ui.events/all-models-ready]]])))
