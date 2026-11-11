package app.xreader.data

/**
 * v1.5.2 loop 2 (DELTA 3): the live-smoke marker. Classes
 * annotated `@Category(LiveSmoke::class)` hit the REAL
 * network; the build excludes them from the deterministic
 * gate by default and runs them only in the smoke set
 * (`-PliveSmoke`). Site downtime must neither fail nor pass
 * the wiring gate — the two sets run and report separately.
 */
interface LiveSmoke
