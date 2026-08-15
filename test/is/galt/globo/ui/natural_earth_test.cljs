(ns is.galt.globo.ui.natural-earth-test
  (:require
   [cljs.test :refer [deftest is testing]]
   [is.galt.globo.ui.natural-earth :as ne]))

(deftest scale-for-altitude-test
  (testing "high altitude uses 110m"
    (is (= :110m (ne/scale-for-altitude 1.0)))
    (is (= :110m (ne/scale-for-altitude 2.2))))
  (testing "closer camera uses 50m"
    (is (= :50m (ne/scale-for-altitude 0.99)))
    (is (= :50m (ne/scale-for-altitude 0.1)))))

(deftest layers-for-altitude-test
  (testing "zoomed out shows only ADM0"
    (is (= {:adm0-scale :110m
            :layers #{:adm0-borders :adm0-names}}
           (ne/layers-for-altitude 1.0)))
    (is (= :110m (:adm0-scale (ne/layers-for-altitude 2.2)))))
  (testing "mid altitude keeps ADM0 names at 50m"
    (is (= {:adm0-scale :50m
            :layers #{:adm0-borders :adm0-names}}
           (ne/layers-for-altitude 0.35)))
    (is (= {:adm0-scale :50m
            :layers #{:adm0-borders :adm0-names}}
           (ne/layers-for-altitude 0.99))))
  (testing "close zoom hides ADM0 names and adds ADM1 plus cities"
    (is (= {:adm0-scale :50m
            :layers #{:adm0-borders :adm1-borders :adm1-names :cities}}
           (ne/layers-for-altitude 0.349)))))

(def line-fc
  {:type "FeatureCollection"
   :features [{:properties {:id "1-0" :name "border"}
               :geometry {:type "LineString"
                          :coordinates [[20 10] [21 11]]}}]})

(deftest paths-from-geojson-test
  (testing "LineString lng/lat becomes [lat lng] path coords"
    (is (= [{:id "1-0" :coords [[10 20] [11 21]]}]
           (ne/paths-from-geojson line-fc)))))

(def label-fc
  {:type "FeatureCollection"
   :features [{:properties {:name "Argentina" :adm0_a3 "ARG"}
               :geometry {:type "Point"
                          :coordinates [-64.17 -33.50]}}]})

(deftest labels-from-geojson-test
  (testing "Point lng/lat becomes a globe.gl label map"
    (is (= [{:id "ARG" :text "Argentina" :lat -33.50 :lng -64.17}]
           (ne/labels-from-geojson label-fc)))))

(def sample-ne
  {:adm0-borders? true
   :adm0-names? true
   :adm1-borders? true
   :adm1-names? true
   :cities? true
   :altitude 2.2
   :layers {:adm0 {:110m {:paths [{:id "p110"}]
                         :labels [{:id "l110"}]}
                  :50m {:paths [{:id "p50"}]
                        :labels [{:id "l50"}]}}}
   :close {:paths [{:id "adm1p"}]
           :labels [{:id "adm1l" :class "ne-label-adm1"}
                    {:id "city" :class "ne-label-city"}]}})

(deftest visible-paths-test
  (testing "zoomed out uses 110m ADM0"
    (is (= [{:id "p110" :kind :adm0-borders}] (ne/visible-paths sample-ne))))
  (testing "mid altitude uses 50m ADM0"
    (is (= [{:id "p50" :kind :adm0-borders}] (ne/visible-paths (assoc sample-ne :altitude 0.5)))))
  (testing "close zoom stacks ADM0 and ADM1 borders"
    (is (= [{:id "p50" :kind :adm0-borders} {:id "adm1p"}]
           (ne/visible-paths (assoc sample-ne :altitude 0.2)))))
  (testing "ADM0 borders toggle"
    (is (= [] (ne/visible-paths (assoc sample-ne :adm0-borders? false))))
    (is (= [{:id "p50" :kind :adm0-borders}]
           (ne/visible-paths (assoc sample-ne :altitude 0.2 :adm1-borders? false))))))

(deftest visible-labels-test
  (testing "zoomed out uses 110m ADM0 names"
    (is (= [{:id "l110" :class "ne-label-adm0"}] (ne/visible-labels sample-ne))))
  (testing "close zoom hides ADM0 names and shows ADM1 plus cities"
    (is (= [{:id "adm1l" :class "ne-label-adm1"} {:id "city" :class "ne-label-city"}]
           (ne/visible-labels (assoc sample-ne :altitude 0.2)))))
  (testing "toggles gate labels even when in band"
    (is (= [] (ne/visible-labels (assoc sample-ne :adm0-names? false))))
    (is (= [{:id "adm1l" :class "ne-label-adm1"}]
           (ne/visible-labels (assoc sample-ne :altitude 0.2 :cities? false))))))

(deftest texture-urls-test
  (testing "color texture is the 8k hypso+relief WebP"
    (is (= "/map/assets/natural-earth/ne-hyp-sr-ob-dr-8k.webp"
           (ne/globe-image-url "/map/assets")))))

(deftest overlay-view-test
  (testing "composes textures and visible overlays from db"
    (is (= {:globe-image-url "/map/assets/natural-earth/ne-hyp-sr-ob-dr-8k.webp"
            :paths [{:id "p110" :kind :adm0-borders}]
            :labels [{:id "l110" :class "ne-label-adm0"}]}
           (ne/overlay-view {:natural-earth sample-ne} "/map/assets"))))
  (testing "close zoom stacks overlays"
    (is (= {:globe-image-url "/map/assets/natural-earth/ne-hyp-sr-ob-dr-8k.webp"
            :paths [{:id "p50" :kind :adm0-borders} {:id "adm1p"}]
            :labels [{:id "adm1l" :class "ne-label-adm1"}
                     {:id "city" :class "ne-label-city"}]}
           (ne/overlay-view {:natural-earth (assoc sample-ne :altitude 0.2)}
                            "/map/assets")))))
