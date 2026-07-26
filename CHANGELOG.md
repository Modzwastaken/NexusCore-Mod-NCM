# Changelog

All notable changes to NexusCore are recorded here.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versioning is
`v1.0` → `v1.5` → `v2.0`, per [ADR-0007](docs/architecture/ADR-0007.md). Minor releases add
features and stay data-compatible; major releases may change data formats and must ship a
migration. **The version number carries no completeness promise** — see
[IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md) for what actually exists.

## [1.0.0] — 2026-07-26 — first public release: NeoForge, Fabric, and Forge

The v1.0 release ships all three loaders at once. Earlier interim tags were collapsed into
this release; nothing had been published externally, so no released version is being
rewritten.

### NexusCore now owns the vanilla commands it supersedes

`/ban` `/kick` `/banlist` `/pardon` `/list` `/tp` `/gamemode` are NexusCore's. Previously
they stayed vanilla and NexusCore's versions were only reachable as `/nban`, `/nkick` and so
on — an operator typing the command they had always typed silently got vanilla behaviour with
no duration, no audit entry, no styled screen and no appeal line.

- `/tp` and `/gamemode` are **rebuilt from vanilla's own argument types**, so `@a`,
  `@e[type=…]`, absolute/relative/local coordinates (`~ ~5 ~`, `^ ^ ^3`), rotation and
  `facing <location|entity [anchor]>` all still work. A structural test asserts every branch
  of that grammar survives, because losing one would not fail a compile.
- Staff teleports deliberately skip the safe-destination search: typed coordinates mean those
  coordinates. `/home`, `/warp`, `/spawn`, `/back` and `/tpa` still run the full safety check.
- Set `overrideVanillaCommands=false` to leave vanilla in charge; NexusCore's versions then
  stay on the `/n`-prefixed names.
- Takeover uses reflection on Brigadier's private child maps because it has no removal API.
  If that ever fails, NexusCore logs why and falls back to `/n`-prefixed names rather than
  half-replacing a command.

### Styled death messages

Vanilla's death broadcast is replaced with NexusCore's styled one on all three loaders. The
wording still comes from vanilla's combat tracker, so causes, mob names and item names stay
correct and translated. This turns off the `showDeathMessages` game rule to avoid sending
each death twice, which is announced at startup with the exact command to undo it. Controlled
by `styleDeathMessages`.

### Message overhaul

Every player-facing message was restyled into a consistent visual language:

- Feature-prefixed chat lines: a bold coloured tag, an arrow, then the body —
  `BAN » Banned Deathcember for 2h.: "Toxic behavior"`. Gray body text, white names,
  orange times, yellow dates.
- Full-screen ban pages: `⚠ YOU ARE TEMPORARILY BANNED ⚠`, who was banned and when
  (`17/04/2026 17:58`), the quoted reason, the unban date with a live countdown
  (`in 1h. 59min. 59sec.`), and the configurable appeal line.
- Long time forms everywhere a player reads them (`1h. 28min. 1sec.`), while commands still
  accept the compact input form (`1d12h30m`). `TimeText` renders output; `DurationParser`
  parses input; the two are deliberately separate.
- The admin panel's ban tooltips match: `Ban · Active`, the quoted reason, then
  `→ Staff / → Date / → Duration / ⌛ Expires in` detail lines.
- The ban screen is now built in exactly one place (`PunishmentMessages`) for all commands
  and all three loaders' login enforcement, so the loaders cannot drift apart.
- Operators can still reword everything via `nexuscore/messages.json` overrides.


### Added
- **Fabric build** — `NexusCore-fabric-1.0.0-1.21.1.jar`. Requires Fabric Loader 0.19.3+ and
  Fabric API.
- **Forge build** — `NexusCore-forge-1.0.0-1.21.1.jar`. Requires MinecraftForge 52.1.16+.
- ADR-0008 recording the multi-loader architecture: why a compiled `common` subproject is
  impossible (proven — Fabric jars are remapped to intermediary, so the same source yields
  different bytecode), and why Forge must stay on Gradle 8 while the others use 9.2.1.
- Regression tests per loader: `FabricEntrypointTest`, `ForgeMetadataTest`.
- Shared `MayflyFlightController`, used by both Fabric and Forge.
- A dedicated test server per loader.

### Fixed
- **Fabric did nothing in singleplayer or on a LAN world.** The mod declared a `server`
  entrypoint and implemented `DedicatedServerModInitializer`, which fires only on a
  *dedicated* server. It loaded, listed itself in the mod list, and was completely silent:
  no commands, no config directory, not one log line. Now uses `ModInitializer` with a
  `main` entrypoint, verified on a real client loading a singleplayer world.
- **Fabric `/back` did not return a player to where they died**, while NeoForge did.
  Registered `ServerLivingEntityEvents.AFTER_DEATH`.
- **Forge rejected the jar outright** with `Missing required field mandatory in dependency`:
  the dependency blocks used NeoForge's `type = "required"` spelling. Forge requires
  `mandatory = true`.

### Changed
- Repository restructured: the git root moved up to `NexusCore/`, with `neoforge/`, `fabric/`
  and `forge/` as sibling builds. History preserved via rename detection.
- Artifact naming is now `NexusCore-<loader>-<version>-<mcVersion>.jar`.
- Server logs are no longer tracked by git.

### Known loader difference
- `/fly` uses NeoForge's additive `creative_flight` attribute, but a single `mayfly` flag on
  Fabric and Forge, neither of which has an equivalent attribute. On those loaders the last
  mod to write the flag wins. The active mechanism is logged at startup.

### Earlier in v1.0 — feature completion on NeoForge

#### Added — v1.0 polish

- `/nexus teleport tp <player> [destination]` and `/tphere` — staff teleports, no warmup or
  cooldown. The permission node existed and was granted to moderators, but no command
  reached it.
- `/seen <player>` — last-seen and first-joined, from NexusCore's own identity records.
- `/list` — online players, vanish-aware.
- `/near [radius]` — nearby players in the same world, vanish-aware.
- `/nexus permission set <player> <node> allow|deny` and `/nexus permission unset` — the
  service supported direct nodes; nothing exposed them.
- `/nexus audit tail [count]` — recent audit activity without reading the file.
- **`/back` now returns you to where you died.** A death is exactly the case where no
  teleport happened, so there would otherwise be no return point.
- `DurationParser.describeElapsed` for "how long ago" rendering.
- Admin actions that change another player's access or position are now surfaced to other
  operators, rather than succeeding silently.

### Fixed — v1.0

- **`/tpa` requests never expired on a quiet server.** The expiry sweep sat behind an early
  return that only ran when a teleport warmup was pending, so with no pending teleports a
  request stayed acceptable indefinitely.
- `/seen` computed elapsed time through an obscure arithmetic trick; replaced with a tested
  helper.

### Added — the v1.0 feature set

**Configuration and messages (M1)**

- JSON settings at `<server>/nexuscore/config.json` with `schemaVersion`, range validation,
  and a validation report naming file, key, invalid value, expected form, and fallback.
- Transactional `/nexus reload`: a bad file leaves the running configuration untouched.
- `MessageService` resolving keys **server-side** so unmodified vanilla clients see real text
  rather than raw translation keys (ADR-0003). 94 keys, operator-overridable via
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

- 178 tests, 0 failures (172 shared + 3 Fabric + 3 Forge). Checkstyle clean. Stub-marker gate clean.
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

### Added — foundation

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
