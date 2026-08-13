(ns is.galt.globo.server.placeables
  "Default PlaceableObjectProvider: a static config vector of 3D-object
  definitions. This is the data historically compiled into the browser
  UI; it now lives server-side so the client can be configured purely
  with server-provided options."
  (:require
   [is.galt.globo.protocols :as protocols]))

(def default-config
  "Static config of placeable 3D objects (model-ids are strings, :icon
  values are emoji rendered by the browser UI)."
  [{:model-id "user-figure-simple"
    :path "3d/user-figure-simple.glb"
    :scale 0.1
    :name "User A"
    :icon "👱"
    :show-in-summary? false}
   {:model-id "user-figure-parts"
    :path "3d/user-figure-parts.glb"
    :scale 0.15
    :name "User B"
    :icon "👷"
    :show-in-summary? false}
   ; {:model-id "carrot" :path "3d/carrot.glb" :scale 10 :name "Carrot"
   ;  :icon "🥕" :show-in-summary? true}
   ; {:model-id "tree" :path "3d/fantasy_tree.glb" :scale 3 :name "Tree"
   ;  :icon "🌳"}
   ; {:model-id "man" :path "3d/mountain_robot.glb" :scale 60 :name "Robot"
   ;  :icon "🤖"}
   {:model-id "ancap-bug" :path "3d/ancap_bug.glb" :scale 5 :name "Bug"
    :icon "🪲"}
   {:model-id "zombie-small" :path "3d/zombie.glb" :scale 5 :name "Zombie"
    :icon "🧟"}
   {:model-id "ancap-flag" :path "3d/ancap_flag.glb" :scale 1.5 :name "Ancap"
    :icon "⚑" :show-in-summary? true}])

(defrecord StaticPlaceableObjects [config]
  protocols/PlaceableObjectProvider
  (placeable-objects [_ _user-id]
    config))

(defn static-placeable-objects
  "Create a PlaceableObjectProvider serving a static config vector.
  Defaults to default-config."
  ([] (static-placeable-objects default-config))
  ([config] (->StaticPlaceableObjects config)))
