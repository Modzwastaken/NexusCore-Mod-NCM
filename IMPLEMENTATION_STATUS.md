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
| `blocked` | Cannot proceed. The specific obstacle is named here. |
| `intentionally excluded` | Out of scope, with the reason recorded. |

`implemented` requires code that compiles. `tested` requires a passing test whose name is
cited. A file that looks finished but has never been compiled is `in progress`, however
complete it appears.

**Last updated:** 2026-07-26 · **Version:** 1.0.0 (first public release) ·
**Milestones passed:** M0, M1, M2, M3, M4, M5 (partial)

> **v1.0 does not mean feature complete.** The version scheme (ADR-0007) is the owner's
> release numbering, not the specification's M11 gate. This document, not the version
> number, is the record of what exists.

---

## The honest summary

NexusCore now does real work: permissions with groups and inheritance, durable JSON storage,
a hash-chained audit log, the full command pipeline, teleport with safety checks, player
utilities, moderation with confirmations, and a working admin GUI.

**Two things are true at once, and both matter:**

1. **178 automated tests pass (172 NeoForge + 3 Fabric + 3 Forge)**, and the whole feature set has been exercised end to end on a
   real NeoForge 21.1.235 dedicated server with zero errors, including a restart.
2. **No human player has ever joined this server.** Every runtime check was driven through
   the console. Anything that only happens with a real player in the world —
   teleport safety in practice, the admin GUI actually rendering, vanish, chat muting, ban
   enforcement at login — is `implemented`, not `tested`. That distinction is the whole point
   of §20.4 and it is not being blurred here.

---

## M0 — Walking skeleton · **PASSED**

| Requirement | Status | Evidence |
|---|---|---|
| NeoForge MDK, Java 21, UTF-8, pinned versions | `tested` | ADR-0001. Clean build green. |
| `neoforge.mods.toml` as the only metadata file | `tested` | Packaged-JAR inspection; no legacy `mods.toml`. |
| Stub-marker gate | `tested` | Passes clean, **and proven to fail** on an injected `TODO`. |
| Checkstyle formatting + static analysis | `tested` | 0 violations across main and test, **and proven to fail** on an injected violation. |
| Reproducible archives | `tested` | Identical SHA-256 across two independent clean builds. |
| CI workflow | `implemented` | `.github/workflows/build.yml`. **Never executed** — no git remote configured. |

## M1 — Configuration, messages, lifecycle · **PASSED**

| Requirement | Status | Evidence |
|---|---|---|
| Typed config with `schemaVersion`, validation report, transactional reload | `tested` | `ConfigurationServiceTest` — 10 tests including `outOfRangeValueIsClampedAndReported` (asserts all five required fields) and `failedReloadIsTransactional`. |
| Corrupt config does not crash the server | `tested` | `unreadableConfigFallsBackSafely`; confirmed at runtime. |
| `MessageService`, server-side resolution | `tested` | `MessageCatalogueTest` — every referenced key exists, no dead keys, no positional placeholders. ADR-0003. |
| Complete `en_us.json` | `tested` | `everyReferencedKeyExists` — 94 keys, mechanically enforced. |
| `/nexus reload` | `tested` | Runtime: `configuration reloaded, no problems found`. |
| Structured logging with correlation ids | `implemented` | Every audit record and error log carries one. |
| Safe mode with non-core modules disabled | `planned` | Needs `ModuleManager`; there are no optional modules yet to disable. |

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
| Write-ahead journal for multi-file transactions | `planned` | Single-document atomic replace is implemented and tested. No operation yet spans two files atomically, so the journal has no caller. **This is a real gap against §11.1** and is the first thing M6's economy will need. |
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
| Alias registration fails softly | `tested` | Runtime: `/ban`, `/kick`, `/banlist` are vanilla; NexusCore registered `/nban`, `/nkick`, `/nbanlist` instead and logged why. |
| No stack trace reaches a player | `implemented` | Pipeline catches everything; the player gets a correlation id. |
| `ModuleManager` | `planned` | M4 scope not met. Services are wired explicitly through `NexusServices`, which is honest for the current size but is not the module contract of §7.3. |
| Generated command documentation from descriptors | `planned` | `docs/admin/commands.md` is **hand-maintained**, which §12.5 warns will drift. Generating it needs a descriptor registry, which needs `ModuleManager`. |

## M5 — Teleport, player utilities, moderation · **PARTIAL**

| Requirement | Status | Evidence |
|---|---|---|
| §19.1 safe-destination algorithm | `implemented` | Border, build height, bounded single-chunk load, collision-shape passability, fluid, harmful blocks, solid support. **No GameTest yet.** |
| Warmup with movement cancellation, recheck at commit | `implemented` | Rechecks safety at commit; `/back` recorded only after commit. |
| Teleport cooldown | `implemented` | `cooldownRemainingMillis`, honouring `teleportCooldownSeconds`. |
| Homes, warps, spawn, `/back`, `/tpa` | `implemented` | Durable; limits enforced. No GameTest. |
| Player utilities: heal, feed, fly, god, speed, vanish, info | `implemented` | Flight uses NeoForge's `creative_flight` attribute, not the deprecated ability field. |
| Moderation: kick, ban, tempban, unban, mute, unmute, warn, warnings | `tested` | `ModerationServiceTest` — 14 tests including `temporaryBanExpiresAtBoundary` and `liftingKeepsHistory`. Exercised at runtime. |
| Punishment expiry re-evaluated at login | `implemented` | Wired to `PlayerLoggedInEvent`. **Never observed with a real player.** |
| Chat muting | `implemented` | Wired to `ServerChatEvent`. **Never observed with a real player.** |
| Restart persistence | `tested` | Runtime: second boot loaded 27 audit records, warnings, groups, and punishments intact; chain continued to 31. |
| **GameTests** | `planned` | **M5's exit condition is not met.** GameTests for teleport safety, home/warp persistence, punishment enforcement, and permission gating do not exist. |
| **Manual test: run for real players for a week** | `planned` | **Not done.** This is M5's actual exit criterion. |

## Admin GUI — ahead of schedule

Built as a **vanilla chest menu**, which works on unmodified clients. The specification
defers the GUI to M9 because custom screens need a client mod; §6.1 permits a vanilla
container menu, so the panel is usable in the server-only release rather than waiting.

| Requirement | Status | Evidence |
|---|---|---|
| Dashboard, player list, per-player actions, moderation, permissions, server pages | `implemented` | `AdminGuiService`. **Never rendered to a real client.** |
| Permission re-checked on every click | `implemented` | `AdminGuiService.guard`, called by every handler. |
| Read-only container — items cannot enter or leave | `implemented` | `AdminMenu.clicked` never calls `super`; `quickMoveStack` returns empty. **No test.** |
| Destructive action behind a confirmation naming the target | `implemented` | Kick confirmation, token bound to that player. |
| Every action audited with `via: gui` | `implemented` | |
| Every panel has a command equivalent | `implemented` | Named on the tiles themselves. |
| Pagination | `implemented` | Bounded by `adminGuiPageSize`, capped at 45. |

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
| Behaviour with more than one player online | **never observed** — every runtime test was single-session, console-driven |
