(ns user
  (:require
    [clj-reload.core :as reload]
    [shadow.cljs.devtools.server]
    [shadow.cljs.devtools.api]
    [is.galt.globo.server]))

(reload/init
  {:no-reload '#{user}})

(defn go!
  "Reloads the code from all changed namespaces and restarts the HTTP server in development"
  []
  (reload/reload))

(defn watch-compile-ui
  []
  (shadow.cljs.devtools.server/start!)
  (shadow.cljs.devtools.api/watch :globo))

(comment
  (is.galt.globo.server/init {:storage nil})
  (watch-compile-ui)
  (go!))
