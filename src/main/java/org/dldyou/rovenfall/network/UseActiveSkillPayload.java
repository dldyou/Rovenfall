package org.dldyou.rovenfall.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.Rovenfall;

public record UseActiveSkillPayload(int slot) implements CustomPacketPayload {
    public static final Type<UseActiveSkillPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "use_active_skill"));
    public static final StreamCodec<RegistryFriendlyByteBuf, UseActiveSkillPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT,
                    UseActiveSkillPayload::slot,
                    UseActiveSkillPayload::new);

    @Override
    public Type<UseActiveSkillPayload> type() {
        return TYPE;
    }
}
