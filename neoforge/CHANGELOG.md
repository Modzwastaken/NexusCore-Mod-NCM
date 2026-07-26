# NexusCore — NeoForge changelog

Changes specific to the **NeoForge** build. Anything affecting all three loaders lives in the
[shared changelog](../CHANGELOG.md); user-facing release summaries live in
[release-notes.md](../release-notes.md).

Artifact: `NexusCore-neoforge-<version>-<mcVersion>.jar`
Toolchain: NeoGradle 7.1.38 · Gradle 9.2.1

## [1.0.1] — 2026-07-26 — development build

### Added
- **`versionLadderCheck`**, a new `check` gate enforcing
  [ADR-0009](../docs/architecture/ADR-0009.md) and [ADR-0010](../docs/architecture/ADR-0010.md).
  It fails the build if `mod_version` is malformed or whitespace-padded, if the shared changelog
  has no entry for it, if that entry's heading carries the wrong label for the build's kind
  (`development build` / `pre-release` / neither), if the version is already archived, or if the
  version is outside its five-rung line — a sixth rung, or a `.0` on a minor that is not a
  release point. Ten distinct failures are proven, not merely the passing case.

  It lives **here and only here** because ADR-0008 makes this project the single owner of
  `gradle.properties`: Fabric and Forge read their versions out of it, so the value can only
  ever be wrong in one place. Running the gate three times would check the same string three
  times. The cost is that `./gradlew build` in `fabric/` or `forge/` alone will not catch a bad
  version — and a release builds all three regardless, which the release checklist requires.

- Configuration-cache safe: the version string and the changelog path are captured as task
  inputs at configuration time, so nothing reaches for `project` inside the task action.

### Notes
- No runtime change. The `1.0.1` in `/nexus version` comes from the loader metadata, which is
  token-expanded from `mod_version` — no source file names a version, and none needed editing.

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
