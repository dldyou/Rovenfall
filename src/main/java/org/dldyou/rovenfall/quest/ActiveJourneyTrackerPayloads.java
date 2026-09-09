package org.dldyou.rovenfall.quest;

import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.Rovenfall;

/** Identifier-free, fixed-shape server projection for the active-journey HUD. */
public final class ActiveJourneyTrackerPayloads {
    public static final int PACKET_REVISION = 1;
    public static final int MAX_TRANSLATION_KEY_LENGTH = 160;
    public static final int MAX_PACKET_BYTES = 384;

    private ActiveJourneyTrackerPayloads() {
    }

    public record Snapshot(
            int packetRevision,
            boolean active,
            JourneyKind journeyKind,
            String titleTranslationKey,
            JourneyStatus status,
            ObjectiveKind objectiveKind,
            String activityTargetTranslationKey,
            long progress,
            long requiredCount) implements CustomPacketPayload {
        public static final Type<Snapshot> TYPE = new Type<>(
                Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "active_journey_tracker"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public Snapshot decode(RegistryFriendlyByteBuf buffer) {
                return new Snapshot(
                        ByteBufCodecs.VAR_INT.decode(buffer),
                        ByteBufCodecs.BOOL.decode(buffer),
                        JourneyKind.fromWireId(ByteBufCodecs.VAR_INT.decode(buffer)).orElse(null),
                        ByteBufCodecs.stringUtf8(MAX_TRANSLATION_KEY_LENGTH).decode(buffer),
                        JourneyStatus.fromWireId(ByteBufCodecs.VAR_INT.decode(buffer)).orElse(null),
                        ObjectiveKind.fromWireId(ByteBufCodecs.VAR_INT.decode(buffer)).orElse(null),
                        ByteBufCodecs.stringUtf8(MAX_TRANSLATION_KEY_LENGTH).decode(buffer),
                        ByteBufCodecs.VAR_LONG.decode(buffer),
                        ByteBufCodecs.VAR_LONG.decode(buffer));
            }

            @Override
            public void encode(RegistryFriendlyByteBuf buffer, Snapshot payload) {
                ByteBufCodecs.VAR_INT.encode(buffer, payload.packetRevision());
                ByteBufCodecs.BOOL.encode(buffer, payload.active());
                ByteBufCodecs.VAR_INT.encode(buffer, wireId(payload.journeyKind()));
                ByteBufCodecs.stringUtf8(MAX_TRANSLATION_KEY_LENGTH)
                        .encode(buffer, payload.titleTranslationKey());
                ByteBufCodecs.VAR_INT.encode(buffer, wireId(payload.status()));
                ByteBufCodecs.VAR_INT.encode(buffer, wireId(payload.objectiveKind()));
                ByteBufCodecs.stringUtf8(MAX_TRANSLATION_KEY_LENGTH)
                        .encode(buffer, payload.activityTargetTranslationKey());
                ByteBufCodecs.VAR_LONG.encode(buffer, payload.progress());
                ByteBufCodecs.VAR_LONG.encode(buffer, payload.requiredCount());
            }
        };

        public static Snapshot inactive() {
            return new Snapshot(
                    PACKET_REVISION,
                    false,
                    JourneyKind.STORY,
                    "",
                    JourneyStatus.AVAILABLE,
                    ObjectiveKind.ACTIVITY,
                    "",
                    0,
                    0);
        }

        public boolean isValid() {
            if (packetRevision != PACKET_REVISION || journeyKind == null || status == null
                    || objectiveKind == null || titleTranslationKey == null
                    || activityTargetTranslationKey == null) {
                return false;
            }
            if (!active) {
                return journeyKind == JourneyKind.STORY && titleTranslationKey.isEmpty()
                        && status == JourneyStatus.AVAILABLE && objectiveKind == ObjectiveKind.ACTIVITY
                        && activityTargetTranslationKey.isEmpty() && progress == 0 && requiredCount == 0;
            }
            return validTranslationKey(titleTranslationKey, false)
                    && validTranslationKey(activityTargetTranslationKey, true)
                    && progress >= 0 && requiredCount >= 1
                    && requiredCount <= QuestDefinition.MAX_REQUIRED_COUNT
                    && progress < requiredCount
                    && (status != JourneyStatus.AVAILABLE || progress == 0)
                    && (objectiveKind == ObjectiveKind.ACTIVITY || activityTargetTranslationKey.isEmpty());
        }

        @Override
        public Type<Snapshot> type() {
            return TYPE;
        }

        private static int wireId(JourneyKind value) {
            return value == null ? -1 : value.wireId();
        }

        private static int wireId(JourneyStatus value) {
            return value == null ? -1 : value.wireId();
        }

        private static int wireId(ObjectiveKind value) {
            return value == null ? -1 : value.wireId();
        }
    }

    private static boolean validTranslationKey(String value, boolean optional) {
        if (value == null || value.length() > MAX_TRANSLATION_KEY_LENGTH) {
            return false;
        }
        if (value.isEmpty()) {
            return optional;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(character >= 'a' && character <= 'z')
                    && !(character >= '0' && character <= '9')
                    && character != '.' && character != '_' && character != '-') {
                return false;
            }
        }
        return true;
    }

    public enum JourneyKind {
        STORY(0), DAILY(1), WEEKLY(2);

        private final int wireId;

        JourneyKind(int wireId) {
            this.wireId = wireId;
        }

        public int wireId() {
            return wireId;
        }

        public static Optional<JourneyKind> fromWireId(int wireId) {
            return switch (wireId) {
                case 0 -> Optional.of(STORY);
                case 1 -> Optional.of(DAILY);
                case 2 -> Optional.of(WEEKLY);
                default -> Optional.empty();
            };
        }
    }

    public enum JourneyStatus {
        AVAILABLE(0), IN_PROGRESS(1);

        private final int wireId;

        JourneyStatus(int wireId) {
            this.wireId = wireId;
        }

        public int wireId() {
            return wireId;
        }

        public static Optional<JourneyStatus> fromWireId(int wireId) {
            return switch (wireId) {
                case 0 -> Optional.of(AVAILABLE);
                case 1 -> Optional.of(IN_PROGRESS);
                default -> Optional.empty();
            };
        }
    }

    public enum ObjectiveKind {
        ACTIVITY(0), SHOP_TRADE(1), CLAIM_PURCHASE(2), BOSS_DEFEAT(3);

        private final int wireId;

        ObjectiveKind(int wireId) {
            this.wireId = wireId;
        }

        public int wireId() {
            return wireId;
        }

        public static Optional<ObjectiveKind> fromWireId(int wireId) {
            return switch (wireId) {
                case 0 -> Optional.of(ACTIVITY);
                case 1 -> Optional.of(SHOP_TRADE);
                case 2 -> Optional.of(CLAIM_PURCHASE);
                case 3 -> Optional.of(BOSS_DEFEAT);
                default -> Optional.empty();
            };
        }
    }
}
