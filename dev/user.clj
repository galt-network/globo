(ns user
  (:require
    [clj-reload.core :as reload]
    [is.galt.globo.server]))

(alter-var-root #'*warn-on-reflection* (constantly true))

(reload/init
  {:no-reload '#{user}})

(defn go!
  "Reloads the code from all changed namespaces and restarts the HTTP server in development"
  []
  (reload/reload))

(comment
  (is.galt.globo.server/init {:storage nil})
  (go!))
