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

**Last updated:** 2026-07-26 · **Version:** 1.0.1 (patch release) ·
**Last feature release:** 1.0.0 · **Milestones passed:** M0, M1, M2, M3, M4, M5 (partial)

> **v1.0 does not mean feature complete.** The version scheme (ADR-0007) is the owner's
> release numbering, not the specification's M11 gate. This document, not the version
> number, is the record of what exists.

> **1.0.1 is a published patch release, and it is fixes only.** It closes two confirmed
> critical defects in v1.0.0 — `/teleport` bypassing NexusCore entirely, and every non-player
> command source being granted root — plus a silent audit-log truncation. It carries **none**
> of the in-progress v1.5 work. Per [ADR-0011](docs/architecture/ADR-0011.md), `1.0.x` is the
> patch line for release v1.0; development toward v1.5.0 happens in `1.1.x`–`1.4.x`, planned
> in [The road to v1.5.0](#the-road-to-v150) below and, per §2.4, only there.

---

## The honest summary

NexusCore now does real work: permissions with groups and inheritance, durable JSON storage,
a hash-chained audit log, the full command pipeline, teleport with safety checks, player
utilities, moderation with confirmations, and a working admin GUI.

**Two things are true at once, and both matter:**

1. **178 automated tests pass (172 NeoForge + 3 Fabric + 3 Forge)**, and the whole feature set has been exercised end to end on a
   real NeoForge 21.1.235 dedicated server with zero errors, including a restart.
2. **No human player has ever joined a NexusCore *server*.** Every dedicated-server check was
   driven through the console. At 1.0.1 a real `ServerPlayer` did enter the world for the first
   time — on the Fabric and Forge **dev clients**, in singleplayer quick-play — which proves the
   integrated-server path and that `PlayerLoggedInEvent` fires with a real player. It proves
   nothing more: **no command was driven in-game and no second player has ever existed.** The
   admin GUI actually rendering, vanish, chat muting, ban enforcement at login and teleport
   safety in practice are still `implemented`, not `tested`. That distinction is the whole point
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
| Version ladder gate (ADR-0009/0010/0011) | `tested` | `versionLadderCheck`. Passes on all four legal shapes and **proven to fail eleven ways** — see the 1.1.1 evidence table below. |
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

## Confirmed defects awaiting a fix

Found by an adversarial sweep of the v1.0.0 sources on 2026-07-26: 45 candidates raised across
six lenses, 13 refuted, 32 confirmed, which dedupe to the distinct defects below. Every one was
confirmed against the real sources — several against Minecraft's own decompiled code — rather
than accepted on assertion.

**Five were fixed in 1.0.1** and are not listed here: the `/execute as` root bypass, the
`/teleport` takeover gap, the audit short write, the `/gamemode` wildcard escalation, and the
missing `core.confirm` grant.

The rest are **not** in 1.0.1, because fixing them is behaviour change rather than repair and a
patch release is fixes only (ADR-0011). They are scheduled on the ladder.

| Defect | Severity | Detail |
|---|---|---|
| Multiple active punishments of the same kind | **high** | A second ban or mute does not deactivate the first. `/unban` lifts one record and reports success while the player stays banned; `activeBans()` emits one row per active record, so `/banlist` and the GUI double-count; `activeRecord()` returns the last-issued match rather than the strictest, so the ban screen can misstate the duration. `ModerationService:108,150,205`. |
| `audit.log` never rotates | **high** | The whole file is read into heap and SHA-256'd on the **server thread** at startup, at shutdown, and on every `/nexus audit verify` and `tail`. Unbounded growth plus a synchronous full read is a tick-time stall that gets worse forever. `AuditService:64,144`. |
| `JsonStore.read()` quarantines on any `IOException` | medium | A transient read error — not just a parse failure — moves an intact `permissions.json` aside and the next boot silently starts from defaults. The quarantine is meant for corrupt data, not unreadable data. `JsonStore:91`. |
| `config.json` silently loses operator keys | medium | `load()` rewrites the file from the typed object, so any key the schema does not know is deleted — while the reload reports `no problems found`. `ConfigurationService:107`. |
| `players.json` rewritten in full on every login and logout | medium | Never pruned, and each write copies a full `.bak` and fsyncs twice. Cost scales with every player ever seen. `IdentityService:82`. |
| Fabric death messages lose their cause | medium | Every styled death reads `<Player> died` on Fabric; NeoForge and Forge are correct. `NexusCoreFabric:151`. |
| `/pardon` and `/banlist` takeover strands vanilla state | medium | Bans issued by vanilla before the takeover become un-liftable, and IP bans are invisible in-game because `ban-ip` / `pardon-ip` are separate commands NexusCore never took over. `NexusCommands:171`. |
| `/nexus reload` silently ignores two settings | low | `commandsPerMinute` and `permissionCacheSize` keep their boot-time values while the reload reports success. `commandsPerMinute` is a rate limit, so this is a security control that does not apply. `NexusServices:244`. |
| Admin GUI acts on a stale `ServerPlayer` | low | A handler captured before the target logged out performs a no-op and audits it as `allowed`. `AdminGuiService:191,419`. |
| Death-message edge cases | low | Enabling `styleDeathMessages` via reload double-broadcasts until restart; the dying player's own death screen loses its cause line; team `deathMessageVisibility` is ignored, so hidden deaths leak. `DeathMessages:43,52,73`. |
| `DurationParser.format()` returns empty below one second | low | `describeRemaining()` yields `""` rather than a time. `DurationParser:135`. |
| `/nexus permission check` bypasses `authorise()` | low | It calls the evaluator directly, so it can never show the operator-bootstrap grant — the one command whose job is explaining a decision under-reports who can do what. `NexusCommands:442`. |
| Gson I/O errors escape the write protocol | low | For documents larger than the writer buffer, Gson wraps the failure in `JsonIOException`, which bypasses `catch (IOException)` and leaves the `.tmp` behind. `JsonStore:116`. |
| One non-UTF-8 byte in `audit.log` prevents startup | low | No recovery path inside the mod. `JsonStore:172`. |
| Fabric login ban screen uses a startup snapshot | low | `/nexus reload` never changes it, unlike NeoForge and Forge. `NexusCoreFabric:122`. |

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

Per [ADR-0009](docs/architecture/ADR-0009.md), [ADR-0010](docs/architecture/ADR-0010.md) and
[ADR-0011](docs/architecture/ADR-0011.md), v1.5.0 is reached through numbered development builds
arranged in **four lines of five** — `1.1.x`, `1.2.x`, `1.3.x`, `1.4.x`. Each rung is built and
tested on all three loaders before the next begins; a rung is not "done" because its code
exists, but because it meets the exit condition named here.

`x.y.1`–`x.y.4` are internal. **`x.y.5` is a pre-release**: archived, handed to testers, and the
only build in its line anyone outside the project is asked to install. The minor then rolls —
`1.1.5` is followed by `1.2.1`, and `1.2.0` never exists.

**`1.0.x` is not on this ladder.** ADR-0011 makes it the published patch line for release
v1.0.0: `1.0.1` is the security patch, and any later `1.0.N` is fixes only. Development starts
at `1.1.1`. That is why the ladder has four lines and not five.

**The ordering is deliberate.** M6's economy needs the write-ahead journal (§11.1) that M2
left `planned` — this document already calls that "the first thing M6's economy will need".
Generated command documentation needs the `ModuleManager` that M4 left `planned`. Those are
two separate dependencies on two different deliverables, not two dependencies of the economy;
between them they mean the ladder closes the M4 and M5 gaps first, then adds features.

**Every rung below except 1.1.1 is `planned` in the §20.4 sense: scheduled, no code.** 1.1.1
is built, and its row records what was actually verified. A rung's contents are a plan, not
a promise, and this table is rewritten as reality arrives rather than defended.

The **Exit condition** column is load-bearing: `RELEASE_CHECKLIST.md` gates each rung on it, so
a cell must name something checkable. Where a criterion has genuinely not been decided yet, the
cell says so rather than being left blank.

**Twenty rungs is a hard ceiling, and 1.4.x is over-subscribed.** ADR-0010's five-rung line is
a budget rather than a fact about the work, but ADR-0011 turns the *total* into a structural
limit: exactly four development lines exist between `1.0.0` and `1.5.0`, so there are twenty
rungs and there is no twenty-first. M7 and M8 currently share `1.4.x`, which is tighter than
any other line. If that work does not fit, the overflow cannot become `1.4.6` — **scope moves
out of v1.5.0, or v1.5.0 moves.** Recorded now rather than discovered at `1.4.4`.

### Line 1.1.x — foundations and structure

| Build | Contents | Exit condition |
|---|---|---|
| **1.1.1** | The ladder itself: ADR-0009, ADR-0010, ADR-0011, `versionLadderCheck`, this table. No runtime change. | Gate proven to pass on all four legal shapes **and to fail eleven ways**; all three loaders build clean; the packaged jar verified against a 1.0.0 data directory on two real servers. **Met** — see below. |
| **1.1.2** | `ModuleManager` (§7.3). Services move from explicit `NexusServices` wiring to the module contract. | Every service resolves through the module registry, and the existing 178 tests pass unchanged — a rewiring of every service is the rung most in need of a named regression check, and that suite is it. |
| **1.1.3** | Shared sources move out of `neoforge/src/` into `common/`. The layout wart ADR-0008 documents but does not fix. | All three loaders build from `common/`, and each jar is **byte-identical** to its 1.0.2 counterpart — a move that changes a single byte of output is not a move. |
| **1.1.4** | Safe mode with non-core modules disabled (M1) and generated command documentation from descriptors (M4). Both wait on `ModuleManager`. | `docs/admin/commands.md` is generated, not hand-maintained, and a test asserts it matches the registry; a server started in safe mode has its non-core modules disabled and says so. **M4 complete.** |
| **1.1.5** · *pre-release* | GameTests: teleport safety, home/warp persistence, punishment enforcement, permission gating. Plus the missing `AdminMenu` read-only container test. | The four GameTests named in M5 exist and pass, and a test proves items cannot enter or leave the admin menu. **First build handed to testers.** |

### Line 1.2.x — M5 completion and the storage foundation M6 needs

| Build | Contents | Exit condition |
|---|---|---|
| **1.2.1** | Sustained multi-player runtime verification. Everything currently `implemented` but never observed with a real player — GUI rendering, vanish, chat muting, ban-at-login, teleport safety in practice. | Every item in the "never observed" column of this document has been observed with at least two real players, or is recorded as still unobserved with the reason. |
| **1.2.2** | Whatever 1.1.1 finds. Deliberately reserved rather than assumed empty — the last two runtime rounds each produced defects no test had caught. | Every defect found in 1.1.1 is fixed with a regression test, or recorded as accepted with a reason. |
| **1.2.3** | Write-ahead journal for multi-file transactions (§11.1). The M2 gap, and M6's prerequisite. | Journal replay verified by a simulated crash mid-transaction. |
| **1.2.4** | Migration fixtures and the schema-bump machinery, which the journal and the coming economy schema both need. | A 1.0.0 data directory loads through the migration path with exact expected results; the `blocked` fixtures row above is unblocked. |
| **1.2.5** · *pre-release* | M5 closure: the week-long real-player run that is M5's actual exit criterion. | Run with real players for a week with no unrecorded defect. **M5 complete.** |

### Line 1.3.x — M6 economy

| Build | Contents | Exit condition |
|---|---|---|
| **1.3.1** | Currency core: fixed-point integer minor units. | No `float` or `double` anywhere in currency code, asserted by a test that scans the sources — not by review. |
| **1.3.2** | Atomic transfer and idempotency keys, on top of 1.1.3's journal. | Debit and credit commit in one transaction boundary; an idempotency replay test proves the same key produces exactly one committed transaction. |
| **1.3.3** | Reversal as a compensating entry. | A reversal creates a new entry and no historical row is edited, asserted by a test. |
| **1.3.4** | Shops. | A shop purchase is all-or-nothing under an injected mid-transaction failure. |
| **1.3.5** · *pre-release* | Economy commands and operator documentation. | All six §10.2 invariants have passing **named** tests; balances and history survive restart. **M6 complete.** |

### Line 1.4.x — M7 and M8 · **over-subscribed, and said so**

M7 (chat and moderation depth) and M8 (automation, backups, diagnostics, benchmarks) were two
lines before ADR-0011 took `1.0.x` for the patch lane. They now share five rungs, and five rungs
is not enough for two milestones. This line is written as the plan of record, and it is also the
first place to look when the ladder slips.

**When it overflows there is no `1.4.6`** — the twenty-rung ceiling is structural, not a budget.
The options at that point are to move scope out of v1.5.0 or to move v1.5.0, and that is a
decision for the owner rather than something to absorb by quietly widening a rung.

| Build | Contents | Exit condition |
|---|---|---|
| **1.4.1** | M7 chat: channels and private messages. | Channel routing delivers only to subscribers and private messages reach only the recipient, each with a named test; **no chat path bypasses the existing mute check**, asserted for both. |
| **1.4.2** | M7 depth: anti-spam, jail, reports, notes. | Anti-spam limit configurable with a boundary test; jail survives restart and relog and cannot be left by any teleport path (`/home`, `/warp`, `/spawn`, `/back`, `/tpa`); reports and notes persist and appear in the audit chain. **M7 complete except SmartGuard, excluded below.** |
| **1.4.3** | M8 automation: durable scheduler, backups, restore with a dry run. | Scheduler survives restart with correct missed-run behaviour; the dry run validates manifest, checksum and schema before applying, proven against a deliberately corrupted backup. |
| **1.4.4** | M8 diagnostics and benchmarks: `/nexus doctor`, `/nexus doctor storage`, support bundle, harness (§16.2), measured budgets. | Both doctor commands distinguish a seeded fault from a clean system; the support bundle contains no secrets, verified by test; the harness produces JSON and a committed baseline and a local run compares against it. **The unverified-claims register below is closed — every number in it is measured or withdrawn. M8 complete.** CI comparison is *not* part of this condition: the M0 table records the CI workflow as never executed with no remote configured, and no rung changes that. |
| **1.4.5** · *pre-release* | Release candidate: documentation sweep and the full three-loader matrix. | `RELEASE_CHECKLIST.md` sections 0–7a and 8 all pass on the candidate. |

### 1.5.0 — the release

| Build | Contents | Exit condition |
|---|---|---|
| **1.5.0** | Release ceremony only. No code that was not already in 1.4.5. | `RELEASE_CHECKLIST.md` sections 0–7a and 8 signed off, in full. Sections 9–11 (M9, M10, M11) are **NOT MET by design** — they are v2.0 scope — and the sign-off records them that way, which is what the checklist's own "recorded as NOT MET with the specific reason" rule requires. The same applies to the four M3 items and SmartGuard excluded below. |

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

### 1.1.1 — what was actually verified

The rung that establishes the ladder is the wrong one to take on trust, so it was run rather
than reasoned about.

| Check | Status | Evidence |
|---|---|---|
| All three loaders build clean at 1.0.1 | `tested` | `clean build` green in `neoforge/`, `fabric/`, `forge/`. Artifacts `NexusCore-<loader>-1.0.1-1.21.1.jar`. |
| Version reaches packaged metadata, not just filenames | `tested` | `version = "1.0.1"` in `neoforge.mods.toml`, `mods.toml`, and `fabric.mod.json`, all token-expanded from the one `mod_version`. Zero jar entries still naming 1.0.0. |
| `versionLadderCheck` **fails** when it should | `tested` | Eleven injected violations, each rejected with its own message. Re-verified under the ADR-0011 rules: malformed version (`-Pmod_version=1.0`); undocumented version (`1.0.9`); sixth rung (`1.1.6` — "roll the minor"); `.0` on a development minor (`1.2.0` — "a development line has no .0 rung"); pre-release whose heading says `development build` (`1.1.5`); release whose heading says `pre-release` (`1.5.0`). Verified earlier on unchanged code paths: whitespace-padded `mod_version`; rebuild at an already-archived version (`1.0.0`); heading only inside a fenced code block; patch release mislabelled `development build`; patch release with no label. |
| `versionLadderCheck` **passes** when it should | `tested` | All four legal shapes: `1.0.1` patch release, `1.1.1` development build, `1.1.5` pre-release, `1.5.0` release. The last three used temporary changelog headings; `CHANGELOG.md` confirmed byte-identical afterwards. |
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
| Behaviour with more than one player online | **never observed** — every runtime test was single-session, console-driven |
