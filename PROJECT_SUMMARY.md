# Globo — Project Summary

An interactive 3D globe application built in Clojure/ClojureScript. Users can place 3D objects on a globe, chat in real time, and see other connected users. Designed as a reusable library that can be embedded in a larger Clojure web application (integration target: "Galt").

## Architecture Overview

Two-tier architecture:

```
┌─────────────────────────────────────────────────────────┐
│  examples/shadow-cljs  (host page + entry point)         │
│    app.main/init  →  is.galt.globo.ui/init                │
├─────────────────────────────────────────────────────────┤
│  CLIENT (ClojureScript / re-frame / Reagent / globe.gl)  │
│    is.galt.globo.ui.*                                    │
│      ↕ HTTP POST  +  SSE GET                            │
├─────────────────────────────────────────────────────────┤
│  SERVER (Clojure / Ring / http-kit)                     │
│    is.galt.globo.server.*                                │
│      in-memory atom storage                             │
├─────────────────────────────────────────────────────────┤
│  examples/server  (Babashka host, routes, middleware)   │
│    server.main/start!                                   │
└─────────────────────────────────────────────────────────┘
```

The **library code** lives under `src/is/galt/globo/`. The **example/integration** code lives under `examples/` and demonstrates how to wire the library into a host application. The library provides the UI (ClojureScript) and server handlers (Clojure); the host app provides the HTTP server, middleware stack, routing, and static-file serving.

## Key File Paths

### Library — Server (Clojure)

| File | Purpose |
|------|---------|
| `src/is/galt/globo/server.clj` | Public server API. `init` entry point (currently a stub). |
| `src/is/galt/globo/server/handlers.clj` | Ring handlers for SSE connection (`GET /map/connection`) and message sending (`POST /map/send-message`). Manages connection lifecycle, user registration, and initial state delivery. |
| `src/is/galt/globo/server/messages.clj` | Message processing dispatch. Handles `:update-object`, `:update-user`, `:user-online`, `:user-offline`, `:new-message`, `:broadcast`. Contains chat message routing logic (world vs direct @username). |
| `src/is/galt/globo/server/middleware.clj` | Ring middleware: static file serving, error wrapping, and permanent user-id cookie assignment (`wrap-user-id`). |
| `src/is/galt/globo/server/sse.clj` | SSE event formatting (`sse-event`) and broadcasting (`send!`) via http-kit channels. |

### Library — UI (ClojureScript)

| File | Purpose |
|------|---------|
| `src/is/galt/globo/ui.cljs` | UI entry point. `init` creates Reagent root, dispatches `:is.galt.globo.ui.db/initialize`, and renders the presentation. Defines the initial app-db schema. Re-exports `start!` / `stop!` for shadow-cljs hot-reload. |
| `src/is/galt/globo/core.cljs` | Thin core namespace. `create` wraps a globe instance (currently minimal). |
| `src/is/galt/globo/ui/events.cljs` | Re-frame events: globe click handling, object placement, chat message sending, HUD state, user name/location updates. Contains `reg-fx ::update-map-objects` for globe.gl layer manipulation. |
| `src/is/galt/globo/ui/subscriptions.cljs` | Re-frame subscriptions: HUD state, map objects, messages, current user, active panel, settings, mobile detection. |
| `src/is/galt/globo/ui/connection.cljs` | SSE client setup. Opens `EventSource`, parses incoming JSON, dispatches to re-frame. |
| `src/is/galt/globo/ui/connection/events.cljs` | Connection re-frame events: SSE initialization, user online/offline, map-object sync, message send (via fetch-fx POST). |
| `src/is/galt/globo/ui/connection/subscriptions.cljs` | Connection subscriptions: `::users-online`, `::status`. |
| `src/is/galt/globo/ui/presentation.cljs` | Top-level presentation component. Wires subscriptions and events into `ui.map/present` and `ui.hud/present`. |
| `src/is/galt/globo/ui/presentation/map.cljs` | Globe.gl integration. Creates Globe instance, preloads GLTF/DRACO 3D models, manages `customLayerData`, handles click events, resize, and hot-reload-safe teardown (`dispose-globe!`). |
| `src/is/galt/globo/ui/presentation/hud.cljs` | HUD overlay UI. Users list, placeable objects buttons, messages chat, settings panel (name/location), responsive mobile/desktop layouts. Uses Bulma CSS. |
| `src/is/galt/globo/ui/map_objects.cljs` | 3D object configuration: model-id, GLB file path, scale, name, icon. 6 objects: carrot, tree, robot, ancap-bug, zombie, ancap-flag. |
| `src/is/galt/globo/ui/globe_gl_helpers.cljs` | `apply-config!` — applies a Clojure map of config to a Globe instance by calling camelCase methods. Supports callback composition. |
| `src/is/galt/globo/ui/icons.cljs` | FontAwesome icon helper. |

### Example / Integration

| File | Purpose |
|------|---------|
| `examples/bb.edn` | Babashka project config for the example server. Depends on the globo library via `:local/root ".."`. Tasks: `server` (start http-kit), `nrepl`. |
| `examples/server/src/server/main.clj` | Example Babashka server. Defines `empty-storage`, `empty-sse-clients`, routes, middleware stack, and `start!`/`stop!`. Entry point for running the example app. |
| `examples/server/src/server/middleware.clj` | Example middleware (mirrors library middleware but supports multiple static-file roots). |
| `examples/shadow-cljs/` | Complete shadow-cljs example app. `app.main` is the entry namespace; `GLOBO_API_BASE_URL` is a `goog-define` defaulting to `"/map"`. |
| `examples/shadow-cljs/src/app/main.cljs` | Bridge: calls `is.galt.globo.ui/init` with config from closure-defines. Exports `init`, `start!`, `stop!` for shadow-cljs lifecycle. |
| `examples/shadow-cljs/public/index.html` | Host HTML page. Loads Bulma CSS, FontAwesome, globe.gl CDN script, and compiled JS. |
| `examples/static/index.html` | Static host page (loads JS from `/map/js/main.js`). |
| `dev/user.clj` | Dev REPL namespace. Uses `clj-reload` for hot code reloading. |

## Dependencies

### Clojure (Server)

| Dependency | Version | Role |
|------------|---------|------|
| `cheshire` | 6.2.0 | JSON encoding/decoding for SSE events and POST bodies |
| `http-kit` | 2.9.0-beta3 | Async HTTP server, SSE channel support (`as-channel`, `send!`) |
| `ring/ring-core` | 1.15.3 | Ring handlers, responses, middleware utilities |
| `ring-logger` | 1.1.1 | Request logging middleware |
| `lambdaisland/uri` | 1.19.155 | URI parsing |
| `clj-reload` | 1.0.0 | Development hot-reload of changed namespaces |

### ClojureScript (UI)

| Dependency | Version | Role |
|------------|---------|------|
| `shadow-cljs` | 3.4.11 | ClojureScript compiler and build tool |
| `reagent` | 2.0.1 | React wrapper for ClojureScript |
| `re-frame` | 1.4.7 | State management (event/subscription pattern) |
| `superstructor/re-frame-fetch-fx` | 0.4.0 | re-frame effect for fetch API calls |
| `applied-science/js-interop` | 0.4.2 | JS interop (`j/call`, `j/get`, `j/update!`) |
| `camel-snake-kebab` | 0.4.3 | Case conversion (kebab-case ↔ camelCase) |
| `binaryage/devtools` | 1.0.7 | Browser dev tools enhancement |
| `day8.re-frame/10x` | 1.11.0 | re-frame debugging tool |

### JavaScript (NPM)

| Dependency | Version | Role |
|------------|---------|------|
| `globe.gl` | 2.46.1 | 3D globe rendering library (loaded via CDN script tag, resolved as global) |
| `three` | ^0.184.0 | Three.js — 3D scene graph, GLTF/DRACO loaders |
| `react` | 19.2.0 | React (via Reagent) |
| `react-dom` | 19.2.0 | React DOM rendering |
| `gsap` | ^3.15.0 | Animation library (currently unused in code but available) |

### Build Aliases

| Alias | Purpose |
|-------|---------|
| `:ui` | Adds ClojureScript + re-frame deps for UI development |
| `:ui-dev` | Adds re-frame tracing and 10x dev tools |
| `:nrepl` | Starts nREPL server with cider middleware |
| `:outdated` | Checks for dependency updates (depot) |

## App-DB Schema (re-frame)

```clojure
{:system-state {:is-mobile? boolean}
 :config {:globo-api-base-url string
          :connection-url string      ; base + "/connection"
          :send-message-url string}   ; base + "/send-message"
 :users {user-id {:id uuid :name string :location {:lat num :lng num} ...}}
 :connection {:status :offline | :online
              :connection-id uuid-string
              :user-id uuid-string
              :users-online #{user-id ...}}
 :ui {:active-panel :users | :places | :messages
      :settings-open? boolean
      :picking-location? boolean}
 :messages [{:id uuid :author {:id uuid :name string} :type keyword
             :content string :viewport map :sent-at string} ...]
 :map-objects #{{:id string :lat number :lng number :model-id keyword :scale number} ...}
 :placeable-map-objects {model-id {:model-id keyword :path string :scale number :name string :icon string}}
 :place-object nil | {:status :in-progress | :cancelled :model-id keyword}
 :hud-open? boolean
 :models-ready? boolean}
```

## Message Protocol

### Client → Server (POST /map/send-message)

```clojure
{:type :update-object
 :connection-id uuid-string
 :content {:op :add | :remove
           :objects [{:id string :lat number :lng number :model-id keyword :scale number} ...]}}

{:type :new-message
 :connection-id uuid-string
 :content {:text string :viewport {:lat number :lng number :altitude number}}}

{:type :update-user
 :connection-id uuid-string
 :content {:id uuid-string :name string}}
```

### Server → Client (SSE GET /map/connection)

```clojure
{:type :connected,         :content {:connection-id uuid :user-id uuid}}
{:type :map-objects,       :content {:objects set-of-map-objects}}
{:type :users-online,      :content {:users [user-maps]}}
{:type :messages,          :content {:messages [message-maps]}}
{:type :update-object,     :content {:op keyword :objects [...]}}
{:type :update-user,       :content user-map}
{:type :user-online,       :content user-map}
{:type :user-offline,       :content {:id uuid}}
{:type :new-message,       :content message-map}
```

## Server Storage Schema (in-memory atom)

```clojure
{:users {user-id {:id uuid :name string :last-seen-at instant}}
 :map-objects #{object-map ...}
 :user-connections {user-id #{connection-id ...}}
 :messages [{:id uuid :author {:id :name} :type keyword :target set-or-nil
             :content string :viewport map :sent-at :received-at :seen-at} ...}}
 :sse-clients {connection-id http-kit-channel}}
```

## Key Patterns & Conventions

- **re-frame event naming**: Namespaced keywords (`::event-name`) per namespace. Cross-namespace dispatch uses full qualified keyword (`:is.galt.globo.ui.connection.events/send-message`).
- **SSE for server→client push**: Single `EventSource` connection per client. Messages dispatched to re-frame via `dispatch-sse->re-frame`.
- **Fetch for client→server**: `superstructor.re-frame.fetch-fx` registered as `:fetch` effect. POST with JSON body.
- **globe.gl integration**: Globe instance stored in `globe-instance` atom. Config applied via `apply-config!` which calls camelCase methods. GLTF models preloaded with DRACO decompression, cached in `model-cache`. `models-ready?` gate prevents rendering objects before models load (avoids green-sphere fallback caching).
- **Hot-reload safety**: `dispose-globe!` tears down Three.js resources, removes canvas, nulls global handle. New Globe construction deferred by one animation frame to avoid Chrome's 16-context cap.
- **User identity**: Permanent UUID cookie (`user-id`) set by `wrap-user-id` middleware. SSE handler sets cookie manually via `Set-Cookie` header (bypasses `wrap-cookies` response path).
- **Message routing**: Chat messages starting with `@username` are `:direct` (sender + target only); others are `:world` (broadcast to all).
- **Middleware ordering**: `wrap-cookies` → `wrap-user-id` → `wrap-error-response` → `wrap-params` → `wrap-with-logger` → `wrap-content-type` → `wrap-public-files`.
- **JS interop**: `applied-science.js-interop` (`j/call`, `j/get`, `j/update!`) preferred over raw interop. `camel-snake-kebab` for method name conversion.
- **Responsive UI**: Mobile detection via `matchMedia` CSS media query. HUD switches between mobile (tabbed) and desktop (3-column) layouts.

## Development Workflow

### Server (Babashka)

```bash
# From examples/ directory:
bb server --example :shadow-cljs --port 3000
# or with nREPL:
bb nrepl
```

### UI (shadow-cljs)

```bash
# From examples/shadow-cljs/ directory:
npx shadow-cljs watch app
# Release build:
npx shadow-cljs release app
```

### REPL (Clojure server)

```clojure
;; In dev/user.clj:
(require '[user :reload])
(user/go!)  ; reload changed namespaces
(is.galt.globo.server/init {:storage nil})
```

### Testing

```bash
clojure -X:test  ; (no test runner configured yet)
```

### Checking Outdated Dependencies

```bash
clojure -M:outdated
```

## Extension Points

1. **Storage**: Replace in-memory atom with a `defprotocol Storage` implementation (planned per TODO.md). Pass custom storage to `is.galt.globo.server/init`.
2. **Transport**: Client transport can be swapped from HTTP+SSE to channels or function callbacks (3 integration modes documented in TODO.md).
3. **3D models**: Add new entries to `map-objects/config` in `ui/map_objects.cljs`. Models must be GLB files (DRACO-compressed supported). Place in `resources/public/3d/`.
4. **HUD panels**: New tabs/panels via `::set-active-panel` event and corresponding view functions in `hud.cljs`.
5. **Message types**: Add new message types in `server/messages.clj` `process` function and `connection/events.cljs` `dispatch-sse->re-frame`.
6. **Integration**: Host app routes `GET /map/connection` and `POST /map/send-message` to library handlers. See `examples/server/src/server/main.clj` for reference.

## Technology Stack

- Clojure 1.12.1, Java 25
- ClojureScript via shadow-cljs (browser target)
- re-frame / Reagent (React 19)
- globe.gl / Three.js (3D globe)
- http-kit + Ring (server)
- Bulma CSS + FontAwesome (UI styling)
- SSE for real-time communication
- Babashka for example server runtime