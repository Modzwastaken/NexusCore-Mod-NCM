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

Versions shaped `x.y.N` where `N` is non-zero are **not releases**, per
[ADR-0009](docs/architecture/ADR-0009.md) and [ADR-0010](docs/architecture/ADR-0010.md). They may
add features. `x.y.1`–`x.y.4` are internal **development builds**; **`x.y.5` is a pre-release**,
archived and handed to testers; only `x.y.0` — on a `.0` or `.5` minor — is a release.

## [1.0.1] — 2026-07-26 — patch release — security fixes

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

## [1.1.1] — 2026-07-26 — development build — the ladder to v1.5.0

First rung of the road to v1.5.0. This build contains no runtime change: it establishes how
the work between here and v1.5.0 gets numbered, and adds the gate that keeps the numbering
honest. `/nexus version` reports `1.0.1`; nothing else about the mod behaves differently.

### Added

- **[ADR-0009](docs/architecture/ADR-0009.md) — the 1.0.x development build ladder.** The
  third version component is now a build counter rather than a semantic-versioning patch
  level. `x.y.0` is a release; `x.y.N` is a numbered development build on the road to the next
  one. v1.5.0 is reached through `1.0.1`, `1.0.2`, … rather than as a single unnumbered jump
  in which nothing intermediate is installable or testable.

  The ADR records what the scheme costs as well as what it buys: `1.0.1` reads as a patch
  release to anything parsing the version as semver and is not one, and there is no hotfix
  lane for `1.0.0` while the ladder is climbing. Neither is hidden.

- **[ADR-0010](docs/architecture/ADR-0010.md) — five-rung lines, and `.5` is a pre-release.**
  A minor line holds exactly five rungs: `.1`–`.4` are internal development builds, **`.5` is a
  pre-release** that gets archived and handed to testers, and the minor then rolls. `1.0.5` is
  followed by `1.1.1`; `1.1.0` never exists, because releases land only on the `.0` and `.5`
  minors (ADR-0007).

  So the road to v1.5.0 is lines of five. **ADR-0011, in the same build, reduced that to four
  lines** — `1.1.x`, `1.2.x`, `1.3.x`, `1.4.x` — because `1.0.x` became the published patch line
  for release v1.0. Twenty rungs and four pre-releases, landing exactly on `1.5.0`.

  This replaces ADR-0009's unbounded counter and its hand-maintained ✅ "milestone snapshot"
  flag. Which builds get handed out is now a property of the version number rather than a
  column in a table that nothing checked. The term *snapshot* is retired in favour of
  *pre-release* throughout.

- **`versionLadderCheck`** — a new `check` gate in the version-owning NeoForge project. It
  fails the build when:

  - `mod_version` is not exactly `MAJOR.MINOR.PATCH`, **including surrounding whitespace**. The
    raw property is validated, never a trimmed copy — Gradle does not trim it either, so
    `mod_version=1.0.1 ` would otherwise pass the gate and then produce
    `NexusCore-neoforge-1.0.1 -1.21.1.jar` and a `fabric.mod.json` that Fabric's
    semantic-version parser rejects.
  - the version has no entry in this changelog. This is the drift that would otherwise happen
    on every rung.
  - the heading carries the wrong label, in any direction. It is three-valued: `development
    build` for `.1`–`.4`, `pre-release` for `.5`, neither for a release. Checking only that the
    right label is *present* would let a pre-release also call itself a development build, and a
    one-way check would let `1.5.0` ship labelled either. The label is ADR-0009's only
    mitigation for the semver ambiguity, so it is enforced in every direction.
  - the version is already present in `archived/`. Archived builds are immutable by rule, so
    building at one means the bump was forgotten — the symmetric failure to bump-without-entry.
  - the version is outside its line (ADR-0010): a sixth rung such as `1.0.6`, or a `.0` on a
    minor that is not a release point such as `1.2.0`. Both look entirely ordinary and neither
    exists in this scheme, so each is rejected with what to write instead.

  Headings inside fenced code blocks do not count as entries, since this changelog documents
  the scheme and will grow examples of headings. The report is written on failure as well as
  success, matching `stubMarkerCheck`, so a report on disk always describes the run that
  produced it.

  Proven to fail **ten** distinct ways and to pass three, not merely to pass — see the evidence
  table in [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md).

- **"The road to v1.5.0"** in [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md) — what each
  build on the ladder is planned to contain, and which ones are pre-releases. It
  lives there rather than in a new `ROADMAP.md` because §2.4 gives that document sole
  authority over naming unfinished work, and a second location would drift from the first.

### Changed

- **ADR-0007 is amended, not superseded.** Its release ladder, its compatibility promise and
  its refusal to let the version number imply completeness all stand. The single line reading
  "**PATCH** (`1.0.0` → `1.0.1`) fixes defects only" no longer holds, and says so in place.

- **`RELEASE_CHECKLIST.md` separates the two ceremonies.** A development build runs a short
  gate; an `x.y.5` pre-release adds six items; `1.5.0` runs the full checklist. The stale
  references to the retired `v0.1` and `v0.5` labels — which ADR-0007 removed and the checklist
  still carried — are corrected, as is an artifact filename pattern predating the three-loader
  split.

- **`RELEASE_CHECKLIST.md` gains a missing M7 section.** The document ran M6 straight into M8,
  so M7 — which v1.5.0 contains — had no checklist coverage at all. Found while checking that
  every rung's exit condition had somewhere to be signed off against. Numbered `7a` to avoid
  renumbering sections that are referenced by number. Its CI item and section 8's are marked
  NOT MET with the reason, rather than left as boxes that cannot be ticked while no git remote
  exists.

- **`archived/` now holds two kinds of thing**, and its README says which is which: releases,
  as before, and `x.y.5` pre-releases. Both are immutable; only one was ever downloaded by the
  public.

- **`THIRD_PARTY_NOTICES.md` corrected.** It still described a single `NexusCore-<version>.jar`
  and declared its position "as of version 0.1.0 (M0)" — an artifact shape retired by ADR-0008
  and a version label retired by ADR-0007. It now names the three-loader artifact pattern,
  lists all three loaders as compiled-against-not-bundled, and adds the two build tools missing
  from its table (Fabric Loom, ForgeGradle). Still nothing is embedded in any jar. Found while
  sweeping for stale version references, not reported.

### Compatibility

No data format changed. Every `schemaVersion` is unchanged, and no code anywhere branches on
the mod version — it is recorded (`generatedByVersion` in `config.json`, `nexuscore_version` on
every audit record) and displayed, never compared. Data compatibility is governed by
`schemaVersion` alone, which is why the version scheme can be changed without touching a
migration.

Two things a `1.0.0` data directory does see, both benign and both worth stating rather than
discovering:

- `config.json` is **restamped**, not left untouched: loading re-writes `generatedByVersion`
  from `1.0.0` to `1.0.1` through the usual atomic protocol, keeping the `.bak`. No other key
  changes.
- The audit chain **spans both versions**. Records written before the bump carry
  `nexuscore_version: "1.0.0"` and records after carry `1.0.1`, in one continuous hash chain.
  That is the field doing the job it was added for, and verification is unaffected — the hash
  covers each record's own content.

Per ADR-0009 the data-compatibility promise applies at every rung of the ladder, not only at
`1.5.0`.

**Both were verified by running the packaged jar, not by reading the code.** The `1.0.1` jar
was installed on the NeoForge and Fabric test servers over data directories written by
`1.0.0`. Both started clean with no NexusCore error, read the existing seven-record audit
chain, and `/nexus audit verify` reported `chain intact` across a chain now spanning both
versions. `/nexus version` reports `1.0.1` on both. `/nexus system status` was run on Fabric
only, where the status header reads `v1.0.1`. Both shut down cleanly.

### Known

- **NeoForge warns once per rung.** Loading a build into a world last saved by a different
  version produces `The following mods have version differences that were not resolved:
  nexuscore (version 1.0.0 -> 1.0.1)`. The world loads and everything works; NexusCore
  registers no `DisplayTest` extension point to resolve the difference, deliberately, because
  suppressing it would also suppress it at `2.0.0` where it will matter. See ADR-0009.
- **All three loaders were verified at runtime for this build**, servers and dev clients. Forge
  included — it had never been runtime-tested before, and its 1.0.0 dev-run limitation turns out
  to have been fixed at 1.0.0 and never re-tested. See `forge/CHANGELOG.md`.

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
