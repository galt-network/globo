(ns is.galt.globo.ui.connection.subscriptions
  (:require
    [re-frame.core :as rf]))

(rf/reg-sub
  ::users-online
  (fn [db _]
    (vals (select-keys (:users db) (get-in db [:connection :users-online])))))

(rf/reg-sub
  ::status
  (fn [db _]
    (get-in db [:connection :status])))
