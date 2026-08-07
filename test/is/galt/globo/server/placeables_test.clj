(ns is.galt.globo.server.placeables-test
  "Tests for the default placeable-objects provider and its static config."
  (:require
   [clojure.test :refer [deftest is testing]]
   [is.galt.globo.protocols :as protocols]
   [is.galt.globo.server.placeables :as placeables]))

(deftest default-config-integrity-test
  (testing "every entry has the expected field types"
    (doseq [entry placeables/default-config]
      (is (string? (:model-id entry)) "model-id is a string")
      (is (string? (:path entry)) "path is a string")
      (is (string? (:name entry)) "name is a string")
      (is (string? (:icon entry)) "icon is a string")
      (is (number? (:scale entry)) "scale is a number"))))

(deftest default-config-ids-unique-test
  (testing "model-ids are unique"
    (is (= (count placeables/default-config)
           (count (distinct (map :model-id placeables/default-config)))))))

(deftest default-config-paths-test
  (testing "paths are relative to the assets base url"
    (doseq [entry placeables/default-config]
      (is (clojure.string/starts-with? (:path entry) "3d/")))))

(deftest static-placeable-objects-test
  (testing "default arity serves the default config"
    (is (= placeables/default-config
           (protocols/placeable-objects (placeables/static-placeable-objects) "u1"))))

  (testing "custom config is served as-is"
    (let [config [{:model-id "custom" :path "3d/custom.glb" :scale 1}]
          provider (placeables/static-placeable-objects config)]
      (is (= config (protocols/placeable-objects provider "u1")))))

  (testing "provider ignores the user-id"
    (is (= placeables/default-config
           (protocols/placeable-objects (placeables/static-placeable-objects) "someone-else")))))
