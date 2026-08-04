(ns is.galt.globo.ui.message-arcs-test
  "Tests for message arc computation helpers."
  (:require
   [cljs.test :refer-macros [deftest is testing]]
   [is.galt.globo.ui.message-arcs :as message-arcs]))

(def half (constantly 0.5))

(def alice {:id "u1" :name "Alice" :location {:lat 10 :lng 20}})
(def bob {:id "u2" :name "bob" :location {:lat 30 :lng 40}})
(def carol {:id "u3" :name "Carol"})
(def users {"u1" alice "u2" bob "u3" carol})

(deftest max-arcs-test
  (testing "world messages cap at 5 arcs"
    (is (= 5 message-arcs/max-arcs))))

(deftest random-globe-spot-test
  (testing "deterministic with a fixed rand-fn"
    (is (= {:lat 0 :lng 0} (message-arcs/random-globe-spot half))))
  (testing "lat bounded to [-80, 80)"
    (is (<= -80 (:lat (message-arcs/random-globe-spot (constantly 0)))))
    (is (< (:lat (message-arcs/random-globe-spot (constantly 0.999))) 80)))
  (testing "lng bounded to [-180, 180)"
    (is (<= -180 (:lng (message-arcs/random-globe-spot (constantly 0)))))
    (is (< (:lng (message-arcs/random-globe-spot (constantly 0.999))) 180))))

(deftest location-test
  (testing "returns {:lat :lng} when both set"
    (is (= {:lat 10 :lng 20} (message-arcs/location alice))))
  (testing "nil when location missing or incomplete"
    (is (nil? (message-arcs/location carol)))
    (is (nil? (message-arcs/location {:location {:lat 1}})))
    (is (nil? (message-arcs/location nil)))))

(deftest origin-location-test
  (testing "user location preferred over viewpoint"
    (is (= {:lat 10 :lng 20}
           (message-arcs/origin-location alice {:lat 1 :lng 2}))))
  (testing "viewpoint fallback when user has no location"
    (is (= {:lat 1 :lng 2}
           (message-arcs/origin-location carol {:lat 1 :lng 2}))))
  (testing "nil when neither available"
    (is (nil? (message-arcs/origin-location carol nil)))
    (is (nil? (message-arcs/origin-location nil nil)))))

(deftest direct-target-test
  (testing "case-insensitive @name match"
    (is (= bob (message-arcs/direct-target users "@BOB hey")))
    (is (= alice (message-arcs/direct-target users "@alice hi"))))
  (testing "unknown username -> nil"
    (is (nil? (message-arcs/direct-target users "@nobody hi"))))
  (testing "no @ prefix -> nil"
    (is (nil? (message-arcs/direct-target users "hello @alice")))
    (is (nil? (message-arcs/direct-target users "hello")))))

(deftest direct-endpoint-test
  (testing "target location when set"
    (is (= {:lat 30 :lng 40} (message-arcs/direct-endpoint bob))))
  (testing "random spot when target has no location"
    (is (= {:lat 0 :lng 0} (message-arcs/direct-endpoint carol half)))))

(deftest select-world-endpoints-test
  (testing "excludes self and users without locations, fills to max"
    (let [endpoints (message-arcs/select-world-endpoints users "u1" 5 half)]
      (is (= 5 (count endpoints)))
      (is (every? #(and (contains? % :lat) (contains? % :lng)) endpoints))
      (is (= #{{:lat 30 :lng 40} {:lat 0 :lng 0}} (set endpoints)))))
  (testing "all random when no online users have locations"
    (let [endpoints (message-arcs/select-world-endpoints {"u1" alice} "u1" 5 half)]
      (is (= 5 (count endpoints)))
      (is (every? #(= {:lat 0 :lng 0} %) endpoints))))
  (testing "caps at max when more located users than max"
    (let [users3 (assoc users "u3" (assoc carol :location {:lat 50 :lng 60}))
          endpoints (message-arcs/select-world-endpoints users3 "u1" 2 half)]
      (is (= 2 (count endpoints)))
      (is (= #{{:lat 30 :lng 40} {:lat 50 :lng 60}} (set endpoints))))))

(deftest endpoints-for-send-test
  (testing "direct message -> single endpoint at target location"
    (is (= [{:lat 30 :lng 40}]
           (message-arcs/endpoints-for-send "@bob hi" "u1" users {"u2" bob}))))
  (testing "direct message with no target location -> random endpoint"
    (is (= [{:lat 0 :lng 0}]
           (message-arcs/endpoints-for-send "@Carol hi" "u1" users {"u3" carol} half))))
  (testing "message to self -> no arcs"
    (is (= [] (message-arcs/endpoints-for-send "@alice hi" "u1" users {"u1" alice} half))))
  (testing "world message -> up to 5 endpoints with random fill"
    (let [endpoints (message-arcs/endpoints-for-send
                     "hello world" "u1" users {"u1" alice "u2" bob} half)]
      (is (= 5 (count endpoints)))
      (is (contains? (set endpoints) {:lat 30 :lng 40})))))

(deftest arc-data-test
  (testing "builds start/end lat-lng keys"
    (is (= {:startLat 10 :startLng 20 :endLat 30 :endLng 40}
           (message-arcs/arc-data {:lat 10 :lng 20} {:lat 30 :lng 40})))))
