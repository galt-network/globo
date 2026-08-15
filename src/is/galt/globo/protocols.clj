(ns is.galt.globo.protocols
  "Host-facing protocols for the globo library.

  A host application (e.g. Galt) implements GloboStorage, ConnectionStore
  and (optionally) PlaceableObjectProvider, then passes the instances to
  is.galt.globo.server/create-globo. Globo ships default in-memory
  implementations in is.galt.globo.server.storage and
  is.galt.globo.server.connections; the default placeable-object source is
  a static config vector in is.galt.globo.server.placeables.")

(defprotocol MapOverlayProvider
  "Viewport overlays (admin borders / names / cities). Hosts implement
   this to back queries with their own store; the default is a static
   in-memory/file provider."
  (query-overlays
    [this {:keys [kinds bbox]}]
    "Return {:paths [{:id :coords}] :labels [{:id :text :lat :lng :class}]}
     for features whose kind is in `kinds` and whose bbox intersects
     `bbox` {:west :south :east :north}."))

(defprotocol GloboStorage
  "Persistent state for the globo server: users, map objects, chat
  messages, and the user -> connection-ids mapping."
  (get-user
    [this user-id]
    "Return the user map for user-id, or nil.")
  (update-user!
    [this user-id f]
    "Apply f to the stored user map (or nil) and store the result.")
  (users-map
    [this]
    "Map of user-id -> user map for all known users.")
  (user-favorites
    [this user-id]
    "Vector of favorite maps for user-id.")
  (update-favorite!
    [this user-id index partial]
    "Merge `partial` into the user's favorite at `index`.")
  (add-favorite!
    [this user-id favorite]
    "Append `favorite` to the user's favorites.")
  (get-map-objects
    [this]
    "Set of placed map objects.")
  (set-map-objects!
    [this objects]
    "Replace the placed map-objects set.")
  (append-message!
    [this message]
    "Append a chat message.")
  (latest-messages
    [this limit]
    "Return up to `limit` most recent chat messages.")
  (connection-ids-for-user
    [this user-id]
    "Set of open connection-ids for user-id.")
  (add-user-connection!
    [this user-id connection-id]
    "Register an open connection for user-id.")
  (remove-user-connection!
    [this user-id connection-id]
    "Deregister a closed connection for user-id."))

(defprotocol ConnectionStore
  "Live registry of open SSE connections: connection-id -> channel."
  (add-connection!
    [this connection-id channel]
    "Register an open SSE connection.")
  (remove-connection!
    [this connection-id]
    "Deregister a closed SSE connection.")
  (registry
    [this]
    "Map of connection-id -> channel for all open connections.")
  (channels-for
    [this connection-ids]
    "Resolve a collection of connection-ids to their channels."))

(defprotocol PlaceableObjectProvider
  "Source of 3D-object configs users can place on the globe, possibly
  varying per user."
  (placeable-objects
    [this user-id]
    "Vector of placeable-object config maps for user-id."))

(defprotocol HexholdStore
  "Shared state for the hexholds (H3 hexagon paint) feature.

  Ownership rules (enforced by every implementation): an unclaimed cell
  is claimed by its first painter; only the owner may repaint or clear
  it; clearing also releases the claim, after which anyone can claim it
  again."
  (paint-hexhold!
    [this hex-id color owner-id]
    "Paint hex-id with a color keyword on behalf of owner-id, or clear
     when color is nil. Returns {:id hex-id :color color-or-nil
     :owner-id owner-or-nil} on success, or nil when rejected (the cell
     is claimed by another user).")
  (hexhold-colors
    [this]
    "Sparse map of hex-id-string -> color-keyword for all painted cells.")
  (query-hexholds
    [this cell-ids]
    "Intersect cell-ids with the land index. Returns a vector of
     {:id string :color (maybe keyword) :owner-id (maybe string)} for
     land cells only; unpainted land cells have :color and :owner-id
     nil. When the land index is nil every requested cell counts as
     land (dev fallback).")
  (hexhold-messages
    [this hex-id]
    "Vector of messages for hex-id (empty when none).")
  (add-hexhold-message!
    [this hex-id author text]
    "Append a message to hex-id. `author` is a user map {:id string
     :name string}. Returns the message map {:id string :author map
     :content string :sent-at string}."))
