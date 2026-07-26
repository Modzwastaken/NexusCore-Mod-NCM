# NexusCore — shared changelog

Changes to the **shared code** — the 22 of 24 source files every loader compiles. Loader-specific
changes live alongside each build:

| Loader | Changelog |
|---|---|
| NeoForge | [neoforge/CHANGELOG.md](neoforge/CHANGELOG.md) |
| Fabric | [fabric/CHANGELOG.md](fabric/CHANGELOG.md) |
| Forge | [forge/CHANGELOG.md](forge/CHANGELOG.md) |

User-facing summaries of major releases across all loaders are in
[release-notes.md](release-notes.md). Released builds are kept in [archived/](archived/).

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versioning is
`v1.0` → `v1.5` → `v2.0`, per [ADR-0007](docs/architecture/ADR-0007.md). Minor releases add
features and stay data-compatible; major releases may change data formats and must ship a
migration. **The version number carries no completeness promise** — see
[IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md) for what actually exists.

Per [ADR-0012](docs/architecture/ADR-0012.md): **`x.y.0` is a version** — `1.0.0`, `1.1.0`,
`1.2.0` … — and **`x.y.1` through `x.y.5` are its builds**, each a *hotfix* or a *pre-release*.
Five builds fill a version, then the minor moves up: `1.0.5` is followed by `1.1.0`, never by
`1.0.6`. Every heading below says which kind it is.

## [1.0.3] — 2026-07-26 — pre-release — ModuleManager

The §7.3 module contract, which M4 left `planned`. No behaviour change: every service starts in
the same order, with the same settings, and the 178 existing tests pass untouched.

### Added

- **`ModuleManager`, `NexusModule`, `ModuleContext`** — services are now registered as modules and
  resolved from a registry instead of being constructed by hand. A module declares its id, whether
  it is `core`, and what it depends on; the manager topologically orders them, rejects dependency
  cycles and missing dependencies, and stops them in reverse order.

- **`NexusBootstrap`** — one shared place that registers the eleven standard modules and starts
  them. **This removes a triplication that was a real defect risk**: `buildServices()` existed
  verbatim in all three loader entry points, differing only in the flight controller, so any
  change to service wiring had to be made three times or the loaders would silently diverge in
  behaviour that no test compares.

- **`NexusPlatform`** — the two things that genuinely differ per loader (the data root and the
  flight controller) behind one interface, so the bootstrap itself is loader-agnostic.

- **`ModuleManagerTest`** — 19 tests: dependency ordering (registered deliberately in the wrong
  order), deterministic tie-breaking over 20 runs, diamonds starting a shared dependency once,
  cycle and self-cycle rejection, unknown and disabled dependencies, duplicate ids, duplicate
  service types, refusal to disable a core module, reverse-order shutdown, and shutdown
  continuing after one module throws.

### Verified

- **11 modules start on all three loaders.** Packaged jars on the real NeoForge 21.1.235, Fabric
  and MinecraftForge 52.1.16 dedicated servers: `NexusCore started 11 module(s)`, `/nexus version`
  reports 1.0.3, the `/execute as` refusal still fires, audit chain intact, clean shutdown, zero
  NexusCore errors. **197 tests pass** — the 178 that existed plus the 19 new ones.

### Changed

- Modules are classified `core` or optional, which is what M1's safe mode needs to have anything
  to disable. **Nothing is disabled yet** — that is 1.0.5, and it needs command gating too, or a
  disabled module leaves commands pointing at a service that was never started. `ModuleManager`
  therefore throws a named `ModuleException` for an unstarted module rather than returning null,
  so a premature attempt fails loudly instead of surfacing as an NPE in a command handler.

- `NexusServices` keeps its accessor API unchanged — it is now a view over the registry. Its
  twelve-argument constructor is retained because the tests build services directly, and forcing
  a registry into a unit test would make the tests worse to prove a point about production wiring.

## [1.0.2] — 2026-07-26 — pre-release — the version scheme and its gate

No runtime change. This build settles how NexusCore is numbered and adds the gate that keeps the
numbering honest. `/nexus version` reports `1.0.2`; nothing else behaves differently.

### Added

- **[ADR-0012](docs/architecture/ADR-0012.md) — the version scheme.** `x.y.0` is a **version**;
  `x.y.1` through `x.y.5` are its **builds**, each a hotfix or a pre-release; `x.y.6` does not
  exist. Five builds fill a version and the minor moves up:

  ```
  1.0.0  version (released)
    1.0.1 … 1.0.5   builds
  1.1.0  version
    1.1.1 … 1.1.5   builds
  1.2.0  version …
  ```

  Every minor behaves identically — there is no special minor and no minor without a `.0`. The
  road to v1.5.0 is simply this sequence run out to `1.4.5`, then `1.5.0`.

- **`versionLadderCheck`** — a `check` gate in the version-owning NeoForge project. It fails the
  build when:

  - `mod_version` is not exactly `MAJOR.MINOR.PATCH`, **including surrounding whitespace**. The
    raw property is validated, never a trimmed copy — Gradle does not trim it either, so
    `mod_version=1.0.2 ` would otherwise pass the gate and then produce
    `NexusCore-neoforge-1.0.2 -1.21.1.jar` and a `fabric.mod.json` that Fabric's semantic-version
    parser rejects.
  - the version has no entry in this changelog, or the only match sits inside a fenced code
    block. This changelog documents the scheme and will grow examples of headings; a quoted
    sample is not an entry.
  - the third component exceeds 5 — the minor should have moved up.
  - a build's heading does not say `hotfix` or `pre-release`, or says **both**, or a version's
    heading claims to be one of them.
  - the version is already present in `archived/`, which is immutable by rule, so building at one
    means the bump was forgotten.

  Proven to fail on every one of those, not merely to pass — see the evidence table in
  [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md).

- **"The road to v1.5.0"** in [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md) — what each
  build is planned to contain. It lives there rather than in a new `ROADMAP.md` because §2.4
  gives that document sole authority over naming unfinished work, and a second location would
  drift from the first.

### Changed

- **Three earlier attempts at this scheme are retired.** ADR-0009 introduced a build counter,
  ADR-0010 added five-rung lines with `.5` as a pre-release, and ADR-0011 split minors into
  release lines and development lines to make room for a hotfix. Each solved a problem the
  previous one created, and together they made `1.1.0` an *illegal* version string. ADR-0012
  supersedes ADR-0010 and ADR-0011 outright and states the whole rule in one place. The ADRs
  stay in the tree as the record of how the scheme was arrived at.

- **`1.0.0.1` and `1.0.0-1` were tested against the real parsers and rejected.** Maven's
  `DefaultArtifactVersion` reads `1.0.0.1` as `major=0` — it supports three numeric components,
  not four — and Fabric's `VersionParser` sorts `1.0.0-1` **below** the release it fixes, because
  semver reads `-1` as a pre-release identifier. That is the worst possible failure for a hotfix.
  Recorded in ADR-0011, and the reason `1.0.1` is the hotfix number.

- **`RELEASE_CHECKLIST.md`** keys its ceremonies to *build* and *version*, and gains a missing M7
  section — the document ran M6 straight into M8, so a milestone v1.5.0 contains had no coverage
  at all. Stale `v0.1`/`v0.5` labels retired by ADR-0007 are corrected, as is an artifact
  filename pattern predating the three-loader split.

- **`THIRD_PARTY_NOTICES.md`** described a single `NexusCore-<version>.jar` and dated itself "as
  of version 0.1.0 (M0)" — an artifact shape retired by ADR-0008 and a version label retired by
  ADR-0007. It now names the three-loader pattern and adds the two build tools missing from its
  table. Still nothing is embedded in any jar.

### Fixed

- **The Forge dev-run limitation was resolved and nobody noticed.** `runClient` and `runServer`
  were documented as failing with `constructed 0 mods`; both work. The `resourcesDir` merge
  written at 1.0.0 as an attempted fix *was* the fix, the limitation notice was left beside it,
  and the dev tasks were never re-run. The note outlived the bug by a whole release — which is
  the argument for re-testing documented limitations rather than copying them forward.

### Compatibility

No data format changed. Every `schemaVersion` is unchanged, and no code branches on the mod
version — it is recorded (`generatedByVersion` in `config.json`, `nexuscore_version` on every
audit record) and displayed, never compared.

Loading an older data directory restamps `config.json` through the usual atomic protocol, keeping
the `.bak`, and the audit chain spans both versions in one continuous hash chain. Both verified
by running the packaged jar against a `1.0.0` data directory on real servers.

## [1.0.1] — 2026-07-26 — hotfix — security fixes

**Fixes only. No new features, no data-format change.** Everyone running v1.0.0 should take
this build. It contains none of the in-progress v1.5 work — that is the entire reason the
patch lane in [ADR-0011](docs/architecture/ADR-0011.md) exists.

### Fixed

- **`/teleport` bypassed NexusCore completely.** Vanilla registers `teleport` as the real
  command and `tp` as a *redirect* to it. NexusCore took over only `tp`, so `/teleport` kept
  running as pure vanilla — **no permission check, no rate limit and no audit record** — while
  every document claimed NexusCore owned the command. Both names are now taken over.

  `ban-ip` and `pardon-ip` are separate vanilla commands for the same reason and are **still
  not covered**; they remain pure vanilla and are recorded as a known gap rather than fixed
  here, because covering them is new behaviour rather than a fix.

- **Any non-player command source was granted root.** Authorisation read
  `CommandSourceStack.getEntity()`, so anything that was not a `ServerPlayer` was treated as
  the console and given **root**. NexusCore's commands carry no vanilla `requires()`, so its own
  check is the only gate — which meant `/execute as @e[type=armor_stand] run nexus …` ran with
  full privileges, and `operatorBootstrap=false` constrained nobody.

  A source presenting a **non-player entity is now refused outright**, which closes the
  `/execute as` path, and a source with no entity is treated as the console only if it holds
  permission level 4.

  The obvious fix — read `CommandSourceStack.source`, the real issuer, which `/execute as` does
  not change — was written first and **had to be abandoned**: that field is private in vanilla
  and public only under NeoForge's access transformer, so it compiled on NeoForge and Forge and
  failed on Fabric. `getPlayer()` and `isPlayer()` are no help either, since both read `entity`.
  Permission level is the only portable discriminator: console and RCON run at 4, command blocks
  and datapack functions at 2.

  **Residual limitation:** a refused attempt made by a level-4 operator through `/execute as` is
  still *attributed* to `CONSOLE` in the audit record, because the real issuer cannot be
  recovered without that private field. The attempt is refused and recorded — the escalation is
  closed — but the name on the denial is wrong. Verified at runtime, and not fixable portably
  today.

  **Behaviour change:** command blocks and datapack functions are no longer privileged. Command
  blocks previously inherited root, which handed administration to anything that could power a
  block — on a server with `enable-command-block=true`, any level-2 builder.

  **Datapack functions are collateral, and that is a real cost.** A function's source is the
  server itself, so unlike a command block it is genuinely server-owner-controlled and was not
  an escalation. But a function and a command block are indistinguishable through public API —
  both present no entity at permission level 2 — and the only accessor that would separate them,
  `CommandSourceStack.source`, is private in vanilla and public only under NeoForge's access
  transformer, so shared code cannot read it. Denying both is the only option that closes the
  command-block hole. A datapack that drove NexusCore commands will stop working; run them from
  the console or `server.properties` startup instead.

- **A short write silently truncated an audit record.** `JsonStore.appendLine` ignored
  `FileChannel.write`'s return value, so a partial write corrupted the hash chain and still
  reported success — in the one file whose purpose is being tamper-evident. The write now loops
  until the buffer is drained and fails loudly if it stalls.

- **Every moderator could give themselves creative mode.** The `/gamemode` takeover checked
  `nexuscore.command.player.gamemode`, and the shipped `moderator` group is granted
  `nexuscore.command.player.*`. So the moment v1.0 took ownership of `/gamemode`, every
  moderator gained an ability that had previously required vanilla operator level 2 — a
  privilege escalation introduced by the takeover itself, not inherited from vanilla.

  The node moved to `nexuscore.command.staff.gamemode`, which `player.*` does not match.
  **Moving the node rather than editing the shipped group is deliberate:** default groups are
  only written when `permissions.json` is absent, so editing them would fix new installs and
  leave every existing server exposed indefinitely. Moving the node fixes both.

- **Nobody below `admin` could confirm a destructive action.**
  `nexuscore.command.core.confirm` was granted to no group, so a moderator could open a
  permanent ban and then be unable to complete it. It is now a `default` grant, which is safe
  because the confirmation token is the real authorisation — bound to actor, action, target and
  parameters, and single-use.

  **This one does need operator action on an existing server**, for the reason above: your
  `permissions.json` already exists, so the new default will not appear in it. Run
  `/nexus permission group add default nexuscore.command.core.confirm`, or add the node by hand.

### Known, not fixed here

`ban-ip` / `pardon-ip` are not taken over. A moderator can still issue an IP ban that NexusCore
neither audits nor shows in `/banlist`. Scheduled on the ladder, not in this patch.

Several further defects were found in the same sweep and are **not** in this patch, because
fixing them is behaviour change rather than repair: `/unban` and `/unmute` lift only one of
several active records, so a second ban leaves the player banned while the command reports
success; `activeBans()` double-counts; `/nexus reload` silently ignores `commandsPerMinute`.
All are recorded in `IMPLEMENTATION_STATUS.md` and scheduled on the ladder.

## [1.0.0] — 2026-07-26 — first public release: NeoForge, Fabric, and Forge

The v1.0 release ships all three loaders at once. Earlier interim tags were collapsed into
this release; nothing had been published externally, so no released version is being
rewritten.

### Mod icon

The NexusCore logo now ships in all three jars at 128x128 and is wired into each loader's
metadata: `logoFile` in `neoforge.mods.toml` and `mods.toml`, `icon` in `fabric.mod.json`.
The same image is also `pack.png`, so it appears as the resource-pack icon rather than the
blank default. It is shown in the README too.

### Resource pack metadata

Added `pack.mcmeta`. A mod jar containing `assets/` is loaded as a resource pack, and Forge
validates it with vanilla's strict codec, which requires `description` and `pack_format`.
NeoForge and Fabric never complained because NeoForge substitutes a lenient
`OPTIONAL_FORMAT` codec that makes both fields optional — so the missing file only surfaced on
Forge, as a dismissible error screen.

A mod jar is read as **both** a resource pack (format 34 on 1.21.1) and a data pack (format
48), which one `pack_format` cannot satisfy, so the file declares
`supported_formats: {min_inclusive: 34, max_inclusive: 48}` to cover both. Formats taken from
Minecraft's own `DetectedVersion`, not from memory.


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
