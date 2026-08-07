(ns is.galt.globo.server.connections
  "Default in-memory ConnectionStore implementation, backed by an atom
  mapping connection-id -> channel."
  (:require
   [is.galt.globo.protocols :as protocols]))

(defrecord InMemoryConnectionStore [state]
  protocols/ConnectionStore
  (add-connection! [_ connection-id channel]
    (swap! state assoc connection-id channel))
  (remove-connection! [_ connection-id]
    (swap! state dissoc connection-id))
  (registry [_]
    @state)
  (channels-for [_ connection-ids]
    (vals (select-keys @state connection-ids))))

(defn in-memory-connection-store
  "Create an in-memory ConnectionStore. Optionally wrap an existing atom
  (used by the legacy back-compat path)."
  ([] (in-memory-connection-store (atom {})))
  ([state]
   (->InMemoryConnectionStore (or state (atom {})))))
