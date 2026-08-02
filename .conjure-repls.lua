return {
  { pattern = "src/is/galt/.*%.clj$",  state = "clj",  port = 1339 },
  { pattern = "examples/server/.*%.clj$",  state = "clj",  port = 1339 },
  { pattern = "src/is/galt/.*%.cljs$", state = "cljs", port = 3333, shadow_build = "globo" },
}
