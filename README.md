# NexusCore Administration Framework

Native server administration for Minecraft Java Edition — permissions, moderation,
teleportation, player utilities, staff tools, configuration, storage, and audit logging in
one mod. No Bukkit/Spigot plugin, no other administration mod, no external database service
required.

| | |
|---|---|
| **Minecraft** | 1.21.1 |
| **NeoForge** | 21.1.235 or newer 21.1.x (compiled against 21.1.235 — see [ADR-0001](docs/architecture/ADR-0001.md)) |
| **Java** | 21 |
| **Mod id** | `nexuscore` |
| **Licence** | All Rights Reserved — see [LICENSE](LICENSE) |
| **Author** | MWT Studios |

---

## Current state

**Version 1.0.0 — first public release.** ([release notes](release-notes.md))

Working today, on a server-only install with vanilla clients:

- **Permissions** — native engine, groups with inheritance and cycle rejection, wildcards,
  explicit denies, a decision cache, and a `check` command that *explains* its answer
- **Storage** — atomic JSON writes, corrupt-file quarantine, path-traversal containment
- **Audit** — append-only, SHA-256 hash-chained, tamper-evident, with write-time redaction
- **Commands** — a nine-step pipeline every command routes through: permission, validation,
  rate limit, confirmation, service call, feedback, audit
- **Teleport** — homes, warps, spawn, `/back` (including back-to-death), `/tpa`, staff `/tp`,
  with real destination-safety checks
- **Player tools** — heal, feed, fly, god, speed, vanish, playerinfo, seen, list, near
- **Moderation** — kick, ban, tempban, unban, mute, unmute, warn, with durable expiry and
  history that cannot be erased
- **Admin GUI** — a chest-menu panel that works on **unmodified vanilla clients**

Verified by 178 passing tests across the three loader builds and an end-to-end run on a real NeoForge 21.1.235 dedicated
server, including a restart, with zero errors.

**Not yet:** GameTests, economy, chat channels, scheduler, backups, the custom-screen client
GUI, and the benchmark harness. And no real player has ever joined this server — every
runtime check so far was driven from the console.

[`IMPLEMENTATION_STATUS.md`](IMPLEMENTATION_STATUS.md) is the authoritative record, and it
distinguishes `implemented` from `tested` deliberately. Read it before trusting any feature
list, including this one.

## Release ladder

Versions advance `v1.0` → `v1.5` → `v2.0` ([ADR-0007](docs/architecture/ADR-0007.md)).
**The version number is not a completeness claim** — `IMPLEMENTATION_STATUS.md` is.

| Release | Contents |
|---|---|
| **v1.0** *(current)* | Permissions, storage, audit, command pipeline, teleport, player tools, moderation, admin GUI. Server-only. |
| **v1.5** *(planned)* | Economy and shops · chat channels, private messages, anti-spam · jail and reports · durable scheduler · backups with verified restore. |
| **v2.0** *(planned)* | Client mod, custom GUI screens, themes and accessibility, public API, integration adapters. |

**Server-only mode is supported forever.** It is not a degraded path, and every GUI action
has a command equivalent.

## Installing

Pick the jar for your loader — they are **not** interchangeable:

| Loader | Jar | Requires |
|---|---|---|
| NeoForge | `NexusCore-neoforge-<version>-1.21.1.jar` | NeoForge 21.1.235+ |
| Fabric | `NexusCore-fabric-<version>-1.21.1.jar` | Fabric Loader 0.19.3+ **and Fabric API** |
| Forge | `NexusCore-forge-<version>-1.21.1.jar` | MinecraftForge 52.1.16+ |

1. Install your loader for Minecraft 1.21.1.
2. Drop the matching jar into `mods/`.
3. Start the server.
4. Run `/nexus version` to confirm it loaded.
5. Join and run `/adminpanel`.

**Players join with an unmodified vanilla client** — no modpack, no version-matched client,
no handshake. That includes the admin GUI, which is a vanilla chest menu.

NexusCore also works in **singleplayer and on LAN worlds** on all three loaders; it
initialises against the integrated server exactly as it does against a dedicated one.

On first start NexusCore creates `<gameDir>/nexuscore/` containing `config.json`,
`permissions.json`, `audit.log`, and the data files. Everything is human-readable JSON.

**Note on `/ban` and `/kick`:** those are vanilla Minecraft commands, and NexusCore does not
override them. Use `/nexus moderation ban` or the `/nban` alias to get NexusCore's version
with durations, reasons, history, and audit. See
[docs/admin/commands.md](docs/admin/commands.md).

## Building from source

Requires JDK 21. Each loader is an **independent Gradle build** in its own directory, with its
own wrapper — see [ADR-0008](neoforge/docs/architecture/ADR-0008.md) for why.

```bash
cd neoforge && ./gradlew clean build
cd ../fabric && ./gradlew clean build
cd ../forge  && ./gradlew clean build
```

The shared sources live in `neoforge/src/main/java`; the Fabric and Forge builds compile those
same files rather than a copy, so a fix lands on all three at once. Only the entry point and
the flight controller differ per loader.

The first build for each loader downloads and decompiles Minecraft. Allow a while and do not
interrupt it — it happens once per loader. Artifacts land in each project's `build/libs/` as
`NexusCore-<loader>-<version>-1.21.1.jar`.

Other useful targets:

```bash
./gradlew compileJava   # fast syntax and API check
./gradlew test          # unit tests
./gradlew check         # tests + Checkstyle + the stub-marker gate
./gradlew runServer     # dedicated server in the development environment
```

## Repository layout

| Path | Contents |
|---|---|
| `src/main/java/com/mwtstudios/nexuscore/` | Mod sources. Packages are created at their milestone, not before. |
| `src/main/resources/META-INF/neoforge.mods.toml` | The **only** mod metadata file. |
| `src/main/resources/assets/nexuscore/lang/` | Message catalogue — server-side first, see [ADR-0003](docs/architecture/ADR-0003.md). |
| `src/test/java/` | Unit tests. |
| `config/checkstyle/` | Formatting and static-analysis ruleset ([ADR-0005](docs/architecture/ADR-0005.md)). |
| `docs/architecture/` | Architecture decision records. |
| `docs/admin/` | Permissions, command reference, admin panel. |
| `IMPLEMENTATION_STATUS.md` | The one place unfinished work is named. |
| `RELEASE_CHECKLIST.md` | Per-release verification, evidence required. |

## Engineering rules this project is held to

- **Server-authoritative.** The server validates every state-changing request. A client GUI
  is a view, never a source of truth.
- **Secure by default.** Administrative features default to denied. UI visibility is not
  security.
- **No stubs.** No `TODO`, `FIXME`, "coming soon", or `UnsupportedOperationException` in
  production sources — enforced by the `stubMarkerCheck` build gate.
- **No green-by-deletion.** A build is never made to pass by removing a permission check, a
  bounds check, a validation, or an assertion.
- **Data integrity first.** Atomic writes, schema versions, forward migrations, and an
  append-only audit chain.
- **Honest limitations.** Performance targets are labelled as targets until the M8 benchmark
  harness measures them. No player-count claim is made without evidence.

## Documentation

- [Command reference](docs/admin/commands.md)
- [Permissions](docs/admin/permissions.md) — how a decision is reached, and the one rule that surprises people
- [Admin panel](docs/admin/admin-gui.md)
- [Release notes](release-notes.md)
- [Architecture decisions](docs/architecture/) — ADR-0001 through ADR-0007
- [Implementation status](IMPLEMENTATION_STATUS.md)
- [Release checklist](RELEASE_CHECKLIST.md)
- [Changelog](CHANGELOG.md)
- [Third-party notices](THIRD_PARTY_NOTICES.md)

`docs/user`, `docs/api`, and `docs/migration` are populated as the milestones that produce
their subject matter land.
