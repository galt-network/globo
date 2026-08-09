This is a new session on the Clojure project.

Please do the following at the start:
1. Read and internalize the full PROJECT_SUMMARY.md file from the project root. Use it as the primary source of truth for project structure, key files, namespaces, and architecture.
2. Also read LLM_CODE_STYLE.md and follow those coding preferences.

Tooling:
- Native read/grep/glob/edit/write are the default for all work.
- Use clojure-mcp tools for Clojure-specific work: `clojure_eval` for REPL-driven development and verification (always `:reload`, switch into the namespace you are working on), `clojure_edit` / `clojure_edit_replace_sexp` for structural edits (whole top-level forms, balanced-sexp rewrites).
- Verify code in the REPL before finishing. Run tests via `clojure -M:test` (server) or `npx shadow-cljs compile test` (UI).

Acknowledge that you have loaded the context, then wait for my task.
