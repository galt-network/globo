(ns is.galt.globo.ui.subscriptions
  "Re-frame subscriptions for app UI state."
  (:require
   [re-frame.core :as rf]))

(rf/reg-sub
 ::hud-open?
 (fn [db _]
   (get-in db [:hud-open?])))

(rf/reg-sub
 ::mouse-action
 (fn [db _]
   (get-in db [:mouse-action])))

(rf/reg-sub
 ::favorites
 (fn [db _]
   (get-in db [:favorites])))

(rf/reg-sub
 ::max-favorite-places
 (fn [db _]
   (get-in db [:config :max-favorite-places])))

(rf/reg-sub
 ::map-classes
 :<- [::mouse-action]
 (fn [action _]
   (cond-> []
     (= :place-object (:type action))      (conj :place-object-in-progress)
     (= :pick-user-location (:type action)) (conj :picking-location)
     (= :set-favorite (:type action))       (conj :setting-favorite))))

(rf/reg-sub
 ::map-objects
 (fn [db _]
   (get-in db [:map-objects])))

(rf/reg-sub
 ::placeable-map-objects
 (fn [db _]
   (vals (get-in db [:placeable-map-objects]))))

(rf/reg-sub
 ::is-mobile?
 (fn [db _]
   (get-in db [:system-state :is-mobile?])))

(rf/reg-sub
 ::active-panel
 (fn [db _]
   (get-in db [:ui :active-panel] :users)))

(rf/reg-sub
 ::messages
 (fn [db]
   (get-in db [:messages])))

(rf/reg-sub
 ::settings-open?
 (fn [db _]
   (get-in db [:ui :settings-open?])))

(rf/reg-sub
 ::current-user
 (fn [db _]
   (let [user-id (get-in db [:connection :user-id])]
     (get-in db [:users user-id]))))

(rf/reg-sub
 ::rings
 (fn [db _]
   (vals (get-in db [:rings]))))
