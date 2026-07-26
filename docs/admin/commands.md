# NexusCore — Command Reference

Every command lives under the canonical `/nexus` root. Short aliases are registered as a
convenience where nothing else owns the name.

**Every command checks its permission on the server**, is rate limited, and writes an audit
record with a correlation id. Nothing here is enforced by hiding a button.

---

## Alias collisions — read this first

`/ban`, `/kick`, and `/banlist` are **vanilla Minecraft commands**. NexusCore does not
override them (§12.3), because silently replacing a vanilla command is how mods break each
other.

That means typing `/ban` gives you **vanilla's** ban, which has no duration, no reason
history, no audit trail, and no NexusCore record. To get NexusCore's version use either:

```bash
/nexus moderation ban <player> [reason]     # canonical
/nban <player> [reason]                     # collision-free alias
```

NexusCore registers an `n`-prefixed alias automatically whenever a short name is already
taken, and logs which ones at startup:

```
NexusCore did not override /ban — another command owns it. Use /nban or /nexus ... instead.
```

`/tp` and `/gamemode` are vanilla and are deliberately left alone entirely.

---

## Core

| Command | Permission | Notes |
|---|---|---|
| `/nexus` · `/nexus help` | `nexuscore.command.core.help` | Lists only what you may actually use |
| `/nexus version` | `nexuscore.command.core.version` | |
| `/nexus reload` | `nexuscore.command.core.reload` | Transactional: a bad file leaves the running config alone |
| `/nexus confirm <token>` | `nexuscore.command.core.confirm` | Completes a destructive action |
| `/nexus system status` | `nexuscore.command.system.status` | Data paths, cache stats, record counts |
| `/nexus audit verify` | `nexuscore.command.audit.verify` | Walks the hash chain and reports the first break |

## Permissions

| Command | Permission |
|---|---|
| `/nexus permission check <player> <node>` | `nexuscore.command.permission.check` |
| `/nexus permission group list` | `nexuscore.command.permission.group` |
| `/nexus permission group add <player> <group>` | `nexuscore.command.permission.group` |
| `/nexus permission group remove <player> <group>` | `nexuscore.command.permission.group` |

See [permissions.md](permissions.md) for how a decision is reached.

## Player utilities

| Command | Alias | Permission |
|---|---|---|
| `/nexus player heal [player]` | `/heal` | `nexuscore.command.player.heal` |
| `/nexus player feed [player]` | `/feed` | `nexuscore.command.player.feed` |
| `/nexus player fly [player]` | `/fly` | `nexuscore.command.player.fly` |
| `/nexus player god [player]` | `/god` | `nexuscore.command.player.god` |
| `/nexus player speed <0.1–10> [fly]` · `/nexus player speed reset` | `/speed` | `nexuscore.command.player.speed` |
| `/nexus player vanish` | `/vanish` | `nexuscore.command.player.vanish` |
| `/nexus player info <player>` | `/playerinfo` | `nexuscore.command.player.info` |

**Flight** is granted through NeoForge's `creative_flight` attribute, not by writing the
deprecated ability flag, so it coexists with other mods that grant flight instead of fighting
them.

**Vanish** makes a player invisible and removes them from the player list. It is a staff
convenience, not a cloaking device — a vanished player still occupies space and still blocks
a doorway.

## Teleport

| Command | Alias | Permission |
|---|---|---|
| `/nexus teleport home [name]` | `/home` | `nexuscore.command.teleport.home` |
| `/nexus teleport sethome [name]` | `/sethome` | `nexuscore.command.teleport.sethome` |
| `/nexus teleport delhome <name>` | `/delhome` | `nexuscore.command.teleport.delhome` |
| `/nexus teleport homes` | `/homes` | `nexuscore.command.teleport.homes` |
| `/nexus teleport warp <name>` | `/warp` | `nexuscore.command.teleport.warp` |
| `/nexus teleport setwarp <name>` | `/setwarp` | `nexuscore.command.teleport.setwarp` |
| `/nexus teleport delwarp <name>` | `/delwarp` | `nexuscore.command.teleport.setwarp` |
| `/nexus teleport warps` | `/warps` | `nexuscore.command.teleport.warps` |
| `/nexus teleport spawn` | `/spawn` | `nexuscore.command.teleport.spawn` |
| `/nexus teleport setspawn` | `/setspawn` | `nexuscore.command.teleport.setspawn` |
| `/nexus teleport back` | `/back` | `nexuscore.command.teleport.back` |
| `/nexus teleport tpa <player>` | `/tpa` | `nexuscore.command.teleport.request` |
| `/nexus teleport tpaccept` | `/tpaccept` | `nexuscore.command.teleport.accept` |
| `/nexus teleport tpdeny` | `/tpdeny` | `nexuscore.command.teleport.deny` |

**Safety.** Every destination is checked for solid footing, open head space, no fluid, no
lava, fire, cactus, magma, berry bush, powder snow, or portal block, and for being inside the
world border and build height. If no safe spot is found within the configured search height,
the teleport is **refused with the specific reason** — never redirected somewhere "close
enough".

**Warmup.** Teleports wait `teleportWarmupSeconds` (default 3) and cancel if the player
moves more than `teleportWarmupMoveTolerance` blocks. Safety is re-checked at the moment of
arrival, not only when the command was typed. `/back` is only recorded once a teleport has
actually committed.

## Moderation

| Command | Alias | Permission |
|---|---|---|
| `/nexus moderation kick <player> [reason]` | `/nkick` | `nexuscore.command.moderation.kick` |
| `/nexus moderation ban <player> [reason]` | `/nban` | `nexuscore.command.moderation.ban` |
| `/nexus moderation tempban <player> <duration> [reason]` | `/tempban` | `nexuscore.command.moderation.tempban` |
| `/nexus moderation unban <player>` | `/unban` | `nexuscore.command.moderation.unban` |
| `/nexus moderation mute <player> [duration] [reason]` | `/mute` | `nexuscore.command.moderation.mute` |
| `/nexus moderation unmute <player>` | `/unmute` | `nexuscore.command.moderation.unmute` |
| `/nexus moderation warn <player> <reason>` | `/warn` | `nexuscore.command.moderation.warn` |
| `/nexus moderation warnings <player>` | `/warnings` | `nexuscore.command.moderation.warnings` |
| `/nexus moderation banlist` | `/nbanlist` | `nexuscore.command.moderation.banlist` |

**A permanent ban requires confirmation.** It prints a token bound to that exact player and
reason:

```
/nexus moderation ban Griefer breaking spawn
  → Confirm this ban. Run /nexus confirm a1b2c3d4e5f6a7b8c9 within 30s.
/nexus confirm a1b2c3d4e5f6a7b8c9
  → Confirmed: permanently ban Griefer (breaking spawn)
```

The token is single-use and cannot ban anyone else, ban the same player for a different
reason, or be spent by a different staff member. Reusing it is refused.

**Punishments are never deleted.** `/unban` marks the record inactive and stamps who lifted
it and when. The history stays, which is what makes a repeat offender visible.

**Bans are enforced by NexusCore.** They are NexusCore records, not vanilla ban-list entries,
which is what gives them durations, actor UUIDs, reasons, and audit linkage. If you remove
the mod, NexusCore bans stop applying.

### Durations

Accepted: `30s` `15m` `2h` `7d` `1w`, combined in **descending** order (`1d12h30m`), or a
permanent keyword (`permanent`, `perm`, `forever`, `never`, `infinite`).

Refused, with a message saying why: ascending or repeated units (`30m1d`, `1h1h`), fractions
(`1.5h`), negatives, zero, bare numbers, and anything over ten years.

## Admin panel

| Command | Permission |
|---|---|
| `/nexus gui` · `/adminpanel` | `nexuscore.gui.admin.open` |

See [admin-gui.md](admin-gui.md).

---

## What is not here yet

Economy, shops, chat channels, private messages, jail, reports, the scheduler, backups, and
world administration are **not implemented**. They are milestones M6 to M8. See
`IMPLEMENTATION_STATUS.md` for the authoritative list — nothing above is listed unless it
exists and has been run.
