package org.dldyou.rovenfall.exploration;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicReference;

final class ExplorationDefinitionStore {
    private final AtomicReference<VersionedSnapshot> current = new AtomicReference<>(
            new VersionedSnapshot(ExplorationDefinitionSnapshot.empty(), 0));

    ExplorationDefinitionSnapshot current() {
        return current.get().snapshot();
    }

    VersionedSnapshot versioned() {
        return current.get();
    }

    ExplorationDefinitionSnapshot replace(Collection<ExplorationDefinitionSnapshot.Source> candidates) {
        ExplorationDefinitionSnapshot prepared = ExplorationDefinitionSnapshot.compile(candidates);
        install(prepared);
        return prepared;
    }

    void install(ExplorationDefinitionSnapshot prepared) {
        current.updateAndGet(previous -> new VersionedSnapshot(prepared,
                Math.incrementExact(previous.revision())));
    }

    record VersionedSnapshot(ExplorationDefinitionSnapshot snapshot, long revision) {
    }
}
