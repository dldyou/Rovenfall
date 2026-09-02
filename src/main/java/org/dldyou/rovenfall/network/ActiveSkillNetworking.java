package org.dldyou.rovenfall.network;

import java.time.Instant;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.dldyou.rovenfall.administration.ActiveSkillGameplay;

public final class ActiveSkillNetworking {
    private ActiveSkillNetworking() {
    }

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(
                UseActiveSkillPayload.TYPE,
                UseActiveSkillPayload.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player) {
                        ActiveSkillGameplay.use(player, payload.slot(), Instant.now().toEpochMilli());
                    }
                });
    }
}
