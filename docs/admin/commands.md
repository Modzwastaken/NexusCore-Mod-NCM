<!-- GENERATED FILE. Do not edit by hand.
     Source: common/src/main/java/com/mwtstudios/nexuscore/command/CommandCatalogue.java
     Regenerate: ./gradlew :generateCommandDocs (from the neoforge project)
     CommandDocsTest fails the build if this file and the catalogue disagree. -->

# NexusCore — Command Reference

Every command lives under the canonical `/nexus` root. Short aliases are registered as a
convenience where nothing else owns the name.

**Every command checks its permission on the server**, is rate limited, and writes an audit
record with a correlation id. Nothing here is enforced by hiding a button.

---

## Vanilla commands NexusCore takes over

`/ban` `/kick` `/banlist` `/pardon` `/list` `/tp` `/teleport` `/gamemode` are **NexusCore's**
when `overrideVanillaCommands` is true, which is the default. Typing `/ban` gets you
NexusCore's ban — with a duration, a reason history, an audit record and a styled screen.

`/tp`, `/teleport` and `/gamemode` are rebuilt from vanilla's own argument types, so
selectors, absolute and relative coordinates, rotation and `facing` all still work.

Set `overrideVanillaCommands=false` to leave vanilla in charge; NexusCore's versions then
stay on the `n`-prefixed names (`/nban`, `/nkick`, …). NexusCore also falls back to those
names automatically if another mod owns the command, and logs which ones at startup.

**Not taken over:** `/ban-ip` and `/pardon-ip` are separate vanilla commands. An IP ban is
therefore neither audited nor listed by NexusCore.

---

## Safe mode

Starting the server with `-Dnexuscore.safemode=true` leaves the optional modules out.
Commands belonging to them refuse with an explanation rather than disappearing:

- **teleport** — 16 command(s) unavailable
- **player-utilities** — 11 command(s) unavailable
- **moderation** — 10 command(s) unavailable

---

## Core

| Command | Alias | Permission | Module |
|---|---|---|---|
| `/nexus version` — show the running NexusCore version | — | `nexuscore.command.core.version` | core |
| `/nexus help` — list the commands you may use | — | `nexuscore.command.core.help` | core |
| `/nexus reload` — re-read config.json and report what changed, transactionally | — | `nexuscore.command.core.reload` | core |
| `/nexus confirm <token>` — complete a destructive action you were prompted about | — | `nexuscore.command.core.confirm` | core |

## System

| Command | Alias | Permission | Module |
|---|---|---|---|
| `/nexus system status` — show data directory, counts, and audit chain state | — | `nexuscore.command.system.status` | core |

## Audit

| Command | Alias | Permission | Module |
|---|---|---|---|
| `/nexus audit tail [count]` — show the most recent audit records | — | `nexuscore.command.audit.tail` | core |
| `/nexus audit verify` — re-hash the whole audit chain and report tampering | — | `nexuscore.command.audit.verify` | core |

## Permissions

| Command | Alias | Permission | Module |
|---|---|---|---|
| `/nexus permission check <player> <node>` — explain why a player does or does not have a node | — | `nexuscore.command.permission.check` | core |
| `/nexus permission set <player> <node> <allow|deny>` — grant or deny a node directly on a player | — | `nexuscore.command.permission.set` | core |
| `/nexus permission group <...>` — inspect and edit groups and their inheritance | — | `nexuscore.command.permission.group` | core |

## Teleport

| Command | Alias | Permission | Module |
|---|---|---|---|
| `/nexus teleport home [name]` — go to a home | `/home` | `nexuscore.command.teleport.home` | teleport |
| `/nexus teleport sethome <name>` — save your current position as a home | `/sethome` | `nexuscore.command.teleport.sethome` | teleport |
| `/nexus teleport delhome <name>` — delete one of your homes | `/delhome` | `nexuscore.command.teleport.delhome` | teleport |
| `/nexus teleport homes` — list your homes | `/homes` | `nexuscore.command.teleport.homes` | teleport |
| `/nexus teleport warp <name>` — go to a warp | `/warp` | `nexuscore.command.teleport.warp` | teleport |
| `/nexus teleport setwarp <name>` — create a server-wide warp here | `/setwarp` | `nexuscore.command.teleport.setwarp` | teleport |
| `/nexus teleport warps` — list the warps | `/warps` | `nexuscore.command.teleport.warps` | teleport |
| `/nexus teleport spawn` — go to spawn | `/spawn` | `nexuscore.command.teleport.spawn` | teleport |
| `/nexus teleport setspawn` — set the spawn point here | `/setspawn` | `nexuscore.command.teleport.setspawn` | teleport |
| `/nexus teleport back` — return to your last position, including where you died | `/back` | `nexuscore.command.teleport.back` | teleport |
| `/nexus teleport request <player>` — ask a player for permission to teleport to them | `/tpa` | `nexuscore.command.teleport.request` | teleport |
| `/nexus teleport accept` — accept a pending teleport request | `/tpaccept` | `nexuscore.command.teleport.accept` | teleport |
| `/nexus teleport deny` — refuse a pending teleport request | `/tpdeny` | `nexuscore.command.teleport.deny` | teleport |
| `/nexus teleport tp <player> [destination]` — staff teleport, with no warmup, cooldown or safety search | — | `nexuscore.command.teleport.tp` | teleport |
| `/tphere <player>` — bring a player to you — same node as the staff teleport above | — | `nexuscore.command.teleport.tp` | teleport |
| `/nexus teleport delwarp <name>` — delete a warp — shares the setwarp node, so creating implies deleting | `/delwarp` | `nexuscore.command.teleport.setwarp` | teleport |

## Player utilities

| Command | Alias | Permission | Module |
|---|---|---|---|
| `/nexus player heal [player]` — restore health | `/heal` | `nexuscore.command.player.heal` | player-utilities |
| `/nexus player feed [player]` — restore hunger | `/feed` | `nexuscore.command.player.feed` | player-utilities |
| `/nexus player fly [player]` — toggle flight | `/fly` | `nexuscore.command.player.fly` | player-utilities |
| `/nexus player god [player]` — toggle invulnerability | `/god` | `nexuscore.command.player.god` | player-utilities |
| `/nexus player speed <value>` — set your own walk and fly speed | `/speed` | `nexuscore.command.player.speed` | player-utilities |
| `/nexus player vanish` — hide yourself from the player list and from sight | `/vanish` | `nexuscore.command.player.vanish` | player-utilities |
| `/nexus player info <player>` — show a player's state, groups and punishments | `/playerinfo` | `nexuscore.command.player.info` | player-utilities |
| `/nexus player seen <player>` — show when a player was last online | `/seen` | `nexuscore.command.player.seen` | player-utilities |
| `/nexus player list` — list online players, respecting vanish | `/list` | `nexuscore.command.player.list` | player-utilities |
| `/nexus player near [radius]` — list players close to you | `/near` | `nexuscore.command.player.near` | player-utilities |

## Staff

| Command | Alias | Permission | Module |
|---|---|---|---|
| `/gamemode <mode> [targets]` — change game mode, with vanilla's full selector grammar preserved | — | `nexuscore.command.staff.gamemode` | core |

## Moderation

| Command | Alias | Permission | Module |
|---|---|---|---|
| `/nexus moderation kick <player> [reason]` — disconnect a player with a styled reason | `/kick` | `nexuscore.command.moderation.kick` | moderation |
| `/nexus moderation ban <player> [reason]` — ban permanently — needs confirmation | `/ban` | `nexuscore.command.moderation.ban` | moderation |
| `/nexus moderation tempban <player> <duration> [reason]` — ban until a durable expiry | `/tempban` | `nexuscore.command.moderation.tempban` | moderation |
| `/nexus moderation unban <player>` — lift a ban, keeping the history | `/unban` | `nexuscore.command.moderation.unban` | moderation |
| `/nexus moderation mute <player> [duration] [reason]` — stop a player chatting | `/mute` | `nexuscore.command.moderation.mute` | moderation |
| `/nexus moderation unmute <player>` — lift a mute, keeping the history | `/unmute` | `nexuscore.command.moderation.unmute` | moderation |
| `/nexus moderation warn <player> <reason>` — record a warning against a player | `/warn` | `nexuscore.command.moderation.warn` | moderation |
| `/nexus moderation warnings <player>` — list a player's warnings | `/warnings` | `nexuscore.command.moderation.warnings` | moderation |
| `/nexus moderation banlist` — list active bans | `/banlist` | `nexuscore.command.moderation.banlist` | moderation |

## Admin GUI

| Command | Alias | Permission | Module |
|---|---|---|---|
| `/adminpanel` — open the admin panel, a vanilla chest menu | — | `nexuscore.gui.admin.open` | core |
| `(GUI) players page` — browse players and act on one | — | `nexuscore.gui.admin.players` | player-utilities |
| `(GUI) moderation page` — review and lift punishments | — | `nexuscore.gui.admin.moderation` | moderation |
| `(GUI) permissions page` — inspect groups and nodes | — | `nexuscore.gui.admin.permissions` | core |

---

## Totals

| | |
|---|---|
| Commands described | 50 |
| Permission nodes | 48 |
