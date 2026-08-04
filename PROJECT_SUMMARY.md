# Globo — Project Summary

An interactive 3D globe application built in Clojure/ClojureScript. Users can place 3D objects on a globe, save favorite locations, chat in real time, and see other connected users. Designed as a reusable library that can be embedded in a larger Clojure web application (integration target: "Galt").

## Architecture Overview

Two-tier architecture:

```
┌─────────────────────────────────────────────────────────┐
│  examples/  (Babashka host: HTTP server, routes,        │
│              middleware, page template)                 │
│    server.main/start! → is.galt.globo.server/routes     │
├─────────────────────────────────────────────────────────┤
│  CLIENT (ClojureScript / re-frame / Reagent / globe.gl) │
│    is.galt.globo.ui.*                                   │
│    build: root shadow-cljs.edn :globo → resources/public│
│      ↕ HTTP POST + SSE GET                             │
├─────────────────────────────────────────────────────────┤
│  SERVER (Clojure / Ring / http-kit / clj-simple-router) │
│    is.galt.globo.server.*                               │
│      in-memory atoms: storage + sse-clients             │
└─────────────────────────────────────────────────────────┘
```

The **library code** lives under `src/is/galt/globo/`. The **example/integration** code lives under `examples/` and demonstrates how to wire the library into a host application. The library provides the UI (ClojureScript), server handlers plus a ready-made route table (Clojure), and static asset serving; the host app provides the HTTP server, middleware stack, page template, and the `storage`/`sse-clients` atoms.

## Key File Paths

### Library — Server (Clojure)

| File | Purpose |
|------|---------|
| `src/is/galt/globo/server.clj` | Public server API. `routes` builds a `clj-simple-router` route table under a configurable `:mount-path`: `GET <mount>/connection`, `POST <mount>/send-message`, `GET <mount>/assets/**`. `create-handler` wraps the routes with a 404 fallback via `router/wrap-routes`. |
| `src/is/galt/globo/server/handlers.clj` | Ring handlers. `new-connection-handler` (SSE lifecycle, user registration, initial state: `:connected`, `:map-objects`, `:users-online`, last 20 `:messages`). `send-message-handler` (parses JSON body, dispatches to `messages/process`; returns 200 iff the message was sent to >= 1 client, else 404). `assets-handler` (serves `resources/public/**` via classpath `resource-response`; mime map incl. `glb → model/gltf-binary`; empty path → `index.html`). `users-online` = users with non-empty connection sets. |
| `src/is/galt/globo/server/messages.clj` | Message dispatch (`process`). Handles `:update-object`, `:update-user`, `:update-favorite`, `:add-favorite`, `:user-online`, `:user-offline`, `:broadcast`, `:new-message`. Chat routing: text starting `@username` matching a known user → `:direct` (sender + target), otherwise `:world` (all). `latest-messages` (default limit 20). |
| `src/is/galt/globo/server/middleware.clj` | Library Ring middleware: `wrap-user-id` (permanent `user-id` UUID cookie; must sit INSIDE `wrap-cookies`), `wrap-error-response`, `wrap-public-files` (static roots, default `["public"]`; empty path → `index.html`; `Cache-Control: no-cache`), plus `set-cookie-header-value` and `mark-sse-response` helpers for the SSE manual-cookie path (marker key `::sse-response`). |
| `src/is/galt/globo/server/sse.clj` | SSE formatting (`sse-event`, JSON data, optional `event:` line) and `send!` (takes a seq of http-kit channels + data; returns boolean "sent to anyone"). |

### Library — UI (ClojureScript)

| File | Purpose |
|------|---------|
| `src/is/galt/globo/ui.cljs` | UI entry point. Exported `init` takes a JS object with `:globo-api-base-url` and optional `:assets-base-url` (defaults to `<api-base>/assets`), creates/reuses a React root on `#app`, dispatches `::initialize` (seeds app-db from the `default-db` schema + config), then renders. `start!`/`stop!` for shadow-cljs hot-reload. |
| `src/is/galt/globo/ui/events.cljs` | Re-frame events. `::click-globe` dispatches on the current `:mouse-action` type (place object / pick user location / set favorite). Also `::place-objects`, `::all-models-ready` (flush gate), `::send-chat-message`, `::set-hud-open`, `::set-mouse-action`/`::clear-mouse-action`, `::go-to-favorite`, `::rename-favorite`, `::add-favorite`, `::set-user-name`, `::set-active-panel`, `::set-settings-open`, `::set-system-state`, `::add-ring`/`::remove-ring`/`::sync-rings`; cofx `::globe-viewpoint` (camera lat/lng/altitude, injected into `::send-chat-message`); fxs `::update-map-objects` (globe layer), `::focus-globe` (flies camera), `::ring-timer`/`::clear-ring-timer` (ring animation timeouts, kept out of handlers). Mobile detection is an idempotent `setup-mobile-detection!` (defonce `delay` + `matchMedia`). Helpers `round-to`, `point-id-hash` (stable id `"p_<abs(hash)>"` from coords rounded to 6 decimals). |
| `src/is/galt/globo/ui/subscriptions.cljs` | Subscriptions: `::hud-open?`, `::mouse-action`, `::favorites`, `::max-favorite-places`, `::assets-base-url`, `::map-classes` (derives globe CSS classes from `:mouse-action`), `::map-objects`, `::placeable-map-objects`, `::is-mobile?`, `::active-panel`, `::messages`, `::settings-open?`, `::current-user`, `::rings`. |
| `src/is/galt/globo/ui/connection.cljs` | SSE client. `setup-sse-events` opens one `EventSource`, parses JSON (postwalk keywordizes `:type`/`:op`), closes any previous source. |
| `src/is/galt/globo/ui/connection/events.cljs` | Connection events. `dispatch-sse->re-frame` maps server SSE types to re-frame events (incl. `:favorite-added`, `:favorite-updated`; unknown types log a console warning instead of throwing). `::initialize`, `::send-message` (fetch-fx POST with `:connection-id` + `:user-id`), `::update-map-objects` (set-difference sync of server objects), `::users-online` (also syncs self favorites), `::user-online`/`::user-offline`, `::connected`/`::disconnected` (failure also sets `:connection :status` to `:offline`), `::receive-initial-messages`/`::receive-new-message`. |
| `src/is/galt/globo/ui/connection/subscriptions.cljs` | `::users-online` (resolved user maps), `::status`. |
| `src/is/galt/globo/ui/presentation.cljs` | Top-level component. Subscribes `::map-classes`, wires map params (`:css-classes`, `:on-globe-click`) into `ui.map/present`, and renders `ui.hud/present`. HUD components subscribe to their own data (no prop drilling). |
| `src/is/galt/globo/ui/presentation/map.cljs` | globe.gl integration. Shadow-resolved imports (`["globe.gl" :as Globe]` global, three GLTFLoader/DRACOLoader). Atoms: `globe-instance`, `model-cache`, `layer-data`, `pending-loads`. `load-gltf!` (DRACO decoder from gstatic 1.5.7) — when `pending-loads` hits 0 dispatches `::all-models-ready`. `add-to-layer`/`remove-from-layer`. `create-3d-object` clones the cached scene + sets scalar scale (green-sphere fallback). `custom-three-object-update` positions clones at lat/lng/alt. `dispose-globe!` teardown; `present` uses a ref callback with one-frame deferred Globe construction (avoids Chrome's 16-WebGL-context cap). |
| `src/is/galt/globo/ui/presentation/hud.cljs` | HUD overlay UI. Panels: users (incl. a Focus button that flies the camera to a user's location), places (object buttons + favorite rows with rename / set-location-on-globe / go-to), messages chat (auto-scroll). `settings-panel` (name + location picker), `status-dot`, `settings-button`, tabbed mobile vs 3-column desktop layouts, collapsed `hud-summary` bar. Bulma CSS. |
| `src/is/galt/globo/ui/map_objects.cljs` | 3D object config vector. model-ids are **strings**; fields: `:model-id :path :scale :name :icon` and optional `:show-in-summary?`. Entries: carrot, tree, man (mountain_robot), ancap-bug, zombie-small, ancap-flag. |
| `src/is/galt/globo/ui/globe_gl_helpers.cljs` | `apply-config!` — applies a Clojure map of config to a Globe instance by calling camelCase methods (kebab→camel via camel-snake-kebab). Supports callback composition. |
| `src/is/galt/globo/ui/icons.cljs` | FontAwesome icon map (`:cancel :settings :pick-location :edit :set-location`) + `icon` helper with optional text. |

### Example / Integration

| File | Purpose |
|------|---------|
| `examples/bb.edn` | Babashka project config. Deps: globo via `:local/root ".."`, clj-simple-router, clj-reload, ring-core, ring-logger, lambdaisland/uri, markdown-clj (currently unused). Tasks: `nrepl` (port 1339, writes `.nrepl-port`), `watch-ui` (shadow-cljs `watch globo` from repo root with `--config-merge` asset-path), `server` (starts http-kit; exec-args `:example :static :port 3000 :mount-path "/map"`). |
| `examples/server/src/server/main.clj` | Example Babashka host. `storage`/`sse-clients` atoms, `middleware-stack`, `make-routes` (mounts globo handler at `* <mount>/**` + `GET /` index handler), `index-handler` fills `{{mount-path}}`/`{{api-base-url}}` into `index.html.template`, `normalize-mount-path`, `start!`/`stop!` with `before-ns-unload`/`after-ns-reload` for clj-reload. `example-roots`: `:shadow-cljs`, `:static`, `:scittle`. Uses the library middleware (`is.galt.globo.server.middleware`), incl. its multi-root `wrap-public-files`. |
| `examples/server/dev/user.clj` | Dev REPL namespace `user`: `go!` (clj-reload), `start!`/`stop!` (server.main + shadow-cljs watch via `shadow-watch` atom), `repo-root`. |
| `dev/user.clj` | Root dev REPL namespace: `go!` (clj-reload), `watch-compile-ui` (shadow devtools server + `watch :globo`). Used via `clojure -M:nrepl` (extra-paths `dev`). |
| `shadow-cljs.edn` (root) | Canonical UI build. `:globo` → output `resources/public/js`, asset-path `/map/assets/js`, module `globo` entry `is.galt.globo.ui`, nrepl port 3333, `:jvm-opts ["--sun-misc-unsafe-memory-access=allow"]`, devtools before/after-load `stop!`/`start!`, release maps tracing → tracing-stubs. Plus a `:test` node-test build (`:ns-regexp "-test$"`, autorun) for CLJS tests. `test/` is on the classpath via deps.edn's `:ui` alias (`:extra-paths ["test"]`). |
| `examples/static/index.html.template` | Host page template with `{{mount-path}}` / `{{api-base-url}}` placeholders; loads Bulma, FontAwesome, globe.gl CDN, `<mount>/assets/js/globo.js`, then `is.galt.globo.ui.init({...})`. |
| `examples/shadow-cljs/` | **Legacy** example app (own `:app` build, `app.main` entry, closure-define `GLOBO_API_BASE_URL`). Superseded by the root `:globo` build; kept for `bb server --example shadow-cljs` / `npx shadow-cljs watch app`. |
| `examples/scittle/` | Placeholder example (`scittle.txt` in its public root). |
| `.conjure-repls.lua` | Conjure REPL ports: clj 1339 for `src/is/galt/**` + `examples/server/**`, cljs 3333 shadow build `globo`. |
| `resources/public/` | Static assets served by the library's `assets-handler`: `css/main.css` (HUD styles), `js/` (compiled globo.js, gitignored), `3d/` (8 GLB models: carrot, fantasy_tree, mountain_robot, ancap_bug, ancap_flag, zombie, scroll, snowman — scroll/snowman present but not yet in config). |
| `README.md` | Brief overview + screenshot; quickstart `bb server --example static`. |
| `ideas.md` | (untracked) Future ideas: message arcs, favorite-place rotation animation + ripple rings. |

## Dependencies

### Clojure (Server) — root `deps.edn`

| Dependency | Version | Role |
|------------|---------|------|
| `cheshire/cheshire` | 6.2.0 | JSON encoding/decoding for SSE events and POST bodies |
| `io.github.tonsky/clj-simple-router` | 0.1.2 | Route table construction (`routes`, `wrap-routes`) for the library route API |
| `io.github.tonsky/clj-reload` | 1.0.0 | Development hot-reload of changed namespaces |
| `ring/ring-core` | 1.15.3 | Ring handlers, responses, middleware utilities |
| `http-kit/http-kit` | 2.9.0-beta3 | Async HTTP server, SSE channel support (`as-channel`, `send!`) |
| `ring-logger/ring-logger` | 1.1.1 | Request logging middleware |
| `lambdaisland/uri` | 1.19.155 | URI parsing |

### ClojureScript (UI) — `:ui` alias in root `deps.edn`

| Dependency | Version | Role |
|------------|---------|------|
| `thheller/shadow-cljs` | 3.4.11 | ClojureScript compiler and build tool |
| `reagent/reagent` | 2.0.1 | React wrapper for ClojureScript |
| `re-frame/re-frame` | 1.4.7 | State management (event/subscription pattern) |
| `superstructor/re-frame-fetch-fx` | 0.4.0 | re-frame `:fetch` effect for POST calls |
| `applied-science/js-interop` | 0.4.2 | JS interop (`j/call`, `j/get`, `j/update!`) |
| `camel-snake-kebab/camel-snake-kebab` | 0.4.3 | Case conversion (kebab-case ↔ camelCase) |
| `binaryage/devtools` | 1.0.7 | Browser dev tools enhancement |

Other aliases: `:ui-dev` → `day8.re-frame/tracing` 0.6.2 + `day8.re-frame/re-frame-10x` 1.11.0. `:nrepl` → nrepl 1.7.0, cider-nrepl 0.61.0, piggieback 0.5.2; `:main-opts ["-m" "nrepl.cmdline" "--middleware" "[cider.nrepl/cider-middleware,cider.piggieback/wrap-cljs-repl]"]`; extra-paths `dev`. `:outdated` → depot 2.4.1. `:test` → cognitect test-runner v0.5.1 (git `dfb30dd`), extra-paths `test`; run via `clojure -M:test`.

### JavaScript (NPM) — root `package.json`

| Dependency | Version | Role |
|------------|---------|------|
| `globe.gl` | 2.46.1 | 3D globe rendering (shadow-resolved as global `Globe`) |
| `three` | ^0.184.0 | Three.js — scene graph, GLTF/DRACO loaders |
| `react` / `react-dom` | 19.2.0 | React (via Reagent) |
| `gsap` | ^3.15.0 | Animation library (available, currently unused) |
| `shadow-cljs` | ^3.4.11 | devDependency for the root build |

## App-DB Schema (re-frame, from `ui.cljs` `default-db`)

```clojure
{:system-state {:is-mobile? boolean}
 :config {:globo-api-base-url string
          :assets-base-url string      ; default (api-base "/assets")
          :connection-url string       ; base + "/connection"
          :send-message-url string     ; base + "/send-message"
          :max-favorite-places 3}
 :users {user-id {:id :name :location {:lat :lng} :favorites [...] ...}}
 :connection {:status :offline | :online
              :connection-id uuid-string
              :user-id uuid-string
              :users-online #{user-id ...}}
 :ui {:active-panel :users | :places | :messages
      :settings-open? boolean}
 :messages [{:id :author {:id :name} :type keyword :target set-or-nil
             :content string :viewport map :sent-at string
             :received-at :seen-at} ...]
 :map-objects #{{:id :lat :lng :model-id :scale ...}}
 :placeable-map-objects {model-id {...}}   ; from map-objects/config
 :mouse-action nil | {:type :place-object :model-id kw}
                    | {:type :pick-user-location}
                    | {:type :set-favorite :index int}
 :favorites [{:id uuid-string :label string :lat num-or-nil :lng num-or-nil} ...]
 :rings {ring-id {:id string :lat num :lng num :duration num-or-nil}}
 :hud-open? true
 :models-ready? false}
```

## Message Protocol

### Client → Server (POST `<mount>/send-message`)

```clojure
{:type :update-object  :connection-id uuid :user-id uuid
 :content {:op :add | :remove
           :objects [{:id string :lat num :lng num :model-id string :scale num} ...]}}

{:type :new-message   :connection-id uuid :user-id uuid
 :content {:text string :viewport {:lat num :lng num :altitude num}}}

{:type :update-user   :connection-id uuid :user-id uuid
 :content {:id uuid :name string}}
;; or {:id uuid :location {:lat num :lng num}}

{:type :update-favorite  :connection-id uuid :user-id uuid
 :content {:index int :partial {:label string | :lat num | :lng num}}}

{:type :add-favorite  :connection-id uuid :user-id uuid}
;; server appends {:id uuid :label "" :lat nil :lng nil}
```

### Server → Client (SSE GET `<mount>/connection`)

```clojure
{:type :connected,          :content {:connection-id uuid :user-id uuid}}
{:type :map-objects,        :content {:objects set-of-map-objects}}
{:type :users-online,       :content {:users [user-maps incl. :favorites]}}
{:type :messages,           :content {:messages [message-maps]}}   ; latest 20
{:type :update-object,      :content {:op :add | :remove :objects [...]}}
{:type :update-user,        :content user-map}
{:type :user-online,        :content user-map}
{:type :user-offline,       :content {:id uuid}}
{:type :new-message,        :content message-map}
{:type :favorite-added,     :content {:index int :favorite fav-map}}
{:type :favorite-updated,   :content {:index int :favorite fav-map}}
```

## Server Storage Schema (in-memory atoms, host-provided)

```clojure
storage    := {:users {user-id {:id :name :location :favorites [...]
                                :last-seen-at instant}}
               :map-objects #{object-map ...}
               :user-connections {user-id #{connection-id ...}}
               :messages [{:id :author {:id :name} :type :direct|:world|:entity
                           :target set-or-nil :content string :viewport map
                           :sent-at :received-at :seen-at} ...]}
sse-clients := {connection-id http-kit-channel ...}   ; separate atom
```

## Key Patterns & Conventions

- **re-frame event naming**: Namespaced keywords (`::event-name`) per namespace. Cross-namespace dispatch uses the fully qualified keyword (e.g. `:is.galt.globo.ui.connection.events/send-message`).
- **Mouse-action state machine**: `db :mouse-action` drives contextual globe clicks: `:place-object` (place model), `:pick-user-location` (set user location), `:set-favorite` (mark favorite coords). `::map-classes` sub turns the action into CSS classes (`place-object-in-progress`, `picking-location`, `setting-favorite`) on `#globe-container`.
- **SSE for server→client push**: Single `EventSource` per client. Server sends initial state on connect, then broadcasts. Client parses via `walk/postwalk` keywordization in `connection.cljs`.
- **Fetch for client→server**: `superstructor.re-frame.fetch-fx` registered as `:fetch`. POST JSON body with `:connection-id` + `:user-id`.
- **globe.gl integration**: Globe instance in `globe-instance` atom; config applied via `apply-config!` (camelCase method calls). GLTF models preloaded with DRACO, cached in `model-cache`, cloned per placed object. `models-ready?` gate (via `pending-loads` → `::all-models-ready`) buffers map-objects until models load (avoids green-sphere fallback caching).
- **Hot-reload safety**: `dispose-globe!` tears down Three.js resources and detaches the canvas (deliberately NOT `renderer.dispose()`/`forceContextLoss()`). New Globe construction deferred one animation frame to avoid Chrome's 16-context cap.
- **User identity**: Permanent UUID cookie (`user-id`) by `wrap-user-id`. SSE handler sets `Set-Cookie` manually via `server.middleware/set-cookie-header-value` (bypasses `wrap-cookies` response path; marked with `::sse-response`).
- **Message routing**: `@username`-prefixed chat → `:direct` (sender + targeted users only); otherwise `:world` (all). `:entity` treated as world (entity UI not built yet).
- **Mount-path config**: everything (routes, asset serving, UI config, page template) is parameterized by `:mount-path` (default `/map`) and `:globo-api-base-url`/`:assets-base-url`. Host page uses `{{mount-path}}`/`{{api-base-url}}` template substitution.
- **Middleware ordering** (example): `wrap-cookies` → `wrap-user-id` → `wrap-error-response` → `wrap-params` → `wrap-with-logger` → `wrap-content-type` → `wrap-public-files`.
- **JS interop**: `applied-science.js-interop` (`j/call`, `j/get`, `j/update!`) preferred. `camel-snake-kebab` for method name conversion.
- **Responsive UI**: mobile detection via `matchMedia` (`(max-width: 1023px)`). HUD switches between mobile (tabbed) and desktop (3-column) layouts; collapsed summary bar shows `:show-in-summary?` placeables + online count.
- **Message ids**: objects use `point-id-hash` (stable id from coords); messages/favorites use server-generated UUIDs.
- **clj-reload**: dev namespaces use `clj-reload` with `:no-reload` on the dev/user ns itself; host `server.main` implements `before-ns-unload`/`after-ns-reload` to restart the server on reload.

## Development Workflow

### Run the example app (Babashka)

```bash
# From examples/ directory:
bb server --example static --port 3000 --mount-path /map   # static host page
bb server --example shadow-cljs                             # legacy example build
bb server --example scittle                                 # placeholder example
```

Open http://localhost:3000 — the index handler fills the template with the mount path and API base URL.

### UI development (root-level shadow-cljs build)

```bash
# From examples/ dir (wires :asset-path to the mount path):
bb watch-ui --mount-path /map
# or from repo root:
npx shadow-cljs watch globo
# Release build:
npx shadow-cljs release globo
```

### REPL (Clojure server)

```bash
# From examples/ dir:
bb nrepl                 # nREPL on port 1339, writes .nrepl-port
# From repo root (library ns):
clojure -M:nrepl         # nREPL with cider + piggieback, dev/ on classpath
```

Conjure port table (`.conjure-repls.lua`): clj 1339, cljs 3333 (shadow build `globo`).

```clojure
;; In the example REPL (ns user in examples/server/dev/user.clj):
(require '[user] :reload)
(user/go!)          ; reload changed namespaces
(user/start!)       ; start shadow watch + HTTP server
(user/stop!)

;; In the root REPL (ns user in dev/user.clj):
(require '[user] :reload)
(user/go!)                      ; reload changed namespaces
(user/watch-compile-ui)         ; shadow devtools watch :globo
```

### Testing

```bash
# Clojure server tests (test/is/galt/globo/server/*):
clojure -M:test

# ClojureScript tests (node-test build, test/is/galt/globo/ui/*):
npx shadow-cljs compile test
```

### Checking Outdated Dependencies

```bash
clojure -M:outdated
```

## Extension Points

1. **Storage**: Replace the in-memory atoms with a `defprotocol Storage` implementation and pass it through `create-handler` deps. (Previously tracked in TODO.md, which no longer exists.)
2. **Transport**: Client transport can be swapped from HTTP+SSE to channels or function callbacks.
3. **3D models**: Add entries to `map-objects/config` in `ui/map_objects.cljs` (model-ids are strings). Models must be GLB files (DRACO-compressed supported), placed in `resources/public/3d/` (e.g. `scroll.glb`, `snowman.glb` already present but unconfigured). Optional `:show-in-summary?` flag shows the model in the collapsed HUD bar.
4. **HUD panels**: New tabs/panels via `::set-active-panel` event and corresponding view functions in `hud.cljs` (mobile tabbed + desktop column layouts).
5. **Message types**: Add new types in `server/messages.clj` `process` (server) and `connection/events.cljs` `dispatch-sse->re-frame` (client), plus `favorite-*`-style broadcast handlers if per-user targeting is needed.
6. **Favorites**: `:max-favorite-places` cap in app-db config; server owns the favorites list per user (`[:users user-id :favorites]`), client mirrors it and syncs via `::users-online`.
7. **Integration**: Host app mounts the globo route table via `globo.server/routes` or `create-handler` under any `:mount-path`, provides `storage` + `sse-clients` atoms and middleware. See `examples/server/src/server/main.clj` for reference.
8. **Mount path / assets**: `:mount-path`, `:globo-api-base-url`, and `:assets-base-url` are configurable end-to-end (server routes, asset serving, page template, UI init).

## Technology Stack

- Clojure 1.12.1, Java 25
- ClojureScript via shadow-cljs (browser target), root build `:globo`
- re-frame / Reagent (React 19)
- globe.gl / Three.js (3D globe)
- http-kit + Ring + clj-simple-router (server)
- Bulma CSS + FontAwesome (UI styling)
- SSE for real-time communication
- Babashka for example server runtime

## Known Inconsistencies

- (none currently tracked; the previous `examples/bb.edn` `dev` task issue was resolved by removing the broken task and default profile in the refactor.)
