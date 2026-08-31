package org.dldyou.rovenfall.quest;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicReference;

final class QuestDefinitionStore {
    private final AtomicReference<VersionedSnapshot> current =
            new AtomicReference<>(new VersionedSnapshot(QuestDefinitionSnapshot.empty(), 0));

    QuestDefinitionSnapshot current() {
        return current.get().snapshot();
    }

    long revision() {
        return current.get().revision();
    }

    VersionedSnapshot versioned() {
        return current.get();
    }

    QuestDefinitionSnapshot replace(Collection<QuestDefinitionSnapshot.Source> candidates) {
        QuestDefinitionSnapshot prepared = QuestDefinitionSnapshot.compile(candidates);
        install(prepared);
        return prepared;
    }

    void install(QuestDefinitionSnapshot prepared) {
        current.updateAndGet(previous -> new VersionedSnapshot(
                prepared, Math.incrementExact(previous.revision())));
    }

    record VersionedSnapshot(QuestDefinitionSnapshot snapshot, long revision) {
    }
}
