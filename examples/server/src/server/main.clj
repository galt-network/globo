(ns server.main
  (:require
   [clj-simple-router.core :as router]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [org.httpkit.server :as hk-server]
   [ring.logger :as logger]
   [ring.middleware.content-type :as content-type]
   [ring.middleware.cookies :as cookie]
   [ring.middleware.params]
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

(defonce server-instance (atom nil))
(defonce last-opts (atom {:example :static :port 3000 :mount-path "/map"}))

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

(defn- normalize-mount-path
  [mount-path]
  (let [p (or mount-path "/map")
        p (if (str/starts-with? p "/") p (str "/" p))]
    (if (= p "/") "" (str/replace p #"/+$" ""))))

(defn index-handler
  "Serves example index.html with {{mount-path}} and {{api-base-url}} filled in."
  [{:keys [mount-path port example-root]}]
  (fn [_req]
    (let [index-file (io/file example-root "index.html.template")]
      (if (.exists index-file)
        (let [api-base-url (str "http://localhost:" port mount-path)
              body (-> (slurp index-file)
                       (str/replace "{{mount-path}}" mount-path)
                       (str/replace "{{api-base-url}}" api-base-url))]
          {:status 200
           :headers {"Content-Type" "text/html; charset=utf-8"}
           :body body})
        {:status 404
         :headers {"Content-Type" "text/plain"}
         :body "index.html.template not found"}))))

(defn make-routes
  [{:keys [mount-path example-root port] :as deps}]
  (cond-> {(str "* " mount-path "/**")
           (globo.server/create-handler
            (assoc deps
                   :mount-path mount-path
                   :storage storage
                   :sse-clients sse-clients))}
    example-root (assoc "GET /" (index-handler deps))))

(defn start!
  {:org.babashka/cli {:coerce {:example :keyword :port :int}}}
  [& [{:keys [port example mount-path] :as opts
       :or {port 3000 example :static mount-path "/map"}}]]
  (let [mount-path (normalize-mount-path mount-path)
        example-root (get example-roots example)
        deps {:public-files-roots ["server/public" example-root]
              :mount-path mount-path
              :port port
              :example example
              :example-root example-root}
        handler (router/router (make-routes deps))
        server-config {:port port :join false :legacy-return-value? false}
        start-opts (assoc opts :port port :example example :mount-path mount-path)]
    (println "Starting server" {:deps (dissoc deps :storage :sse-clients) :opts start-opts})
    (reset! last-opts start-opts)
    (reset! storage empty-storage)
    (reset! sse-clients empty-sse-clients)
    (reset! server-instance
            (hk-server/run-server (middleware-stack deps handler) server-config))))

(defn stop!
  ([]
   (stop! @server-instance))
  ([instance]
   (when instance
     (deref (hk-server/server-stop! instance)))
   (reset! server-instance nil)))

(defn before-ns-unload []
  (stop!))

(defn after-ns-reload []
  (start! @last-opts))

(defn -main [& args]
  (start!)
  @(promise))
