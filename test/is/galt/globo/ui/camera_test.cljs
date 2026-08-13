(ns is.galt.globo.ui.camera-test
  (:require
   [cljs.test :refer-macros [deftest is]]
   [is.galt.globo.ui.camera :as camera]))

(deftest hop-legs-high-start-is-nil
  (is (nil? (camera/hop-legs
             {:lat 0 :lng 0 :altitude 2.5}
             {:lat 40 :lng -74 :altitude 0.06}))))

(deftest hop-legs-low-near-has-three-legs
  (let [legs (camera/hop-legs
              {:lat 0 :lng 0 :altitude 0.06}
              {:lat 0 :lng 0.15 :altitude 0.06})
        [out move in] legs]
    (is (= 3 (count legs)))
    (is (= 0 (:lat out)))
    (is (= 0 (:lng out)))
    (is (< (Math/abs (- (:altitude out) 0.25)) 0.01))
    (is (= 0 (:lat move)))
    (is (= 0.15 (:lng move)))
    (is (= (:altitude out) (:altitude move)))
    (is (= {:lat 0 :lng 0.15 :altitude 0.06} (select-keys in [:lat :lng :altitude])))))

(deftest hop-legs-low-far-caps-cruise
  (let [legs (camera/hop-legs
              {:lat 0 :lng 0 :altitude 0.06}
              {:lat 0 :lng 180 :altitude 0.06})
        cruise (:altitude (first legs))]
    (is (= 3 (count legs)))
    (is (= 2.0 cruise))
    (is (= 2.0 (:altitude (second legs))))))

(deftest hop-legs-same-point-or-already-high-enough-is-nil
  (is (nil? (camera/hop-legs
             {:lat 10 :lng 20 :altitude 0.06}
             {:lat 10 :lng 20 :altitude 0.06})))
  (is (nil? (camera/hop-legs
             {:lat 0 :lng 0 :altitude 0.22}
             {:lat 0 :lng 0.15 :altitude 0.06}))))
