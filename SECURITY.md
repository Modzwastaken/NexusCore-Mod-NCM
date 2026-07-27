# Security Policy

NexusCore controls who can do what on a Minecraft server: permissions, bans, and an audit log
meant to be tamper-evident. A defect in any of those is a security defect, not a bug report.

## Reporting a vulnerability

**Do not open a public issue.** Use GitHub's
[private vulnerability reporting](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing-information-about-vulnerabilities/privately-reporting-a-security-vulnerability)
on this repository, or email **mwt@mwtstudios.net**.

Please include the loader and version, what the attacker starts with (an ordinary player? a
level-2 operator? a command block?), and the steps. A concrete command sequence is worth more
than a description.

You will get an acknowledgement. This is a small project with one maintainer, so there is no
guaranteed response time — that is stated plainly rather than promised and missed.

## Supported versions

| Version | Supported |
|---|---|
| `1.1.0` | Yes — the current release |
| `1.0.0` | **No.** It has two confirmed critical defects. Upgrade to `1.1.0`. |

Only `x.y.0` versions and `x.y.1`–`x.y.5` builds exist; see
[ADR-0012](docs/architecture/ADR-0012.md).

This table lists only versions that can actually be obtained. `1.0.1` through `1.0.5` are real
builds on the ladder and their fixes are in `1.1.0`, but no `1.0.x` build was ever published as a
download — there is no tag, no GitHub Release and no `archived/v1.0.x/`. A security policy that
tells you to install something you cannot install is worse than one that omits it.

## What counts

**In scope**

- Any way to obtain a permission you have not been granted, including via `/execute`, a command
  block, a datapack function, or the admin GUI
- Anything that makes the audit log wrong: a missing record, a forged actor, a broken hash chain
- Reading or writing files outside the `nexuscore/` data directory
- A command that crashes the server or hangs the server thread
- A moderation action that reports success without taking effect

**Out of scope**

- Anything requiring server-console or filesystem access — the console is root by design, and an
  operator who can edit `config.json` can already do anything
- Vanilla Minecraft or mod-loader vulnerabilities; report those upstream
- Behaviour of other mods installed alongside NexusCore

## Known issues

Confirmed defects that are not yet fixed are listed openly in
[IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md) under *Confirmed defects awaiting a fix*,
including the security-relevant ones. They are published rather than withheld because an operator
running this software should be able to decide for themselves whether a known gap matters to them.

The most significant one at `1.1.0`: `IdentityService.resolve()` falls back to a blocking Mojang
HTTP lookup on the server thread. Any ordinary player can reach it with `/seen <unknown name>`, so
a slow or unreachable Mojang API stalls the whole server for as long as that request takes. A
non-blocking `getAsync` already exists; this is the first item scheduled for `1.1.1`.

The `/execute as <player>` permission borrow that this section previously named was **fixed in
`1.1.0`** — portably, and **without** the per-loader access widener once thought necessary — along
with the sign and lectern confused-deputy paths the same fix closed, and two permission values that
failed open. See [CHANGELOG.md](CHANGELOG.md).
