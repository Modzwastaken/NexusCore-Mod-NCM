# NexusCore — Release Notes

**NexusCore Administration Framework** · MWT Studios · Minecraft 1.21.1 · Java 21

Newest first. Per [ADR-0012](docs/architecture/ADR-0012.md) this page covers **versions**
(`x.y.0`); the builds between them are in [CHANGELOG.md](CHANGELOG.md).

---

## v1.1.0 — M4 complete

Rolls up the five builds of the 1.0 line. **If you are on v1.0.0, upgrade** — it has two
confirmed critical defects, both fixed in `1.0.1` and included here.

### Security fixes carried from 1.0.1

- **`/teleport` bypassed NexusCore completely.** Vanilla registers `teleport` as the real command
  and `tp` as a redirect to it; only `tp` was taken over, so `/teleport` ran as pure vanilla with
  no permission check, no rate limit and **no audit record**.
- **Any non-player command source was granted root.** `/execute as @e[…] run nexus …` ran with
  full privileges, and `operatorBootstrap=false` constrained nobody.
- **Every moderator could give themselves creative mode**, because `/gamemode`'s permission node
  sat under the `nexuscore.command.player.*` wildcard the shipped `moderator` group holds.
- **A short write could silently truncate an audit record** and still report success.
- **Nobody below `admin` could confirm a destructive action**, so a moderator could open a
  permanent ban and never complete it. *Existing servers need one operator action:*
  `/nexus permission group add default nexuscore.command.core.confirm`

### New in this version

- **NexusCore now owns the vanilla commands it replaces** — `/ban` `/kick` `/banlist` `/pardon`
  `/list` `/tp` `/teleport` `/gamemode`. Typing `/ban` gets you a duration, a reason, history, an
  audit record and a styled screen. Set `overrideVanillaCommands=false` to opt out.
- **Safe mode.** Start with `-Dnexuscore.safemode=true` to run with only the core modules, for
  recovering a server you cannot otherwise start. Vanilla's own moderation commands are left
  intact in this mode, so you can still remove a griefer.
- **The command reference is generated from the code** and cannot drift —
  [docs/admin/commands.md](docs/admin/commands.md), 50 commands.
- **A module system** underneath: services are registered, dependency-ordered and started by a
  registry rather than wired by hand in each loader's entry point.

### Known limitations, stated plainly

- **A level-2 operator can use `/execute as <player>` to act with that player's NexusCore
  permissions**, and the audit records the impersonated player as the actor.
  `operatorBootstrap=false` does not prevent it. Scheduled as the first item of `1.1.1`.
- **Hand edits to `permissions.json` are ignored** and will be overwritten by the next permission
  change. Use the commands, not the file, until `1.1.1`.
- **`/seen <unknown name>` can stall the server** on a Mojang lookup.
- **Only one player has ever been online at a time.** v1.1.0 has been human-tested on a real
  dedicated server — the admin panel renders to an unmodified vanilla client, teleporting, player
  tools and moderation all work — but everything that needs a *second* player is unverified:
  vanish hiding you from someone else's list, a muted message failing to reach others, and
  multi-player behaviour generally. **Four confirmed vanish faults appear only on another player's
  client.** If you run a populated server, expect vanish to misbehave for onlookers.
- Every other confirmed defect is listed in
  [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md) rather than withheld.

### Verified

214 automated tests. All three loaders build reproducibly, and **CI builds all three from a bare
checkout** — the first time a genuine cold build of this project has been demonstrated anywhere.
Packaged jars were run on real NeoForge 21.1.235, Fabric and MinecraftForge 52.1.16 dedicated
servers in **both** normal and safe mode, with zero errors.

**Human-tested**: a real player joined a real dedicated server and exercised most of the feature
set, including the admin panel on an unmodified vanilla client.

---

## v1.0.0 — first public release

Released 2026-07-26. NeoForge 21.1.235+ · Java 21.

> **Superseded.** This version has two confirmed critical defects. Use `1.1.0`.

Native server administration for Minecraft Java Edition. Permissions, moderation,
teleportation, player tools, audit logging, and an admin GUI — in one mod, with no plugin
loader, no other administration mod, and no external database.

---

## Install

Three jars, one per loader. They are **not** interchangeable.

| Loader | Jar | Requires |
|---|---|---|
| NeoForge | `NexusCore-neoforge-1.0.0-1.21.1.jar` | NeoForge 21.1.235+ |
| Fabric | `NexusCore-fabric-1.0.0-1.21.1.jar` | Fabric Loader 0.19.3+ **and Fabric API** |
| Forge | `NexusCore-forge-1.0.0-1.21.1.jar` | MinecraftForge 52.1.16+ |

1. Install your loader for Minecraft 1.21.1.
2. Drop the matching jar into `mods/`.
3. Start the server.
4. Run `/nexus version`.

**Players join with an unmodified vanilla client.** No modpack, no client download, no
resource pack, no handshake. That includes the admin GUI.

Works in **singleplayer and on LAN** too, on all three loaders.

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

### NexusCore owns the commands, not vanilla

`/ban` `/kick` `/banlist` `/pardon` `/list` `/tp` `/gamemode` now run NexusCore's versions.
Type `/ban griefer 2h spam` and you get a duration, an audit record, and the styled ban
screen — not vanilla's bare kick-with-no-expiry.

`/tp` and `/gamemode` keep **all** of vanilla's syntax, rebuilt from vanilla's own argument
types: selectors (`@a`, `@e[type=…]`), absolute, relative (`~`) and local (`^`) coordinates,
rotation, and `facing <location | entity [anchor]>`.

Prefer vanilla? Set `overrideVanillaCommands=false` in `config.json` and NexusCore's versions
move to `/nban`, `/nkick`, and so on.

### Death messages

Death messages are styled on all three loaders. NexusCore turns off the `showDeathMessages`
game rule so deaths are not announced twice, and says so at startup with the command to undo
it. Set `styleDeathMessages=false` to go back to vanilla.

**Join and leave messages are still vanilla.** Minecraft broadcasts those from inside
`PlayerList` with no hook before it on any loader — verified in the sources, not assumed —
so replacing them needs a mixin, and NexusCore v1.0 ships none by policy. It is on the v1.5
list.

### The message style

All messages follow one visual language, and every one of them can be reworded by the
operator in `nexuscore/messages.json`:

```
BAN » Banned Deathcember for 2h.: "Toxic behavior"
MUTE » You are muted and cannot chat. — Reason: "spam" — Expires in: 14min. 58sec.
SEEN » Deathcember was last seen 2min. 16sec. ago. — First joined: 15/04/2026
```

A banned player sees a full styled screen: `⚠ YOU ARE TEMPORARILY BANNED ⚠`, the date they
were banned, the quoted reason, the exact unban date with a live countdown, and your
configurable appeal line (set `banAppealMessage` in `config.json` — a Discord invite fits
there).

Note: the styling covers what NexusCore does today. AFK detection, chat channels, mentions,
rank nametags, and player levels — visible in some community styling references — are
feature work planned for v1.5, not part of this release.

### Loader differences you should know about

- **`/fly` behaves differently on Fabric and Forge.** NeoForge grants flight through an
  additive `creative_flight` attribute, so NexusCore revokes only its own grant and coexists
  with other flight mods. Neither Fabric nor Forge has an equivalent (verified against Forge
  52.1.16), so both write a single `mayfly` flag where the last mod to write it wins. If you
  run another flight-granting mod on those loaders, expect interference. The active mechanism
  is logged at startup.
- **Fabric requires Fabric API.** NeoForge and Forge have no extra dependency.
- The three jars are not interchangeable; the loader is in the filename for that reason.

### Known limitations

- **No GameTests.** Unit coverage is good (94 catalogue keys; 172 tests on the shared sources); in-world automated tests do not exist.
- **Bans are NexusCore records, not vanilla ban-list entries.** That is what gives them
  durations, actor UUIDs, reasons, and audit linkage — but if you remove the mod, NexusCore
  bans stop applying.
- **`/ban-ip` and `/pardon-ip` are not taken over.** They are separate vanilla commands, so an
  IP ban is neither audited nor shown by `/banlist`. (`/ban`, `/kick`, `/banlist`, `/pardon`,
  `/list`, `/tp`, `/teleport` and `/gamemode` *are* NexusCore's — see the v1.1.0 notes above.)
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

Every jar was run on a **real dedicated server for its own loader**, not just built.

| Check | NeoForge | Fabric | Forge |
|---|---|---|---|
| `clean build` from a clean checkout | pass | pass | pass |
| Unit tests | 172 | 3 | 3 |
| Checkstyle + stub-marker gate | clean | clean | n/a |
| Packaged jar on a real dedicated server | pass | pass | pass |
| Server `ERROR` lines caused by NexusCore | 0 | 0 | 0 |
| Clean shutdown, exit 0 | yes | yes | yes |
| Commands, permissions, `/seen` | pass | pass | pass |
| Ban confirmation + token-reuse refusal | pass | pass | pass |
| Audit chain intact | pass | pass | pass |
| **Client / singleplayer initialisation** | pass | pass | pass |

The Fabric client run loaded an actual singleplayer world and confirmed the integrated server
starts with NexusCore registered against it:

```
[Render thread] NexusCore ready on Fabric: 73 message(s), 3 permission group(s)
[Worker-Main-4] NexusCore did not override /list — ...
[Server thread] Starting integrated minecraft server version 1.21.1
```

**Not verified:** anything requiring a second real player. The admin GUI has never been
rendered to a client; vanish, chat muting, and ban-at-login are wired and unit-covered but
unobserved in a live session. The Forge build has not been run on a client.

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

It is being built as a sequence of numbered builds rather than one jump, so each increment is
installable and testable ([ADR-0012](docs/architecture/ADR-0012.md)): `1.0.1` … `1.0.5`, then
`1.1.0`, and so on to `1.5.0`.

**`x.y.0` is a version; `x.y.1`–`x.y.5` are its builds** — each a hotfix or a pre-release. This
page covers versions only.

**If you are running v1.0.0, take the `1.0.1` hotfix**: it closes a permission bypass. Otherwise
v1.0.0 remains the production version until v1.5.0 arrives.

What each build is planned to contain is in
[IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md#the-road-to-v150).

- Full record of what exists: [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md)
- Commands: [docs/admin/commands.md](docs/admin/commands.md)
- Permissions: [docs/admin/permissions.md](docs/admin/permissions.md)
- Admin panel: [docs/admin/admin-gui.md](docs/admin/admin-gui.md)

## Licence

All Rights Reserved — MWT Studios. The JAR embeds no third-party code; see
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
