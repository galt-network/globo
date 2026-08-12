(ns is.galt.globo.server.hexholds
  "Default in-memory HexholdStore implementation: a sparse map of
  hex-id -> color keyword in an atom, plus an optional land index
  (a set of land hex-ids loaded from a classpath resource)."
  (:require
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as str]
   [is.galt.globo.protocols :as protocols]))

(def default-state
  {:colors {}})

(defrecord InMemoryHexholdStore [state land-cells]
  protocols/HexholdStore
  (paint-hexhold! [_ hex-id color]
    (let [color (some-> color keyword)
          result {:id hex-id :color color}]
      (if color
        (swap! state assoc-in [:colors hex-id] color)
        (swap! state update :colors dissoc hex-id))
      result))
  (hexhold-colors [_]
    (:colors @state))
  (query-hexholds [_ cell-ids]
    (let [colors (:colors @state)
          requested (if land-cells
                      (set/intersection (set cell-ids) land-cells)
                      (set cell-ids))]
      (mapv (fn [id]
              {:id id :color (get colors id)})
            requested))))

(defn in-memory-hexhold-store
  "Create an in-memory HexholdStore. Optionally pass an existing atom with
  the default-state shape and/or a land index (a set of hex-id strings)."
  ([] (in-memory-hexhold-store nil))
  ([land-cells] (in-memory-hexhold-store land-cells nil))
  ([land-cells state]
   (->InMemoryHexholdStore (or state (atom default-state)) land-cells)))

(defn load-land-index
  "Load a land index of one hex-id per line from a classpath resource.
  Returns a set of hex-id strings, or nil when the resource is missing
  (all-land dev fallback)."
  [resource-path]
  (when-let [res (io/resource resource-path)]
    (with-open [reader (io/reader res)]
      (->> (line-seq reader)
           (map str/trim)
           (remove str/blank?)
           (into #{})))))
