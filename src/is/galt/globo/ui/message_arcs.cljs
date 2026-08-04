(ns is.galt.globo.ui.message-arcs
  "Pure helpers for computing globe.gl message arcs: resolving a
  @username direct-message target, picking world-message endpoints
  (online users with locations, random spots as filler), and building
  the arc data handed to the globe arcsData layer."
  (:require
   [clojure.string :as str]))

(def max-arcs
  "Maximum number of arcs to emit for a world message."
  5)

(defn random-globe-spot
  "A uniformly random lat/lng: lat in [-80, 80) to avoid pole-degenerate
  arcs, lng in [-180, 180). Accepts an optional rand-fn for tests."
  ([] (random-globe-spot js/Math.random))
  ([rand-fn]
   {:lat (- (* (rand-fn) 160) 80)
    :lng (- (* (rand-fn) 360) 180)}))

(defn location
  "The {:lat :lng} of a user map when both are set, else nil."
  [user]
  (let [{:keys [lat lng]} (:location user)]
    (when (and lat lng)
      {:lat lat :lng lng})))

(defn origin-location
  "Best available arc start point: the user's :location when set, else
  the camera viewpoint (lat/lng only). nil when neither is known."
  [user viewpoint]
  (or (location user)
      (when (and viewpoint (:lat viewpoint) (:lng viewpoint))
        (select-keys viewpoint [:lat :lng]))))

(defn direct-target
  "Resolve a leading @username in `text` to a user in the `users` map
  (case-insensitive :name match). Returns the user map or nil. Mirrors
  the server's parse-message-type: an unknown username is not direct."
  [users text]
  (when-let [[_ username] (re-find #"^@(\S+)" text)]
    (some (fn [[_ user]]
            (when (and (:name user)
                       (= (str/lower-case (:name user))
                          (str/lower-case username)))
              user))
          users)))

(defn direct-endpoint
  "Endpoint for a direct message: the target's location when set, else a
  random globe spot so the sender still gets visual feedback."
  ([target] (direct-endpoint target js/Math.random))
  ([target rand-fn]
   (or (location target) (random-globe-spot rand-fn))))

(defn select-world-endpoints
  "Pick up to `max` online users (excluding self) who have a location
  set, shuffled; fill the remainder with random globe spots. Returns a
  vector of {:lat :lng}."
  ([online-users self-id]
   (select-world-endpoints online-users self-id max-arcs js/Math.random))
  ([online-users self-id max]
   (select-world-endpoints online-users self-id max js/Math.random))
  ([online-users self-id max rand-fn]
   (let [located (->> (vals online-users)
                      (remove #(= (:id %) self-id))
                      (filter location)
                      (shuffle)
                      (take max)
                      (map location))
         n (count located)]
     (into (vec located)
           (repeatedly (- max n) #(random-globe-spot rand-fn))))))

(defn endpoints-for-send
  "Endpoints (vector of {:lat :lng}) for arcs shown when the current
  user sends `text`: the direct-message target for @username messages,
  otherwise world-message selection. A message addressed to self emits
  no arcs. Optional rand-fn for tests."
  ([text self-id users online-users]
   (endpoints-for-send text self-id users online-users js/Math.random))
  ([text self-id users online-users rand-fn]
   (if-let [target (direct-target users text)]
     (if (= (:id target) self-id)
       []
       [(direct-endpoint target rand-fn)])
     (vec (select-world-endpoints online-users self-id max-arcs rand-fn)))))

(defn arc-data
  "Build a globe.gl arcsData object from an origin and an endpoint."
  [origin endpoint]
  {:startLat (:lat origin)
   :startLng (:lng origin)
   :endLat (:lat endpoint)
   :endLng (:lng endpoint)})
