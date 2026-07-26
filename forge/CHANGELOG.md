# NexusCore — Forge changelog

Changes specific to the **MinecraftForge** build. Anything affecting all three loaders lives in
the [shared changelog](../CHANGELOG.md); user-facing release summaries live in
[release-notes.md](../release-notes.md).

Artifact: `NexusCore-forge-<version>-<mcVersion>.jar`
Toolchain: ForgeGradle 6.0.54 · **Gradle 8.10.2**
Requires: MinecraftForge 52.1.16+

## [1.0.0] — 2026-07-26

### Added
- Forge port, sharing the same sources as the other two loaders.
- Run configurations, which did not exist at all, plus a quick-play argument on `runClient`.
- `ForgeMetadataTest` — guards the `mods.toml` differences below, which look interchangeable
  with NeoForge's and are not.

### Fixed
- **Forge rejected the jar outright** with `Missing required field mandatory in dependency`.
  The dependency blocks had been copied from the NeoForge metadata: Forge requires
  `mandatory = true` where NeoForge uses `type = "required"`.
- **The dev runs could not load the mod**, failing with
  `constructed 0 mods: [], but had 1 mods specified: [nexuscore]` for both `runClient` and
  `runServer`. ForgeGradle puts `build/classes/java/main` and `build/resources/main` on the dev
  classpath as two separate entries and never emits the grouping that tells FML they are one
  mod, so FML found `mods.toml` in the resources directory, treated it as a standalone mod file
  with no `@Mod` class, and aborted. Neither `MOD_CLASSES` nor any `modFolders` key exists in
  ForgeGradle 6.0.54 or fmlloader 52.1.16, so there was nothing to configure. Fixed by pointing
  the resource output at the classes output, giving FML one directory with both.

### Loader notes
- **Pinned to Gradle 8.10.2** while the other two use 9.2.1: ForgeGradle 6.0.54 refuses to
  apply on Gradle 9 (*"Versions Gradle 9.0 and newer are not supported yet"*). Because each
  loader is an independent build with its own wrapper, Forge sits on 8.x without dragging the
  others back. This is the main reason the loaders are separate builds rather than one
  multi-project — see [ADR-0008](../docs/architecture/ADR-0008.md).
- **Flight uses `Abilities.mayfly`**; `ForgeMod` registers no `creative_flight` attribute
  (verified against 52.1.16), so the last mod to write the flag wins.
- Forge uses vanilla's strict `pack.mcmeta` codec, which is why the missing pack metadata
  surfaced here and not on NeoForge.
