## Summary

Describe the player-visible or operational outcome.

## Validation

- [ ] `./gradlew build --warning-mode all`
- [ ] `./gradlew runGameTestServer` when server behavior or persistence changes
- [ ] Korean, English, and Japanese localization keys stay aligned when text changes
- [ ] Persistence changes include versioning and migration coverage
- [ ] Privileged or economic mutations are server-authoritative, validated, atomic, and audited

## Risk and rollback

Note compatibility, migration, abuse, or rollback concerns. Write `None` when not applicable.

