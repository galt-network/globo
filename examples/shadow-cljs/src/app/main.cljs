(ns app.main
  (:require
   [is.galt.globo.ui :as globo.ui]))

(goog-define GLOBO_API_BASE_URL "/map")

(defn init
  ([] (init {:globo-api-base-url GLOBO_API_BASE_URL}))
  ([config] (globo.ui/init (js->clj config :keywordize-keys true))))

(defn start!
  []
  (globo.ui/start!))

(defn stop!
  []
  (globo.ui/stop!))
