package org.dldyou.rovenfall.administration;

import net.minecraft.server.level.ServerPlayer;

/** Single integration seam for domain-specific administration screens. */
final class AdministrationDomainMenuRouter {
    private AdministrationDomainMenuRouter() {
    }

    static boolean open(ServerPlayer player, AdministrationReadViewService.Domain domain) {
        return switch (domain) {
            case PLAYERS, SHOPS, RECEIPTS -> AdministrationEconomyMenu.open(player, domain);
            case CLAIMS, PORTALS -> AdministrationWorldMenu.open(player, domain);
            default -> false;
        };
    }
}
