(ns is.galt.globo.server
  (:require
   [clj-simple-router.core :as router]
   [is.galt.globo.server.handlers :as handlers]))

(defn init
  [{:keys [storage]}]
  {:status :ok})

(defn routes
  [{:keys [mount-path] :as deps}]
  (let [endpoint (fn [method root path] (str method " " root path))]
    {(endpoint "GET" mount-path "/connection") (partial handlers/new-connection-handler deps)
     (endpoint "POST" mount-path "/send-message") (partial handlers/send-message-handler deps)
     (endpoint "GET" mount-path "/assets/**") (partial handlers/assets-handler deps)}))

(defn create-handler
  [{:keys [storage sse-clients mount-path] :as deps}]
  (router/wrap-routes
    (fn [req] (println ">>> NOT FOUND") {:status 404 :body "Not Found"})
    (routes deps)))
