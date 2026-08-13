(ns is.galt.globo.ui.hexholds
  "Pure helpers for the hexholds (H3 hexagon paint) feature. No side
  effects — fully unit tested. h3-js 4.5.0 API: latLngToCell,
  cellToLatLng, gridDisk, cellToBoundary, cellArea (string units)."
  (:require
   ["h3-js" :as h3]
   [clojure.string :as str]))

(def resolution 5)
(def max-altitude 0.8)
(def max-viewport-cells 1500)
;; three-render-objects constructs `new three.PerspectiveCamera()` — the
;; three.js default vertical FOV of 50 degrees.
(def fov 50)
(def color-cycle [nil :red :blue :green :yellow :purple])
(def color->rgba
  {:red "rgba(220, 50, 50, 0.4)"
   :blue "rgba(40, 100, 230, 0.4)"
   :green "rgba(40, 180, 80, 0.4)"
   :yellow "rgba(230, 200, 40, 0.4)"
   :purple "rgba(160, 60, 200, 0.4)"})
(def color->hover-rgba
  {:red "rgba(255, 120, 120, 0.55)"
   :blue "rgba(120, 160, 255, 0.55)"
   :green "rgba(120, 255, 160, 0.55)"
   :yellow "rgba(255, 240, 140, 0.55)"
   :purple "rgba(220, 140, 255, 0.55)"})
(def transparent-fill "rgba(0, 0, 0, 0)")
(def unpainted-fill "rgba(255, 255, 255, 0.08)")
(def unpainted-hover-fill "rgba(255, 255, 255, 0.22)")
(def default-stroke "rgba(255, 255, 255, 0.7)")
;; The hover outline is the cell's OWN stroke, tinted teal. The polygons
;; layer renders strokes at scale 1 + alt + 1e-4 — exactly on top of the
;; caps — so any separate outline layer at cap altitude is covered by the
;; stroke and invisible. Tinting the stroke keeps the outline aligned to
;; the cell border by construction, at any camera angle.
(def highlight-stroke-color "rgba(0, 188, 212, 1)")
;; Altitudes are RELATIVE to the globe radius: r = R * (1 + alt). The
;; polygons layer renders caps at scale 1 + polygon-altitude, so hit-test
;; rings MUST use the same convention (r = R*(1+ring-altitude) = 100.5).
(def polygon-altitude 0.005)
(def ring-altitude polygon-altitude)

(defn next-color
  "Cycle color-cycle forward: nil -> :red -> :blue -> :green -> :yellow
   -> :purple -> nil. Unknown colors are treated as nil."
  [color]
  (let [idx (js/Math.max 0 (.indexOf color-cycle color))
        next (nth color-cycle (mod (inc idx) (count color-cycle)))]
    next))

(defn fill-color
  "RGBA fill string for a color keyword; nil (unpainted) -> unpainted-fill."
  [color]
  (get color->rgba color unpainted-fill))

(defn hover-fill-color
  "RGBA fill string for the hovered cell; nil (unpainted) -> the faint
   white hover tint."
  [color]
  (get color->hover-rgba color unpainted-hover-fill))

(defn hover-stroke-color
  "Border stroke for a cell: teal when hovered, else the default white
   stroke."
  [hovered?]
  (if hovered? highlight-stroke-color default-stroke))

(defn within-lod?
  "LOD gate: hexes render only at or below max-altitude."
  [altitude]
  (and (some? altitude) (<= altitude max-altitude)))

(defn can-paint?
  "Ownership gate: an entry (visible hexhold map with :owner-id) can be
   painted by user-id when it is unowned or owned by them. A missing
   entry is not blocked client-side — the server decides."
  [entry user-id]
  (let [owner (:owner-id entry)]
    (or (nil? owner) (= owner user-id))))

(defn click-paint-hexhold
  "Pure decision: the hex-id to paint for a click point, or nil. Requires
   an explicit :hex-id from the screen-space hit test — lat/lng-only
   points (globe.gl's stale internal raycast on background clicks) are
   never painted. Also gates on the layer being active, the camera being
   within the LOD altitude, the cell being part of :visible, and the
   ownership rules (can-paint?)."
  [db point altitude]
  (let [hex-id (:hex-id point)
        visible (get-in db [:hexholds :visible])
        entry (some #(when (= hex-id (:id %)) %) visible)
        user-id (get-in db [:connection :user-id])]
    (when (and (some? hex-id)
               (= :hexholds (get-in db [:ui :active-view]))
               (within-lod? altitude)
               (some? entry)
               (can-paint? entry user-id))
      {:hex-id hex-id})))

(defn update-visible-entry
  "Set the :color of the :visible entry whose :id equals hex-id; all other
   entries are returned unchanged. Entries not in :visible are not added
   (the viewport query owns the id set)."
  [visible hex-id color]
  (mapv (fn [{entry-id :id :as h}]
          (if (= entry-id hex-id)
            (assoc h :color color)
            h))
        visible))

(defn latlng->cell
  [lat lng]
  (h3/latLngToCell lat lng resolution))

(defn cell->latlng
  [hex-id]
  (let [[lat lng] (h3/cellToLatLng hex-id)]
    {:lat lat :lng lng}))

(defn resolve-paint-hex-id
  "Explicit hex-id wins; otherwise derive the cell from lat/lng; nil when
   neither is available."
  [{:keys [hex-id lat lng]}]
  (cond
    hex-id hex-id
    (and lat lng) (latlng->cell lat lng)
    :else nil))

(defn cell-boundary-ring
  "Closed [lng lat] ring of the hexagon, with the winding reversed from
   h3's default (three-conic-polygon-geometry needs the opposite winding
   or the hex is treated as the sphere's exterior). h3 returns a CLOSED
   ring (first == last); the duplicate vertex is dropped and the ring is
   re-closed exactly once. Vertices are plain CLJS vectors."
  [hex-id]
  (let [ring (mapv vec (h3/cellToBoundary hex-id true))
        open (pop ring)
        reversed (vec (reverse open))]
    (conj reversed (first reversed))))

(defn ring-signed-area
  "Shoelace signed area of a [lng lat] ring (positive = one winding,
   negative = the other). Exported for tests."
  [ring]
  (let [n (count ring)]
    (loop [i 0
           area 0]
      (if (>= i n)
        (/ area 2)
        (let [[x1 y1] (nth ring i)
              [x2 y2] (nth ring (mod (inc i) n))]
          (recur (inc i) (+ area (- (* x1 y2) (* x2 y1)))))))))

(defn- deg->rad
  [d]
  (* d (/ js/Math.PI 180)))

(defn- rad->deg
  [r]
  (* r (/ 180 js/Math.PI)))

(defn- angular-distance-deg
  "Great-circle angular distance (degrees) between two lat/lng points."
  [lat1 lng1 lat2 lng2]
  (let [dlat (deg->rad (- lat2 lat1))
        dlng (deg->rad (- lng2 lng1))
        a (+ (* (js/Math.sin (/ dlat 2)) (js/Math.sin (/ dlat 2)))
             (* (js/Math.cos (deg->rad lat1))
                (js/Math.cos (deg->rad lat2))
                (js/Math.sin (/ dlng 2))
                (js/Math.sin (/ dlng 2))))]
    (rad->deg (* 2 (js/Math.asin (js/Math.sqrt a))))))

(defn- frustum-corner-half-angle
  "Frustum half-angle (degrees) along the screen-corner ray: the viewport
   diagonal extends the vertical half-angle by sqrt(1 + aspect^2)."
  [aspect]
  (rad->deg (js/Math.atan (* (js/Math.tan (deg->rad (/ fov 2)))
                             (js/Math.sqrt (inc (* aspect aspect)))))))

(defn cap-angle-deg
  "Angular radius (degrees) of the globe cap visible in the viewport along
   the screen-corner ray, at camera altitude `a` (a = d/R - 1) and screen
   `aspect`. 90 when the whole globe fits (the corner ray misses the
   sphere)."
  [altitude aspect]
  (let [a (or altitude 0)
        phi (deg->rad (frustum-corner-half-angle (or aspect 1)))
        s (js/Math.sin phi)
        c (js/Math.cos phi)
        d (* (inc a) s)]
    (if (>= d 1)
      90
      (rad->deg (js/Math.acos (- (inc a)
                                 (* c (- (* (inc a) c)
                                         (js/Math.sqrt (- 1 (* d d)))))))))))

(defn ring-spacing-deg
  "Mean center-to-center distance (degrees) from `center-cell` to its six
   gridDisk-1 neighbors — the lattice spacing, self-adapting to the local
   icosahedral distortion."
  [center-cell]
  (let [{center-lat :lat center-lng :lng} (cell->latlng center-cell)]
    (/ (->> (h3/gridDisk center-cell 1)
            (remove #(= % center-cell))
            (map (fn [c]
                   (let [{lat :lat lng :lng} (cell->latlng c)]
                     (angular-distance-deg center-lat center-lng lat lng))))
            (reduce +))
       6)))

(defn- max-k
  "Largest gridDisk k whose cell count (3k^2+3k+1) fits within max-cells."
  [max-cells]
  (js/Math.floor (/ (- (js/Math.sqrt (- (* 12 max-cells) 3)) 3) 6)))

(defn disk-k
  "gridDisk radius for a viewpoint: the smallest k whose patch reaches the
   viewport corner (k >= cap-angle / ring-spacing), capped at
   max-viewport-cells. The cap is a plateau — the count never shrinks with
   altitude, it saturates."
  [{:keys [lat lng altitude aspect]}]
  (let [spacing (ring-spacing-deg (latlng->cell (or lat 0) (or lng 0)))
        k (js/Math.ceil (/ (cap-angle-deg altitude aspect) spacing))]
    (js/Math.min k (max-k max-viewport-cells))))

(defn viewport-cells
  "Cell-ids of the hexagonal gridDisk patch around the viewpoint cell,
   sized to cover the whole viewport (corner-to-corner) and capped at
   max-viewport-cells. The patch is a hexagon by construction — h3 gridDisk
   is index-space, so it is also safe across the antimeridian."
  [viewpoint]
  (h3/gridDisk (latlng->cell (or (:lat viewpoint) 0)
                             (or (:lng viewpoint) 0))
               (disk-k viewpoint)))

(defn screen->ndc
  [px py width height]
  {:x (- (* 2 (/ px width)) 1)
   :y (- 1 (* 2 (/ py height)))})

(defn ndc->screen
  [ndc-x ndc-y width height]
  {:x (* (+ ndc-x 1) 0.5 width)
   :y (* (- 1 ndc-y) 0.5 height)})

(defn- on-segment?
  "True when (px py) lies within tolerance of the segment a->b."
  [px py ax ay bx by]
  (let [seg-len-sq (+ (* (- bx ax) (- bx ax)) (* (- by ay) (- by ay)))]
    (when (pos? seg-len-sq)
      (let [t (/ (+ (* (- px ax) (- bx ax)) (* (- py ay) (- by ay)))
                  seg-len-sq)
            t (js/Math.max 0 (js/Math.min 1 t))
            proj-x (+ ax (* t (- bx ax)))
            proj-y (+ ay (* t (- by ay)))
            dx (- px proj-x)
            dy (- py proj-y)
            tolerance (* 1e-6 (js/Math.max 1 (js/Math.sqrt seg-len-sq)))]
        (< (js/Math.sqrt (+ (* dx dx) (* dy dy))) tolerance)))))

(defn point-in-polygon?
  "Strict containment: true only when (x y) is strictly inside the ring.
   Points on edges or vertices are NOT inside (borders belong to no
   cell)."
  [x y ring]
  (let [n (count ring)]
    (loop [i 0
           j (dec n)
           inside? false]
      (if (>= i n)
        inside?
        (let [[xi yi] (nth ring i)
              [xj yj] (nth ring j)
              on-edge? (or (on-segment? x y xi yi xj yj)
                           (on-segment? x y xj yj xi yi))]
          (if on-edge?
            false
            (let [crossing? (and (not= (> yi y) (> yj y))
                                 (< x (+ xj (* (- y yj)
                                               (/ (- xi xj) (- yi yj))))))]
              (recur (inc i) i (if crossing? (not inside?) inside?)))))))))

(defn hit-test-point
  "First ring-map entry whose ring strictly contains (x y), or nil.
   ring-map: {id [[x y] ...closed-ring]}."
  [ring-map x y]
  (some (fn [[id ring]]
          (when (point-in-polygon? x y ring) id))
        ring-map))

(defn hexhold->props
  "Globe polygonsData props for a cell (hover-independent)."
  [color]
  {:altitude polygon-altitude
   :cap-color (fill-color color)
   :stroke-color default-stroke})

(defn polygon-feature
  "globe.gl polygonsData entry for a cell."
  [id color]
  {:id id
   :color color
   :geometry {:type "Polygon"
              :coordinates [(cell-boundary-ring id)]}})

(def earth-radius-km 6371)
(def max-zoom-out-altitude 2.5)
(def paint-colors [:red :blue :green :yellow :purple])

(defn my-hexholds
  "Visible hexhold entries owned by user-id. Unowned cells are never
   listed as anyone's."
  [visible user-id]
  (filterv #(and (:owner-id %) (= user-id (:owner-id %))) visible))

(defn height-km
  "Camera height above the surface in km (relative altitude x R)."
  [altitude]
  (* earth-radius-km (or altitude 0)))

(defn zoom-pct
  "Zoom as a percentage: 100 at the surface, 0 when fully zoomed out
   (the whole globe at max-zoom-out-altitude)."
  [altitude]
  (let [a (or altitude 0)]
    (js/Math.max 0 (js/Math.min 100 (* 100 (- 1 (/ a max-zoom-out-altitude)))))))

(defn visible-cap-area-km2
  "Approximate km2 of the globe surface actually in view: the spherical
   cap along the screen-corner ray, 2*pi*R^2*(1 - cos theta) with theta
   from cap-angle-deg."
  [altitude aspect]
  (let [theta (deg->rad (cap-angle-deg altitude aspect))]
    (* 2 js/Math.PI earth-radius-km earth-radius-km (- 1 (js/Math.cos theta)))))

(defn viewport-info
  "Live map info for the hexholds-info column: camera zoom/height, the
   visible globe-cap area, and viewport hexhold counts. Returns nil when
   the camera is unavailable (altitude or aspect missing)."
  [{:keys [altitude aspect]} visible]
  (when (and (some? altitude) (some? aspect))
    (let [n (count visible)
          painted (count (filter :color visible))]
      {:altitude altitude
       :zoom-pct (zoom-pct altitude)
       :height-km (height-km altitude)
       :visible-area-km2 (visible-cap-area-km2 altitude aspect)
       :visible-count n
       :painted-count painted
       :painted-pct (if (zero? n) 0 (js/Math.round (* 100 (/ painted n))))})))

(defn upsert-message
  "Conjoin a hexhold message, replacing any existing message with the
   same :id (server echo overwrites the optimistic entry)."
  [messages message]
  (conj (filterv #(not= (:id %) (:id message)) messages) message))

(defn short-hex-id
  "Display form of a hex-id: first 8 chars with an ellipsis when longer."
  [hex-id]
  (if (<= (count hex-id) 8)
    hex-id
    (str (subs hex-id 0 8) "…")))

(defn format-thousands
  "Round a number and insert thousands separators (display only)."
  [n]
  (let [digits (str (js/Math.round (js/Math.abs n)))]
    (if (<= (count digits) 3)
      digits
      (let [rem (mod (count digits) 3)]
        (->> (if (zero? rem)
               (partition-all 3 digits)
               (cons (subs digits 0 rem)
                     (partition-all 3 (subs digits rem))))
             (map str/join)
             (str/join ","))))))
