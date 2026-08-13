(ns is.galt.globo.server
  "Public server API. `create-globo` builds the Globo component; `routes`
  and `create-handler` expose it as a clj-simple-router route table or a
  Ring handler; `publish!` pushes validated SSE events; `send-message!` is
  the host-facing entry point into the message pipeline."
  (:require
   [clj-simple-router.core :as router]
   [is.galt.globo.protocols :as protocols]
   [is.galt.globo.server.connections :as connections]
   [is.galt.globo.server.handlers :as handlers]
   [is.galt.globo.server.hexholds :as hexholds]
   [is.galt.globo.server.messages :as messages]
   [is.galt.globo.server.placeables :as placeables]
   [is.galt.globo.server.publish :as publish]
   [is.galt.globo.server.storage :as storage]
   [is.galt.globo.server.validation :as validation]))

(defrecord Globo
  [mount-path storage connections placeables hexholds log-fn
   validate-user-update max-user-name-length])

(def default-max-user-name-length 42)

(defn- build-validate-user-update
  "Compose the host validation fn (if any) with the built-in max-length
  check. The returned fn only validates when :name is present in the
  patch and returns nil (accepted) or {:error string :details map}."
  [host-fn max-length]
  (fn [globo _user-id patch]
    (when (contains? patch :name)
      (or (when host-fn (host-fn globo _user-id patch))
          (when (and (some? (:name patch))
                     (> (count (:name patch)) max-length))
            {:error (str "Name must be at most " max-length " characters.")
             :details {:max max-length :actual (count (:name patch))}})))))

(defn create-globo
  "Build a Globo component.

  Options:
    :mount-path    - route prefix, default \"/map\"
    :storage       - GloboStorage implementation, default in-memory
    :connections   - ConnectionStore implementation, default in-memory
    :placeables    - placeable-objects config vector (data) or a
                     PlaceableObjectProvider implementation,
                     default the built-in static config
    :hexholds      - HexholdStore implementation, default in-memory with
                     an optional land index loaded from classpath
    :log-fn        - logging fn (args are printed), default println
    :max-user-name-length - server-enforced username length limit,
                     default 42; delivered to clients in the
                     :connected burst so the UI can enforce it
    :validate-user-update - host fn [globo user-id patch] returning nil
                     (accepted) or {:error string :details map}; runs in
                     addition to the max-length check whenever :name is
                     present in an :update-user patch (e.g. uniqueness)"
  ([] (create-globo {}))
  ([{:keys [mount-path storage connections placeables hexholds log-fn
            validate-user-update max-user-name-length]}]
   (let [max-length (or max-user-name-length default-max-user-name-length)]
     (->Globo (or mount-path "/map")
              (or storage (storage/in-memory-globo-storage))
              (or connections (connections/in-memory-connection-store))
              (cond
                (nil? placeables) (placeables/static-placeable-objects)
                (satisfies? protocols/PlaceableObjectProvider placeables) placeables
                :else (placeables/static-placeable-objects placeables))
              (or hexholds (hexholds/in-memory-hexhold-store
                            (hexholds/load-land-index "hexholds/land-res5.txt")))
              (or log-fn println)
              (build-validate-user-update validate-user-update max-length)
              max-length))))

(defn normalize
  "Accepts either a Globo record or legacy deps
  {:keys [storage sse-clients mount-path]}; legacy atoms are wrapped in the
  default in-memory implementations."
  [deps]
  (if (instance? Globo deps)
    deps
    (create-globo {:mount-path (:mount-path deps)
                   :storage (storage/in-memory-globo-storage (:storage deps))
                   :connections (connections/in-memory-connection-store (:sse-clients deps))
                   :placeables (:placeable-objects deps)})))

(defn publish!
  "Validate event and send it to the target's connections.

  target is a collection of connection-ids, :everybody, or a fn receiving
  the connection registry map and returning connection-ids."
  [globo target event]
  (publish/publish! globo target event))

(defn send-message!
  "Host-facing entry point: validates and processes message.

  Returns [:ok sent?] or [:error nil errors]."
  [globo message]
  (if-let [errors (validation/inbound-errors message)]
    [:error nil errors]
    (let [user-id (:user-id message)]
      (if (and user-id (nil? (protocols/get-user (:storage globo) user-id)))
        (do
          ((:log-fn globo) "[globo] unknown user-id in send-message!:" {:user-id user-id :message message})
          (when-let [conns (seq (protocols/connection-ids-for-user (:storage globo) user-id))]
            (publish/publish! globo conns (validation/system-notification :error message)))
          [:error nil [:unknown-user]])
        (try
          (let [result (messages/process {:globo globo :user-id user-id} message)]
            (if (= :rejected (:status result))
              [:error {:error (:error result) :details (:details result)} [:user-name-rejected]]
              [:ok result]))
          (catch Exception e
            ((:log-fn globo) "[globo] send-message! failed:" {:message message :error e})
            [:error nil [:processing-failed]]))))))

(defn routes
  "clj-simple-router route table for a Globo component.
  Accepts a Globo record or legacy deps map."
  [deps-or-globo]
  (let [globo (normalize deps-or-globo)]
    {(str "GET" (:mount-path globo) "/connection")
     (partial handlers/new-connection-handler globo)
     (str "POST" (:mount-path globo) "/send-message")
     (partial handlers/send-message-handler globo)
     (str "POST" (:mount-path globo) "/hexholds/query")
     (partial handlers/hexholds-query-handler globo)
     (str "POST" (:mount-path globo) "/hexholds/messages")
     (partial handlers/hexhold-messages-handler globo)
     (str "GET" (:mount-path globo) "/assets/**")
     handlers/assets-handler}))

(defn create-handler
  "Ring handler wrapping the routes with a 404 fallback.
  Accepts a Globo record or legacy deps map."
  [deps-or-globo]
  (let [globo (normalize deps-or-globo)]
    (router/wrap-routes (fn [_] {:status 404 :body "Not Found"})
                        (routes globo))))
