(ns user
  (:require
    [clj-reload.core :as reload]
    [server.main]))

(alter-var-root #'*warn-on-reflection* (constantly true))

(reload/init
  {:no-reload '#{user}})

(defn go!
  "Reloads the code from all changed namespaces and restarts the HTTP server in development"
  []
  (reload/reload))

(defn start!
  "Starts the HTTP server"
  []
  (server.main/start! {:example :static :port 3000}))

(comment
  (start!)
  (go!))
