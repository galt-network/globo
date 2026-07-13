(ns is.galt.globo.server.handlers
  (:require
   [org.httpkit.server :as hk-server]
   [cheshire.core :as json]
   [is.galt.globo.server.sse :as sse]
   [is.galt.globo.server.messages :as server.messages]
   [is.galt.globo.server.middleware :as server.middleware]
   [ring.util.mime-type :as mime]
   [ring.util.response :as response]))

(defn users-online
  [storage]
  (keep (fn [user-id] (some-> storage (get-in [:users user-id]))) (keep (fn [[user-id connections]] (when (not (empty? connections)) user-id)) (:user-connections storage))))

(defn new-connection-handler
  "Ring handler for GET /map/connection. Opens an SSE connection,
    registers it in sse-clients, sends a welcome event, then streams
    periodic example events with JSON data."
  [{:keys [sse-clients storage]} req]
  (let [user-id (:user-id req)]
    (server.middleware/mark-sse-response
     (hk-server/as-channel
      req
      {:init
       (fn [ch]
         (let [connection-id (str (java.util.UUID/randomUUID))
               user-connections (into #{} (get-in @storage [:user-connections user-id] []))
               process-message (fn [message]
                                 (server.messages/process
                                  {:send! sse/send!
                                   :storage storage
                                   :sse-clients sse-clients
                                   :user-id user-id}
                                  message))
               connection-closed-message {:type :user-offline
                                          :connection-id connection-id
                                          :content {:id user-id}}]
           (swap! storage update-in [:users user-id] assoc :id user-id :last-seen-at (java.time.Instant/now))
           (hk-server/on-close ch
                               (fn [status]
                                 (swap! sse-clients dissoc connection-id)
                                 (swap! storage update-in [:user-connections user-id] disj connection-id)
                                 (when (empty? (get-in @storage [:user-connections user-id]))
                                   (process-message connection-closed-message))
                                 (println "[SSE] disconnected:" {:connection-id connection-id :user-id user-id}
                                          "reason:" status
                                          "remaining:" (count @sse-clients))))

           (swap! sse-clients assoc connection-id ch)
           (swap! storage assoc-in [:user-connections user-id] (conj user-connections connection-id))
           (println "[SSE] connected:" {:connection-id connection-id :user-id user-id}
                    "total:" (count @sse-clients))

           (hk-server/send! ch
                            {:status 200
                             :headers {"Content-Type" "text/event-stream"
                                       "Cache-Control" "no-cache"
                                       "Connection" "keep-alive"
                                       "X-Accel-Buffering" "no"
                                       "Set-Cookie" (server.middleware/set-cookie-header-value user-id)}
                             :body (str
                                    (sse/sse-event {:type :connected :content {:connection-id connection-id :user-id user-id}})
                                    (sse/sse-event {:type :map-objects :content {:objects (get @storage :map-objects)}})
                                    (sse/sse-event {:type :users-online :content {:users (users-online @storage)}})
                                    (sse/sse-event {:type :messages :content {:messages (server.messages/latest-messages @storage 20)}}))}
                            false)
           (process-message {:type :user-online :content (get-in @storage [:users user-id])})))}))))

(defn send-message-handler
  "POST /map/send-message handler. Reads a JSON body with a :connection-id
   key and sends a fixed place-object message to that SSE client via
   sse/send-to-client!."
  [{:keys [storage sse-clients]} req]
  (try
    (let [message (update (json/parse-string (slurp (:body req)) true) :type keyword)
          client-id (:connection-id message)
          user-id (:user-id req)]
      (println ">>> send-message-handler" {:client-id client-id :user-id user-id :message message})
      (if (server.messages/process {:send! sse/send!
                                    :storage storage
                                    :sse-clients sse-clients
                                    :user-id user-id}
                                   message)
        {:status 200
         :headers {"Content-Type" "application/json"}
         :body (json/generate-string {:status "sent" :connection-id client-id})}
        {:status 404
         :headers {"Content-Type" "application/json"}
         :body (json/generate-string
                {:status "error"
                 :error "client not found or send failed"
                 :client-id client-id})}))
    (catch Exception e
      {:status 400
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string {:status "error"
                                    :error (.getMessage e)})})))

(defn assets-handler
  [deps req]
  (let [[path] (:path-params req)
        file-path (or (not-empty path) "index.html")]
    (println ">>> assets-handler serving" file-path)
    (if-let [resp (response/resource-response (str "public/" file-path))]
      (response/content-type resp (or (mime/ext-mime-type file-path {"glb" "model/gltf-binary"})
                                      "application/octet-stream"))
      (-> (response/response "Not Found")
          (response/status 404)
          (response/content-type "text/plain")))))
