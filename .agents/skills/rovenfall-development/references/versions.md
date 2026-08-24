# Versions and build baseline

## Source of truth

Read versions from the repository before making a version-sensitive decision:

| Concern | Authoritative location | Verified target baseline |
|---|---|---|
| Gradle | `gradle/wrapper/gradle-wrapper.properties` | 9.7.1 |
| Gradle daemon JVM | `gradle/gradle-daemon-jvm.properties` | 25 |
| Java toolchain | `build.gradle` | 25 |
| ModDevGradle | `build.gradle` | 2.0.144 |
| Foojay resolver | `settings.gradle` | 1.0.0 |
| NeoForge | `gradle.properties` | 26.2.0.66 |
| Minecraft | `gradle.properties` and metadata range | 26.2 / `[26.2]` |

This table is a verified compatibility snapshot, not an instruction to ignore the files. When a version changes, update the configuration, this snapshot, and the compatibility evidence in the same change.

Official baselines:

- [NeoForge 26.2 ModDevGradle template](https://github.com/NeoForgeMDKs/MDK-26.2-ModDevGradle)
- [Gradle 9.1 Java 25 support](https://docs.gradle.org/9.1.0/release-notes.html#support-for-java-25)
- [NeoForge project versions](https://projects.neoforged.net/neoforged/neoforge)

## Foundation status

The workspace is aligned with the target baseline:

- Minecraft and its metadata range use `26.2` and `[26.2]`.
- The project uses official Minecraft names without Parchment overrides.
- The mod has a minimal NeoForge 26.2 entry point and current metadata.
- `ko_kr`, `en_us`, and `ja_jp` language catalogs are present.
- JUnit has an NBT codec round-trip harness, and `rovenfall:foundation` exercises the GameTest registration seam.

## Upgrade discipline

1. Capture `gradlew --version` and a clean build result.
2. Check the target NeoForge MDK and official migration primer.
3. Change one compatibility axis at a time: JDK/Gradle, build plugin, then Minecraft/NeoForge.
4. Keep Minecraft, NeoForge, metadata ranges, mappings policy, and Java toolchain mutually compatible.
5. Regenerate the wrapper with the selected Gradle release; keep `gradlew`, `gradlew.bat`, wrapper JAR, and wrapper properties versioned.
6. Reload the IDE and run the build plus relevant GameTests after every axis.
7. Treat deprecation warnings as migration work before the next major Gradle or NeoForge upgrade.

Resolve NeoForge types and signatures from the dependency sources in the IDE or Gradle cache. Do not port names from 1.21-era tutorials without verification.

## Build products

- Build on Windows: `.\gradlew.bat build`
- Inspect runtime: `.\gradlew.bat --version`
- Expected distributable: `build/libs/rovenfall-<mod_version>.jar`
- `build/moddev/artifacts/minecraft-*.jar` files are development artifacts and must not be distributed as Rovenfall.
- `src/generated/resources` is generated output. Change the generator or source data rather than hand-editing generated files.
- Release tags use annotated SemVer names such as `v1.0.0`; the release workflow removes the leading `v`, overrides `mod_version`, and attaches the versioned JAR plus its SHA-256 checksum to GitHub Releases.
