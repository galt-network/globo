(ns is.galt.globo.server.messages
  "Message dispatch for the globo server. process routes incoming messages
  from clients to handlers that update storage and broadcast SSE events to
  the right connections."
  (:require
   [clojure.set :as set]
   [clojure.string :as str]))

(def default-favorites
  "Default favorites for a brand-new user. Empty list - the user adds
  their own."
  [])

(defn- channels-for-ids
  "Resolve the SSE channels for a set of connection-ids."
  [sse-clients connection-ids]
  (vals (select-keys @sse-clients connection-ids)))

(defn- broadcast-to-user
  "Send data to all SSE connections for `user-id` (sender + other tabs of
  the same user)."
  [send! sse-clients storage user-id data]
  (let [connection-ids (get-in @storage [:user-connections user-id] #{})]
    (send! (channels-for-ids sse-clients connection-ids) data)))

(defn update-user
  "Merge :name/:location from the message content into the user, then
  broadcast the updated user map to everyone except the sender."
  [{:keys [send! storage user-id connections-for]} message]
  (swap! storage update-in [:users user-id] merge (select-keys (:content message) [:name :location]))
  (send! (connections-for :all-but-sender)
         {:type :update-user
          :user-id user-id
          :content (get-in @storage [:users user-id])}))

(defn update-favorite
  "Merge `partial` into the user's favorite at `index`, then broadcast the
   merged entry to all of the user's connections so every tab stays in sync."
  [{:keys [send! sse-clients storage user-id]} message]
  (let [{:keys [index partial]} (:content message)
        merged (merge (get-in @storage [:users user-id :favorites index])
                      partial)]
    (swap! storage assoc-in [:users user-id :favorites index] merged)
    (broadcast-to-user send! sse-clients storage user-id
                       {:type :favorite-updated
                        :user-id user-id
                        :content {:index index :favorite merged}})))

(defn add-favorite
  "Append a server-generated empty favorite for the user and broadcast
   it to all of the user's connections."
  [{:keys [send! sse-clients storage user-id]} _message]
  (let [favorite {:id (str (java.util.UUID/randomUUID))
                  :label ""
                  :lat nil
                  :lng nil}
        current (vec (get-in @storage [:users user-id :favorites] []))
        updated (conj current favorite)
        index (dec (count updated))]
    (swap! storage assoc-in [:users user-id :favorites] updated)
    (broadcast-to-user send! sse-clients storage user-id
                       {:type :favorite-added
                        :user-id user-id
                        :content {:index index :favorite favorite}})))

(defn user-offline
  "Broadcast a user-offline event to everyone except the sender when the
  user has no remaining open connections."
  [{:keys [send! storage user-id connections-for]} message]
  (when (empty? (get-in @storage [:user-connections user-id]))
    (send! (connections-for :all-but-sender) (assoc-in message [:content :id] user-id))))

(defn update-map-objects
  "Apply the :op (:add | :remove) in the message content to the shared
  map-objects set, then broadcast the change to everyone except the sender."
  [{:keys [send! storage user-id connections-for]} message]
  (let [content (:content message)
        update-fn (case (keyword (:op content))
                    :add set/union
                    :remove set/difference
                    (throw (ex-info "Unrecognized :op"
                                    {:op (:op content) :message message})))
        updated-objects (update-fn (get-in @storage [:map-objects])
                                   (into #{} (:objects content)))
        message {:type :update-object :content content}]
    (swap! storage assoc-in [:map-objects] updated-objects)
    (send! (connections-for :all-but-sender) message)))

(defn connections-for
  "Resolve the SSE channels for a broadcast target:
     :everybody      - all open connections
     :all-but-sender - every open connection except the current user's"
  [{:keys [storage sse-clients user-id]} target]
  (let [everybody (reduce into #{} (vals (get-in @storage [:user-connections])))
        sender (into #{} (get-in @storage [:user-connections user-id]))
        target-ids (case target
                     :everybody everybody
                     :all-but-sender (set/difference everybody sender))]
    (channels-for-ids sse-clients target-ids)))

(defn latest-messages
  "Return up to `limit` most recent messages from storage."
  [storage & [limit]]
  (let [msgs (:messages storage)
        n (min (or limit 20) (count msgs))]
    (subvec msgs (- (count msgs) n))))

(defn- resolve-recipient-ids
  "Find user-id whose :name matches the @username prefix (case-insensitive).
   Returns user-id or nil."
  [storage username]
  (some (fn [[uid {:keys [name]}]]
          (when (and name (= (str/lower-case name) (str/lower-case username)))
            uid))
        (:users storage)))

(defn- parse-message-type
  "Check if `text` starts with @username.
   If it does and the username matches a known user, classify as :direct.
   Otherwise classify as :world."
  [storage text]
  (if-let [[_ username] (re-find #"^@(\S+)" text)]
    (if-let [user-id (resolve-recipient-ids storage username)]
      {:type :direct :target #{user-id}}
      {:type :world :target nil})
    {:type :world :target nil}))

(defn handle-new-message
  "Store a new chat message and route it to the appropriate recipients.
   :world  -> broadcast to everybody
   :direct -> send only to sender + targeted user(s)
   :entity -> broadcast to everybody (entity UI not built yet)"
  [{:keys [send! storage user-id connections-for sse-clients]} message]
  (let [content (:content message)
        text (:text content)
        viewport (:viewport content)
        user-name (get-in @storage [:users user-id :name])
        {:keys [type target]} (parse-message-type @storage text)
        msg {:id (str (java.util.UUID/randomUUID))
             :author {:id user-id :name (or user-name "Anonymous")}
             :type type
             :target target
             :content text
             :viewport viewport
             :sent-at (str (java.time.Instant/now))
             :received-at nil
             :seen-at nil}]
    (swap! storage update :messages conj msg)
    (case type
      :direct (let [sender-ids (get-in @storage [:user-connections user-id] #{})
                    recipient-ids (reduce into #{} (map #(get-in @storage [:user-connections %] #{}) target))
                    target-ids (set/union sender-ids recipient-ids)]
                (send! (channels-for-ids sse-clients target-ids)
                       {:type :new-message :content msg}))
      (send! (connections-for :everybody) {:type :new-message :content msg}))))

(defn process
  "Dispatch a client message by :type. Returns the boolean result of the
  final send (true when the event reached at least one client), or throws
  ex-info for an unrecognized :type."
  [{:keys [send!] :as params} message]
  (let [connections-for (partial connections-for params)
        deps (assoc params :connections-for connections-for)]
    (case (:type message)
      :update-object (update-map-objects deps message)
      :update-user (update-user deps message)
      :update-favorite (update-favorite deps message)
      :add-favorite (add-favorite deps message)
      :user-offline (user-offline deps message)
      :user-online (send! (connections-for :everybody) message)
      :broadcast (send! (connections-for :everybody) message)
      :new-message (handle-new-message deps message)
      (throw (ex-info "Unrecognized message :type"
                      {:message message})))))
