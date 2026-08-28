package org.dldyou.rovenfall.administration;

import net.minecraft.server.level.ServerPlayer;

/** Server-side target for bounded text submitted by the shared administration screen. */
interface AdministrationTextInputMenu {
    int MAX_INPUT_LENGTH = 2_048;

    boolean applyTextInput(ServerPlayer player, String input);
}
