# Release archive

Builds exactly as they were handed out. These files are never rebuilt or regenerated: a jar in
here is the one somebody installed, so a bug report against it can be reproduced against the
same bytes.

Two kinds live here, and the difference matters when reading a bug report:

| Type | What it is | Who ran it |
|---|---|---|
| **Release** | An `x.y.0` version that completed the full `RELEASE_CHECKLIST.md`. | The public. |
| **Pre-release** | An `x.y.5` build — the fifth and last rung of a line on the ladder ([ADR-0010](../docs/architecture/ADR-0010.md)). Not a release, not for production. | Testers, on request. |

A pre-release is archived for the same reason a release is — so the bytes someone ran still
exist — and under the same immutability rule. It carries none of a release's promises.

**Which builds land here is decided by the version number, not by judgement.** `x.y.5` is
archived; `x.y.1` through `x.y.4` are not, and live in their tags. Earlier drafts of this scheme
used a hand-maintained "milestone snapshot" flag; that was withdrawn because nothing checked it.

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
- A pre-release is archived **only after it has been handed to someone**, by the same
  reasoning. A build nobody installed does not belong here; it is reproducible from its tag.
- Only `x.y.5` builds are archived. Every other development build lives in its tag and nowhere
  else.
- Archived files are immutable. If something is wrong with a release, the fix is a new
  version, never an edit here.
- `checksums.txt` is generated from the archived jars themselves, so it verifies what is in
  this folder rather than what a build directory happened to contain at the time.
- All three loader jars are archived together. A release is the set, not one loader.

## Archived builds

| Version | Type | Date | Minecraft | Loaders | Notes |
|---|---|---|---|---|---|
| [v1.0.0](v1.0.0/) | Release | 2026-07-26 | 1.21.1 | NeoForge, Fabric, Forge | First public release |

Development build `1.0.1` is not archived: it is an ordinary rung, not a `.5` pre-release, and
it contains no runtime change to test. The first pre-release will be `1.0.5`.
