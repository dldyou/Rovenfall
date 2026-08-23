---
name: rovenfall-development
description: "Implement, extend, review, or debug the Rovenfall NeoForge mod across economy, RPG careers and skills, land claims, hub and wilderness worlds, portals, custom mobs and bosses, administration, persistence, networking, localization, tests, and version upgrades."
---

# Rovenfall Development

Preserve Rovenfall as a server-authoritative multiplayer RPG platform. Treat balance and content as data, mutable gameplay state as owned domain data, and every cross-domain state change as an auditable transaction.

## Load the relevant context

- Read [references/versions.md](references/versions.md) before changing Gradle, Java, NeoForge, ModDevGradle, mappings, metadata, dependencies, or generated-project conventions. Read versions from the repository before relying on the documented snapshot.
- Read [references/domain-model.md](references/domain-model.md) before implementing gameplay behavior, commands, screens, persistence, or interactions between domains.
- Read [references/invariants.md](references/invariants.md) for every implementation, bug fix, migration, or review that can affect server state. Apply every relevant invariant, including failure paths.
- Read [references/roadmap.md](references/roadmap.md) when planning or prioritizing work. Complete the foundation milestone before adding gameplay systems.

## Workflow

1. Inspect the current configuration and resolved NeoForge API. Do not implement 26.2 behavior from older examples or memory.
2. Name the owning domain, state owner, entry point, authorization boundary, persistence boundary, audit event, and failure behavior before editing.
3. Establish the smallest runnable baseline that reaches the changed behavior. Preserve unrelated user changes in the workspace.
4. Implement through the owning domain service. Other domains request operations through that service; they do not mutate its state directly.
5. Put definitions and balance values in validated data, mutable runtime state in versioned persistence, and user-visible text in `ko_kr`, `en_us`, and `ja_jp` translation keys.
6. Add the smallest test at the real seam. State-changing features require success, rejection, persistence, and audit coverage.
7. Run the relevant focused checks, then `./gradlew build` (`.\gradlew.bat build` on Windows). Confirm the distributable JAR under `build/libs`.

## Completion gate

A feature is complete only when:

- the JDK 25 build and relevant tests or GameTests pass;
- restart preserves state and any schema change has a tested migration;
- unauthorized or malformed client requests fail without partial mutation;
- successful and denied security-relevant operations emit the required audit evidence;
- all user-visible strings exist in Korean, English, and Japanese; and
- generated resources and development Minecraft artifacts are not mistaken for the distributable mod JAR.
