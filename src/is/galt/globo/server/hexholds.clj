(ns is.galt.globo.server.hexholds
  "Default in-memory HexholdStore implementation: an atom holding sparse
  maps of hex-id -> color keyword, hex-id -> owner-id, and hex-id ->
  messages, plus an optional land index (a set of land hex-ids loaded
  from a classpath resource). Enforces the ownership rules: the first
  painter claims a cell, only the owner may repaint or clear it, and
  clearing releases the claim."
  (:require
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as str]
   [is.galt.globo.protocols :as protocols]))

(def default-state
  {:colors {}
   :owners {}
   :messages {}})

(defn- apply-paint
  [st hex-id color owner-id]
  (-> st
      ((if color
         #(assoc-in % [:colors hex-id] color)
         #(update % :colors dissoc hex-id)))
      ((if color
         #(assoc-in % [:owners hex-id] owner-id)
         #(update % :owners dissoc hex-id)))))

(defrecord InMemoryHexholdStore [state land-cells]
  protocols/HexholdStore
  (paint-hexhold! [_ hex-id color owner-id]
    (let [color (some-> color keyword)]
      (locking state
        (let [current-owner (get (or (:owners @state) {}) hex-id)]
          (when (or (nil? current-owner) (= current-owner owner-id))
            (swap! state apply-paint hex-id color owner-id)
            {:id hex-id :color color :owner-id (when color owner-id)})))))
  (hexhold-colors [_]
    (:colors @state))
  (query-hexholds [_ cell-ids]
    (let [colors (:colors @state)
          owners (or (:owners @state) {})
          requested (if land-cells
                      (set/intersection (set cell-ids) land-cells)
                      (set cell-ids))]
      (mapv (fn [id]
              {:id id :color (get colors id) :owner-id (get owners id)})
            requested)))
  (hexhold-messages [_ hex-id]
    (get (or (:messages @state) {}) hex-id []))
  (add-hexhold-message! [_ hex-id author text]
    (let [message {:id (str (random-uuid))
                   :author author
                   :content text
                   :sent-at (str (java.time.Instant/now))}]
      (swap! state update-in [:messages hex-id] (fnil conj []) message)
      message)))

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
