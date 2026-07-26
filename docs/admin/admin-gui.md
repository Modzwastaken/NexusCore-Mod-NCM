# NexusCore — Admin Panel

Open it with `/adminpanel` or `/nexus gui`. Requires `nexuscore.gui.admin.open`.

**It works on an unmodified vanilla client.** No client mod, no modpack, no resource pack, no
handshake. Players and staff join your server the normal way.

---

## Why it looks like a chest

NexusCore v0.1 is server-only by design (§3.1 of the build specification). A custom Minecraft
`Screen` is client-side code — it cannot be opened on a client that does not have the mod
installed. A custom-screen admin panel in a server-only release would therefore be invisible
to every single person using it.

The specification anticipates this and permits "a vanilla-container menu where one genuinely
fits" (§6.1). A chest grid fits: every vanilla client already knows how to render one, so the
panel works for everyone today.

The richer custom-screen interface is milestone M9, and it arrives alongside the client mod.
When it does, **this panel keeps working**, because server-only remains a supported mode
forever.

---

## Screens

### Dashboard

| Slot | Opens |
|---|---|
| Player Manager | The online player list |
| Moderation | Active bans |
| Permissions | Group overview |
| Server | Diagnostics, audit chain state, bootstrap status |
| Close | Closes the panel |

Each tile shows live counts and names the equivalent command underneath, so the panel teaches
the commands rather than replacing them.

### Player Manager

A paginated list of online players. Each head shows health, hunger, game mode, world,
coordinates, and ping. Page size is `adminGuiPageSize` in `config.json` (default 28, max 45).

Click a player to manage them:

| Action | Permission checked |
|---|---|
| Heal | `nexuscore.command.player.heal` |
| Feed | `nexuscore.command.player.feed` |
| Toggle flight | `nexuscore.command.player.fly` |
| Toggle god mode | `nexuscore.command.player.god` |
| Teleport to them | `nexuscore.command.teleport.tp` |
| Kick | `nexuscore.command.moderation.kick` |

### Kick confirmation

Kicking opens a confirmation screen naming the exact player. The confirm button carries a
token bound to that player — it cannot kick anyone else, and it is spent on use.

### Moderation

Every active ban, with reason, who issued it, and time remaining. Lifting is done from the
command line (`/nexus moderation unban <player>`), which keeps the destructive action on a
path that is audited and typed rather than one click away.

### Permissions

Every group with its weight, and which one is the default. Editing is done through
`/nexus permission ...`.

### Server

NexusCore version, online count, world count, data directory, audit record count and **chain
verification state**, permission cache statistics, and whether operator bootstrap is still
enabled.

---

## Security

**Permission is re-checked on every click, not when the panel was drawn.** If a staff
member's access is revoked while their panel is open, the next click is refused and the
refusal is written to the audit log. Hiding a button is a courtesy to the operator; it is
never the control (§15: UI visibility is not security).

**The panel is read-only as a container.** Clicks never move items — not by picking up, not
by shift-click, not by number-key swap, not by dragging. There is no code path by which an
item enters or leaves it, so the panel cannot be used as free storage and the icons cannot be
taken.

**Every action is audited** with the actor, the target, the outcome, and a correlation id,
tagged `via: gui` so panel actions are distinguishable from typed commands.

---

## Everything has a command equivalent

Nothing in the panel is the only way to do something (§6.3). The panel is a convenience over
the command tree, which matters for accessibility and for operators who work from the
console. Each screen names the commands it corresponds to.

## Turning it off

Set `"adminGuiEnabled": false` in `nexuscore/config.json` and run `/nexus reload`. The
commands continue to work.
