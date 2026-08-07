(ns is.galt.globo.server.storage
  "Default in-memory GloboStorage implementation, backed by an atom with
  the shape {:users {} :map-objects #{} :user-connections {} :messages []}."
  (:require
   [is.galt.globo.protocols :as protocols]))

(def default-state
  {:users {}
   :map-objects #{}
   :user-connections {}
   :messages []})

(defrecord InMemoryGloboStorage [state]
  protocols/GloboStorage
  (get-user [_ user-id]
    (get-in @state [:users user-id]))
  (update-user! [_ user-id f]
    (swap! state update-in [:users user-id] f))
  (users-map [_]
    (:users @state))
  (user-favorites [_ user-id]
    (get-in @state [:users user-id :favorites] []))
  (update-favorite! [_ user-id index partial]
    (swap! state update-in [:users user-id :favorites index] #(merge % partial))
    (get-in @state [:users user-id :favorites index]))
  (add-favorite! [_ user-id favorite]
    (swap! state update-in [:users user-id :favorites] (fnil conj []) favorite))
  (get-map-objects [_]
    (:map-objects @state))
  (set-map-objects! [_ objects]
    (swap! state assoc :map-objects objects))
  (append-message! [_ message]
    (swap! state update :messages conj message))
  (latest-messages [_ limit]
    (let [messages (:messages @state)]
      (subvec messages (max 0 (- (count messages) limit)))))
  (connection-ids-for-user [_ user-id]
    (get-in @state [:user-connections user-id] #{}))
  (add-user-connection! [_ user-id connection-id]
    (swap! state update-in [:user-connections user-id] (fnil conj #{}) connection-id))
  (remove-user-connection! [_ user-id connection-id]
    (swap! state update-in [:user-connections user-id] disj connection-id)))

(defn in-memory-globo-storage
  "Create an in-memory GloboStorage. Optionally wrap an existing atom with
  the default-state shape (used by the legacy back-compat path)."
  ([] (in-memory-globo-storage (atom default-state)))
  ([state]
   (->InMemoryGloboStorage (or state (atom default-state)))))
