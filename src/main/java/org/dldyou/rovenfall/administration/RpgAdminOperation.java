package org.dldyou.rovenfall.administration;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;
import org.dldyou.rovenfall.rpg.RpgPlayerState;
import org.dldyou.rovenfall.rpg.SkillResetPlan;

/** Durable Platform-side intent for an audited administrative RPG mutation. */
public record RpgAdminOperation(
        UUID actorId,
        UUID playerId,
        Action action,
        Identifier target,
        long delta,
        long expectedBefore,
        Optional<SkillResetPlan> resetPlan,
        String reason,
        long timestampEpochMillis,
        Phase phase) {
    private static final UUID ZERO_UUID = new UUID(0L, 0L);
    public static final int MAX_REASON_LENGTH = 160;

    public static final Codec<RpgAdminOperation> CODEC = RecordCodecBuilder.<RpgAdminOperation>create(instance ->
            instance.group(
                    UUIDUtil.STRING_CODEC.fieldOf("actor").forGetter(RpgAdminOperation::actorId),
                    UUIDUtil.STRING_CODEC.fieldOf("player").forGetter(RpgAdminOperation::playerId),
                    Action.CODEC.fieldOf("action").forGetter(RpgAdminOperation::action),
                    Identifier.CODEC.fieldOf("target").forGetter(RpgAdminOperation::target),
                    Codec.LONG.fieldOf("delta").forGetter(RpgAdminOperation::delta),
                    Codec.LONG.fieldOf("expected_before").forGetter(RpgAdminOperation::expectedBefore),
                    SkillResetPlan.CODEC.optionalFieldOf("reset_plan").forGetter(RpgAdminOperation::resetPlan),
                    Codec.string(1, MAX_REASON_LENGTH).fieldOf("reason").forGetter(RpgAdminOperation::reason),
                    Codec.LONG.fieldOf("timestamp").forGetter(RpgAdminOperation::timestampEpochMillis),
                    Phase.CODEC.fieldOf("phase").forGetter(RpgAdminOperation::phase)
            ).apply(instance, RpgAdminOperation::new)).validate(RpgAdminOperation::validate);

    public RpgAdminOperation {
        resetPlan = resetPlan == null ? Optional.empty() : resetPlan;
    }

    public RpgAdminOperation completed() {
        return new RpgAdminOperation(actorId, playerId, action, target, delta, expectedBefore,
                resetPlan, reason, timestampEpochMillis, Phase.COMPLETED);
    }

    private static DataResult<RpgAdminOperation> validate(RpgAdminOperation operation) {
        if (operation == null || operation.actorId == null
                || operation.playerId == null || ZERO_UUID.equals(operation.playerId)
                || operation.action == null || operation.target == null
                || operation.reason == null || operation.reason.isBlank()
                || operation.reason.length() > MAX_REASON_LENGTH || operation.timestampEpochMillis < 0
                || operation.phase == null || operation.expectedBefore < 0
                || operation.expectedBefore > RpgPlayerState.MAX_XP) {
            return DataResult.error(() -> "RPG admin operation is invalid");
        }
        return switch (operation.action) {
            case XP_ADJUST -> operation.delta == 0 || operation.resetPlan.isPresent()
                    ? DataResult.error(() -> "RPG XP adjustment operation is invalid")
                    : DataResult.success(operation);
            case PROMOTION_RECOVERY -> operation.delta != 0 || operation.expectedBefore != 0
                    || operation.resetPlan.isPresent()
                    ? DataResult.error(() -> "RPG promotion recovery operation is invalid")
                    : DataResult.success(operation);
            case SKILL_RESET -> operation.delta != 0 || operation.expectedBefore != 0
                    || operation.resetPlan.isEmpty()
                    || !operation.resetPlan.orElseThrow().target().equals(operation.target)
                    ? DataResult.error(() -> "RPG skill reset operation is invalid")
                    : DataResult.success(operation);
        };
    }

    public enum Action implements StringRepresentable {
        XP_ADJUST("xp_adjust"),
        PROMOTION_RECOVERY("promotion_recovery"),
        SKILL_RESET("skill_reset");

        public static final Codec<Action> CODEC = StringRepresentable.fromEnum(Action::values);
        private final String id;

        Action(String id) {
            this.id = id;
        }

        @Override
        public String getSerializedName() {
            return id;
        }
    }

    public enum Phase implements StringRepresentable {
        PENDING("pending"),
        COMPLETED("completed");

        public static final Codec<Phase> CODEC = StringRepresentable.fromEnum(Phase::values);
        private final String id;

        Phase(String id) {
            this.id = id;
        }

        @Override
        public String getSerializedName() {
            return id;
        }
    }
}
