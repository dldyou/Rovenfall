# Contributing

Rovenfall targets Minecraft 26.2, NeoForge 26.2, Gradle 9.2.1, and Java 25.

Before opening a pull request, run:

```shell
./gradlew build --warning-mode all
./gradlew runGameTestServer
```

Keep gameplay state and mutations server-authoritative. Validate permissions and inputs before mutation, make multi-step mutations atomic, and record privileged or economic changes in the audit log. Version persistent data and provide migrations. User-facing text must keep matching keys in Korean, English, and Japanese.

Use the pull request template to document validation, compatibility, and rollback risks.
