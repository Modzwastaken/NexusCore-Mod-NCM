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

**Last updated:** 2026-07-25 · **Current milestone:** M0 · **Current version:** 0.1.0 (unreleased)

---

## M0 — Walking skeleton

| Requirement | Status | Evidence / note |
|---|---|---|
| NeoForge MDK initialised for 1.21.1 | `in progress` | Project scaffolded from `MDK-1.21.1-NeoGradle`. Awaiting first green `compileJava`. |
| NeoForge patch version verified and recorded | `implemented` | ADR-0001. Verified 2026-07-25 against `maven.neoforged.net` metadata; latest 21.1.x was `21.1.243`, pinned `21.1.235`. |
| Gradle wrapper pinned | `implemented` | `gradle-9.2.1-bin`, wrapper JAR committed. |
| Java 21 toolchain, UTF-8 | `in progress` | Declared in `build.gradle`. Proven by the first successful compile. |
| Archive base name `NexusCore-<version>.jar` | `in progress` | `base.archivesName = NexusCore`. Proven by inspecting `build/libs`. |
| `neoforge.mods.toml` (only metadata file) | `in progress` | Written; token expansion proven by `generatedMetadataHasNoUnexpandedTokens`. |
| `@Mod` entry point | `in progress` | `NexusCore.java`. Bootstrap only. |
| Exactly one command: `/nexus version` | `in progress` | `NexusVersionCommand.java`. |
| Stub-marker gate (§2.4) | `implemented` | `stubMarkerCheck` task in `build.gradle`, wired into `check`. |
| Formatting and static analysis | `implemented` | Checkstyle 10.21.1, `config/checkstyle/checkstyle.xml`. ADR-0005. |
| CI workflow | `implemented` | `.github/workflows/build.yml`. |
| `IMPLEMENTATION_STATUS.md`, `RELEASE_CHECKLIST.md` | `implemented` | This file and `RELEASE_CHECKLIST.md`. |
| ADR-0001 … ADR-0004 (plus ADR-0005) | `implemented` | `docs/architecture/`. |
| Exit: `./gradlew clean build` green from clean checkout | `planned` | Not yet claimed. |
| Exit: `build/libs/NexusCore-0.1.0.jar` exists | `planned` | Not yet claimed. |
| Exit: `runServer` reaches "Done", zero NexusCore errors, clean shutdown | `planned` | Not yet claimed. |
| Exit: JAR loads in a **real** dedicated server | `planned` | Not yet claimed. Requires a NeoForge 21.1.235 server install. |

### Known interim states carried into M1

| Item | Status | Detail |
|---|---|---|
| Player-facing text uses a literal component, not a translation key | `implemented` | Deliberate, per ADR-0003. A vanilla client cannot resolve a NexusCore translation key, and v0.1 is server-only. The format string and the `en_us.json` value are held equal by `NexusVersionCommandTest.formatStringMatchesLanguageFile`. `MessageService` (M1) replaces this with server-side catalogue resolution. |
| `gameTestServer` run config not wired into CI | `planned` | Correct until M5. It fails by design when no GameTests are registered. |
| `runClient` unused | `planned` | Correct until M9. Kept working so the toolchain does not rot. |

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
