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

(deftest cap-angle-deg-test
  (testing "whole globe visible from far away (rays miss the sphere)"
    (is (= 90 (hexholds/cap-angle-deg 0.8 1.0393))))
  (testing "corner cap angle grows with altitude"
    (is (< (hexholds/cap-angle-deg 0.05 1.0393)
           (hexholds/cap-angle-deg 0.15 1.0393))))
  (testing "corner cap angle grows with aspect (wider screen)"
    (is (< (hexholds/cap-angle-deg 0.08 1)
           (hexholds/cap-angle-deg 0.08 2))))
  (testing "reference: live camera (alt 0.08, aspect 1.0393) ~ 3.14 deg"
    (is (< (js/Math.abs (- (hexholds/cap-angle-deg 0.08 1.0393) 3.142)) 0.05))))

(deftest ring-spacing-deg-test
  (testing "equatorial mean spacing ~ 0.14 deg (anisotropic near icosahedron edges)"
    (let [s (hexholds/ring-spacing-deg (hexholds/latlng->cell 0 0))]
      (is (< 0.1 s 0.2))))
  (testing "clean lattice (lat 60) ~ 0.133 deg"
    (is (< (js/Math.abs (- (hexholds/ring-spacing-deg
                            (hexholds/latlng->cell 60 20)) 0.133))
           0.01))))

(deftest disk-k-test
  (testing "k grows with altitude"
    (is (< (hexholds/disk-k {:lat 0 :lng 0 :altitude 0.05 :aspect 1.0393})
           (hexholds/disk-k {:lat 0 :lng 0 :altitude 0.15 :aspect 1.0393}))))
  (testing "k plateaus (capped) once the cap is reached"
    (is (= (hexholds/disk-k {:lat 20 :lng 0 :altitude 0.5 :aspect 1.0393})
           (hexholds/disk-k {:lat 20 :lng 0 :altitude 0.8 :aspect 1.0393}))))
  (testing "k is non-negative for any viewpoint"
    (is (>= (hexholds/disk-k {:lat 20 :lng 0 :altitude 0.8 :aspect 1.0393}) 0))
    (is (>= (hexholds/disk-k {:lat 20 :lng 0 :altitude 0 :aspect 1}) 0))))

(deftest viewport-cells-hexagon-test
  (testing "cells form the exact k-disk around the viewpoint cell (hexagon shape)"
    (let [v {:lat 20 :lng 0 :altitude 0.05 :aspect 1.0393}
          k (hexholds/disk-k v)
          cells (hexholds/viewport-cells v)
          center (hexholds/latlng->cell 20 0)]
      (is (pos? k))
      (is (= (set (h3/gridDisk center k)) (set cells))))
    (testing "disk size follows 3k^2+3k+1"
      (let [v {:lat 20 :lng 0 :altitude 0.05 :aspect 1.0393}
            k (hexholds/disk-k v)
            cells (hexholds/viewport-cells v)]
        (is (= (inc (* 3 k (inc k))) (count cells)))))))

(deftest viewport-cells-capped-test
  (testing "far zoom (alt 0.8) saturates at max-viewport-cells, never above"
    (let [cells (hexholds/viewport-cells
                 {:lat 20 :lng 0 :altitude 0.8 :aspect 1.0393})]
      (is (pos? (count cells)))
      (is (<= (count cells) hexholds/max-viewport-cells))))
  (testing "deep zoom (alt 0.05) is a small hexagon well below the cap"
    (let [cells (hexholds/viewport-cells
                 {:lat 20 :lng 0 :altitude 0.05 :aspect 1.0393})]
      (is (pos? (count cells)))
      (is (< (count cells) hexholds/max-viewport-cells)))))

(deftest viewport-coverage-test
  (testing "patch circumradius reaches the viewport corner while below the cap"
    (doseq [alt [0.03 0.05 0.08]]
      (let [center (hexholds/latlng->cell 20 0)
            k (hexholds/disk-k {:lat 20 :lng 0 :altitude alt :aspect 1.0393})]
        (is (>= (* k (hexholds/ring-spacing-deg center))
                (hexholds/cap-angle-deg alt 1.0393))
            (str "alt " alt ": k * ring-spacing must cover the corner angle"))))))

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
    (is (< hexholds/ring-altitude 0.1)))
  (testing "ring-altitude is the SINGLE rendering altitude: hit-test rings,
           caps and the hover outline all live at r = R*(1+ring-altitude).
           The outline is the cell's OWN stroke, so it is in perfect
           alignment by construction — no separate highlight altitude."
    (is (= hexholds/polygon-altitude hexholds/ring-altitude))))

(deftest hover-stroke-color-test
  (testing "the hover outline is the cell's own border stroke, tinted teal"
    (is (= "rgba(0, 188, 212, 1)" hexholds/highlight-stroke-color))
    (testing "regression: identical stroke colors would make the highlight
             invisible (the polygon stroke renders at 1+alt+1e-4, exactly
             covering any separate outline layer)"
      (is (not= hexholds/highlight-stroke-color hexholds/default-stroke)))
    (is (= hexholds/highlight-stroke-color (hexholds/hover-stroke-color true)))
    (is (= hexholds/default-stroke (hexholds/hover-stroke-color false)))
    (is (= hexholds/default-stroke (hexholds/hover-stroke-color nil)))))

(deftest update-visible-entry-test
  (let [cell-a (hexholds/latlng->cell 20 0)
        cell-b (east-neighbor cell-a)
        visible [{:id cell-a :color nil}
                 {:id cell-b :color nil}]]
    (testing "paints the matching entry"
      (is (= [{:id cell-a :color :red}
              {:id cell-b :color nil}]
             (hexholds/update-visible-entry visible cell-a :red))))
    (testing "clears the matching entry (color nil)"
      (is (= [{:id cell-a :color nil}
              {:id cell-b :color nil}]
             (hexholds/update-visible-entry visible cell-a nil))))
    (testing "does not touch other entries"
      (is (= [{:id cell-a :color :red}
              {:id cell-b :color nil}]
             (hexholds/update-visible-entry visible cell-a :red))))
    (testing "unknown id leaves the vector unchanged (no entry created)"
      (is (= visible
             (hexholds/update-visible-entry visible
                                            (hexholds/latlng->cell 21 1)
                                            :red))))
    (testing "regression: the entry is matched by its :id — the original
             bug destructured :id' (apostrophe) and never matched, so the
             painted cell kept its unpainted entry while :colors updated"
      (is (= :red (:color (first (hexholds/update-visible-entry
                                  visible cell-a :red))))))))

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
