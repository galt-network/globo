(ns is.galt.globo.ui.map-objects-test
  "Tests for the 3D object model config data."
  (:require
   [cljs.test :refer-macros [deftest is testing]]
   [clojure.string :as str]
   [is.galt.globo.ui.map-objects :as map-objects]))

(deftest config-integrity-test
  (testing "every entry has the required string fields"
    (doseq [entry map-objects/config]
      (is (string? (:model-id entry)))
      (is (string? (:path entry)))
      (is (string? (:name entry)))
      (is (string? (:icon entry)))
      (is (number? (:scale entry))))))

(deftest config-ids-unique-test
  (testing "model-ids are unique"
    (let [ids (map :model-id map-objects/config)]
      (is (= (count ids) (count (set ids)))))))

(deftest config-paths-test
  (testing "every path points into the 3d assets directory"
    (doseq [{:keys [path]} map-objects/config]
      (is (str/starts-with? path "3d/")))))
