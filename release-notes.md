# NexusCore v1.0.0 — Release Notes

**NexusCore Administration Framework** · MWT Studios
Minecraft 1.21.1 · NeoForge 21.1.235+ · Java 21 · Released 2026-07-26

Native server administration for Minecraft Java Edition. Permissions, moderation,
teleportation, player tools, audit logging, and an admin GUI — in one mod, with no plugin
loader, no other administration mod, and no external database.

---

## Install

1. Install **NeoForge 21.1.235 or newer 21.1.x** for Minecraft 1.21.1 on your server.
2. Drop `NexusCore-1.21.1-1.0.0-neoforge.jar` into the server's `mods/` folder.
3. Start the server.
4. Run `/nexus version` in the console.

**Players join with an unmodified vanilla client.** No modpack, no client download, no
resource pack, no handshake. That includes the admin GUI.

On first start NexusCore creates `<server>/nexuscore/` with `config.json`,
`permissions.json`, `audit.log`, and its data files — all human-readable JSON.

---

## What is in v1.0

### Permissions
Native engine with groups, multiple inheritance, and cycle rejection. Wildcards, explicit
denies, a bounded decision cache, and three shipped groups (`default`, `moderator`, `admin`).

`/nexus permission check <player> <node>` **explains** its answer — the matched pattern,
where it came from, and the player's group chain — rather than printing a bare yes or no.

### Moderation
`kick` · `ban` · `tempban` · `unban` · `mute` · `unmute` · `warn` · `warnings` · `banlist`

Durations accept `30s 15m 2h 7d 1w` and combinations in descending order (`1d12h30m`).
Ambiguous input is refused with a message saying why.

A permanent ban requires a **confirmation token** bound to that exact player and reason. It
is single-use and cannot ban anyone else.

**Punishments are never deleted.** Unbanning marks the record inactive and stamps who lifted
it — a repeat offender's history stays visible.

### Teleportation
`home` · `sethome` · `delhome` · `homes` · `warp` · `setwarp` · `delwarp` · `warps` ·
`spawn` · `setspawn` · `back` · `tpa` · `tpaccept` · `tpdeny` · `tp` · `tphere`

Every destination is checked for solid footing, head space, fluids, lava, fire, cactus,
magma, berry bushes, powder snow, portals, the world border, and build height. If nowhere
safe is found, the teleport is **refused with the reason** — never redirected somewhere
"close enough". Safety is re-checked at the moment of arrival, not just when you typed the
command.

`/back` also returns you to **where you died**.

### Player tools
`heal` · `feed` · `fly` · `god` · `speed` · `vanish` · `playerinfo` · `seen` · `list` · `near`

Flight is granted through NeoForge's `creative_flight` attribute rather than the deprecated
ability flag, so it coexists with other mods that grant flight instead of overwriting them.

Vanished staff are hidden from `/list`, `/near`, and `/seen` for anyone without the vanish
permission.

### Admin GUI
`/adminpanel` — a chest-menu panel that **works on unmodified vanilla clients**.

Dashboard, paginated player list, per-player heal / feed / fly / god / teleport / kick,
active-ban overview, permission groups, and server diagnostics.

Permission is re-checked on **every click**, not when the panel was drawn. The container is
read-only: there is no code path that moves an item into or out of it.

### Audit
Append-only, SHA-256 hash-chained. `/nexus audit verify` walks the chain and names the first
record that was altered or removed. `/nexus audit tail` shows recent activity.

IP addresses, passwords, and tokens are redacted **at write time**, so they never enter the
file at all.

### Storage
Atomic writes (`.tmp` → fsync → atomic move) with `.bak` retention. A corrupt file is moved
aside and reported — never silently replaced with defaults. Every path is resolved to a real
path and proven to be inside the data directory, so a symlink cannot escape it.

---

## What is **not** in v1.0

This matters more than the feature list, so it is here rather than in a roadmap.

**Version 1.0 does not mean feature complete.** See [ADR-0007](docs/architecture/ADR-0007.md).

- **No economy.** No balances, no `/pay`, no shops.
- **No chat system.** No private messages, channels, staff chat, or anti-spam.
- **No jail, reports, or staff notes.**
- **No scheduler, announcements, or automated backups.**
- **No custom GUI screens.** The admin panel is a chest menu. Custom screens need a client
  mod, which does not exist yet.
- **No public Java API** for other mods to build against.
- **No benchmark harness.** Every performance figure anywhere in this project is a **target,
  not a measurement**, and is labelled as such. No player-count claim is made.

### Known limitations

- **No GameTests.** Unit coverage is good (162 tests); in-world automated tests do not exist.
- **Bans are NexusCore records, not vanilla ban-list entries.** That is what gives them
  durations, actor UUIDs, reasons, and audit linkage — but if you remove the mod, NexusCore
  bans stop applying.
- **`/ban`, `/kick`, and `/banlist` are vanilla commands and NexusCore does not override
  them.** Use `/nexus moderation ban` or the `/nban` alias. NexusCore logs which aliases it
  could not take at startup.
- **Vanish is not a cloaking device.** A vanished player is invisible and off the player
  list, but still occupies space and still blocks a doorway.
- **`/back` and vanish do not survive a restart.** Both are session state by design.
- **Operator bootstrap is on by default.** Any level-4 operator has full NexusCore access
  until you turn it off. This is warned at every startup — see
  [docs/admin/permissions.md](docs/admin/permissions.md) for the four-step handover.
- **Multi-player behaviour is untested.** Every runtime verification for this release was
  single-session and console-driven.

---

## Verification performed

| Check | Result |
|---|---|
| `./gradlew clean build` from a clean checkout | exit 0 |
| Unit tests | **162 passed, 0 failed, 0 skipped** |
| Checkstyle (main + test) | 0 violations |
| Stub-marker gate | 0 violations across 24 files |
| NexusCore-introduced compiler warnings | 0 |
| Real NeoForge 21.1.235 dedicated server, packaged JAR | starts, runs, stops cleanly, **0 errors** |
| Restart persistence | permissions, punishments, warnings, homes, audit chain all intact |
| Audit chain verification | intact across restart |
| Confirmation token reuse | refused |

**Not verified:** anything requiring a real player in the world. The admin GUI has never
been rendered to a client; vanish, chat muting, and ban-at-login are wired but unobserved.

---

## Upgrading

v1.0.0 is the first release. There is nothing to upgrade from.

Data documents each carry a `schemaVersion`, so future releases can migrate them. Per
[ADR-0007](docs/architecture/ADR-0007.md), a minor release (v1.5) will read a v1.0 data
directory without operator intervention.

---

## Next

**v1.5** is intended to add the economy, chat channels and private messages, jail and
reports, the durable scheduler, and backups with verified restore.

- Full record of what exists: [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md)
- Commands: [docs/admin/commands.md](docs/admin/commands.md)
- Permissions: [docs/admin/permissions.md](docs/admin/permissions.md)
- Admin panel: [docs/admin/admin-gui.md](docs/admin/admin-gui.md)

## Licence

All Rights Reserved — MWT Studios. The JAR embeds no third-party code; see
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
