(ns is.galt.globo.ui.globe-gl-helpers
  (:require
    [camel-snake-kebab.core :as csk]))

(defn apply-config!
  "Apply a map of config to an existing Globe instance.
   Keys can be kebab-case or camelCase (converted automatically).

   When app-callback-config map is provided, then the function passed
   to the Globe.GL will be a composition of the function in the config
   and the one from app-callback-config

   Returns the globe for threading."
  [g config & [app-callback-config]]
  (doseq [[k v] config]
    (let [meth-name (name (csk/->camelCase k))
          f (aget g meth-name)
          app-callback (get app-callback-config k)
          accessor-fn? (fn? v)]
      (if f
        (if accessor-fn?
          (.call f g (if (fn? app-callback) (comp app-callback v) v))
          (.call f g v))
        (js/console.warn (str "Globe has no method: " meth-name)))))
  g)
