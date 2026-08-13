(ns is.galt.globo.server.messages
  "Message dispatch. Both browser-originated POSTs and host-originated
  send-message! calls funnel through `process`, which mutates the
  GloboStorage and pushes events via publish!. All outbound events are
  validated by publish! before reaching the browser."
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
   [is.galt.globo.protocols :as protocols]
   [is.galt.globo.server.publish :as publish]
   [is.galt.globo.server.validation :as validation]))

(def default-favorites
  "Favorites seeded for newly registered users."
  [])

(defn- user-storage
  [globo]
  (:storage globo))

(defn- all-but-sender-ids
  "Connection-ids of every connected user except `user-id` (explicit set,
  because publish! itself has no sender context)."
  [globo user-id]
  (publish/resolve-target-ids {:globo globo :user-id user-id} :all-but-sender))

(defn update-user
  "Merge :name/:location from the message into the user and broadcast the
  updated user to everybody (echo to sender; clients apply it idempotently)."
  [{:keys [globo user-id]} {:keys [content]}]
  (let [storage (user-storage globo)]
    (protocols/update-user! storage user-id #(merge % (select-keys content [:name :location])))
    (publish/publish! globo :everybody
                      {:type :update-user :user-id user-id
                       :content (protocols/get-user storage user-id)})))

(defn update-favorite
  "Merge :partial into the user's favorite at :index and broadcast it to
  the user's own connections."
  [{:keys [globo user-id]} {:keys [content]}]
  (let [storage (user-storage globo)
        favorite (protocols/update-favorite! storage user-id (:index content) (:partial content))]
    (publish/publish! globo (protocols/connection-ids-for-user storage user-id)
                      {:type :favorite-updated :user-id user-id
                       :content {:index (:index content) :favorite favorite}})))

(defn add-favorite
  "Append a fresh favorite for the user and broadcast it to the user's own
  connections."
  [{:keys [globo user-id]} _message]
  (let [storage (user-storage globo)
        favorite {:id (str (java.util.UUID/randomUUID)) :label "" :lat nil :lng nil}]
    (protocols/add-favorite! storage user-id favorite)
    (let [index (dec (count (protocols/user-favorites storage user-id)))]
      (publish/publish! globo (protocols/connection-ids-for-user storage user-id)
                        {:type :favorite-added :user-id user-id
                         :content {:index index :favorite favorite}}))))

(defn user-offline
  "Broadcast :user-offline to everyone except the sender, but only when the
  user has no remaining connections."
  [{:keys [globo user-id]} message]
  (let [storage (user-storage globo)]
    (when (empty? (protocols/connection-ids-for-user storage user-id))
      (publish/publish! globo (all-but-sender-ids globo user-id)
                        (assoc-in message [:content :id] user-id)))))

(defn update-map-objects
  "Apply :add/:remove to the shared map-objects set and broadcast the
  change to everybody (echo to sender; clients sync via set-difference)."
  [{:keys [globo user-id]} {:keys [content]}]
  (let [storage (user-storage globo)
        op (keyword (:op content))
        update-fn (case op
                    :add set/union
                    :remove set/difference
                    (throw (ex-info "Unrecognized :op" {:message content})))
        updated (update-fn (protocols/get-map-objects storage)
                           (into #{} (:objects content)))]
    (protocols/set-map-objects! storage updated)
    (publish/publish! globo :everybody
                      {:type :update-object :content content})))

(defn resolve-recipient-ids
  "Return the user-ids whose (case-insensitive) name matches `username`."
  [storage username]
  (->> (protocols/users-map storage)
       (keep (fn [[user-id user]]
               (when (and (:name user)
                          (= (str/lower-case (:name user)) username))
                 user-id)))))

(defn parse-message-type
  "Chat routing: @username prefix matching a known user -> :direct with the
  matched user-ids as target, otherwise :world."
  [storage text]
  (if-let [[_ username] (re-find #"^@(\S+)" text)]
    (let [ids (resolve-recipient-ids storage (str/lower-case username))]
      (if (seq ids)
        {:type :direct :target (set ids)}
        {:type :world :target nil}))
    {:type :world :target nil}))

(defn handle-new-message
  "Store a chat message and broadcast it: :direct goes to the sender's and
  the target users' connections, everything else to everybody."
  [{:keys [globo user-id]} {:keys [content]}]
  (let [storage (user-storage globo)
        text (:text content)
        {:keys [type target]} (parse-message-type storage text)
        msg {:id (str (java.util.UUID/randomUUID))
             :author {:id user-id :name (or (:name (protocols/get-user storage user-id)) "Anonymous")}
             :type type :target target
             :content text :viewport (:viewport content)
             :sent-at (str (java.time.Instant/now))
             :received-at nil :seen-at nil}
        event {:type :new-message :content msg}]
    (protocols/append-message! storage msg)
    (if (= type :direct)
      (publish/publish! globo
                        (into #{} (mapcat #(protocols/connection-ids-for-user storage %)
                                          (cons user-id (vec target))))
                        event)
      (publish/publish! globo :everybody event))))

(defn paint-hexhold
  "Store a hexhold paint (or clear when :color is nil) on behalf of the
  sender and broadcast the change to everybody. When the cell is claimed
  by another user, the paint is rejected and a warning notification goes
  to the sender's own connections only."
  [{:keys [globo user-id]} {:keys [content]}]
  (let [result (protocols/paint-hexhold! (:hexholds globo)
                                         (:hex-id content)
                                         (:color content)
                                         user-id)]
    (if result
      (publish/publish! globo :everybody
                        {:type :hexholds-updated :content result})
      (publish/publish! globo (protocols/connection-ids-for-user (:storage globo) user-id)
                        (validation/system-notification
                         :warning
                         {:type :paint-hexhold :hex-id (:hex-id content)}
                         "This hexagon is claimed by another user.")))))

(defn hexhold-message
  "Append a message to a hexhold and broadcast it to everybody."
  [{:keys [globo user-id]} {:keys [content]}]
  (let [storage (user-storage globo)
        author {:id user-id :name (or (:name (protocols/get-user storage user-id)) "Anonymous")}
        msg (protocols/add-hexhold-message! (:hexholds globo)
                                            (:hex-id content)
                                            author
                                            (:text content))]
    (publish/publish! globo :everybody
                      {:type :hexhold-message
                       :content {:hex-id (:hex-id content) :message msg}})))

(defn process
  "Dispatch an inbound message. Returns the boolean result of the final
  publish (false when nobody was reached)."
  [{:keys [globo user-id]} message]
  (case (:type message)
    :update-object (update-map-objects {:globo globo :user-id user-id} message)
    :update-user (update-user {:globo globo :user-id user-id} message)
    :update-favorite (update-favorite {:globo globo :user-id user-id} message)
    :add-favorite (add-favorite {:globo globo :user-id user-id} message)
    :paint-hexhold (paint-hexhold {:globo globo :user-id user-id} message)
    :hexhold-message (hexhold-message {:globo globo :user-id user-id} message)
    :user-offline (user-offline {:globo globo :user-id user-id} message)
    :user-online (publish/publish! globo :everybody message)
    :broadcast (publish/publish! globo :everybody message)
    :system-notification (publish/publish! globo :everybody message)
    :new-message (handle-new-message {:globo globo :user-id user-id} message)
    (throw (ex-info "Unrecognized message :type" {:message message}))))
