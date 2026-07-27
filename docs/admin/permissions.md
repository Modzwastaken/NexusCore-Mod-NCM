# NexusCore — Permissions

NexusCore has its own permission engine. It does not require LuckPerms or any other
permission mod, and it does not use vanilla's operator levels except for the bootstrap
described below.

Everything is stored in `<server>/nexuscore/permissions.json`. Edits made through commands are
written immediately; edits made by hand take effect on the next `/nexus reload`.

> **Before 1.1.0 that second half was untrue.** The file was read once at startup and `/nexus
> reload` only cleared the decision cache, so a hand edit had no effect and was then **overwritten**
> by the next permission change. If you are on an earlier build, use the commands, not the file.

**A node value must be exactly `ALLOW` or `DENY`** (case and surrounding spaces are ignored).
Anything else — `denied`, `no`, `false`, an empty string, a JSON `null` — is **skipped and logged**,
never treated as a grant. Before 1.1.0 every unrecognised value was read as an *allow*, so a typo
granted the node it was meant to deny. A node pattern that does not parse is likewise skipped and
logged rather than silently dropped.

---

## The shipped groups

| Group | Weight | Inherits | What it can do |
|---|---|---|---|
| `default` | 0 | — | Homes, warps, spawn, `/back`, teleport requests, help |
| `moderator` | 50 | `default` | All player utilities, all moderation, teleport to players, set warps, the admin panel |
| `admin` | 100 | `moderator` | Everything (`nexuscore.*`) |

Every player who is not explicitly in a group is treated as being in `default`.

---

## Commands

```bash
/nexus permission check <player> <node>
```

Explains a decision rather than just reporting it — it names the matched pattern, where the
pattern came from, and the player's group chain. This is the first thing to run when access
is not behaving as expected.

```bash
/nexus permission group list
/nexus permission group add <player> <group>
/nexus permission group remove <player> <group>
```

---

## How a decision is made

For a request such as `nexuscore.command.moderation.ban`, every pattern that matches is
scored, and the highest-priority match wins.

**Specificity score**

| Pattern shape | Score | Example |
|---|---|---|
| exact match | literal segments × 2 + 1 | `nexuscore.command.moderation.ban` → 9 |
| trailing wildcard | literal segments × 2 | `nexuscore.command.moderation.*` → 6 |
| | | `nexuscore.command.*` → 4 |
| | | `nexuscore.*` → 2 |
| `*` | 0 | |

**Priority, highest first**

1. Higher specificity score
2. **A direct node on the player beats a group node**
3. Explicit `DENY` beats `ALLOW`
4. Higher group weight
5. Pattern, then source, alphabetically — so the answer is always the same

---

## The one rule that surprises people

**Rule 2 sits above rule 3.** A permission granted *directly to a player* beats a group deny
at the same specificity.

If a player has a direct grant and you want to take it away, you must **remove the direct
grant**. Adding a deny to one of their groups will not work:

```bash
# This will NOT revoke a direct grant:
#   (adding a group-level deny)
#
# This WILL:
/nexus permission check <player> <node>     # confirm the source says "direct"
# then remove the direct node from permissions.json and run /nexus reload
```

This is deliberate. A node attached to one player is an explicit decision about that person;
a group node is policy about a class of people. The specific decision wins. `/nexus permission
check` says so explicitly whenever a direct node decided the result, so you are never left
guessing.

---

## Wildcards

A wildcard is allowed **only** as the whole pattern (`*`) or as the final segment
(`nexuscore.command.*`).

`nexuscore.*.ban` is rejected when it is created. Mid-pattern wildcards make "a longer path
beats a shorter one" impossible to define, so they are refused rather than resolved
inconsistently.

---

## Denied vs never granted

`/nexus permission check` distinguishes two things that look the same from the outside:

- **DENY** — something explicitly refuses this. Find it and remove it.
- **UNDEFINED** — nothing grants it. Add a grant.

Neither is treated as permission. The distinction exists because they need opposite fixes.

---

## Operator bootstrap

A brand-new server has no groups configured, so nobody could grant themselves anything. To
avoid that deadlock, `operatorBootstrap` in `nexuscore/config.json` defaults to `true`, and
while it is on, any vanilla level-4 operator has full NexusCore access.

This is a bootstrap, not a permission model:

- It is **logged as a warning at every startup** while enabled.
- It appears in `/nexus system status` and in the admin panel's Server page.
- `/nexus permission check` reports `operator-bootstrap` as the source when it is what
  granted access, so it never hides behind a real rule.
- **An explicit DENY still wins over it.** Bootstrap fills in gaps; it does not override
  decisions you have made.

**Turn it off once real groups exist:**

1. Put yourself in `admin`: `/nexus permission group add <you> admin`
2. Confirm: `/nexus permission check <you> nexuscore.command.moderation.ban` should say
   `group:admin`, not `operator-bootstrap`
3. Set `"operatorBootstrap": false` in `nexuscore/config.json`
4. `/nexus reload`

The console is always root. That is not configurable, and it is not a bypass worth removing —
anyone with console access already controls the server process.

---

## Node naming

```
nexuscore.command.<module>.<action>     nexuscore.command.moderation.ban
nexuscore.gui.<screen>.<action>         nexuscore.gui.admin.open
nexuscore.bypass.<rule>
nexuscore.admin.<high-risk-action>
```

High-risk nodes are not included in `moderator` by default, and adding a broad wildcard to a
moderator group will pull them in — check with `/nexus permission check` after any wildcard
grant.
