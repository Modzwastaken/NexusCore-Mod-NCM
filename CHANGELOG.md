# Changelog

All notable changes to NexusCore are recorded here.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versioning follows
the release ladder in the master build prompt §3.4: `0.1.0` → `0.5.0` → `1.0.0`. Pre-1.0
releases may break data formats **only** with a written migration. From 1.0.0, semantic
versioning applies against the public API.

## [Unreleased]

### Added — M1 to M5: the first genuinely usable release

**Configuration and messages (M1)**

- JSON settings at `<server>/nexuscore/config.json` with `schemaVersion`, range validation,
  and a validation report naming file, key, invalid value, expected form, and fallback.
- Transactional `/nexus reload`: a bad file leaves the running configuration untouched.
- `MessageService` resolving keys **server-side** so unmodified vanilla clients see real text
  rather than raw translation keys (ADR-0003). 63 keys, operator-overridable via
  `messages.json`, with `&` colour codes.

**Storage, identity, audit (M2)**

- `JsonStore`: atomic replace (`.tmp` → fsync → `ATOMIC_MOVE`), `.bak` retention, and
  quarantine-on-corruption — unreadable data is preserved and reported, never discarded.
- `PathSafety`: real-path containment that catches symlink escapes, not just `..` strings.
- `IdentityService`: UUID-first, with offline name lookup and name history.
- `AuditService`: append-only, SHA-256 hash-chained, with **write-time** redaction of IPs,
  passwords, and tokens. `/nexus audit verify` names the first broken link.

**Permissions (M3)**

- The full ADR-0004 engine: specificity scoring, direct-beats-group, deny-beats-allow, group
  weight, and a lexicographic tie-break that makes the order total and evaluation
  reproducible.
- Groups with multiple inheritance and cycle rejection. Bounded LRU decision cache.
- Mid-pattern wildcards rejected at construction.
- `/nexus permission check` **explains** a decision — matched pattern, source, group chain.
- Operator bootstrap: switchable, warned at startup, named in explain output, and overridden
  by an explicit deny.

**Command framework (M4)**

- The §12.2 nine-step pipeline as the single registration path.
- One `DurationParser` for the whole project.
- Per-player token-bucket rate limiting, bounded against UUID flooding.
- Single-use confirmation tokens bound to actor, action, target, and parameter hash.
- Aliases that fail softly, and register a collision-free `n`-prefixed form when a name is
  already owned.

**Teleport, players, moderation (M5)**

- §19.1 safe-destination search: border, build height, bounded single-chunk load, collision
  shape, fluids, harmful blocks, solid support. Refuses with a specific reason rather than
  dropping a player somewhere "close enough".
- Warmup with movement cancellation, safety re-checked at commit, cooldown.
- Homes, warps, spawn, `/back`, `/tpa`.
- heal, feed, fly, god, speed, vanish, playerinfo.
- kick, ban (confirmation required), tempban, unban, mute, unmute, warn, warnings, banlist.
  Punishments are never deleted — lifting stamps who and when.

**Admin GUI — ahead of schedule**

- A **vanilla chest-menu** admin panel that works on unmodified clients, rather than waiting
  for the M9 client mod. Dashboard, paginated player list, per-player actions, moderation and
  permission overviews, server diagnostics.
- Permission re-checked on every click. Read-only container: no code path moves an item.

**Quality**

- 160 tests, 0 failures. Checkstyle clean. Stub-marker gate clean.
- `MessageCatalogueTest` mechanically enforces §2.3 part 6 — a player-facing string without a
  catalogue key fails the build.
- ADR-0006 records the JSON-configuration decision.

### Fixed

- Services were built on `ServerAboutToStartEvent`, but `RegisterCommandsEvent` fires earlier,
  on a worker thread — the command tree would have been built against services that did not
  exist. Found on a real server. Services now build in the mod constructor.
- `/ban`, `/kick`, and `/banlist` silently resolved to vanilla's versions. NexusCore now
  registers `/nban`, `/nkick`, `/nbanlist` and says so at startup.
- `adminGuiEnabled` and `teleportCooldownSeconds` were config keys nothing read.

### Known limitations

- **No GameTests.** M5's exit condition is not met.
- **No real player has ever joined.** Every runtime check was console-driven. The GUI has
  never been rendered to a client; vanish, chat muting, and ban-at-login are wired but
  unobserved.
- No `ModuleManager`; command documentation is hand-maintained and may drift.
- No write-ahead journal for multi-file transactions.
- No economy, chat, scheduler, backups, or benchmark harness (M6–M8).
- Every performance figure in the specification remains a **target, not a measurement**.

### Added — M0, walking skeleton

- NeoForge MDK project for Minecraft 1.21.1, Java 21 toolchain, UTF-8 compilation.
- Pinned platform versions: NeoForge `21.1.235`, supported range `[21.1.235,)`, NeoGradle
  userdev `7.1.38`, Gradle wrapper `9.2.1`. Verified against the NeoForged Maven metadata on
  2026-07-25 and recorded in ADR-0001.
- `@Mod` entry point `com.mwtstudios.nexuscore.NexusCore`, bootstrap only.
- Exactly one command: `/nexus version`.
- `META-INF/neoforge.mods.toml` as the only metadata file, with all versions expanded from
  `gradle.properties` so metadata cannot drift from the compiled versions.
- Stub-marker gate (`stubMarkerCheck`) failing the build on `TODO`, `FIXME`, "coming soon",
  or `UnsupportedOperationException` in `src/main/java`.
- Checkstyle 10.21.1 as the combined formatting and static-analysis gate, zero tolerance.
- GitHub Actions CI running the full `build`.
- Reproducible archives: stable entry order, no embedded timestamps.
- Sources and Javadoc JARs.
- ADR-0001 (pinned versions), ADR-0002 (zero mixins), ADR-0003 (server-side message
  resolution), ADR-0004 (permission precedence), ADR-0005 (quality-gate tooling).
- `IMPLEMENTATION_STATUS.md` and `RELEASE_CHECKLIST.md`.

### Notes

- No product logic exists yet. `IMPLEMENTATION_STATUS.md` is the authoritative record.
- Performance figures in the specification are **targets, not measurements**, until the
  benchmark harness lands at M8.
