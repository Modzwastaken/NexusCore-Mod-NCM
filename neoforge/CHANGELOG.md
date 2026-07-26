# NexusCore — NeoForge changelog

Changes specific to the **NeoForge** build. Anything affecting all three loaders lives in the
[shared changelog](../CHANGELOG.md); user-facing release summaries live in
[release-notes.md](../release-notes.md).

Artifact: `NexusCore-neoforge-<version>-<mcVersion>.jar`
Toolchain: NeoGradle 7.1.38 · Gradle 9.2.1

## [1.0.0] — 2026-07-26

### Added
- First NexusCore build, and the loader the mod was originally written against. Every service,
  command and screen was developed here before the other two loaders existed.
- `FMLPaths.GAMEDIR` used for the data directory at mod-construction time.
- Quick-play argument on `runClient` (`-PquickPlay=<world>`) so the singleplayer path can be
  driven headlessly.

### Fixed
- **Commands were not registered on first start.** Services were built on
  `ServerAboutToStartEvent`, but `RegisterCommandsEvent` fires earlier, on a worker thread,
  while the server's resources are assembled. The command tree had nothing to register
  against and NexusCore logged `services are not ready at command registration`. Services are
  now built in the mod constructor, which happens before every event.

### Loader notes
- **Flight uses NeoForge's `creative_flight` attribute.** Modifiers are additive and keyed per
  mod, so NexusCore revokes only its own grant and coexists with other flight mods. This is
  the best of the three implementations — Fabric and Forge have no equivalent.
- NeoForge supplies a lenient `pack.mcmeta` codec, so the missing pack metadata that broke
  Forge never surfaced here.
