(ns is.galt.globo.user-figure)

(def default-model-id "user-figure-parts")
(def default-scale 0.15)
(def default-color :blue)
(def palette-colors [:red :blue :green :yellow :purple])
(def palette (set palette-colors))
(def focus-altitude 0.06)
(def focus-ms 1500)

(def color->hex
  {:red "#dc3232"
   :blue "#2864e6"
   :green "#28b450"
   :yellow "#e6c828"
   :purple "#a03cc8"})

(def min-separation-deg 0.15)

(defn- sin [x]
  #?(:clj (Math/sin x) :cljs (js/Math.sin x)))

(defn- cos [x]
  #?(:clj (Math/cos x) :cljs (js/Math.cos x)))

(defn- atan2 [y x]
  #?(:clj (Math/atan2 y x) :cljs (js/Math.atan2 y x)))

(defn- sqrt [x]
  #?(:clj (Math/sqrt x) :cljs (js/Math.sqrt x)))

(def ^:private pi #?(:clj Math/PI :cljs js/Math.PI))

(defn- to-rad [deg]
  (* deg (/ pi 180.0)))

(defn- angular-distance-deg [lat1 lng1 lat2 lng2]
  (let [φ1 (to-rad lat1)
        φ2 (to-rad lat2)
        Δφ (to-rad (- lat2 lat1))
        Δλ (to-rad (- lng2 lng1))
        a (+ (* (sin (/ Δφ 2)) (sin (/ Δφ 2)))
             (* (cos φ1) (cos φ2) (sin (/ Δλ 2)) (sin (/ Δλ 2))))
        c (* 2 (atan2 (sqrt a) (sqrt (- 1 a))))]
    (* c (/ 180.0 pi))))

(defn normalize-color [color]
  (let [kw (if (string? color) (keyword color) color)]
    (if (contains? palette kw) kw default-color)))

(defn build-location
  ([lat lng]
   (build-location lat lng nil))
  ([lat lng {:keys [color scale id]}]
   {:lat lat
    :lng lng
    :model {:id (or id default-model-id)
            :scale (or scale default-scale)
            :color (normalize-color (or color default-color))}}))

(defn too-close?
  [users self-id {:keys [lat lng]}]
  (boolean
   (some (fn [[id u]]
           (when-let [loc (:location u)]
             (when (and (not= id self-id) (:lat loc) (:lng loc))
               (< (angular-distance-deg lat lng (:lat loc) (:lng loc))
                  min-separation-deg))))
         users)))

(defn figure-id [user-id]
  (str "user-figure-" user-id))

(defn layer-object [{:keys [id location]}]
  (when-let [model (:model location)]
    {:id (figure-id id)
     :lat (:lat location)
     :lng (:lng location)
     :model-id (:id model)
     :scale (:scale model)
     :color (normalize-color (:color model))
     :user-id id}))

(def figure-model-ids #{"user-figure-simple" "user-figure-parts"})

(defn user-figure-model? [model-id]
  (contains? figure-model-ids (name model-id)))

(defn layer-objects [users]
  (into [] (keep layer-object) (vals users)))

(defn has-figure? [user]
  (some? (get-in user [:location :model])))

(defn sync-actions [prev next]
  (let [prev-by-id (into {} (map (juxt :id identity) prev))
        next-by-id (into {} (map (juxt :id identity) next))]
    (reduce (fn [acc id]
              (let [p (get prev-by-id id)
                    n (get next-by-id id)]
                (cond
                  (nil? n) (update acc :remove conj p)
                  (nil? p) (update acc :add conj n)
                  (not= p n) (-> acc (update :remove conj p) (update :add conj n))
                  :else acc)))
            {:remove [] :add []}
            (into #{} (concat (keys prev-by-id) (keys next-by-id))))))

(defn apply-pick
  [users self-id {:keys [lat lng]} existing-location]
  (if (too-close? users self-id {:lat lat :lng lng})
    {:status :too-close}
    {:status :ok
     :location (build-location lat lng {:color (get-in existing-location [:model :color])})}))



