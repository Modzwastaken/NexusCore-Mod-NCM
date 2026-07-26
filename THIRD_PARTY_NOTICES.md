# Third-Party Notices

This file lists every third-party component **redistributed inside**
`NexusCore-<loader>-<version>-<mcVersion>.jar`, together with its licence text, as required by
§8.2. It covers all three loader artifacts (ADR-0008); none of them embeds anything.

---

## Embedded components

**As of 1.0.2: none, in any of the three jars.**

No jar contains Jar-in-Jar dependencies or shaded or repackaged third-party code. Each ships
NexusCore's own compiled classes, its resources, and its mod metadata — nothing else.

This is a deliberate position, not an accident of it being early. Per §8.1 NexusCore
requires no administration dependency — not LuckPerms, Vault, Essentials, PlaceholderAPI,
WorldEdit, WorldGuard, nor any economy, chat, or database plugin. Every equivalent
capability is native. Per §11.1 the default storage provider is structured JSON files, so no
database driver is embedded either.

---

## Components used but **not** redistributed

The following are provided by the Minecraft platform, by the mod loader, or by the Java
runtime. They are compiled against but **not** bundled, per §8.2. Their licences are the
responsibility of the platform that supplies them.

| Component | Supplied by |
|---|---|
| Minecraft: Java Edition 1.21.1 | Mojang AB / Microsoft — end-user licence applies |
| NeoForge 21.1.x | The NeoForged project (LGPL-2.1) — NeoForge build only |
| Fabric Loader 0.19.3+, Fabric API | FabricMC (Apache-2.0) — Fabric build only |
| MinecraftForge 52.1.x | The MinecraftForge project (LGPL-2.1) — Forge build only |
| Brigadier | Supplied by Minecraft |
| Gson | Supplied by Minecraft |
| Guava | Supplied by Minecraft |
| SLF4J, Log4j2 | Supplied by Minecraft / NeoForge |
| Netty | Supplied by Minecraft |
| JOML | Supplied by Minecraft |
| Apache Commons | Supplied by Minecraft |
| Java 21 runtime | The JRE the server operator runs |

---

## Build-time and test-only tools

These never enter the released JAR and are listed for completeness only.

| Tool | Version | Licence |
|---|---|---|
| Gradle | 9.2.1 (NeoForge, Fabric) · 8.10.2 (Forge — see `forge/settings.gradle`) | Apache-2.0 |
| NeoGradle userdev plugin | 7.1.38 | LGPL-2.1 |
| Fabric Loom | 1.15.4 | Apache-2.0 |
| ForgeGradle | 6.0.54 | LGPL-2.1 |
| Parchment mappings | 1.21.1-2024.11.17 | ParchmentMC — mapping data only |
| Checkstyle | 10.21.1 | LGPL-2.1 |
| JUnit 5 | 5.11.4 | EPL-2.0 |

---

## Adding a dependency

Any new dependency must pass all six §8.3 acceptance checks — need, compatibility, licence,
security, packaging, testing — before it is added. When one is embedded via Jar-in-Jar, its
**full licence text** is appended to this file in the same change that adds it, and the
table above is updated. A release whose JAR contains a component absent from this file fails
the release checklist.
