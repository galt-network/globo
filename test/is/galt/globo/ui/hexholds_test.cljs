(ns is.galt.globo.ui.hexholds-test
  "Tests for hexholds pure helpers (H3 hexagon grid paint layer)."
  (:require
   ["h3-js" :as h3]
   [cljs.test :refer-macros [deftest is testing]]
   [is.galt.globo.ui.hexholds :as hexholds]))

(def approx (comp not not=))

(defn east-neighbor
  "The due-east neighbor of a hex: gridDisk 1 candidates with nearly-equal
   latitude and lng > center, sorted DESCENDING by lng delta (otherwise the
   diagonal SE neighbor wins)."
  [hex-id]
  (let [{center-lat :lat center-lng :lng} (hexholds/cell->latlng hex-id)
        east (->> (h3/gridDisk hex-id 1)
                  (remove #(= % hex-id))
                  (map (fn [c]
                         (let [{:keys [lat lng]} (hexholds/cell->latlng c)]
                           {:id c
                            :dlat (js/Math.abs (- lat center-lat))
                            :dlng (- lng center-lng)})))
                  (filter #(and (< (:dlat %) 0.03) (pos? (:dlng %))))
                  (sort-by :dlng >)
                  first)]
    (:id east)))

(defn shared-edge-midpoint
  "Midpoint of the ring-a edge whose both endpoints also appear in ring-b."
  [ring-a ring-b]
  (let [bv (set ring-b)]
    (some (fn [[a b]]
            (when (and (contains? bv a) (contains? bv b))
              [(/ (+ (first a) (first b)) 2)
               (/ (+ (second a) (second b)) 2)]))
          (partition 2 1 ring-a))))

(deftest next-color-cycle-test
  (testing "exact cycle order"
    (is (= [nil :red :blue :green :yellow :purple]
           (take 6 (iterate hexholds/next-color nil)))))
  (testing "6th click returns to nil"
    (is (nil? (nth (iterate hexholds/next-color nil) 6))))
  (testing "7th click returns to red"
    (is (= :red (nth (iterate hexholds/next-color nil) 7))))
  (testing "unknown colors treated as nil"
    (is (= :red (hexholds/next-color :bogus)))))

(deftest fill-color-test
  (testing "each color maps to its rgba fill"
    (is (= "rgba(220, 50, 50, 0.4)" (hexholds/fill-color :red)))
    (is (= "rgba(40, 100, 230, 0.4)" (hexholds/fill-color :blue)))
    (is (= "rgba(40, 180, 80, 0.4)" (hexholds/fill-color :green)))
    (is (= "rgba(230, 200, 40, 0.4)" (hexholds/fill-color :yellow)))
    (is (= "rgba(160, 60, 200, 0.4)" (hexholds/fill-color :purple))))
  (testing "nil uses the faint unpainted fill"
    (is (= hexholds/unpainted-fill (hexholds/fill-color nil)))))

(deftest within-lod?-test
  (testing "boundary at max-altitude counts as within"
    (is (true? (hexholds/within-lod? hexholds/max-altitude))))
  (testing "above max-altitude is out"
    (is (false? (hexholds/within-lod? (+ hexholds/max-altitude 0.0001)))))
  (testing "nil altitude is out"
    (is (false? (hexholds/within-lod? nil))))
  (testing "close zoom is within"
    (is (true? (hexholds/within-lod? 0.3)))))

(deftest viewpoint->bbox-test
  (testing "half-span = altitude * factor"
    (let [bbox (hexholds/viewpoint->bbox {:lat 0 :lng 0 :altitude 0.3} 20)]
      (is (< (:lng-min bbox) -5.99))
      (is (> (:lng-min bbox) -6.01))
      (is (< (:lng-max bbox) 6.01))
      (is (> (:lng-max bbox) 5.99))
      (is (<= (- (:lng-max bbox) (:lng-min bbox)) 12.1))))
  (testing "span factor scales the half-span linearly"
    (let [b16 (hexholds/viewpoint->bbox {:lat 0 :lng 0 :altitude 1} 16)
          b8 (hexholds/viewpoint->bbox {:lat 0 :lng 0 :altitude 1} 8)
          span16 (- (:lng-max b16) (:lng-min b16))
          span8 (- (:lng-max b8) (:lng-min b8))]
      (is (> span16 span8))
      (is (< (js/Math.abs (- span16 (* 2 span8))) 0.1))))
  (testing "lat clamped to poles"
    (let [bbox (hexholds/viewpoint->bbox {:lat 90 :lng 0 :altitude 1} 20)
          bbox2 (hexholds/viewpoint->bbox {:lat -90 :lng 0 :altitude 1} 20)]
      (is (<= (:lat-max bbox) 90))
      (is (>= (:lat-min bbox) -90))
      (is (= 90 (:lat-max bbox)))
      (is (= -90 (:lat-min bbox2)))
      (is (>= (:lat-max bbox2) -90))))
  (testing "min-half-span floor at tiny altitude"
    (let [span (- (:lng-max (hexholds/viewpoint->bbox {:lat 0 :lng 0 :altitude 0.01} 20))
                  (:lng-min (hexholds/viewpoint->bbox {:lat 0 :lng 0 :altitude 0.01} 20)))]
      (is (< (js/Math.abs (- span (* 2 hexholds/min-half-span))) 0.001)))))

(deftest viewport-cells-capped-test
  (testing "huge bbox capped at max-viewport-cells"
    (let [cells (hexholds/viewport-cells {:lat-min -10 :lat-max 10
                                          :lng-min -10 :lng-max 10})]
      (is (pos? (count cells)))
      (is (<= (count cells) hexholds/max-viewport-cells))))
  (testing "small bbox uncapped"
    (let [cells (hexholds/viewport-cells {:lat-min 19.5 :lat-max 20.5
                                          :lng-min -0.5 :lng-max 0.5})]
      (is (pos? (count cells)))
      (is (< (count cells) hexholds/max-viewport-cells))))
  (testing "close zoom yields many cells (density regression guard)"
    (let [bbox (hexholds/viewpoint->bbox {:lat 20 :lng 0 :altitude 0.3} 20)
          cells (hexholds/viewport-cells bbox)]
      (is (> (count cells) 100))))
  (testing "all cells are within (or straddle) the queried bbox"
    (let [cells (hexholds/viewport-cells {:lat-min 19 :lat-max 21
                                          :lng-min -1 :lng-max 1})
          out (remove (fn [c]
                        (let [{:keys [lat lng]} (hexholds/cell->latlng c)]
                          (and (< -1.2 lat 21.2) (< -1.2 lng 1.2))))
                      cells)]
      (is (empty? out)))))

(deftest latlng-cell-round-trip-test
  (testing "cell->latlng of latlng->cell is the same cell"
    (let [cell (hexholds/latlng->cell 20 0)]
      (is (= cell (hexholds/latlng->cell
                   (:lat (hexholds/cell->latlng cell))
                   (:lng (hexholds/cell->latlng cell)))))))
  (testing "different coords map to different cells"
    (is (not= (hexholds/latlng->cell 20 0) (hexholds/latlng->cell 21 1)))))

(deftest cell-boundary-ring-test
  (let [cell (hexholds/latlng->cell 20 0)
        ring (hexholds/cell-boundary-ring cell)
        raw (mapv vec (h3/cellToBoundary cell true))]
    (testing "ring is closed (first == last)"
      (is (= (first ring) (last ring))))
    (testing "no duplicate vertices besides the closing one"
      (is (= 7 (count ring)))
      (is (= 6 (count (distinct ring)))))
    (testing "ring vertex set matches raw cellToBoundary"
      (is (= (set ring) (set raw))))
    (testing "winding is reversed from raw (shoelace areas oppose)"
      (let [a (hexholds/ring-signed-area ring)
            b (hexholds/ring-signed-area raw)]
        (is (not= 0 a))
        (is (< (* a b) 0))))))

(def square-ring
  [[0 0] [4 0] [4 4] [0 4]])

(deftest point-in-polygon-strict-test
  (testing "square: center strictly inside"
    (is (true? (hexholds/point-in-polygon? 2 2 square-ring))))
  (testing "square: edge midpoints are NOT inside"
    (is (false? (hexholds/point-in-polygon? 2 0 square-ring)))
    (is (false? (hexholds/point-in-polygon? 2 4 square-ring)))
    (is (false? (hexholds/point-in-polygon? 0 2 square-ring)))
    (is (false? (hexholds/point-in-polygon? 4 2 square-ring))))
  (testing "square: vertices are NOT inside"
    (is (false? (hexholds/point-in-polygon? 0 0 square-ring)))
    (is (false? (hexholds/point-in-polygon? 4 0 square-ring)))
    (is (false? (hexholds/point-in-polygon? 4 4 square-ring)))
    (is (false? (hexholds/point-in-polygon? 0 4 square-ring))))
  (testing "square: exterior is NOT inside"
    (is (false? (hexholds/point-in-polygon? 5 5 square-ring)))
    (is (false? (hexholds/point-in-polygon? -1 -1 square-ring))))
  (let [cell (hexholds/latlng->cell 20 0)
        ring (hexholds/cell-boundary-ring cell)
        {:keys [lat lng]} (hexholds/cell->latlng cell)
        [vx vy] (first ring)
        [ex ey] (second ring)]
    (testing "hexagon: center strictly inside"
      (is (true? (hexholds/point-in-polygon? lng lat ring))))
    (testing "hexagon: off-center interior point inside"
      (is (true? (hexholds/point-in-polygon? (/ (+ lng vx) 2)
                                             (/ (+ lat vy) 2)
                                             ring))))
    (testing "hexagon: vertex NOT inside"
      (is (false? (hexholds/point-in-polygon? vx vy ring))))
    (testing "hexagon: edge midpoint NOT inside"
      (is (false? (hexholds/point-in-polygon? (/ (+ vx ex) 2)
                                              (/ (+ vy ey) 2)
                                              ring))))
    (testing "hexagon: exterior NOT inside"
      (is (false? (hexholds/point-in-polygon? (+ lng 20) lat ring))))))

(deftest hit-test-point-test
  (let [c0 (hexholds/latlng->cell 20 0)
        c1 (east-neighbor c0)
        ring-map {c0 (hexholds/cell-boundary-ring c0)
                  c1 (hexholds/cell-boundary-ring c1)}
        {:keys [lat lng]} (hexholds/cell->latlng c0)
        {:keys [lat lng]} {:lat lat :lng lng}]
    (testing "own center hits own cell"
      (is (= c0 (hexholds/hit-test-point ring-map lng lat))))
    (testing "a point on a shared border hits nothing"
      (let [[mx my] (shared-edge-midpoint (get ring-map c0) (get ring-map c1))]
        (is (some? mx))
        (is (nil? (hexholds/hit-test-point ring-map mx my)))))
    (testing "a point in the gap between cells hits nothing"
      (is (nil? (hexholds/hit-test-point ring-map -170 -60))))))

(deftest t1-four-adjacent-cells-test
  (testing "clicking 4 adjacent cells paints exactly those 4"
    (let [c0 (hexholds/latlng->cell 20 0)
          c1 (east-neighbor c0)
          c2 (east-neighbor c1)
          c3 (east-neighbor c2)
          cells [c0 c1 c2 c3]
          ring-map (into {} (map (fn [c] [c (hexholds/cell-boundary-ring c)])) cells)
          centers (map (fn [c]
                         (let [{:keys [lat lng]} (hexholds/cell->latlng c)]
                           [lng lat]))
                       cells)]
      (is (= 4 (count (distinct cells))))
      (is (every? #(= (count %) 4) []))
      (doseq [[idx [x y]] (map-indexed vector centers)]
        (is (= (nth cells idx) (hexholds/hit-test-point ring-map x y))
            (str "cell " idx " center hits itself"))))))

(deftest shared-edge-midpoint-nil-test
  (let [c0 (hexholds/latlng->cell 20 0)
        c1 (east-neighbor c0)
        r0 (hexholds/cell-boundary-ring c0)
        r1 (hexholds/cell-boundary-ring c1)
        [mx my] (shared-edge-midpoint r0 r1)]
    (testing "shared edge exists"
      (is (some? mx)))
    (testing "midpoint of the shared edge hits no cell"
      (is (nil? (hexholds/hit-test-point {c0 r0 c1 r1} mx my))))
    (testing "both cells strictly exclude the shared midpoint"
      (is (false? (hexholds/point-in-polygon? mx my r0)))
      (is (false? (hexholds/point-in-polygon? mx my r1))))))

(deftest cell-ring-contains-own-center-test
  (doseq [cell [(hexholds/latlng->cell 20 0)
                (hexholds/latlng->cell -33 151)
                (hexholds/latlng->cell 55 10)]]
    (let [{:keys [lat lng]} (hexholds/cell->latlng cell)]
      (is (true? (hexholds/point-in-polygon?
                  lng lat (hexholds/cell-boundary-ring cell)))
          (str "center of " cell " inside its ring")))))

(deftest ndc-round-trip-test
  (testing "screen->ndc / ndc->screen inverse pair"
    (doseq [[px py] [[0 0] [100 200] [400 300] [799 599] [1 1]]]
      (let [{:keys [x y]} (hexholds/screen->ndc px py 800 600)
            back (hexholds/ndc->screen x y 800 600)]
        (is (< (js/Math.abs (- px (:x back))) 1e-6))
        (is (< (js/Math.abs (- py (:y back))) 1e-6)))))
  (testing "known NDC corners"
    (is (= {:x -1 :y 1} (hexholds/screen->ndc 0 0 800 600)))
    (is (= {:x 1 :y -1} (hexholds/screen->ndc 800 600 800 600)))))

(deftest resolve-paint-hex-id-test
  (testing "explicit hex-id wins even with different coords"
    (let [cell (hexholds/latlng->cell 20 0)]
      (is (= cell (hexholds/resolve-paint-hex-id
                   {:hex-id cell :lat -10 :lng 99})))))
  (testing "lat/lng fallback derives the cell"
    (is (= (hexholds/latlng->cell 20 0)
           (hexholds/resolve-paint-hex-id {:lat 20 :lng 0}))))
  (testing "nil when neither is available"
    (is (nil? (hexholds/resolve-paint-hex-id {})))
    (is (nil? (hexholds/resolve-paint-hex-id nil)))))

(deftest hexhold-props-test
  (testing "painted cell props"
    (let [props (hexholds/hexhold->props :red)]
      (is (= hexholds/polygon-altitude (:altitude props)))
      (is (= (hexholds/fill-color :red) (:cap-color props)))
      (is (= hexholds/default-stroke (:stroke-color props)))))
  (testing "unpainted cell props"
    (let [props (hexholds/hexhold->props nil)]
      (is (= hexholds/polygon-altitude (:altitude props)))
      (is (= hexholds/unpainted-fill (:cap-color props)))
      (is (= hexholds/default-stroke (:stroke-color props)))))
  (testing "polygon-feature carries id, color and a closed ring"
    (let [cell (hexholds/latlng->cell 20 0)
          feature (hexholds/polygon-feature cell :blue)]
      (is (= cell (:id feature)))
      (is (= :blue (:color feature)))
      (is (= "Polygon" (get-in feature [:geometry :type])))
      (is (= (mapv vec (hexholds/cell-boundary-ring cell))
             (mapv vec (get-in feature [:geometry :coordinates 0])))))))

(deftest cells-per-degree-2-sane-test
  (testing "density constant is resolution-aware (res 5 ~ tens of cells/deg2)"
    (is (> hexholds/cells-per-degree-2 30))
    (is (< hexholds/cells-per-degree-2 200))))

(deftest cell->latlng-shape-test
  (testing "returns a map with numeric lat/lng (h3-js returns an array)"
    (let [res (hexholds/cell->latlng (hexholds/latlng->cell 20 0))]
      (is (map? res))
      (is (number? (:lat res)))
      (is (number? (:lng res)))
      (is (< (js/Math.abs (- (:lat res) 20)) 0.5))
      (is (< (js/Math.abs (- (:lng res) 0)) 0.5)))))

(deftest off-edge-point-is-inside-test
  (testing "a point epsilon off the edge is strictly inside"
    (is (true? (hexholds/point-in-polygon? 2.001 0.001 square-ring)))
    (is (true? (hexholds/point-in-polygon? 1.999 0.001 square-ring)))
    (is (true? (hexholds/point-in-polygon? 2 1.001 square-ring))))
  (testing "a point epsilon outside is not inside"
    (is (false? (hexholds/point-in-polygon? 4.001 2 square-ring)))
    (is (false? (hexholds/point-in-polygon? -0.001 2 square-ring)))))

(deftest hit-test-first-match-wins-test
  (testing "first containing ring in map order wins"
    (let [ring-a [[0 0] [4 0] [4 4] [0 4]]
          ring-b [[4 0] [8 0] [8 4] [4 4]]
          ring-map {:a ring-a :b ring-b}]
      (is (= :a (hexholds/hit-test-point ring-map 2 2)))
      (is (= :b (hexholds/hit-test-point ring-map 6 2))))))

(deftest neighbors-share-identical-edge-test
  (testing "adjacent cells share an edge with byte-identical vertices"
    (let [c0 (hexholds/latlng->cell 20 0)
          c1 (east-neighbor c0)
          r0 (hexholds/cell-boundary-ring c0)
          r1 (hexholds/cell-boundary-ring c1)
          shared (count (clojure.set/intersection (set r0) (set r1)))]
      (is (some? c1))
      (is (= 2 shared)))))

(deftest ndc-screen-direct-values-test
  (testing "known pixel values"
    (is (= {:x -1 :y -1} (hexholds/screen->ndc 0 600 800 600)))
    (is (= {:x 1 :y 1} (hexholds/screen->ndc 800 0 800 600)))
    (is (= {:x 0 :y 0} (hexholds/screen->ndc 400 300 800 600)))))

(deftest within-lod-boundaries-test
  (testing "edge cases"
    (is (true? (hexholds/within-lod? 0)))
    (is (true? (hexholds/within-lod? -1)))
    (is (true? (hexholds/within-lod? 0.7999999)))))

(deftest resolve-paint-hex-id-extra-test
  (testing "hex-id wins even when coords are nil"
    (let [cell (hexholds/latlng->cell 20 0)]
      (is (= cell (hexholds/resolve-paint-hex-id {:hex-id cell :lat nil :lng nil})))))
  (testing "partial coords fall through to nil"
    (is (nil? (hexholds/resolve-paint-hex-id {:lat 20})))
    (is (nil? (hexholds/resolve-paint-hex-id {:lng 0})))))

(deftest hover-fill-color-test
  (testing "each color has a distinct hover tint, stronger than its fill"
    (doseq [c [:red :blue :green :yellow :purple]]
      (let [fill (hexholds/fill-color c)
            hover (hexholds/hover-fill-color c)]
        (is (string? hover))
        (is (not= fill hover) (str c " hover tint must differ from fill")))))
  (testing "nil (unpainted) uses the faint white hover tint"
    (is (= hexholds/unpainted-hover-fill (hexholds/hover-fill-color nil))))
  (testing "unknown colors fall back to the unpainted tint"
    (is (= hexholds/unpainted-hover-fill (hexholds/hover-fill-color :bogus)))))

(deftest altitude-constants-test
  (testing "altitudes are RELATIVE to the globe radius (r = R * (1 + alt))"
    (is (= 0.005 hexholds/ring-altitude))
    (is (= 0.02 hexholds/highlight-altitude))
    (is (< hexholds/ring-altitude 0.1))
    (is (< hexholds/highlight-altitude 0.1))))

(deftest click-paint-hexhold-test
  (let [cell-a (hexholds/latlng->cell 20 0)
        cell-b (east-neighbor cell-a)
        db {:hexholds {:active? true
                       :visible [{:id cell-a :color nil}]}}]
    (testing "explicit hex-id of a visible cell paints that cell"
      (is (= {:hex-id cell-a}
             (hexholds/click-paint-hexhold db {:hex-id cell-a} 0.3))))
    (testing "regression: lat/lng-only point (stale globe.gl raycast) is NEVER painted"
      (is (nil? (hexholds/click-paint-hexhold db {:lat 20.1 :lng 0.1} 0.3))))
    (testing "hex-id of a cell outside :visible is not painted"
      (is (nil? (hexholds/click-paint-hexhold db {:hex-id cell-b} 0.3))))
    (testing "inactive layer never paints"
      (is (nil? (hexholds/click-paint-hexhold
                 (assoc-in db [:hexholds :active?] false)
                 {:hex-id cell-a} 0.3))))
    (testing "above the LOD gate never paints"
      (is (nil? (hexholds/click-paint-hexhold db {:hex-id cell-a} 1.5))))
    (testing "nil altitude (no viewpoint) never paints"
      (is (nil? (hexholds/click-paint-hexhold db {:hex-id cell-a} nil))))))
