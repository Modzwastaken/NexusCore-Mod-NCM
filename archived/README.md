# Release archive

Every **full release**, exactly as it was published. These files are never rebuilt or
regenerated: a jar in here is the one people downloaded, so a bug report against v1.0.0 can be
reproduced against the same bytes.

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

- A version is archived **only after the release is confirmed published** — not when it is
  built, and not when it is tagged. A tag can move; a published download cannot.
- Archived files are immutable. If something is wrong with a release, the fix is a new
  version, never an edit here.
- `checksums.txt` is generated from the archived jars themselves, so it verifies what is in
  this folder rather than what a build directory happened to contain at the time.
- All three loader jars are archived together. A release is the set, not one loader.

## Releases

| Version | Date | Minecraft | Loaders | Notes |
|---|---|---|---|---|
| [v1.0.0](v1.0.0/) | 2026-07-26 | 1.21.1 | NeoForge, Fabric, Forge | First public release |
