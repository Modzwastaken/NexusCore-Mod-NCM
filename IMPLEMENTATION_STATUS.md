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

**Last updated:** 2026-07-28 · **Version:** 1.1.1 (in progress) ·
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

> **The `§` references are being re-derived, and the provenance has changed.** Every `§` citation
> in this repository — `§11.1` in the storage layer, `§9` in permissions, `§20.4`'s status
> vocabulary above — points into an external specification **this repository has never contained
> and cannot recover**. Those citations looked like references and functioned as assertions: no
> reader could check them. At the owner's ruling (2026-07-27) the sections NexusCore actually
> depends on are being rewritten as NexusCore's own, in [docs/spec/](docs/spec/), keeping the
> inherited numbering so existing citations stay valid.
>
> **A re-derived section cannot be used to argue the code is correct** — it was derived from the
> code, so agreement is circular. It fixes the requirement in place for what comes next.
> [§11 — Storage and persistence](docs/spec/11-storage.md) is written, because the write-ahead
> journal is built and M6's economy will be measured against it. The remaining sections are not,
> and their absence is recorded here rather than in a placeholder there.

---

## The honest summary

NexusCore now does real work: permissions with groups and inheritance, durable JSON storage,
a hash-chained audit log, the full command pipeline, teleport with safety checks, player
utilities, moderation with confirmations, and a working admin GUI.

**Two things are true at once, and both matter:**

1. **314 automated tests pass, and since 1.1.1 the 308 shared ones run on every loader** — 930
   test executions in total (308 shared × 3, plus 3 Fabric-specific and 3 Forge-specific).
   **Until 1.1.1 they did not.** The shared suite was wired into `check` for NeoForge only, so
   "214 tests, three loaders green" was 208 tests on one loader and 3 on each of the others while
   all three compiled the code under test. The count read like three-way coverage and was not.
   Found in review of the write-ahead journal, whose crash-recovery proofs were the clearest case:
   they guard the substrate for every future money and item transfer and were exercised on one
   loader of three. The sources are identical so the code cannot diverge, but each loader supplies
   its own Gson and logging binding, and the storage layer is built on exactly those.

   **CI now proves a cold
   build works** — all three loaders build from a bare checkout on a clean runner, which no local
   run had ever actually demonstrated (NeoGradle restores neoForm outputs from a cache outside
   `build/`, so the decompile step had never run cold here). The whole feature set has also been
   exercised end to end on real dedicated servers for all three loaders, with zero errors.

   **34 of those are the write-ahead journal's**, and the journal has **no production caller** — so
   unlike everything else in this list it has never run on a real server and is proven by tests
   alone. M6's economy is the intended first caller.
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
| Write-ahead journal for multi-file transactions | `tested` | `JournalTest` — 34 tests on **all three loaders**, including `crashMidTransactionIsRepairedByReplay` (three files, killed after the first is applied; the assertion checks the state really is torn before recovering it), `crashAtEveryPointIsRecoverable` (all four crash points) and `unreadableRecordRefusesEveryTime`. **Proven to fail reproducibly** by `tools/mutate-journal.sh`, which breaks the journal four ways and asserts a named test catches each — the earlier "proven to fail two ways" claim was hand-run and left no artifact, and a review rightly refused it. An adversarial review then found **six defects, three able to lose a committed transaction**; all six are fixed and each has a regression test. **This closes the M2 gap.** **Evidence ceiling (§20.4):** crashes are simulated in-process, so this proves ordering and recovery, **not durability** — deleting any `force()` call would still pass. **Both durability ceilings this row used to carry were resolved at 1.1.4** and no longer qualify it: `JsonStore.move` has no non-atomic fallback — it refuses, so §11.1-R4 holds or the write fails — and `forceDirectory`'s Windows no-op is now a named row on the published defect list, reported once per run at WARN rather than at DEBUG. What remains true is only the first sentence of this ceiling: the crashes are simulated in-process, so **durability itself is still argued rather than demonstrated**. No production caller yet: M6's economy is the intended one, and 1.2.2 builds atomic transfer on it. |
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
| **GameTests** | `tested` | Twelve GameTests — teleport safety, home persistence, punishment enforcement, permission gating, and the admin panel's read-only container — run on **all three loaders** via per-loader declared holders over shared bodies in `common/`. Counted by `tools/verify-gametests.sh --expect 12` per loader, which refuses a run that executed nothing. Warp persistence remains uncovered — homes are, warps are not — so M5's exit condition still wants the warp case and the real-player run below. |
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

**Two of the three `1.1.1` priorities are fixed and have been removed from this table**, per the
convention that a row leaves only when its fix carries a named test: `IdentityService.resolve()`
blocking the server thread (`IdentityServiceTest`, seven tests, two of them regression guards) and
the multiple-active-punishments group (`ModerationServiceTest`, six new tests). Both are recorded
in `CHANGELOG.md` under `[1.1.1]`, which is the record. The third — the four vanish faults — heads
the list below and is not yet fixed.

| Defect | Severity | Detail |
|---|---|---|
| Vanish: two client-rendering faults **awaiting the 1.1.3 sweep** | medium | **Two of the original four are fixed** and covered by `VanishParityTest` on all three loaders: vanish is now applied to players who join later (`hideVanishedFrom`), and it survives death (`reapplyVanish`), which previously left NexusCore reporting a staff member hidden while every client could see them. **Two remain, both client-rendering and neither guessed at:** removing the staff member's `PlayerInfo` makes their chat render as a red chat-validation error for everyone else, and un-vanishing does not restore the entity for clients that received `AddEntity` while vanished. Fixing the first changes how staff chat is delivered, which is a product decision rather than a defect fix, so it is **not** being made unilaterally. Both are scheduled for the 1.1.3 two-real-players sweep with the owner's second account — awaiting that sweep, not unreproducible. |
| `config.json` `defaultGroup` is ignored at startup | low | The permissions module reads only `permissionCacheSize` from settings, so a `defaultGroup` set in `config.json` takes effect only after some later mutation rewrites the permissions document. |
| `/ban` in safe mode stages a confirmation that can never complete | low | **Fixed in code, not yet closed by test.** `proposeBan()` now calls `services.moderation()` before staging, so the refusal happens before a token exists; `confirm()` audits a token spent on a body that threw and tells the operator the token is gone. Both changes are at command call sites, which no test can invoke without a `CommandSourceStack` harness — removing either leaves all tests green, verified by mutation. `SafeModeConfirmationTest` pins the mechanisms (the module throws in safe mode, a failed body does not return the token, audit survives safe mode) but not the ordering. Closes when 1.1.2's command tests can drive the propose/confirm path. |
| Teleport warmup does not re-check permission at commit | low | The class documents a §19.1 step-5 permission re-check that does not exist; only destination safety is rechecked. |
| `setHome` leaks an empty entry when refused | low | The per-owner map is inserted before the limit check, so refused `/sethome` calls leave permanent empty entries in `homes.json`. |
| **A rename is not flushed to disk on Windows** | medium | `JsonStore.forceDirectory` opens the directory as a channel and forces it. **Windows cannot open a directory as a channel at all**, so every directory fsync NexusCore performs is a no-op there, and a completed rename is durable only once the filesystem writes its own metadata on its own schedule. Documents are still *replaced* atomically — what is weakened is surviving a power loss in the moments after. **Ruled rather than fixed at 1.1.4**: there is no directory-fsync equivalent to reach for on that platform. Reported once per run at WARN since 1.1.4; before that it was logged at DEBUG, which nobody runs, so the weakening was invisible on the one platform where it always happens. Linux and macOS are unaffected. |
| **A damaged `audit.log` cannot be repaired, only reported** | medium | Since 1.1.4 a log with bytes that are not UTF-8 no longer prevents startup, and `/nexus audit verify` names the record where the chain breaks. There is **no repair path**: no truncate-to-last-good-record, no re-anchor. So the break is permanent, and the consequence is not the corrupt line — it is that **`verify` stays red forever afterwards**, and a signal that is always red stops being read. That turns the tamper-evidence mechanism off by attrition rather than by failure, which is worse than a loud break. **Rated `medium`, not `low`, for that reason and not for the corruption itself.** Deliberately not built into the 1.1.4 boot fix: any repair that re-anchors a hash chain is **indistinguishable from tampering** unless it records what it changed and why, in a log whose entire purpose is being tamper-evident — so it needs its own design, not an appendix to a startup fix. |
| `config.json` silently loses operator keys | medium | `load()` rewrites the file from the typed object, deleting any key the schema does not know, while reporting `no problems found`. |
| Fabric death messages lose their cause | medium | Every styled death reads `<Player> died` on Fabric only. |
| `ban-ip`/`pardon-ip` stay vanilla commands | low | IP bans are now listed by `/banlist`, but their issue and lift still bypass NexusCore, so they are not audited. |
| Death-message edge cases | low | Reload double-broadcasts until restart; the dying player's own screen loses its cause; team `deathMessageVisibility` is ignored. |
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
| **1.1.2** | **In-server test harness, now genuinely on all three loaders.** The regression this row recorded — NeoForge registering ZERO GameTests after the move to `common/`, caught by `tools/verify-gametests.sh` on its first run — is resolved by the per-loader-holder design that entry named as the way out: each loader has holder classes that DECLARE the twelve methods (vanilla registers `getDeclaredMethods()` only) and delegate to the shared bodies. The holders differ because the loaders' discovery rules differ, each read from that loader's sources rather than assumed: NeoForge derives the namespace from `@GameTestHolder` and needs `@PrefixGameTestTemplate(false)` or the structure name grows a class prefix; **Forge's `enabledGameTestNamespaces` filters TEST NAMES, not namespaces** (`ForgeGameTestHooks.addTest`), so its holder's `@GameTestHolder` value is what puts tests behind the filter, and its template keeps the full `nexuscore:empty` path because Forge uses a colon-bearing template verbatim; Fabric instantiates the shared classes directly as `fabric-gametest` entrypoints. **First-ever Forge execution found two real test defects**: the suffocation and lava refusal tests left part of the search space to harness geometry, which differs per loader in both directions — Forge's structures float over a platform (two air blocks below, probed) and are encased in barrier blocks (solid ground two above the lava, probed) — so `SafeDestination` correctly found a pocket each time and the tests wrongly read the feature working as the check failing. Both tests are now hermetic, filling every position the search can visit. **Still to do:** the three 1.1.1 refusal paths (`/ban`'s propose-time module check needs a safe-mode run configuration the harness does not have; `confirm()`'s spent-token audit and the admin panel's departed-target refusal need a command source and a disconnect). | **12 / 12 / 12 — `tools/verify-gametests.sh --expect 12` passes on NeoForge, Fabric and Forge**, each run executing a positive, counted number of tests. The guard remains proven by the regression it caught; `tools/mutate-gametests.sh`, which the guard's own header promises, still does not exist and is recorded here rather than implied. |
| **1.1.3** | Sustained multi-player runtime verification — GUI rendering, vanish (four confirmed faults), chat muting, ban-at-login, teleport safety in practice. | Every item in the "never observed" column has been observed with at least two real players, or is recorded as still unobserved with the reason. |
| **1.1.4** | **Storage is trustworthy.** The write-ahead journal (§11.1) — *done*; the four economy-blocking storage defects (`JsonStore` quarantining on `IOException` rather than on bad content, `JsonIOException` escaping that catch entirely, chain-aware audit rotation, `players.json` full-rewrite damping); and the two inherited durability ceilings closed **or ruled** as named platform limitations. | Journal: **met** — `JournalTest`, 34 tests on three loaders, crash at every point, `tools/mutate-journal.sh` proving the suite constrains the protocol. Remaining: each storage fix has a named regression test; each durability ceiling is closed with a test, or appears on the **published** defect list as a named platform limitation — not as a javadoc comment. **Runnable the day the work lands.** **Residual, stated rather than implied (§20.4):** damping `players.json` means an **unclean** stop — a crash, an OOM kill, a host losing power — loses at most **20 last-seen updates** or one 30-second window of activity, whichever comes first. A clean stop loses nothing, because the identity module flushes at shutdown. Both bounds are needed and neither is sufficient: `ServerStoppingEvent` does not fire on a crash, and the time bound never fires on an idle server because damping runs on player events rather than on a ticking clock. Nothing that changes what the document *means* is damped at all — a new player or a rename writes through immediately. The trade is deliberate and is against a full-document rewrite, a backup copy and an fsync on every join and every leave. |
| **1.1.5** | Migration fixtures and the schema-bump machinery. The week-long real-player run that is M5's actual exit criterion. | A 1.0.0 data directory loads through the migration path with exact expected results; a week of real-player use with no unrecorded defect. **M5 complete.** |

> **1.1.4's deliverable was built during 1.1.1, out of order.** The owner directed it: v1.2.0's
> shape was still being decided, and the journal is a prerequisite under every candidate design,
> so starting it could not be wasted work whichever way that decision went. It is recorded here
> rather than by quietly renumbering the table, because the table's value is that it says what
> actually happened — and because "1.1.4 is done" and "the journal is done" are not the same claim.
> **No 1.1.4 build has been cut.** The journal ships in whichever build is cut next, and what
> 1.1.4's slot now holds is the owner's call, not this document's.

> **1.1.4 was re-planned on 2026-07-28, and split.** Recorded here under this table's own overflow
> rule — *"if a version's work needs more than five builds it moves up early and carries the
> remainder — recorded when it happens"* — rather than edited quietly.
>
> The v1.2-line design brief proposed one widened 1.1.4 carrying the journal, a segmented ledger
> substrate, the four storage fixes and the two durability ceilings, with an exit requiring replay
> **"for a two-file money commit and an escrow item commit"**. That exit could not be run: escrow
> is 1.2.3 content, so the gate for 1.1.4 depended on a feature three builds later. A rung whose
> exit cannot be run until later is not a gate — it is a rung that gets ticked on faith, which
> `RELEASE_CHECKLIST.md`'s "Milestones say when work landed; content says what to check" forbids in
> as many words.
>
> So the rung is split, and each piece lands where its evidence can exist:
>
> - **1.1.4 keeps** the journal, the four storage fixes and the two durability ceilings — one
>   coherent claim, *storage is trustworthy*, with an exit runnable the day the work lands.
> - **The segmented ledger substrate moves to 1.2.1**, where the economy skeleton gives it its
>   first writer. Building a ledger substrate a build before anything writes to it is how the
>   journal ended up with no caller for a whole version; that is a mistake worth not repeating
>   immediately after making it.
> - **The escrow half of the exit moves to 1.2.3**, where escrow exists.
>
> **The two durability ceilings were the gate before 1.2.2, and both are now resolved** — one
> closed, one ruled. They were inherited from the single-document write protocol and predated the
> journal, but the journal is what made them matter, because 1.2.2 is where money starts riding on
> them. **For money, "we recorded the ceiling" is a weaker position than it sounds.**
>
> - **`JsonStore.move` — closed.** The non-atomic fallback is gone; the write refuses instead, so
>   §11.1-R4 holds or nothing happens. It could be closed rather than ruled because every atomic
>   move in the codebase is same-directory, which made the fallback unreachable for NexusCore's own
>   writes while still carrying the risk of silently weakening the guarantee.
> - **`forceDirectory` — ruled.** Windows cannot open a directory as a channel and there is no
>   equivalent to reach for, so it ships as a **named platform limitation on the published defect
>   list**, reported once per run at WARN. Not a comment in a javadoc, which is what the ruling
>   required.
>
> See [the conformance table](docs/spec/11-storage.md): R22 met by closing, R21 met by ruling. What
> is *not* resolved, and is recorded on the journal row above rather than here, is that the crash
> tests are in-process — so durability remains argued rather than demonstrated.

### Version 1.2 — M6 economy

| Build | Contents | Exit condition |
|---|---|---|
| **1.2.0** · *version* | The M5-complete milestone. | 1.1.x verified together; the unverified-claims register reflects what the real-player run established. |
| **1.2.1** | Currency core: fixed-point integer minor units. **Plus the segmented ledger substrate, moved here from 1.1.4** so it lands with its first writer; the economy module skeleton with all four safe-mode touch points; and the batch-3 command hygiene rows. | No `float` or `double` anywhere in currency code, asserted by a test that scans the sources — not by review. **Added:** every new setting is reload-honoured with a test, and a safe-mode boot proves the economy module drops cleanly. The four touch points are asserted together, because 1.0.5 shipped a regression by wiring three of them. |
| **1.2.2** | Atomic transfer and idempotency keys, on 1.1.4's journal. **Kept verbatim** — the design brief proposed no change and none is made. | Debit and credit commit in one transaction boundary; an idempotency replay test proves one key yields exactly one committed transaction. **Gated on 1.1.4's two durability ceilings being resolved first**, which they now are: this is the build where money starts riding on the storage layer. |
| **1.2.3** | Reversal as a compensating entry. **Plus the item escrow vault and claim box** — new scope the printed M6 rows never named, because the journal covers multi-file JSON and items live in vanilla player NBT, outside its boundary. | A reversal creates a new entry and no historical row is edited, asserted by a test. **Plus the escrow custody invariant, moved here from 1.1.4** because it cannot be asserted before escrow exists: an item is in the inventory or in escrow after replay at every kill point — never both, never neither. Three conditions ride on it, recorded under *Confirmed defects* rather than assumed: the forced player-save's per-call cost is **measured** before market listing ships, vanilla's own `.dat_old` rollback is named as a residual duplication vector no custody test can observe, and a decode failure quarantines the item rather than discarding it. |
| **1.2.4** | Shops — the economy's faucet and sink. **Plus fixed-price market listings** (list, browse, buy-now, cancel, claim) with the incident levers: market freeze, inspect, remove. Timed bidding is **not** here; it is deferred to 1.3.x. | A shop purchase is all-or-nothing under an injected mid-transaction failure. **Added, same shape:** a market purchase is too, and **two players cannot both buy one listing**, proven by a concurrent-purchase test rather than by argument — a fixed-price buy is a check-then-act race with no auction clock to serialise it, and its failure mode duplicates items rather than merely mis-awarding them. An expired or cancelled listing returns the item **exactly once** under a replayed expiry sweep. |
| **1.2.5** | Economy commands and operator documentation. **Plus player-to-player trade and kits** — both explicitly cuttable, in that order, under the pre-agreed cut order (kits first, then the market GUI drops to commands-only, then trade). **Plus the second two-real-players sweep**, covering trade, market and the freeze levers. | All six §10.2 invariants have passing **named** tests; balances and history survive restart. **M6 complete.** The §10.2 invariants are **written, not recovered**: the owner's re-derive ruling makes them NexusCore's own, in `docs/spec/`, alongside [§11](docs/spec/11-storage.md). An earlier note recorded this exit as `blocked` if the external specification could not be found — **that is superseded**; it is not blocked, it is unwritten, and the difference matters because one waits on the world and the other waits on us. The sweep needs a second real player the owner supplies; if it cannot be scheduled it is recorded NOT MET with that reason, never reworded. |

> **Every exit condition above must answer one question: if this check could not run, would
> anything say so?**
>
> Recorded here on 2026-07-28 because the studio hit it three times in one day, in three costumes.
> A tripwire that pinned a *spelling* rather than a property fired on a legitimate refactor. A
> mutation round proved nothing because Gradle served `:test` from cache for mutated source, so a
> harness whose whole job is failing reported success without executing. And a token-gated check
> **vanished** instead of reporting `UNVERIFIABLE`, so a rule that never ran once read as healthy.
>
> The first two are wrong answers. The third is worse: **no answer, presented as a good one.** A
> check that cannot run and says nothing is indistinguishable from a check that passed, and it stays
> that way until somebody goes looking for a reason no reason exists.
>
> So an exit condition here is not met by a green result. It is met by a green result that *could
> have been red* — which is why the mutation harnesses run with `--rerun-tasks`, why the journal's
> crash tests assert the state is genuinely torn before recovering it, and why a row whose fix no
> test can exercise stays open with that named as the obstacle rather than being retired.

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
