This is a new session on the Clojure/ClojureScript project (globo).

## Startup (do this first)

1. Read and internalize `PROJECT_SUMMARY.md` (architecture, namespaces, protocol, layout).
2. Read `LLM_CODE_STYLE.md` and follow it.
3. Call `list_nrepl_ports`. Expected long-lived servers **in this repo**:
   - Backend Babashka nREPL → **1339** (`examples/`, ns `user`)
   - shadow-cljs nREPL      → **3333** (build `:globo`)
4. If 1339 or 3333 is missing, start it (see "Start missing REPLs"). Do **not** use nREPL ports from other directories (e.g. galt).
5. Acknowledge context is loaded, then wait for the task.

## Tooling priority

- Native `read` / `grep` / `glob` / `edit` / `write` for exploration and ordinary file work.
- Clojure-specific work (understand, debug, try ideas, verify): clojure-mcp
  - `clojure_eval` — primary feedback loop. Always pass `port`. `in-ns` before evals in that ns.
  - `clojure_edit` / `clojure_edit_replace_sexp` — structural edits.
  - `list_nrepl_ports` — confirm target before eval.
- Prefer `clojure_eval` over `bash` + `clojure -M:…` / `npx shadow-cljs …` during the loop.
- Full suite only before declaring done, or when you need the whole graph:
  - Server: `clojure -M:test`
  - UI: `npx shadow-cljs compile test`
- chrome-devtools: live DOM, console, network, screenshots. Not a substitute for the CLJS REPL when inspecting re-frame/app-db or evaluating ClojureScript.
- Bugs: load `diagnose` + `tdd` skills. New behavior: load `tdd`. Do not paste those skills into this file.

## Dual-REPL rules

| Area                               | Port | How                                                                                                                                           |
| ---------------------------------- | ---- | --------------------------------------------------------------------------------------------------------------------------------------------- |
| `*.clj`, server, `examples/server` | 1339 | Babashka. `user/go!` and `user/start!` live here.                                                                                             |
| `*.cljs`, UI, re-frame, globe.gl   | 3333 | If still CLJ, first eval `(shadow/repl :globo)`. `(shadow/active-builds)` if unsure. Runtime/`app-db` needs a browser tab on http://localhost:3000. |

Pass `port` on every `clojure_eval`. After `(shadow/repl :globo)`, later evals on 3333 stay in CLJS.

## Start missing REPLs

Start automatically. Do not ask.

**1339 down:** from `examples/`, start `bb nrepl` (background, long-lived). Then on port 1339:

```clojure
(start! {:port 3000 :mount-path "/map"})
```

`bb nrepl` does **not** start HTTP. Skip `start!` if the server is already serving :3000 — `start!` resets `server.main/storage` and `sse-clients`.

**3333 down:** from repo root, `npx shadow-cljs watch globo` (background). Do not start a second shadow if 3333 already answers.

## Reload (CLJ): `go!`, not `require :reload`

This project uses [clj-reload](https://github.com/tonsky/clj-reload). On 1339, `user/go!` is `(clj-reload.core/reload)` (`examples/server/dev/user.clj`). It reloads **changed namespaces and their dependents**.

**Do not** `(require '[the.ns] :reload)` for library/server Clojure. That skips dependents and bypasses unload/reload hooks.

After any `.clj` edit (or before evaluating code you just changed):

```clojure
(in-ns 'user)
(go!)
(in-ns 'the.ns.you.are.working.on)
```

- `go!` is the only CLJ reload. Call it after writes, before re-eval or re-running tests.
- If `server.main` is in the reload set, `before-ns-unload` / `after-ns-reload` stop+start HTTP and **wipe** `storage` / `sse-clients`. That is expected. Do not also call `start!`.
- Never `start!` just to “make sure” — that wipes users, messages, map-objects, SSE clients.
- First load of a namespace that is not yet required: `(require '[the.ns])` **without** `:reload`, after `go!`.

CLJS: shadow-cljs watch reloads on save. Force a ns in the 3333 REPL with `(require '[the.ns] :reload)` only if the watch did not pick it up. clj-reload does not apply to `.cljs`.

## Evidence gate (non-negotiable)

Reading code is not verification. **Do not claim a bug is fixed or a feature works unless you show:**

1. A failing check **before** the change (test output or REPL result).
2. The **same** check passing **after**.
3. Quoted tool output, not a paraphrase.

“Looks correct” / “should work” / “I updated the function” is not done.

## TDD (bugs and new behavior only)

Not required for refactors, docs, or mechanical one-liners. Required for bugfixes and new behavior.

Vertical slices only (one test → one implementation). Do not write a pile of tests then all the code.

```
RED:   write one test for one observable behavior
       go! (CLJ) then eval it — it MUST fail for the right reason
GREEN: minimal implementation
       go! then eval the same test — it MUST pass
```

A test written after the implementation is not a regression test. If you cannot get RED first, the test is insensitive — rewrite it.

1339 (bb) does **not** have `test/` on the classpath. During the loop, eval the assertion against the reloaded fn, or run one JVM ns:

```bash
clojure -M:test -n is.galt.globo.server.messages-test
```

CLJS tests: `npx shadow-cljs compile test` (node-test build). Pure helpers live in `test/is/galt/globo/ui/*_test.cljs`.

Then run the relevant full suite before saying done.

## Diagnose by inspecting live state

Do this proactively when debugging or trying a fix. Change one thing, `go!`, re-eval, read the result.

**Server (1339):**

```clojure
(in-ns 'user)
@server.main/storage
@server.main/sse-clients
;; or call process / handlers against a globo wrapping those atoms
```

**UI (3333, after `(shadow/repl :globo)`, browser on :3000):**

```clojure
(require '[re-frame.db :as rfdb])
(require '[re-frame.core :as rf])
(keys @rfdb/app-db)
(get-in @rfdb/app-db [:connection])
(rf/dispatch [::some-event …])
```

**Browser:** chrome-devtools on http://localhost:3000 for console errors, failed fetches/SSE, DOM. If HTTP is down and you just started 1339, you forgot `(start!)`.

## Mandatory REPL loop (every non-trivial change)

Goal: seconds, not minutes.

1. Identify ns + port.
2. CLJ: `(in-ns 'user)` `(go!)` `(in-ns 'that.ns)`. CLJS: confirm `:globo` REPL, require if needed.
3. Eval the function or a minimal repro (nil / empty / invalid too).
4. Bugs/features: RED test before the edit.
5. Edit (prefer structural clojure-mcp edits for sexps).
6. CLJ: `go!`, re-eval, confirm GREEN.
7. Suite before done.

If you have not evaluated it, it does not exist.
