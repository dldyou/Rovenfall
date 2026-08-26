package org.dldyou.rovenfall.mobs;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicReference;

final class MobContentStore {
    private final AtomicReference<MobContentSnapshot> current = new AtomicReference<>(MobContentSnapshot.empty());

    MobContentSnapshot current() {
        return current.get();
    }

    MobContentSnapshot replace(
            Collection<MobContentSnapshot.Source> candidates, MobContentSnapshot.RuntimeBindings bindings) {
        MobContentSnapshot prepared = MobContentSnapshot.compile(candidates).validateRuntimeBindings(bindings);
        install(prepared);
        return prepared;
    }

    void install(MobContentSnapshot prepared) {
        current.set(prepared);
    }
}
