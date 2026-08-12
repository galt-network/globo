(ns is.galt.globo.protocols
  "Host-facing protocols for the globo library.

  A host application (e.g. Galt) implements GloboStorage, ConnectionStore
  and (optionally) PlaceableObjectProvider, then passes the instances to
  is.galt.globo.server/create-globo. Globo ships default in-memory
  implementations in is.galt.globo.server.storage and
  is.galt.globo.server.connections; the default placeable-object source is
  a static config vector in is.galt.globo.server.placeables.")

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
  "Shared state for the hexholds (H3 hexagon paint) feature."
  (paint-hexhold!
    [this hex-id color]
    "Paint hex-id with a color keyword, or clear when color is nil.
     Returns {:id hex-id :color color-or-nil}.")
  (hexhold-colors
    [this]
    "Sparse map of hex-id-string -> color-keyword for all painted cells.")
  (query-hexholds
    [this cell-ids]
    "Intersect cell-ids with the land index. Returns a vector of
     {:id string :color (maybe keyword)} for land cells only; unpainted
     land cells have :color nil. When the land index is nil every
     requested cell counts as land (dev fallback)."))
