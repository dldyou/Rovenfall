package org.dldyou.rovenfall.claims;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.util.StringRepresentable;

public record ClaimMutationReceipt(
        long timestampEpochMillis,
        UUID actorId,
        ClaimKey claim,
        Kind kind,
        String payload) {
    public static final int MAX_PAYLOAD_LENGTH = 512;
    public static final Codec<ClaimMutationReceipt> CODEC = RecordCodecBuilder.<ClaimMutationReceipt>create(
            instance -> instance.group(
                    Codec.LONG.fieldOf("timestamp").forGetter(ClaimMutationReceipt::timestampEpochMillis),
                    UUIDUtil.STRING_CODEC.fieldOf("actor").forGetter(ClaimMutationReceipt::actorId),
                    ClaimKey.CODEC.fieldOf("claim").forGetter(ClaimMutationReceipt::claim),
                    Kind.CODEC.fieldOf("kind").forGetter(ClaimMutationReceipt::kind),
                    Codec.STRING.fieldOf("payload").forGetter(ClaimMutationReceipt::payload)
            ).apply(instance, ClaimMutationReceipt::new)).validate(ClaimMutationReceipt::validate);

    public boolean matches(UUID actor, ClaimKey key, Kind operation, String operationPayload) {
        return actorId.equals(actor) && claim.equals(key) && kind == operation && payload.equals(operationPayload);
    }

    private static DataResult<ClaimMutationReceipt> validate(ClaimMutationReceipt receipt) {
        if (receipt == null || receipt.timestampEpochMillis < 0 || receipt.actorId == null || receipt.claim == null
                || receipt.kind == null || receipt.payload == null || receipt.payload.isEmpty()
                || receipt.payload.length() > MAX_PAYLOAD_LENGTH) {
            return DataResult.error(() -> "Claim mutation receipt is invalid");
        }
        return DataResult.success(receipt);
    }

    public enum Kind implements StringRepresentable {
        ROLE_SET("role_set"),
        ROLE_REMOVE("role_remove"),
        SETTINGS_SET("settings_set"),
        TRANSFER_OFFER("transfer_offer"),
        TRANSFER_CANCEL("transfer_cancel"),
        TRANSFER_ACCEPT("transfer_accept");

        public static final Codec<Kind> CODEC = StringRepresentable.fromEnum(Kind::values);
        private final String id;

        Kind(String id) {
            this.id = id;
        }

        @Override
        public String getSerializedName() {
            return id;
        }
    }
}
