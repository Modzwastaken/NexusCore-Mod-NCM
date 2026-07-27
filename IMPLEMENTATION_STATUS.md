# NexusCore — Implementation Status

The single place where unfinished work is allowed to be named (§2.4). Source files carry no
`TODO`, `FIXME`, "coming soon", or `UnsupportedOperationException`; the stub-marker gate
fails the build if they do.

**Status vocabulary (§20.4) — exactly one per requirement:**

| Status | Meaning |
|---|---|
| `planned` | Scheduled for a named milestone. No code. |
| `in progress` | Code exists. **Not yet compiled**, or compiled but incomplete. |
| `implemented` | Code exists **and compiles**. No passing test cited yet. |
| `tested` | A passing test exists and **its name is cited here**. |
| `tested (manual)` | Observed working by a person, with who observed it and when recorded here. **Weaker than `tested`**: nothing re-checks it on the next build, so it can silently stop being true. Introduced at 1.1.0 for the human testing, because calling an observation `tested` would blur exactly the line §20.4 exists to draw. |
| `blocked` | Cannot proceed. The specific obstacle is named here. |
| `intentionally excluded` | Out of scope, with the reason recorded. |

`implemented` requires code that compiles. `tested` requires a passing test whose name is
cited. A file that looks finished but has never been compiled is `in progress`, however
complete it appears.

**Last updated:** 2026-07-27 · **Version:** 1.1.1 (in progress) ·
**Previous version:** 1.0.0 · **Milestones passed:** M0, M1, M2, M3, M4, M5 (partial)

> **v1.0 does not mean feature complete.** The version scheme (ADR-0007) is the owner's
> release numbering, not the specification's M11 gate. This document, not the version
> number, is the record of what exists.

> **Versions and builds.** Per [ADR-0012](docs/architecture/ADR-0012.md), `x.y.0` is a
> **version** and `x.y.1`–`x.y.5` are its **builds**, each a hotfix or a pre-release; after five
> the minor moves up. `1.0.1` is the security **hotfix** for v1.0.0 — fixes only, branched from
> the `v1.0.0` tag. **`1.1.0` is this version** — the 1.0 line filled at five builds, so the minor
> moved up. Its builds are `1.1.1`–`1.1.5`. What each later build is planned to contain is in
> [The road to v1.5.0](#the-road-to-v150) below and, per §2.4, only there.

---

## The honest summary

NexusCore now does real work: permissions with groups and inheritance, durable JSON storage,
a hash-chained audit log, the full command pipeline, teleport with safety checks, player
utilities, moderation with confirmations, and a working admin GUI.

**Two things are true at once, and both matter:**

1. **242 automated tests pass (236 NeoForge + 3 Fabric + 3 Forge)**, and **CI now proves a cold
   build works** — all three loaders build from a bare checkout on a clean runner, which no local
   run had ever actually demonstrated (NeoGradle restores neoForm outputs from a cache outside
   `build/`, so the decompile step had never run cold here). The whole feature set has also been
   exercised end to end on real dedicated servers for all three loaders, with zero errors.

   **28 of those 242 are the write-ahead journal's and postdate the v1.1.0 release**: they have
   passed locally on all three loaders and have **not** yet run in CI, because the branch carrying
   them is not merged. The journal also has no production caller and so has never run on a real
   server — unlike everything else in this list, it is proven by tests alone.
2. **The mod has now been human-tested on a real dedicated server, by one player.** Reported by
   the project owner at the 1.1.0 release: a real player joined a real dedicated server and
   exercised most of the feature set, including the admin panel rendering to an unmodified vanilla
   client. That closes the largest gap this document has carried since v1.0.

   **It is owner-attested rather than instrumented**, so it is recorded as `tested (manual)` on the
   rows it covers rather than as an automated test with a cited name — the distinction §20.4 draws
   between a passing test and an observation still applies, and this is an observation.

   **A second player has still never existed.** Everything that only manifests on another client is
   therefore untouched: vanish hiding you from someone else's list, a muted player's message not
   reaching others, and multi-player behaviour generally. The 1.1.0 sweep confirmed **four specific
   vanish faults that only appear on another player's client**, none of which single-player testing
   could have surfaced. Those rows stay `implemented`.

---

## M0 — Walking skeleton · **PASSED**

| Requirement | Status | Evidence |
|---|---|---|
| NeoForge MDK, Java 21, UTF-8, pinned versions | `tested` | ADR-0001. Clean build green. |
| `neoforge.mods.toml` as the only metadata file | `tested` | Packaged-JAR inspection; no legacy `mods.toml`. |
| Stub-marker gate | `tested` | Passes clean, **and proven to fail** on an injected `TODO`. |
| Checkstyle formatting + static analysis | `tested` | 0 violations across main and test, **and proven to fail** on an injected violation. |
| Reproducible archives | `tested` | Identical SHA-256 across two independent clean builds, **on all three loaders**. Until 1.0.4 the enforcing `AbstractArchiveTask` block was in `neoforge/build.gradle` only, and two clean Forge builds genuinely produced different jars — so this row was true for one artifact of three. Found by 1.0.4's byte-identity check. |
| Version gate (ADR-0012) | `tested` | `versionLadderCheck`. Passes on every legal shape and **proven to fail nine ways** — see the 1.0.2 evidence table below. |
| CI workflow | `tested` | `.github/workflows/build.yml` at the repository root, building all three loaders as a matrix. **Green on all three** (run 30228708083: fabric 1m17s, forge 1m0s, neoforge 3m5s), with each jar uploaded as an artifact. The first run failed on NeoForge only, at `neoFormTransformSource`, with a `NoSuchFileException` for `ats/accesstransformer.cfg` inside NeoGradle's expanded-zip cache: the workflow ran `clean build` in one invocation while `org.gradle.parallel=true`, so a parallel `clean` deleted that cache — which lives under `build/tmp` — mid-read. Removing `clean` fixed it. |

## M1 — Configuration, messages, lifecycle · **PASSED**

| Requirement | Status | Evidence |
|---|---|---|
| Typed config with `schemaVersion`, validation report, transactional reload | `tested` | `ConfigurationServiceTest` — 10 tests including `outOfRangeValueIsClampedAndReported` (asserts all five required fields) and `failedReloadIsTransactional`. |
| Corrupt config does not crash the server | `tested` | `unreadableConfigFallsBackSafely`; confirmed at runtime. |
| `MessageService`, server-side resolution | `tested` | `MessageCatalogueTest` — every referenced key exists, no dead keys, no positional placeholders. ADR-0003. |
| Complete `en_us.json` | `tested` | `everyReferencedKeyExists` — 94 keys, mechanically enforced. |
| `/nexus reload` | `tested` | Runtime: `configuration reloaded, no problems found`. |
| Structured logging with correlation ids | `implemented` | Every audit record and error log carries one. |
| Safe mode with non-core modules disabled | `tested` | `-Dnexuscore.safemode=true`. Runtime on **all three** loaders: `started 8 module(s), 3 disabled`, core commands working, disabled commands refusing with an explanation, `/nexus system status` degrading, clean shutdown, zero per-tick exceptions. A system property rather than a config key, because a bad `config.json` is one of the things safe mode recovers from. |

## M2 — Storage, identity, audit · **PASSED**

| Requirement | Status | Evidence |
|---|---|---|
| Atomic write protocol (`.tmp` → fsync → `ATOMIC_MOVE`) | `tested` | `StorageTest.noTemporaryFileLeftBehind`, `overwriteKeepsBackup`. |
| Corrupt data preserved, never silently discarded | `tested` | `corruptFileIsQuarantinedNotDiscarded`. |
| `PathSafety` containment | `tested` | `traversalRefused` (4 cases), `absolutePathRefused`, `symlinkEscapeRefused`, `storeRefusesEscape`. |
| `IdentityService`, UUID-first with offline lookup | `implemented` | Exercised at runtime against a seeded offline player. No unit test. |
| `AuditService` with §15.2 fields and hash chaining | `tested` | `AuditServiceTest` — 11 tests including `editedRecordDetected`, `deletedRecordDetected`, `forgedAppendDetected`, `chainContinuesAcrossRestart`. |
| Write-time redaction | `tested` | `sensitiveParametersRedactedAtWriteTime` — IPs, passwords, tokens never reach the file. |
| Schema versions on every document | `implemented` | All six documents carry `schemaVersion`. |
| Write-ahead journal for multi-file transactions | `tested` | `JournalTest` — 28 tests, including `crashMidTransactionIsRepairedByReplay` (three files, killed after the first is applied: the assertion checks the state really is torn before recovering it) and `crashAtEveryPointIsRecoverable` (all four crash points, including the one past the last entry where the work is done but still owed). **Proven to fail two ways**: applying before writing the record instead of after — the write-ahead property itself — fails 9 tests; dropping the already-applied skip that makes replay idempotent fails 4. **This closes the M2 gap.** It has no production caller yet: M6's economy is the intended one, and 1.2.2 builds atomic transfer on it. |
| Migration fixtures from a prior version | `blocked` | There is no prior schema version to migrate from — every document is at v1. Fixtures become meaningful at the first bump. |
| `/nexus doctor storage` | `planned` | M8. `/nexus system status` and `/nexus audit verify` cover part of the ground. |

## M3 — Permissions · **PASSED**

| Requirement | Status | Evidence |
|---|---|---|
| §9 engine with ADR-0004 precedence | `tested` | `PermissionServiceTest` + `PermissionNodeTest` — 30 tests. |
| Default deny | `tested` | `defaultDeny` — `UNDEFINED` is never granted. |
| Exact beats wildcard, longer beats shorter | `tested` | `exactBeatsWildcard`, `longerBeatsShorter`. |
| Deny beats allow at equal specificity | `tested` | `denyBeatsAllowAtEqualSpecificity`. |
| Direct subject beats group | `tested` | `directSubjectBeatsGroup`, `removingDirectNodeRestoresGroupDeny`. |
| Mid-pattern wildcards rejected at construction | `tested` | `midPatternWildcardsRejected` (5 cases). |
| Group inheritance and cycle rejection | `tested` | `inheritanceIsFollowed`, `inheritanceCycleRefused`. |
| Determinism across repeated evaluation | `tested` | `evaluationIsDeterministic` — 50 iterations, total order verified. |
| Bounded cache with invalidation | `tested` | `cacheIsUsedAndBounded`. |
| Explain output | `tested` | Runtime: `/nexus permission check` names pattern, source, and group chain. |
| Operator bootstrap, switchable and visible | `implemented` | Warned at startup, shown in status and GUI, named in explain output. Deny still wins over it. |
| Temporary permissions with durable expiry | `planned` | Punishments have expiry; permission grants do not yet. |
| Context filtering (world, dimension, time) | `planned` | The resolver has no context dimension yet. |
| Import/export schema | `planned` | `permissions.json` is human-readable and hand-editable in the meantime. |
| Single-use recovery file grant | `planned` | Operator bootstrap covers the lockout case for now. |

## M4 — Command framework · **PASSED**

| Requirement | Status | Evidence |
|---|---|---|
| §12.2 nine-step pipeline, single registration path | `tested` | Every command routes through `NexusCommands.run`; verified at runtime for permission, validation, audit, and failure paths. |
| Single `DurationParser` | `tested` | `DurationParserTest` — 40 tests covering the full §12.4 ambiguity matrix. |
| Token-bucket rate limiting | `tested` | `RateLimiterTest` — 8 tests including `trackedSubjectsAreBounded` and `backwardsClockIsSafe`. |
| Signed single-use confirmation tokens | `tested` | `ConfirmationServiceTest` — 13 tests. **A token for one ban cannot authorise another** is tested five ways (actor, action, target, parameters, replay). |
| Confirmation proven at runtime | `tested` | Runtime: prompt → `/nexus confirm` → executed → **reuse refused**. |
| Permission-filtered `/nexus help` | `implemented` | Lists only what the source may use. |
| Alias registration fails softly | `tested` | Runtime: with `overrideVanillaCommands=true` (the default) NexusCore **takes over** `/ban`, `/kick`, `/banlist`, `/pardon`, `/list`, `/tp`, `/teleport`, `/gamemode` and logs each. When a name cannot be taken it falls back to the `n`-prefixed form and logs why; in safe mode the moderation and player aliases are not registered at all, so vanilla's stay in place. |
| No stack trace reaches a player | `implemented` | Pipeline catches everything; the player gets a correlation id. |
| `ModuleManager` | `tested` | `ModuleManagerTest` — 19 tests covering dependency ordering, deterministic tie-breaking, diamonds, cycle and self-cycle rejection, unknown/disabled dependencies, duplicate ids and service types, core-module protection, and reverse-order shutdown including failure. Runtime: `NexusCore started 11 module(s)` on all three loaders. |
| Generated command documentation from descriptors | `tested` | `CommandDocsTest` — 5 tests: the committed `docs/admin/commands.md` must equal what `CommandCatalogue` renders; every node the code checks must have a descriptor; **every descriptor must name a node the code checks**; descriptors must be well-formed; rendering must be deterministic. `/nexus help` renders from the same catalogue. §12.5's drift was real — the old document denied the v1.0 vanilla takeover for two releases. |

## M5 — Teleport, player utilities, moderation · **PARTIAL**

| Requirement | Status | Evidence |
|---|---|---|
| §19.1 safe-destination algorithm | `tested (manual)` | Border, build height, bounded single-chunk load, collision-shape passability, fluid, harmful blocks, solid support. **No GameTest yet.** |
| Warmup with movement cancellation, recheck at commit | `implemented` | Rechecks safety at commit; `/back` recorded only after commit. |
| Teleport cooldown | `implemented` | `cooldownRemainingMillis`, honouring `teleportCooldownSeconds`. |
| Homes, warps, spawn, `/back`, `/tpa` | `implemented` | Durable; limits enforced. No GameTest. |
| Player utilities: heal, feed, fly, god, speed, vanish, info | `tested (manual)` | Exercised by a real player on a dedicated server at 1.1.0, owner-attested. Flight uses NeoForge's `creative_flight` attribute, not the deprecated ability field. **Vanish is the exception**: only its effect on the vanishing player was observable with one player, and the 1.1.0 sweep confirmed four faults that appear only on *another* player's client. |
| Moderation: kick, ban, tempban, unban, mute, unmute, warn, warnings | `tested` | `ModerationServiceTest` — 14 tests including `temporaryBanExpiresAtBoundary` and `liftingKeepsHistory`. Exercised at runtime. |
| Punishment expiry re-evaluated at login | `tested (manual)` | Wired to `PlayerLoggedInEvent`. **Observed with a real player** on a dedicated server at the 1.1.0 release, owner-attested. No automated test. |
| Chat muting | `implemented` | Wired to `ServerChatEvent`. The muted player seeing the notice was observed at 1.1.0; **that a muted message fails to reach *others* has not been**, because it needs a second player. |
| Restart persistence | `tested` | Runtime: second boot loaded 27 audit records, warnings, groups, and punishments intact; chain continued to 31. |
| **GameTests** | `planned` | **M5's exit condition is not met.** GameTests for teleport safety, home/warp persistence, punishment enforcement, and permission gating do not exist. |
| **Manual test: run for real players for a week** | `planned` | **Partially done.** A real player exercised most of the feature set on a dedicated server at 1.1.0. The criterion as written asks for *players*, plural, over a week — neither the second player nor the duration has happened, and the four confirmed vanish faults are precisely what a second player would surface. |

## Admin GUI — ahead of schedule

Built as a **vanilla chest menu**, which works on unmodified clients. The specification
defers the GUI to M9 because custom screens need a client mod; §6.1 permits a vanilla
container menu, so the panel is usable in the server-only release rather than waiting.

| Requirement | Status | Evidence |
|---|---|---|
| Dashboard, player list, per-player actions, moderation, permissions, server pages | `tested (manual)` | `AdminGuiService`. **Rendered to a real vanilla client** on a dedicated server at 1.1.0, owner-attested. |
| Permission re-checked on every click | `implemented` | `AdminGuiService.guard`, called by every handler. |
| Read-only container — items cannot enter or leave | `implemented` | `AdminMenu.clicked` never calls `super`; `quickMoveStack` returns empty. **No test.** |
| Destructive action behind a confirmation naming the target | `implemented` | Kick confirmation, token bound to that player. |
| Every action audited with `via: gui` | `implemented` | |
| Every panel has a command equivalent | `implemented` | Named on the tiles themselves. |
| Pagination | `implemented` | Bounded by `adminGuiPageSize`, capped at 45. |

---

## Confirmed defects awaiting a fix

**Updated at 1.1.0** by two adversarial sweeps — eight lenses then six. Together they raised 120
candidates, refuted 34, and confirmed the rest. What was fixed in 1.1.0 is recorded in
`CHANGELOG.md` rather than here: the `/teleport` deletion, safe mode stripping vanilla moderation
commands, the `/nexus reload` NPE, `/nexus help` claiming vanilla's names, the takeover gated on the
wrong setting, four wrong descriptors, the GUI's missing crash barrier, the `/execute as` permission
borrow (**with the sign and lectern confused-deputy paths it also closed**), fail-open permission
values, and `permissions.json` never reloading.

**Two counts here were reconciled on 2026-07-27, and one gap is recorded rather than closed.**
This paragraph previously asserted that **twelve** were fixed. Its own enumeration does not
reproduce that number under either reading — nine items if "four wrong descriptors" is one entry,
thirteen if it is four — and the `[1.1.0]` changelog entry does not resolve it either, because
several fixes are grouped under one bullet and two sit under the wrong heading. The count has been
removed rather than replaced with a different unverifiable one; `CHANGELOG.md` is the record.

The `120 candidates / 34 refuted` above is the **aggregate of both sweeps**. The 1.1.0 row in "The
road to v1.5.0" reports `41 / 17 / 24` for the stability sweep alone, which is consistent with this
total but only if the split is known — and **the per-sweep breakdown of the other sweep was not
kept**. It cannot be recovered from the repository. Neither figure is published on any external
surface, and neither should be until somebody who ran the sweeps reconstructs it.

Two of the three items previously listed here as the `1.1.1` priorities are now fixed. The
remaining one heads the list below.

| Defect | Severity | Detail |
|---|---|---|
| **`IdentityService.resolve()` blocks the server thread on a Mojang HTTP lookup** | **high** | It falls back to `GameProfileCache.get(String)`, which performs a network call. Reachable by any ordinary player via `/seen <name>` with an unknown name, so a slow or unreachable Mojang API stalls the whole server for as long as the request takes. A non-blocking `getAsync` exists and its executor lives for the whole server lifetime — the first item for `1.1.1`. |
| Vanish desynchronises the client's player list | medium | Removing the staff member's `PlayerInfo` makes their chat render as a red chat-validation error for everyone else; un-vanishing does not restore the entity for clients that received `AddEntity` while vanished; vanish is not re-applied to players who join later; and the vanished set survives death while the invisibility flag does not. Four related faults in one mechanism. Needs a real client to confirm each. |
| Multiple active punishments of the same kind | medium | A second ban or mute does not deactivate the first. `/unban` lifts one and reports success while the player stays banned; `activeBans()` double-counts; `activeRecord()` returns the last-issued match rather than the strictest. `ModerationService:108,150,205`. |
| `audit.log` never rotates | medium | Fully read into heap and SHA-256'd on the **server thread** at startup, at shutdown, and on every `verify` and `tail`. Unbounded growth plus a synchronous full read. |
| `config.json` `defaultGroup` is ignored at startup | low | The permissions module reads only `permissionCacheSize` from settings, so a `defaultGroup` set in `config.json` takes effect only after some later mutation rewrites the permissions document. |
| `/ban` in safe mode stages a confirmation that can never complete | low | The prompt is issued before the module check, and confirming spends the token with no audit record. |
| `/nexus permission set` cannot express a wildcard | low | `StringArgumentType.word()` rejects `*`, so the one permission form operators most need to grant cannot be typed. |
| Teleport warmup does not re-check permission at commit | low | The class documents a §19.1 step-5 permission re-check that does not exist; only destination safety is rechecked. |
| `setHome` leaks an empty entry when refused | low | The per-owner map is inserted before the limit check, so refused `/sethome` calls leave permanent empty entries in `homes.json`. |
| `JsonStore.read()` quarantines on any `IOException` | medium | A transient read error moves an intact `permissions.json` aside and the next boot starts from defaults. |
| `config.json` silently loses operator keys | medium | `load()` rewrites the file from the typed object, deleting any key the schema does not know, while reporting `no problems found`. |
| `players.json` rewritten in full on every login and logout | medium | Never pruned; each write copies a full `.bak` and fsyncs twice. |
| Fabric death messages lose their cause | medium | Every styled death reads `<Player> died` on Fabric only. |
| `/pardon` and `/banlist` strand vanilla ban state | medium | Pre-takeover vanilla bans become un-liftable, and `ban-ip`/`pardon-ip` are separate commands NexusCore never takes over, so IP bans are neither audited nor listed. |
| `/nexus reload` silently ignores two settings | low | `commandsPerMinute` and `permissionCacheSize` keep boot-time values while the reload reports success. `commandsPerMinute` is a rate limit, so a security control does not apply. |
| Admin GUI acts on a stale `ServerPlayer` | low | A handler captured before the target logged out performs a no-op and audits it as `allowed`. |
| Death-message edge cases | low | Reload double-broadcasts until restart; the dying player's own screen loses its cause; team `deathMessageVisibility` is ignored. |
| `DurationParser.format()` returns empty below one second | low | `describeRemaining()` yields `""` rather than a time. |
| `/nexus permission check` bypasses `authorise()` | low | It calls the evaluator directly, so it can never show the operator-bootstrap grant — the command whose job is explaining a decision under-reports who can do what. |
| Gson I/O errors escape the write protocol | low | For documents larger than the writer buffer, Gson wraps the failure in `JsonIOException`, bypassing `catch (IOException)` and leaving the `.tmp`. |
| One non-UTF-8 byte in `audit.log` prevents startup | low | No recovery path inside the mod. |
| Fabric login ban screen uses a startup snapshot | low | `/nexus reload` never changes it, unlike the other two loaders. |

---

## Defects found by actually running it

Both were found on a real dedicated server, not in review. Neither would have been caught by
the test suite.

| Defect | Detail | Resolution |
|---|---|---|
| `/tpa` requests never expired on a quiet server | The expiry sweep sat after an early `return` that only ran when a teleport warmup was pending. With nothing pending, a request stayed acceptable indefinitely. | Expiry now runs every tick, before the pending check. |
| Permission node with no command | `nexuscore.command.teleport.tp` was granted to moderators and used by the GUI, but no `/tp` command existed. | `/nexus teleport tp` and `/tphere` added. |
| `Feedback.broadcastToOperators` was a dead field | Always false, never settable — admin actions succeeded silently. | Wired to `broadcast()`; access and position changes now surface to other operators. |
| Services did not exist when commands registered | `RegisterCommandsEvent` fires on a datapack **worker thread before** `ServerAboutToStartEvent` fires on the server thread. The guard reported `no commands were registered` rather than registering a broken tree. | Services now build in the mod constructor from `FMLPaths.GAMEDIR`, which precedes every event. The ordering constraint is removed rather than satisfied. |
| `/ban`, `/kick`, `/banlist` silently resolved to vanilla | The alias code correctly refused to override vanilla (§12.3) — but that left an operator typing `/ban` getting vanilla's ban, with no duration, audit, or history, and no indication anything was different. | NexusCore now registers a collision-free `n`-prefixed alias (`/nban`) whenever a name is taken, logs which ones, and `docs/admin/commands.md` leads with the explanation. |

Also closed before release: `adminGuiEnabled` and `teleportCooldownSeconds` were config keys
that nothing read. A setting with no effect is a lie in a config file; both are now honoured.

---

## Not started

**M6 Economy** · **M7 Moderation depth and chat** (jail, reports, notes, PMs, channels,
anti-spam, SmartGuard) · **M8 Automation, backups, diagnostics, benchmarks** ·
**M9 Client mod and networking** · **M10 Custom GUI screens, themes, accessibility** ·
**M11 Public API, full documentation, release candidate**.

M6 to M8 are the contents of v1.5.0 and are scheduled on the ladder below. M9 to M11 are not
in v1.5.0 and have no rung.

---

## The road to v1.5.0

Per [ADR-0012](docs/architecture/ADR-0012.md): **`x.y.0` is a version, `x.y.1`–`x.y.5` are its
builds** (each a hotfix or a pre-release), and after five builds the minor moves up. The route to
v1.5.0 is that sequence run out:

```
1.0.0  released      1.0.1 … 1.0.5      →  1.1.0   1.1.1 … 1.1.5
   →  1.2.0   1.2.1 … 1.2.5   →  1.3.0   1.3.1 … 1.3.5
   →  1.4.0   1.4.1 … 1.4.5   →  1.5.0
```

Each build is built and tested on all three loaders before the next begins. A build is not
"done" because its code exists, but because it meets the exit condition named here.

**The ordering is deliberate.** M6's economy needs the write-ahead journal (§11.1) that M2 left
`planned` — this document already called that "the first thing M6's economy will need", and it is
now `tested`, built ahead of its slot for the reason recorded under 1.1.4. Generated
command documentation needs the `ModuleManager` that M4 left `planned`. Those are two separate
dependencies on two different deliverables, so the sequence closes the M4 and M5 gaps first, then
adds features.

**Everything below except 1.0.1 and 1.0.2 is `planned` in the §20.4 sense: scheduled, no code.**
A build's contents are a plan, not a promise, and this table is rewritten as reality arrives
rather than defended.

The **Exit condition** column is load-bearing: `RELEASE_CHECKLIST.md` gates each build on it, so
a cell must name something checkable. Where a criterion has not been decided yet, the cell says
so rather than being left blank.

**Five builds per version is a floor, not a ceiling on the work.** If a version's work needs more
than five builds it moves up early and carries the remainder — recorded when it happens. The
sequence is open-ended, so nothing has to be crushed to fit.

### Version 1.0 — security, and the foundations

| Build | Contents | Exit condition |
|---|---|---|
| **1.0.1** · *hotfix* | The v1.0.0 security patch: `/teleport` bypass, `/execute as` root, `/gamemode` wildcard escalation, audit short write, missing `core.confirm` grant. | **Met.** Three loaders build, 178 tests; packaged jars verified on all three dedicated servers — `/execute as` refused, `/teleport` taken over, audit chain intact. Branched from the `v1.0.0` tag. |
| **1.0.2** · *pre-release* | The version scheme (ADR-0012) and `versionLadderCheck`. No runtime change. | **Met.** Gate proven to pass on four legal shapes and to fail nine ways; three loaders build clean at 1.0.2. |
| **1.0.3** · *pre-release* | `ModuleManager` (§7.3). Services move from explicit `NexusServices` wiring to the module contract. | **Met.** All 11 services resolve through the registry; `NexusCore started 11 module(s)` on all three real servers with zero errors; the 178 pre-existing tests pass unchanged alongside 19 new ones. |
| **1.0.4** · *pre-release* | Shared sources moved out of `neoforge/src/` into `common/`. The layout wart ADR-0008 documents but does not fix. **Met.** Also fixed: Forge/Fabric archive reproducibility, and `MessageCatalogueTest`'s single-source-root assumption. | All three loaders build from `common/`, and **the same version built from both layouts produces byte-identical jars** — compared by rebuilding the new layout at `-Pmod_version=1.0.3`, since a 1.0.4 jar legitimately differs from a 1.0.3 one in its expanded metadata. |
| **1.0.5** · *pre-release* | Safe mode with non-core modules disabled (M1) and generated command documentation from descriptors (M4). **Met.** Also fixed: three mangled Fabric string literals shipped in 1.0.3/1.0.4, the test blindness that let them ship, and `/nexus system status` refusing in safe mode. | `docs/admin/commands.md` is generated, not hand-maintained, with a test asserting it matches the registry; a server started in safe mode disables its non-core modules and says so. **M4 complete.** |

### Version 1.1 — M5 completion and the storage foundation M6 needs

| Build | Contents | Exit condition |
|---|---|---|
| **1.1.0** · *version* | The M4-complete milestone, rolled up from the 1.0 builds, plus seven fixes from the stability sweep. | **Met.** **214 tests** (208 NeoForge + 3 Fabric + 3 Forge), 0 failures, 0 errors, 0 skipped — counted from the JUnit XML of a `--rerun-tasks` run on 2026-07-27, not carried forward; three loaders build reproducibly; **six runtime runs** — normal and safe mode on each loader — with zero errors, which with the two targeted runs (the substituted-source refusal and the permissions reload) are the **eight** `CHANGELOG.md` counts. A whole-mod sweep raised 41 candidates, refuted 17, confirmed 24; seven fixed here, the rest recorded above. Those four figures describe **the stability sweep only** — the aggregate across both 1.1.0 sweeps is in "Confirmed defects awaiting a fix". |
| **1.1.1** | `IdentityService.resolve()` blocking the server thread on a Mojang lookup (`getAsync` exists), the four vanish faults, and the multiple-active-punishments group — the highest-severity items left after 1.1.0's twelve fixes. | Each has a named regression test. The vanish faults need a real client, so any that cannot be reproduced headlessly are recorded as such rather than ticked. |
| **1.1.2** | GameTests: teleport safety, home/warp persistence, punishment enforcement, permission gating. Plus the missing `AdminMenu` read-only container test. | The four GameTests named in M5 exist and pass, and a test proves items cannot enter or leave the admin menu. |
| **1.1.3** | Sustained multi-player runtime verification — GUI rendering, vanish (four confirmed faults), chat muting, ban-at-login, teleport safety in practice. | Every item in the "never observed" column has been observed with at least two real players, or is recorded as still unobserved with the reason. |
| **1.1.4** | Write-ahead journal for multi-file transactions (§11.1). The M2 gap, and M6's prerequisite. | **Met, ahead of its slot.** `JournalTest` crashes a three-file transaction after the first file lands, asserts the on-disk state is genuinely torn, and proves replay repairs it; all four crash points are covered, and the write-ahead property is proven to fail. Three loaders build. **The code landed during 1.1.1, not 1.1.4** — see below. |
| **1.1.5** | Migration fixtures and the schema-bump machinery. The week-long real-player run that is M5's actual exit criterion. | A 1.0.0 data directory loads through the migration path with exact expected results; a week of real-player use with no unrecorded defect. **M5 complete.** |

> **1.1.4's deliverable was built during 1.1.1, out of order.** The owner directed it: v1.2.0's
> shape was still being decided, and the journal is a prerequisite under every candidate design,
> so starting it could not be wasted work whichever way that decision went. It is recorded here
> rather than by quietly renumbering the table, because the table's value is that it says what
> actually happened — and because "1.1.4 is done" and "the journal is done" are not the same claim.
> **No 1.1.4 build has been cut.** The journal ships in whichever build is cut next, and what
> 1.1.4's slot now holds is the owner's call, not this document's.

### Version 1.2 — M6 economy

| Build | Contents | Exit condition |
|---|---|---|
| **1.2.0** · *version* | The M5-complete milestone. | 1.1.x verified together; the unverified-claims register reflects what the real-player run established. |
| **1.2.1** | Currency core: fixed-point integer minor units. | No `float` or `double` anywhere in currency code, asserted by a test that scans the sources — not by review. |
| **1.2.2** | Atomic transfer and idempotency keys, on 1.1.4's journal. | Debit and credit commit in one transaction boundary; an idempotency replay test proves one key yields exactly one committed transaction. |
| **1.2.3** | Reversal as a compensating entry. | A reversal creates a new entry and no historical row is edited, asserted by a test. |
| **1.2.4** | Shops. | A shop purchase is all-or-nothing under an injected mid-transaction failure. |
| **1.2.5** | Economy commands and operator documentation. | All six §10.2 invariants have passing **named** tests; balances and history survive restart. **M6 complete.** |

### Version 1.3 — M7 chat and moderation depth

| Build | Contents | Exit condition |
|---|---|---|
| **1.3.0** · *version* | The M6-complete milestone. | 1.2.x verified together. |
| **1.3.1** | Chat channels. | Routing delivers only to subscribers, with a named test. |
| **1.3.2** | Private messages. | Delivery reaches only the recipient; **no chat path bypasses the existing mute check**, asserted for channels and private messages both. |
| **1.3.3** | Anti-spam. | The limit is configurable and enforced, with a boundary test. |
| **1.3.4** | Jail. | Survives restart and relog, and a jailed player cannot leave by any teleport path — `/home`, `/warp`, `/spawn`, `/back`, `/tpa`. |
| **1.3.5** | Reports and notes. | Both persist across restart and appear in the audit chain. **M7 complete except SmartGuard, excluded below.** |

### Version 1.4 — M8 automation, diagnostics, benchmarks

| Build | Contents | Exit condition |
|---|---|---|
| **1.4.0** · *version* | The M7-complete milestone. | 1.3.x verified together. |
| **1.4.1** | Durable scheduler. | Survives restart with correct missed-run behaviour, with a named test. |
| **1.4.2** | Backups, and restore with a dry run. | The dry run validates manifest, checksum and schema before applying; restore proven against a deliberately corrupted backup. |
| **1.4.3** | Diagnostics: `/nexus doctor`, `/nexus doctor storage`, support bundle. | Both doctor commands distinguish a seeded fault from a clean system; the support bundle contains no secrets, verified by test. |
| **1.4.4** | Benchmark harness (§16.2) and measured budget numbers. | Harness produces JSON and a committed baseline, and a local run compares against it. **The unverified-claims register below is closed — every number in it measured or withdrawn.** CI comparison is *not* part of this: the M0 table records the CI workflow as never executed with no remote configured. |
| **1.4.5** | Release candidate: documentation sweep, the confirmed-defect backlog above, and the full three-loader matrix. | `RELEASE_CHECKLIST.md` sections 0–7a and 8 all pass on the candidate. **M8 complete.** |

### 1.5.0 — the feature release

| Build | Contents | Exit condition |
|---|---|---|
| **1.5.0** · *version* | Release ceremony only. No code that was not already in 1.4.5. | `RELEASE_CHECKLIST.md` sections 0–7a and 8 signed off, in full. Sections 9–11 (M9, M10, M11) are **NOT MET by design** — v2.0 scope — and the sign-off records them that way, which is what the checklist's own "recorded as NOT MET with the specific reason" rule requires. The same applies to the four M3 items and SmartGuard excluded below. |

### Not on this ladder

Named here so they are not mistaken for oversights. Each is `planned` with no rung assigned:

| Item | Milestone | Why not in v1.5.0 |
|---|---|---|
| SmartGuard | M7 | Named in the M7 line above, and deliberately not scheduled. The M7 rungs deliver chat and moderation depth; SmartGuard is a separate body of work, nothing in M8 depends on it, and folding it into 1.0.10 would make that rung unbounded. This is why 1.0.10 claims M7 complete **except SmartGuard** rather than M7 complete. |
| Temporary permission grants with durable expiry | M3 | Punishments have expiry; permission grants do not. Wanted, but nothing in M6–M8 depends on it. |
| Permission context filtering (world, dimension, time) | M3 | The resolver has no context dimension. A structural change to the evaluator, and ADR-0004's precedence rules would need reopening. |
| Permission import/export schema | M3 | `permissions.json` is hand-editable in the meantime. |
| Single-use recovery file grant | M3 | Operator bootstrap covers the lockout case. |

If any of these is wanted in v1.5.0, it gets a rung; it does not get quietly folded into
another one.

### 1.0.2 — what was actually verified

The rung that establishes the ladder is the wrong one to take on trust, so it was run rather
than reasoned about.

| Check | Status | Evidence |
|---|---|---|
| All three loaders build clean at 1.0.2 | `tested` | `clean build` green in `neoforge/`, `fabric/`, `forge/`. Artifacts `NexusCore-<loader>-1.0.1-1.21.1.jar`. |
| Version reaches packaged metadata, not just filenames | `tested` | `version = "1.0.1"` in `neoforge.mods.toml`, `mods.toml`, and `fabric.mod.json`, all token-expanded from the one `mod_version`. Zero jar entries still naming 1.0.0. |
| `versionLadderCheck` **fails** when it should | `tested` | Nine injected violations under the ADR-0012 rules, each rejected with its own message: malformed version (`-Pmod_version=1.0`); whitespace-padded `mod_version` (`1.0.2 `); sixth build (`1.0.6` — "move up to 1.1.0"); in-range version with no changelog entry (`1.2.3`); heading only inside a fenced code block (`1.0.3`); version whose heading calls it a hotfix (`1.1.0`); build with no label (`1.1.2`); build labelled **both** hotfix and pre-release (`1.1.3`); rebuild at an already-archived version (`1.0.0`). |
| `versionLadderCheck` **passes** when it should | `tested` | Every legal shape: `1.0.2` (build, pre-release), `1.1.0` (version), `1.1.5` (build, hotfix), `1.5.0` (version). Temporary changelog headings were used and `CHANGELOG.md` confirmed byte-identical afterwards. |
| Heading lookup cannot collide on a prefix | `tested` | A `## [1.0.10]` heading was temporarily inserted above `## [1.0.1]` and the gate run at both versions: each resolved to its own heading, because the `]` is part of the search prefix. **The rationale for this test is now gone** — ADR-0010 caps the build counter at 5 and the minor at 9, so no two-digit component can occur. Kept because the check costs nothing and the collision would be silent, not because the ladder still reaches 1.0.13. |
| Heading inside a fenced code block is not an entry | `tested` | A `## [1.0.2]` heading added inside a ```` ``` ```` block was rejected: `No changelog entry for 1.0.2`. Reverted; the changelog already contains fenced blocks, so this is a shape the file will grow into. |
| `/nexus version` reports the build | `tested` | Runtime, **both** NeoForge and Fabric: `NexusCore Administration Framework version 1.0.1`. The string comes from loader metadata, so no source file names a version. |
| `/nexus system status` header | `tested` | Runtime, **Fabric only**: `NEXUS » Status — v1.0.1`. The command was not run on the NeoForge server, whose session was `/nexus version` and `/nexus audit verify` only. |
| A 1.0.0 data directory opens in 1.0.1 | `tested` | Runtime, both loaders: started against data written by 1.0.0, `no problems found`, `7 audit record(s)` read, no NexusCore error, clean shutdown. |
| Audit chain survives the version change | `tested` | Runtime, both loaders: `/nexus audit verify` → `Audit chain intact: 8 record(s) verified`, over a chain whose records carry both `1.0.0` and `1.0.1`. |
| Forge runtime | `tested` | **First ever Forge runtime verification.** Packaged jar on the real Forge 52.1.16 server: started clean, `/nexus version` → 1.0.1, `/teleport` taken over, `/execute as` refused, `Audit chain intact: 9 record(s)`, clean shutdown, zero NexusCore errors. The 1.0.0 dev-run limitation is **resolved** — `runClient` reaches a world with the mod loaded and `runServer` reaches ready state; see `forge/CHANGELOG.md`. |

**Observed, not predicted:** NeoForge emits
`The following mods have version differences that were not resolved: nexuscore (version 1.0.0 -> 1.0.1)`
when loading a world last saved by another version. Benign — the world loads and everything
works — but it will recur on every rung. ADR-0009 records why it is not suppressed.

**Also true:** `config.json` is restamped on load (`generatedByVersion` 1.0.0 → 1.0.1) through
the atomic protocol, keeping its `.bak`. `schemaVersion` is untouched at 1. No code anywhere
branches on the mod version — it is recorded and displayed, never compared — which is why a
version-scheme change needs no migration.

---

## Intentionally excluded

| Item | Reason |
|---|---|
| Mixins | ADR-0002. Zero mixins; supported NeoForge APIs cover every requirement so far. |
| `nexuscore-server.toml` (NeoForge `ModConfigSpec`) | ADR-0006. One JSON configuration system instead of one TOML plus ten JSON. |
| Managing third-party mods from inside Minecraft · replacing an observability platform · circumventing Mojang authentication · ML-based moderation · unconditionally overriding vanilla commands | §3.5. |
| Required dependency on LuckPerms, Vault, Essentials, PlaceholderAPI, WorldEdit, WorldGuard, or any economy/chat/database plugin | §8.1. Every capability is native. The JAR embeds nothing. |

---

## Unverified claims register

No performance number in this repository is measured. The §16.2 benchmark harness is an M8
deliverable and does not exist.

| Claim | Status |
|---|---|
| Idle tick cost under 0.10 ms | **target, not measured** |
| Active tick cost under 1.0 ms mean / 2.0 ms p95 | **target, not measured** |
| Supported player count | **not claimed anywhere**, and must not be until measured |
| Behaviour with more than one player online | **never observed** — the 1.1.0 human testing was a single player on a dedicated server. Everything that only manifests on a second client is unverified, including the four confirmed vanish faults |
