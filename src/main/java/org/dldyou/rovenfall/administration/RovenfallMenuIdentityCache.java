package org.dldyou.rovenfall.administration;

import java.util.Optional;

/** Holds the single server-issued identity for the menu currently being opened. */
final class RovenfallMenuIdentityCache {
    private PlayerMenuNetwork.MenuIdentity pending;

    boolean accept(PlayerMenuNetwork.MenuIdentity identity) {
        Optional<PlayerMenuNetwork.MenuKind> kind = identity == null
                ? Optional.empty()
                : PlayerMenuNetwork.MenuKind.fromWireId(identity.kind());
        if (identity == null || identity.packetRevision() != PlayerMenuNetwork.PACKET_REVISION
                || identity.containerId() < 0 || identity.stateId() < 0 || kind.isEmpty()) {
            pending = null;
            return false;
        }
        pending = identity;
        return true;
    }

    Optional<PlayerMenuNetwork.MenuKind> consume(int containerId, int stateId) {
        if (pending == null || pending.containerId() != containerId || pending.stateId() != stateId) {
            pending = null;
            return Optional.empty();
        }
        PlayerMenuNetwork.MenuKind kind = PlayerMenuNetwork.MenuKind.fromWireId(pending.kind()).orElseThrow();
        pending = null;
        return Optional.of(kind);
    }
}
