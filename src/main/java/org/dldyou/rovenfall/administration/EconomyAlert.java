package org.dldyou.rovenfall.administration;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.util.StringRepresentable;

public record EconomyAlert(
        long timestampEpochMillis,
        UUID playerId,
        UUID transactionId,
        Type type,
        long observedValue,
        long threshold) {
    public static final Codec<EconomyAlert> CODEC = RecordCodecBuilder.<EconomyAlert>create(instance -> instance.group(
            Codec.LONG.fieldOf("timestamp").forGetter(EconomyAlert::timestampEpochMillis),
            UUIDUtil.STRING_CODEC.fieldOf("player").forGetter(EconomyAlert::playerId),
            UUIDUtil.STRING_CODEC.fieldOf("transaction").forGetter(EconomyAlert::transactionId),
            Type.CODEC.fieldOf("type").forGetter(EconomyAlert::type),
            Codec.LONG.fieldOf("observed").forGetter(EconomyAlert::observedValue),
            Codec.LONG.fieldOf("threshold").forGetter(EconomyAlert::threshold)
    ).apply(instance, EconomyAlert::new)).validate(value -> value == null || value.timestampEpochMillis < 0
            || value.playerId == null || value.transactionId == null || value.type == null
            || value.observedValue < 0 || value.threshold < 1
            ? DataResult.error(() -> "Economy alert is invalid")
            : DataResult.success(value));

    public enum Type implements StringRepresentable {
        AMOUNT("amount"),
        RATE("rate");

        static final Codec<Type> CODEC = StringRepresentable.fromEnum(Type::values);
        private final String id;

        Type(String id) {
            this.id = id;
        }

        @Override
        public String getSerializedName() {
            return id;
        }
    }
}
