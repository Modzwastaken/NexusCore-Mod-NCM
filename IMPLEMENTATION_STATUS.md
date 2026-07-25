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

**Last updated:** 2026-07-25 · **Current milestone:** M0 — **PASSED** · **Next:** M1 ·
**Current version:** 0.1.0 (unreleased) · **Commit:** `59c7f69`

---

## M0 — Walking skeleton · **PASSED 2026-07-25**

| Requirement | Status | Evidence / note |
|---|---|---|
| NeoForge MDK initialised for 1.21.1 | `tested` | `./gradlew clean build` exit 0. |
| NeoForge patch version verified and recorded | `implemented` | ADR-0001. Verified 2026-07-25 against `maven.neoforged.net` metadata; latest 21.1.x was `21.1.243`, pinned `21.1.235` so the compiler enforces the `[21.1.235,)` support range. |
| Gradle wrapper pinned | `implemented` | `gradle-9.2.1-bin`, wrapper JAR committed. |
| Java 21 toolchain, UTF-8 | `tested` | Compiles under JDK 21.0.11; server ran on the same toolchain. |
| Archive base name `NexusCore-<version>.jar` | `tested` | `build/libs/NexusCore-0.1.0.jar`, 5236 bytes. |
| `neoforge.mods.toml` (only metadata file) | `tested` | `NexusVersionCommandTest.generatedMetadataHasNoUnexpandedTokens`; JAR listing shows exactly one metadata file and no legacy `mods.toml`. |
| `@Mod` entry point, bootstrap only | `tested` | Server log: `[co.mw.ne.NexusCore/]: NexusCore Administration Framework 0.1.0 bootstrapped`. |
| Exactly one command: `/nexus version` | `tested` | Real dedicated server console returned `NexusCore Administration Framework version 0.1.0`. |
| Stub-marker gate (§2.4) | `tested` | Passes clean (2 files, 0 violations) **and proven to fail**: an injected `// TODO` produced `Stub-marker gate failed (§2.4): 1 violation(s)`, BUILD FAILED. |
| Formatting and static analysis | `tested` | Checkstyle 10.21.1 passes clean **and proven to fail** on an injected violation. ADR-0005. |
| CI workflow | `implemented` | `.github/workflows/build.yml` — build, packaged-JAR inspection, checksums, report upload. Not yet executed on a runner; no remote configured. |
| `IMPLEMENTATION_STATUS.md`, `RELEASE_CHECKLIST.md` | `implemented` | This file and `RELEASE_CHECKLIST.md`. |
| ADR-0001 … ADR-0004 (plus ADR-0005) | `implemented` | `docs/architecture/`. |
| Exit: `./gradlew clean build` green from clean checkout | `tested` | Exit code 0. 37 tasks. 4 tests passed, 0 failed, 0 skipped. |
| Exit: `build/libs/NexusCore-0.1.0.jar` exists | `tested` | 5236 bytes · SHA-256 `fafa4eec250cc52a9e06372e86583bad9c5eca1713aea42ef666b3eb38c58b23`. Identical hash across two independent clean builds — reproducible archive settings verified. |
| Exit: `runServer` reaches "Done", zero NexusCore errors, clean shutdown | `tested` | `Done (0.573s)!` · zero `/ERROR]` lines · `stop` typed into the console · BUILD SUCCESSFUL, exit 0. |
| Exit: JAR loads in a **real** dedicated server | `tested` | Standalone NeoForge 21.1.235 install at `testserver/`, JAR in `mods/`. `Done (0.544s)!` · zero `/ERROR]` lines · `/nexus version` answered · clean shutdown, exit 0. |

### Fixed during M0

| Problem | Resolution |
|---|---|
| `processResources` failed: a dollar-brace sequence in a **comment** inside `neoforge.mods.toml` was evaluated as a Groovy expression by the template engine. | Comment rewritten, and the file now carries an explicit warning about the template engine so the trap is not re-entered. |
| `./gradlew runServer` could not be shut down cleanly: NeoGradle's run tasks are `JavaExec` tasks, and Gradle gives `JavaExec` an **empty** standard input, so nothing typed into the console reached the server. Only SIGTERM worked, which exits 143 and reports BUILD FAILED. | `build.gradle` now forwards `System.in` to any `run*` task at execution time. `stop` works, and the exit condition is genuinely verifiable rather than approximated. |
| One `/ERROR]` line on the test server: `No key layers in MapLike[{}]`. | Caused by the **test** `server.properties` requesting `level-type=minecraft:flat` with no `generator-settings`. Vanilla, not NexusCore. Test config corrected; the transcript is now zero-error. |

### Known interim states carried into M1

| Item | Status | Detail |
|---|---|---|
| Player-facing text uses a literal component, not a translation key | `implemented` | Deliberate, per ADR-0003. A vanilla client cannot resolve a NexusCore translation key, and v0.1 is server-only. The format string and the `en_us.json` value are held equal by `NexusVersionCommandTest.formatStringMatchesLanguageFile`. `MessageService` (M1) replaces this with server-side catalogue resolution. |
| `gameTestServer` run config not wired into CI | `planned` | Correct until M5. It fails by design when no GameTests are registered. |
| `runClient` unused | `planned` | Correct until M9. Kept working so the toolchain does not rot. `runClient` has **not** been executed — this machine has not been verified as able to run a graphical client. |
| Automated "dedicated server loads zero client classes" test | `planned` | M9 exit condition. Current evidence is weaker but sufficient for M0: the JAR contains no `client/` package at all (12 entries, all listed in the release checklist), and the dedicated server loaded it without error. |
| CI workflow never executed | `planned` | No git remote is configured, so `.github/workflows/build.yml` has not run on a GitHub runner. Its steps mirror what was run locally, but that is not the same as a green CI badge. |

---

## M1 — Configuration, messages, lifecycle · `planned`

`LifecycleService` · `ConfigurationService` (typed schema, validation reports,
`schema_version`, transactional reload) · `MessageService` (§ADR-0003 server-side
resolution) · structured logging with correlation IDs · complete `en_us.json` · safe mode.

## M2 — Storage, identity, audit · `planned`

`StorageProvider` + JSON implementation with atomic replace and write-ahead journal ·
schema versioning and forward migrations · migration fixtures · `IdentityService` ·
`AuditService` (§15.2 fields, write-time redaction, hash chaining) · `PathSafety` ·
`/nexus doctor storage`.

## M3 — Permissions · `planned`

The §9 engine per ADR-0004 · bounded cache · `/nexus permission check` with explain output ·
console-root and operator bootstrap · single-use recovery grant · import/export · audit.

## M4 — Command framework and module manager · `planned`

`CommandService` from descriptors · the §12.2 nine-step pipeline · single `DurationParser` ·
token-bucket rate limits · signed confirmation tokens · permission-filtered `/nexus help` ·
generated command docs · `ModuleManager` · soft-failing alias registration.

## M5 — Teleport, player utilities, core moderation → **v0.1** · `planned`

§19.1 teleport safety · homes, warps, spawn, `/back`, requests · heal/feed/fly/god/speed/
freeze/vanish/info · kick, ban, tempban, unban, mute, unmute, warn with durable expiry ·
Appendix D Tier 1 · first GameTests.

## M6 — Economy · `planned`
## M7 — Moderation depth and chat · `planned`
## M8 — Automation, backups, diagnostics, benchmarks → **v0.5** · `planned`
## M9 — Client mod, networking, GUI shell · `planned`
## M10 — GUI screens, themes, accessibility · `planned`
## M11 — Public API, documentation, release candidate → **v1.0.0** · `planned`

---

## Intentionally excluded

| Item | Reason |
|---|---|
| Mixins | ADR-0002. v1.0.0 ships zero mixins; supported NeoForge APIs cover the requirements. Adding the first one requires a new ADR. |
| Managing, installing, deleting, or hot-reloading third-party mods from inside Minecraft | §3.5. |
| Replacing an observability platform or an OS process manager | §3.5. |
| Circumventing Minecraft authentication or Mojang/Microsoft services | §3.5. |
| Autonomous moderation based on opaque machine-learning judgement | §3.5. SmartGuard (M7) uses deterministic configured rules and never auto-bans by default. |
| Unconditionally overriding vanilla commands | §3.5, §12.3. `/nexus` is the canonical root; aliases are opt-in and collision-checked. |
| Required dependency on LuckPerms, Vault, Essentials, PlaceholderAPI, WorldEdit, WorldGuard, or any economy/chat/database plugin | §8.1. Every equivalent capability is native. Integrations are optional adapters (M11). |

---

## Unverified claims register

Nothing in this repository may state a performance number as measured until the §16.2
benchmark harness exists at M8.

| Claim | Status |
|---|---|
| Idle tick cost under 0.10 ms | **target, not measured** — no harness until M8 |
| Active tick cost under 1.0 ms mean / 2.0 ms p95 | **target, not measured** — no harness until M8 |
| Supported player count | **not claimed anywhere**, and must not be until measured |
