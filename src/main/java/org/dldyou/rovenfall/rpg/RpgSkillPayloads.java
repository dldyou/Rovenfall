package org.dldyou.rovenfall.rpg;

import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.Rovenfall;

public final class RpgSkillPayloads {
    public static final int PACKET_REVISION = 1;
    public static final int MAX_ACTIVATE_PACKET_BYTES = 128;
    public static final int MAX_STATE_SYNC_PACKET_BYTES = 64;

    private RpgSkillPayloads() {
    }

    public record Activate(
            int packetRevision,
            long definitionRevision,
            long requestId,
            int slot,
            Identifier dimension,
            int targetEntityId,
            UUID session) implements CustomPacketPayload {
        public static final Type<Activate> TYPE = new Type<>(
                Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "activate_skill"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Activate> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Activate::packetRevision,
                ByteBufCodecs.VAR_LONG, Activate::definitionRevision,
                ByteBufCodecs.VAR_LONG, Activate::requestId,
                ByteBufCodecs.VAR_INT, Activate::slot,
                Identifier.STREAM_CODEC, Activate::dimension,
                ByteBufCodecs.VAR_INT, Activate::targetEntityId,
                UUIDUtil.STREAM_CODEC, Activate::session,
                Activate::new);

        @Override
        public Type<Activate> type() {
            return TYPE;
        }
    }

    public record StateSync(
            int packetRevision,
            long definitionRevision,
            long nextRequestId,
            int enabledSlots,
            UUID session) implements CustomPacketPayload {
        public static final Type<StateSync> TYPE = new Type<>(
                Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "active_skill_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, StateSync> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, StateSync::packetRevision,
                ByteBufCodecs.VAR_LONG, StateSync::definitionRevision,
                ByteBufCodecs.VAR_LONG, StateSync::nextRequestId,
                ByteBufCodecs.VAR_INT, StateSync::enabledSlots,
                UUIDUtil.STREAM_CODEC, StateSync::session,
                StateSync::new);

        @Override
        public Type<StateSync> type() {
            return TYPE;
        }
    }
}
