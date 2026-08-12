(ns is.galt.globo.ui.hexholds
  "Pure helpers for the hexholds (H3 hexagon paint) feature. No side
  effects — fully unit tested. h3-js 4.5.0 API: latLngToCell,
  cellToLatLng, polygonToCells, cellToBoundary, cellArea (string units)."
  (:require
   ["h3-js" :as h3]))

(def resolution 5)
(def max-altitude 0.8)
(def max-viewport-cells 1500)
(def viewport-span-factor 20)
(def min-half-span 0.8)
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

(def cells-per-degree-2
  "Estimated cell count per square degree at the equator, computed from
  the actual h3 cell area (resolution-aware)."
  (let [cell (h3/latLngToCell 0 0 resolution)]
    (/ (* 111.32 111.32) (h3/cellArea cell "km2"))))

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

(defn click-paint-hexhold
  "Pure decision: the hex-id to paint for a click point, or nil. Requires
   an explicit :hex-id from the screen-space hit test — lat/lng-only
   points (globe.gl's stale internal raycast on background clicks) are
   never painted. Also gates on the layer being active, the camera being
   within the LOD altitude, and the cell being part of :visible."
  [db point altitude]
  (let [hex-id (:hex-id point)
        visible-ids (into #{} (map :id) (get-in db [:hexholds :visible]))]
    (when (and (some? hex-id)
               (get-in db [:hexholds :active?])
               (within-lod? altitude)
               (contains? visible-ids hex-id))
      {:hex-id hex-id})))

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

(defn viewpoint->bbox
  "Bounding box half-span grows linearly with camera altitude
   (span-factor degrees per unit altitude), floored at min-half-span."
  ([viewpoint]
   (viewpoint->bbox viewpoint viewport-span-factor))
  ([{:keys [lat lng altitude]} span-factor]
   (let [half-span (max min-half-span (* (or altitude 1) span-factor))
         lat (or lat 0)
         lng (or lng 0)]
     {:lat-min (max -90 (- lat half-span))
      :lat-max (min 90 (+ lat half-span))
      :lng-min (- lng half-span)
      :lng-max (+ lng half-span)})))

(defn- cells-in-bbox
  "All h3 cells intersecting the bbox polygon."
  [bbox]
  (h3/polygonToCells
   (clj->js [[(:lat-min bbox) (:lng-min bbox)]
             [(:lat-min bbox) (:lng-max bbox)]
             [(:lat-max bbox) (:lng-max bbox)]
             [(:lat-max bbox) (:lng-min bbox)]])
   resolution
   false))

(defn viewport-cells
  "Cell-ids inside the bbox, capped at max-viewport-cells. When the
   estimate exceeds the cap the bbox is pre-shrunk toward its center by
   sqrt(cap/estimate) BEFORE the polygonToCells call, so the query never
   explodes."
  [bbox]
  (let [estimate (* cells-per-degree-2
                    (* (- (:lat-max bbox) (:lat-min bbox))
                       (- (:lng-max bbox) (:lng-min bbox))))]
    (if (<= estimate max-viewport-cells)
      (cells-in-bbox bbox)
      (let [factor (js/Math.sqrt (/ max-viewport-cells estimate))
            lat-min (:lat-min bbox)
            lat-max (:lat-max bbox)
            lng-min (:lng-min bbox)
            lng-max (:lng-max bbox)
            half-lat (* (- lat-max lat-min) factor 0.5)
            half-lng (* (- lng-max lng-min) factor 0.5)
            mid-lat (/ (+ lat-min lat-max) 2)
            mid-lng (/ (+ lng-min lng-max) 2)]
        (cells-in-bbox {:lat-min (- mid-lat half-lat)
                        :lat-max (+ mid-lat half-lat)
                        :lng-min (- mid-lng half-lng)
                        :lng-max (+ mid-lng half-lng)})))))

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
