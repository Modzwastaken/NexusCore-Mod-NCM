# NexusCore — Fabric changelog

Changes specific to the **Fabric** build. Anything affecting all three loaders lives in the
[shared changelog](../CHANGELOG.md); user-facing release summaries live in
[release-notes.md](../release-notes.md).

Artifact: `NexusCore-fabric-<version>-<mcVersion>.jar`
Toolchain: Fabric Loom 1.15.4 · Gradle 9.2.1 · Mojang mappings
Requires: Fabric Loader 0.19.3+ **and Fabric API**

## [1.0.0] — 2026-07-26

### Added
- Fabric port. 22 of 24 source files compile unchanged from the shared sources; only the entry
  point and the flight controller are Fabric-specific.
- Mojang mappings rather than Yarn, so the shared sources need no renaming.
- Quick-play argument on `runClient` (`-PquickPlay=<world>`).
- `FabricEntrypointTest` — three assertions that guard the entrypoint defect below, because it
  is invisible to the compiler and to any test run against a dedicated server.

### Fixed
- **The mod did nothing in singleplayer or on a LAN world.** It declared a `server` entrypoint
  and implemented `DedicatedServerModInitializer`, which fires only on a *dedicated* server.
  On a client the mod loaded, listed itself, and was completely silent: no commands, no config
  directory, not one log line. Now uses `ModInitializer` with a `main` entrypoint. Verified on
  a real client loading a singleplayer world.
- **`/back` did not return a player to where they died**, while NeoForge did. Registered
  `ServerLivingEntityEvents.AFTER_DEATH`.

### Loader notes
- **Flight uses `Abilities.mayfly`** via the shared `MayflyFlightController`; Fabric has no
  equivalent of NeoForge's `creative_flight` attribute, so the last mod to write the flag wins.
- Fabric has no equivalent of NeoForge's `NameFormat` event, which is why group-prefix
  nametags were dropped rather than shipped on two loaders out of three.
