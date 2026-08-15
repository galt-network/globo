(ns is.galt.globo.server.overlays-test
  (:require [clojure.test :refer [deftest is testing]]
            [is.galt.globo.protocols :as protocols]
            [is.galt.globo.server.overlays :as overlays]))

(def cordoba-bbox {:west -65 :south -33 :east -63 :north -31})
(def europe-bbox {:west 0 :south 40 :east 20 :north 55})

(deftest bbox-intersects?-test
  (testing "overlapping boxes intersect"
    (is (overlays/bbox-intersects? cordoba-bbox {:west -64.5 :south -32.5 :east -64 :north -32})))
  (testing "disjoint boxes do not"
    (is (not (overlays/bbox-intersects? cordoba-bbox europe-bbox)))))

(deftest query-overlays-filters-by-kind-and-bbox-test
  (let [p (overlays/static-overlay-provider
           {:paths [{:id "arg-1" :kind :adm1-borders :bbox {:west -65 :south -33 :east -63 :north -31}
                     :coords [[-32 -64] [-33 -64]]}
                    {:id "fra-1" :kind :adm1-borders :bbox {:west 1 :south 43 :east 5 :north 49}
                     :coords [[45 2] [46 3]]}]
            :labels [{:id "cordoba" :kind :adm1-names :bbox {:west -64.3 :south -31.5 :east -64.1 :north -31.3}
                      :text "Córdoba" :lat -31.4 :lng -64.2}
                     {:id "paris" :kind :adm1-names :bbox {:west 2.2 :south 48.8 :east 2.4 :north 49.0}
                      :text "Paris" :lat 48.9 :lng 2.3}]})]
    (is (= {:paths [{:id "arg-1" :coords [[-32 -64] [-33 -64]] :kind :adm1-borders}]
            :labels [{:id "cordoba" :text "Córdoba" :lat -31.4 :lng -64.2 :class "ne-label-adm1"}]}
           (protocols/query-overlays p {:kinds #{:adm1-borders :adm1-names}
                                        :bbox cordoba-bbox})))
    (is (= {:paths [] :labels []}
           (protocols/query-overlays p {:kinds #{:cities} :bbox cordoba-bbox})))))

(deftest select-labels-prefers-adm1-with-big-city-test
  (let [labels (into (mapv (fn [i]
                             {:id (str "m" i) :kind :adm1-names :text (str "Mun " i)
                              :lat 57 :lng (+ 24.2 i) :labelrank 8 :area-sqkm 0})
                           (range 20))
                     [{:id "riga" :kind :adm1-names :text "Riga" :lat 57 :lng 24
                       :labelrank 8 :area-sqkm 0}
                      {:id "c-riga" :kind :cities :text "Riga" :lat 56.95 :lng 24.1 :pop-max 742572}])]
    (is (= "Riga" (:text (first (overlays/select-labels labels 0.3)))))))

(deftest select-labels-thins-dense-adm1-test
  (let [adm1 (mapv (fn [i]
                     {:id (str "m" i) :kind :adm1-names :text (str "Mun " i)
                      :lat 57 :lng (+ 24 i) :labelrank 8 :area-sqkm (- 100 i)})
                   (range 20))
        picked (overlays/select-labels adm1 0.3)]
    (is (= 14 (count picked)))
    (is (= "Mun 0" (:text (first picked))))))

(deftest select-labels-fills-sparse-with-cities-test
  (let [labels [{:id "p1" :kind :adm1-names :text "Córdoba" :lat -31.4 :lng -64.2
                 :labelrank 3 :area-sqkm 165000}
                {:id "c1" :kind :cities :text "Córdoba" :lat -31.4 :lng -64.18 :pop-max 1452000}
                {:id "c2" :kind :cities :text "Rosario" :lat -32.9 :lng -60.7 :pop-max 1203000}
                {:id "c3" :kind :cities :text "Mendoza" :lat -32.9 :lng -68.8 :pop-max 893000}]]
    (let [picked (overlays/select-labels labels 0.3)]
      (is (= ["Córdoba" "Rosario" "Mendoza"] (mapv :text picked)))
      (is (= 1 (count (filter #(= :adm1-names (:kind %)) picked)))))))
