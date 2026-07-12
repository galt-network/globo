(ns is.galt.globo.core)

(defonce instances {})

(defn create [globe]
  {:globe globe
   :answer 42})
