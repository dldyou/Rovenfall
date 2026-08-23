package org.dldyou.rovenfall.administration;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;

public record AuditEntry(
        long timestampEpochMillis,
        UUID actorId,
        Identifier actionType,
        String target,
        Optional<Identifier> dimension,
        Optional<BlockPos> position,
        String beforeValue,
        String afterValue,
        String reason,
        UUID transactionId) {

    public static final Codec<AuditEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.LONG.fieldOf("timestamp").forGetter(AuditEntry::timestampEpochMillis),
            UUIDUtil.STRING_CODEC.fieldOf("actor").forGetter(AuditEntry::actorId),
            Identifier.CODEC.fieldOf("action").forGetter(AuditEntry::actionType),
            Codec.STRING.fieldOf("target").forGetter(AuditEntry::target),
            Identifier.CODEC.optionalFieldOf("dimension").forGetter(AuditEntry::dimension),
            BlockPos.CODEC.optionalFieldOf("position").forGetter(AuditEntry::position),
            Codec.STRING.fieldOf("before").forGetter(AuditEntry::beforeValue),
            Codec.STRING.fieldOf("after").forGetter(AuditEntry::afterValue),
            Codec.STRING.fieldOf("reason").forGetter(AuditEntry::reason),
            UUIDUtil.STRING_CODEC.fieldOf("transaction_id").forGetter(AuditEntry::transactionId)
    ).apply(instance, AuditEntry::new));
}
