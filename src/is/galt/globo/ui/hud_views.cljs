(ns is.galt.globo.ui.hud-views
  "Pure helpers for the HUD radio view state machine. Exactly one of
   :user-communication / :settings / :hexholds is active at a time —
   the three HUD view buttons behave like radio buttons."
  (:require
   [is.galt.globo.ui.hexholds :as hexholds]))

(def view-keys [:user-communication :settings :hexholds])

(defn active-view
  "The currently active HUD view; defaults to :user-communication."
  [db]
  (get-in db [:ui :active-view] :user-communication))

(defn hexholds-view?
  "True when the hexholds view (grid + panel) is active."
  [db]
  (= :hexholds (active-view db)))

(defn leave-hexholds
  "Cleanup for turning the hexholds view off: clears the grid data and
   resets the user-communication sub-tab, emitting grid-sync fx.
   Operates on the PRE-switch db — the caller applies the new
   active-view afterwards."
  [db]
  (let [colors (get-in db [:hexholds :colors])
        hover-id (get-in db [:hexholds :hover-id])]
    {:db (-> db
             (assoc-in [:hexholds :visible] [])
             (assoc-in [:hexholds :hover-id] nil)
             (assoc-in [:hexholds :selected-id] nil)
             (assoc-in [:ui :active-panel] :users))
     :fx [[:is.galt.globo.ui.events/sync-hexholds {:visible [] :colors colors}]
          [:is.galt.globo.ui.events/update-hexhold-hover-tint
           {:from-id hover-id :to-id nil :colors colors}]]}))

(defn enter-hexholds
  "Activates the hexholds view. Within LOD the viewport is re-queried;
   above it the user gets the zoom-in toast instead."
  [db viewpoint]
  (if (hexholds/within-lod? (:altitude viewpoint))
    {:db db
     :fx [[:dispatch [:is.galt.globo.ui.events/refresh-hexholds-viewport]]
          [:dispatch [:is.galt.globo.ui.events/update-hexholds-info]]]}
    {:db db
     :fx [[:dispatch
           [:is.galt.globo.ui.connection.events/system-notification
            {:content {:message "Zoom in to see hexholds"
                       :severity :info}}]]]}))

(defn apply-view
  "Radio-style view switch: returns {:db ... :fx [...]}, or nil when
   `view` is already active (clicking the active button is a no-op).
   Leaving the hexholds view turns the grid off; entering it re-queries
   the viewport (or shows the LOD toast)."
  [db view viewpoint]
  (when-not (= view (active-view db))
    (let [cleanup (when (hexholds-view? db) (leave-hexholds db))
          db' (-> (or (:db cleanup) db)
                  (assoc-in [:ui :active-view] view))]
      (if (= view :hexholds)
        (enter-hexholds db' viewpoint)
        (cond-> {:db db'}
          (:fx cleanup) (assoc :fx (:fx cleanup)))))))
