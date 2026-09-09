# Contributing

Rovenfall targets Minecraft 26.2, NeoForge 26.2, Gradle 9.7.1, and Java 25.

Before opening a pull request, run:

```shell
./gradlew build --warning-mode all
./gradlew runGameTestServer
```

## Admin web

Use Node 24 and pnpm 9.15.4. After changing the admin UI or its dependencies, run:

```shell
pnpm --dir admin-web install --frozen-lockfile
pnpm --dir admin-web lint
pnpm --dir admin-web typecheck
pnpm --dir admin-web build
```

Commit the rebuilt files under `src/main/resources/assets/rovenfall/admin` with the source changes;
the mod JAR serves those bundled files. The Build workflow also runs these web checks.

## Code coverage

Generate the JUnit coverage reports before changing tested Java code:

```shell
./gradlew test jacocoTestReport
```

Open `build/reports/jacoco/test/html/index.html` for the HTML report; tooling can consume `build/reports/jacoco/test/jacocoTestReport.xml`. The Code coverage workflow runs this command for pull requests and pushes to `main`, writes line and branch percentages to its job summary, and uploads both reports as an artifact.

Keep gameplay state and mutations server-authoritative. Validate permissions and inputs before mutation, make multi-step mutations atomic, and record privileged or economic changes in the audit log. Version persistent data and provide migrations. User-facing text must keep matching keys in Korean, English, and Japanese.

Use the pull request template to document validation, compatibility, and rollback risks.

## Releases

Releases are built from annotated Semantic Versioning tags on `main`. Keep tags immutable: if a release needs a correction, create a new patch version instead of moving an existing tag.

```shell
git switch main
git pull --ff-only
git tag -a v1.0.0 -m "Rovenfall 1.0.0"
git push origin v1.0.0
```

The release workflow validates the tag and its `main` ancestry, derives `mod_version` from the tag, runs the JDK 25 build and required GameTests, and creates the matching GitHub Release. The release contains `rovenfall-<version>.jar` and its SHA-256 checksum. Development artifacts under `build/moddev` are never published.

Prerelease tags such as `v1.1.0-rc.1` create GitHub prereleases. Rerun transient workflow failures against the same tag. If source or workflow changes are required, merge the fix and create a new version tag rather than moving the failed tag.
