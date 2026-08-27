package org.dldyou.rovenfall.rpg;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicReference;

final class RpgDefinitionStore {
    private final AtomicReference<VersionedSnapshot> current =
            new AtomicReference<>(new VersionedSnapshot(RpgDefinitionSnapshot.empty(), 0));

    RpgDefinitionSnapshot current() {
        return current.get().snapshot();
    }

    long revision() {
        return current.get().revision();
    }

    VersionedSnapshot versioned() {
        return current.get();
    }

    RpgDefinitionSnapshot replace(
            Collection<RpgDefinitionSnapshot.ActivitySource> activities,
            Collection<RpgDefinitionSnapshot.CareerSource> careers,
            Collection<RpgDefinitionSnapshot.SkillSource> skills) {
        RpgDefinitionSnapshot prepared = RpgDefinitionSnapshot.compile(activities, careers, skills);
        install(prepared);
        return prepared;
    }

    void install(RpgDefinitionSnapshot prepared) {
        current.updateAndGet(previous -> new VersionedSnapshot(
                prepared, Math.incrementExact(previous.revision())));
    }

    record VersionedSnapshot(RpgDefinitionSnapshot snapshot, long revision) {
    }
}
