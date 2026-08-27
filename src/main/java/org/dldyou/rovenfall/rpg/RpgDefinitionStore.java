package org.dldyou.rovenfall.rpg;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicReference;

final class RpgDefinitionStore {
    private final AtomicReference<RpgDefinitionSnapshot> current =
            new AtomicReference<>(RpgDefinitionSnapshot.empty());

    RpgDefinitionSnapshot current() {
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
        current.set(prepared);
    }
}
