# Release archive

Builds exactly as they were handed out. These files are never rebuilt or regenerated: a jar in
here is the one somebody installed, so a bug report against it can be reproduced against the
same bytes.

Two kinds live here, and the difference matters when reading a bug report:

| Type | What it is | Who ran it |
|---|---|---|
| **Version** | An `x.y.0` build that completed the full `RELEASE_CHECKLIST.md`. | The public. |
| **Build** | An `x.y.1`–`x.y.5` build — a hotfix or a pre-release ([ADR-0012](../docs/architecture/ADR-0012.md)). Not a version. | Testers, or anyone told to take a hotfix. |

A build is archived for the same reason a version is — so the bytes someone ran still
exist — and under the same immutability rule. It carries none of a release's promises.

**What lands here is decided by one question: was it handed to somebody?** Every `x.y.0` version
is archived. A build is archived when someone was asked to install it — a published hotfix
always, an internal pre-release only if it left the machine. Builds nobody ran live in their tags.

## Layout

```
archived/
└── v<version>/
    ├── NexusCore-neoforge-<version>-<mc>.jar
    ├── NexusCore-fabric-<version>-<mc>.jar
    ├── NexusCore-forge-<version>-<mc>.jar
    └── checksums.txt
```

## Rules

- A release is archived **only after it is confirmed published** — not when it is built, and
  not when it is tagged. A tag can move; a published download cannot.
- A build is archived **only after it has been handed to someone**, by the same reasoning. A
  build nobody installed does not belong here; it is reproducible from its tag.
- Archived files are immutable. If something is wrong with a release, the fix is a new
  version, never an edit here.
- `checksums.txt` is generated from the archived jars themselves, so it verifies what is in
  this folder rather than what a build directory happened to contain at the time.
- All three loader jars are archived together. A release is the set, not one loader.

## Archived builds

| Version | Type | Date | Minecraft | Loaders | Notes |
|---|---|---|---|---|---|
| [v1.1.0](v1.1.0/) | Version | 2026-07-26 | 1.21.1 | NeoForge, Fabric, Forge | M4 complete, human-tested. **Archived at tag, before publication** — see below |
| [v1.0.0](v1.0.0/) | Release | 2026-07-26 | 1.21.1 | NeoForge, Fabric, Forge | First public release. **Superseded** — two confirmed critical defects, fixed in `1.0.1` |

**v1.1.0 was archived at tag time, ahead of publication**, at the owner's direction. That is a
deliberate exception to the rule above, recorded rather than left to be inferred: until a GitHub
Release exists, `archived/v1.1.0/` holds bytes nobody has downloaded. They are the bytes the
`v1.1.0` tag produces — verified reproducible across two clean builds on each loader — so when the
Release is cut it must be cut from **these exact files**, not from a rebuild.

`1.0.1` is the security hotfix for v1.0.0 and will be archived once it is published. `1.0.2`–`1.0.5`
produced no separately published artifact and are not archived.
