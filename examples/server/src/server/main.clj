(ns server.main
  (:require
   [clj-simple-router.core :as router]
   [org.httpkit.server :as hk-server]
   [ring.logger :as logger]
   [ring.middleware.content-type :as content-type]
   [ring.middleware.cookies :as cookie]
   [ring.middleware.params]
   ; [is.galt.globo.server.handlers :as globo.handlers]
   [is.galt.globo.server :as globo.server]
   [server.middleware :as middleware]))

(def empty-storage
  {:users {}
   :map-objects #{}
   :user-connections {}
   :messages []})

(defonce storage
  (atom empty-storage))

; Map of client-id (UUID) => open channel of the SSE HTTP connection
; To get all user connections (in case they have multiple tabs open)
; use [:user-connections (:user-id req)] from @storage and then get the
; connections from @sse-clients
(def empty-sse-clients {})
(defonce sse-clients (atom empty-sse-clients))

(def routes
  {"* /map/**" (globo.server/create-handler {:mount-path "/map" :storage storage :sse-clients sse-clients})})

(defonce server-instance (atom nil))

(defn middleware-stack
  [{:keys [public-files-roots]} handler]
  (-> handler
      (middleware/wrap-public-files ,,, public-files-roots)
      (content-type/wrap-content-type)
      (logger/wrap-with-logger)
      (ring.middleware.params/wrap-params)
      middleware/wrap-error-response
      middleware/wrap-user-id
      (cookie/wrap-cookies)))

(def example-roots
  {:shadow-cljs "shadow-cljs/public"
   :static "static"
   :scittle "scittle/public"})

(defn start!
  {:org.babashka/cli {:coerce {:example :keyword :port :int}}}
  [& [{:keys [port example] :as opts :or {port 3000 example :static}}]]
  (let [handler (router/router routes)
        deps {:public-files-roots ["server/public" (get example-roots example)]}
        server-config {:port port :join false :legacy-return-value? false}]
    (println "Starting server" {:deps deps :opts opts})
    (reset! storage empty-storage)
    (reset! sse-clients empty-sse-clients)
    (reset! server-instance (hk-server/run-server (middleware-stack deps handler) server-config))))

(defn stop!
  ([]
   (stop! @server-instance))
  ([instance]
   (deref (hk-server/server-stop! instance))))

(defn before-ns-unload []
  (stop!))

(defn after-ns-reload []
  (start!))

(defn -main [& args]
  (start!)
  @(promise))
