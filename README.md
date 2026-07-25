# NexusCore Administration Framework

Native server administration for Minecraft Java Edition — permissions, economy, moderation,
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

**Version 0.1.0 — in development at M0 (walking skeleton).**

This repository is built milestone by milestone, and every milestone ends in a JAR that
loads. Nothing here is claimed to work unless a test or a pasted terminal transcript backs
it up. [`IMPLEMENTATION_STATUS.md`](IMPLEMENTATION_STATUS.md) is the authoritative record of
what exists — read it before the feature list below.

At M0 the mod registers exactly one command, `/nexus version`, and nothing else. That is
intentional: the toolchain is proven end to end before a single line of product logic is
written.

## Release ladder

| Release | Milestone | Contents |
|---|---|---|
| **v0.1 "Bedrock"** | M5 | Server-only. Permissions with groups · JSON storage with atomic writes · audit log · config and messages · 14 commands · homes, warps, spawn, safe teleport · player utilities · kick, ban, tempban, mute, warn. |
| **v0.5 "Console"** | M8 | Adds ledger-backed economy and shops · full moderation · chat channels and anti-spam · durable scheduler · backups with verified restore · diagnostics. Still server-only. |
| **v1.0 "Full"** | M11 | Adds the client mod, thirteen GUI screens, themes and accessibility, public API, and integration adapters. |

**Server-only mode is supported forever.** It is how v0.1 and v0.5 work, and it is not a
degraded path. Every GUI action has a command equivalent.

## Installing

NexusCore v0.1 installs on the **dedicated server only**. Players join with an unmodified
vanilla client — no modpack, no version-matched client, no handshake.

1. Install NeoForge 21.1.235 (or newer 21.1.x) for Minecraft 1.21.1 on the server.
2. Drop `NexusCore-<version>.jar` into the server's `mods/` directory.
3. Start the server.
4. Run `/nexus version` in the console to confirm it loaded.

## Building from source

Requires JDK 21. The Gradle wrapper handles everything else.

```bash
./gradlew clean build
```

The first build downloads and decompiles Minecraft. Allow up to an hour on a clean machine
and do not interrupt it — it happens once. The artifact lands at
`build/libs/NexusCore-<version>.jar`.

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

- [Architecture decisions](docs/architecture/) — ADR-0001 through ADR-0005
- [Implementation status](IMPLEMENTATION_STATUS.md)
- [Release checklist](RELEASE_CHECKLIST.md)
- [Changelog](CHANGELOG.md)
- [Third-party notices](THIRD_PARTY_NOTICES.md)

`docs/user`, `docs/admin`, `docs/api`, and `docs/migration` are populated as the milestones
that produce their subject matter land.
