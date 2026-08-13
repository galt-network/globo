(ns is.galt.globo.ui.camera)

(def hop-below-alt 0.25)
(def hop-min-cruise 0.25)
(def hop-max-cruise 2.0)
(def hop-cruise-per-deg 0.02)
(def hop-out-ms 400)
(def hop-in-ms 400)
(def hop-min-move-ms 400)
(def hop-max-move-ms 1200)
(def hop-move-ms-per-deg 8)
(def hop-min-lift 0.05)

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

(defn- clamp [x lo hi]
  (min hi (max lo x)))

(defn- cruise-alt [deg]
  (clamp (+ hop-min-cruise (* deg hop-cruise-per-deg))
         hop-min-cruise
         hop-max-cruise))

(defn- move-ms [deg]
  (clamp (+ hop-min-move-ms (* deg hop-move-ms-per-deg))
         hop-min-move-ms
         hop-max-move-ms))

(defn hop-legs
  [{from-lat :lat from-lng :lng from-alt :altitude}
   {to-lat :lat to-lng :lng to-alt :altitude}]
  (when (and from-alt
             (<= from-alt hop-below-alt)
             (or (not= from-lat to-lat) (not= from-lng to-lng)))
    (let [deg (angular-distance-deg from-lat from-lng to-lat to-lng)
          cruise (cruise-alt deg)]
      (when (> cruise (+ from-alt hop-min-lift))
        [{:lat from-lat :lng from-lng :altitude cruise :duration hop-out-ms}
         {:lat to-lat :lng to-lng :altitude cruise :duration (move-ms deg)}
         {:lat to-lat :lng to-lng :altitude to-alt :duration hop-in-ms}]))))
