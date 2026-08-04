(ns is.galt.globo.server
  "Public server API for the globo library. Builds a clj-simple-router
  route table (routes) or a full Ring handler (create-handler) that the
  host application mounts under a configurable :mount-path."
  (:require
   [clj-simple-router.core :as router]
   [is.galt.globo.server.handlers :as handlers]))

(defn routes
  "Returns a clj-simple-router route table for the globo endpoints under
  :mount-path:

    GET  <mount>/connection    SSE endpoint
    POST <mount>/send-message  incoming client messages
    GET  <mount>/assets/**     static assets from resources/public"
  [{:keys [mount-path] :as deps}]
  (let [endpoint (fn [method root path] (str method " " root path))]
    {(endpoint "GET" mount-path "/connection") (partial handlers/new-connection-handler deps)
     (endpoint "POST" mount-path "/send-message") (partial handlers/send-message-handler deps)
     (endpoint "GET" mount-path "/assets/**") handlers/assets-handler}))

(defn create-handler
  "Wraps the globo routes with a 404 fallback for unmatched paths."
  [{:keys [storage sse-clients mount-path] :as deps}]
  (router/wrap-routes
   (fn [req] {:status 404 :body "Not Found"})
   (routes deps)))
