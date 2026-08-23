package org.dldyou.rovenfall.economy;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicReference;

final class ShopTemplateStore {
    private final AtomicReference<ShopTemplateSnapshot> current = new AtomicReference<>(ShopTemplateSnapshot.empty());

    ShopTemplateSnapshot current() {
        return current.get();
    }

    ShopTemplateSnapshot replace(Collection<ShopTemplateSnapshot.Source> candidates) {
        ShopTemplateSnapshot prepared = ShopTemplateSnapshot.compile(candidates).validateBoundItems();
        install(prepared);
        return prepared;
    }

    void install(ShopTemplateSnapshot prepared) {
        current.set(prepared);
    }
}
