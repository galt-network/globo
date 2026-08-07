(ns is.galt.globo.server.handlers
  "Ring handlers for the Globo SSE connection and message endpoints."
  (:require
   [cheshire.core :as json]
   [is.galt.globo.protocols :as protocols]
   [is.galt.globo.server.messages :as messages]
   [is.galt.globo.server.middleware :as middleware]
   [is.galt.globo.server.sse :as sse]
   [is.galt.globo.server.validation :as validation]
   [org.httpkit.server :as hk-server]
   [ring.util.mime-type :as mime]
   [ring.util.response :as response]))

(defn users-online
  "Users that currently have at least one open connection."
  [globo]
  (let [storage (:storage globo)]
    (->> (protocols/users-map storage)
         (keep (fn [[user-id _]]
                 (when (seq (protocols/connection-ids-for-user storage user-id))
                   (protocols/get-user storage user-id)))))))

(defn register-user!
  "Ensure user-id exists in storage, seeding defaults on first sight."
  [globo user-id]
  (protocols/update-user! (:storage globo) user-id
                          (fn [u]
                            (-> (or u {})
                                (assoc :id user-id
                                       :last-seen-at (java.time.Instant/now)
                                       :favorites (or (:favorites u) messages/default-favorites))))))

(defn initial-burst-events
  "Initial state events sent to a freshly connected client."
  [globo connection-id user-id]
  (let [storage (:storage globo)]
    [{:type :connected :content {:connection-id connection-id :user-id user-id}}
     {:type :map-objects :content {:objects (protocols/get-map-objects storage)}}
     {:type :users-online :content {:users (vec (users-online globo))}}
     {:type :messages :content {:messages (protocols/latest-messages storage 20)}}
     {:type :placeable-map-objects :content {:objects (protocols/placeable-objects (:placeables globo) user-id)}}]))

(defn safe-sse-event
  "Validates an outbound event; invalid events are replaced with a
  :system-notification (the offending event is logged by publish!)."
  [globo event]
  (if (validation/outbound-errors event)
    (validation/system-notification :error event)
    event))

(defn initial-burst-body
  "SSE body string for the initial burst, each event validated."
  [globo connection-id user-id]
  (->> (initial-burst-events globo connection-id user-id)
       (map #(sse/sse-event (safe-sse-event globo %)))
       (apply str)))

(defn new-connection-handler
  "SSE connection endpoint: registers the connection and user, then sends
  the initial state burst as a single response body."
  [globo req]
  (let [user-id (:user-id req)]
    (middleware/mark-sse-response
     (hk-server/as-channel
      req
      {:init (fn [ch]
               (let [connection-id (str (java.util.UUID/randomUUID))
                     process-message (fn [m] (messages/process {:globo globo :user-id user-id} m))
                     connection-closed-message {:type :user-offline
                                                :connection-id connection-id
                                                :content {:id user-id}}]
                 (register-user! globo user-id)
                 (hk-server/on-close ch (fn [status]
                                          (protocols/remove-connection! (:connections globo) connection-id)
                                          (protocols/remove-user-connection! (:storage globo) user-id connection-id)
                                          (when (empty? (protocols/connection-ids-for-user (:storage globo) user-id))
                                            (process-message connection-closed-message))
                                          (println "[SSE] disconnected:" {:connection-id connection-id
                                                                          :user-id user-id}
                                                   "reason:" status
                                                   "remaining:" (count (protocols/registry (:connections globo))))))
                 (protocols/add-connection! (:connections globo) connection-id ch)
                 (protocols/add-user-connection! (:storage globo) user-id connection-id)
                 (println "[SSE] connected:" {:connection-id connection-id
                                              :user-id user-id}
                          "total:" (count (protocols/registry (:connections globo))))
                 (hk-server/send! ch
                                  {:status 200
                                   :headers {"Content-Type" "text/event-stream"
                                             "Cache-Control" "no-cache"
                                             "Connection" "keep-alive"
                                             "X-Accel-Buffering" "no"
                                             "Set-Cookie" (middleware/set-cookie-header-value user-id)}
                                   :body (initial-burst-body globo connection-id user-id)}
                                  false)
                 (process-message {:type :user-online :content (protocols/get-user (:storage globo) user-id)})))}))))

(defn json-response
  [status body]
  {:status status
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string body)})

(defn send-message-handler
  "POST endpoint: validates the inbound message, then dispatches it."
  [globo req]
  (try
    (let [message (update (json/parse-string (slurp (:body req)) true) :type keyword)
          client-id (:connection-id message)
          user-id (:user-id req)]
      (if-let [errors (validation/inbound-errors message)]
        (json-response 400 {:status "error"
                            :error (str "invalid message: " errors)
                            :client-id client-id})
        (if (messages/process {:globo globo :user-id user-id} message)
          (json-response 200 {:status "sent" :connection-id client-id})
          (json-response 404 {:status "error"
                              :error "client not found or send failed"
                              :client-id client-id}))))
    (catch Exception e
      (json-response 400 {:status "error" :error (.getMessage e)}))))

(defn assets-handler
  "Serves static assets from resources/public/ (e.g. compiled JS, 3D models)."
  [req]
  (let [[path] (:path-params req)
        file-path (or (not-empty path) "index.html")]
    (if-let [resp (response/resource-response (str "public/" file-path))]
      (response/content-type resp (or (mime/ext-mime-type file-path {"glb" "model/gltf-binary"})
                                      "application/octet-stream"))
      (-> (response/response "Not Found")
          (response/status 404)
          (response/content-type "text/plain")))))
