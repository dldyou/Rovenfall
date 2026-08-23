package org.dldyou.rovenfall.definition;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicReference;

public final class DefinitionStore {
    private final AtomicReference<DefinitionSnapshot> current = new AtomicReference<>(DefinitionSnapshot.empty());

    public DefinitionSnapshot current() {
        return current.get();
    }

    public DefinitionSnapshot replace(Collection<DefinitionSnapshot.Source> candidates) {
        DefinitionSnapshot prepared = DefinitionSnapshot.compile(candidates);
        install(prepared);
        return prepared;
    }

    void install(DefinitionSnapshot prepared) {
        current.set(prepared);
    }
}
