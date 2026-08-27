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

/** Durable evidence used to finish a paid RPG reset after an interrupted save. */
public record RpgSkillOperation(
        UUID playerId,
        SkillResetPlan.Mode mode,
        Identifier target,
        long cost,
        long timestampEpochMillis,
        Optional<SkillResetPlan> plan,
        Phase phase) {
    public static final Codec<RpgSkillOperation> CODEC = RecordCodecBuilder.<RpgSkillOperation>create(instance ->
            instance.group(
                    UUIDUtil.STRING_CODEC.fieldOf("player").forGetter(RpgSkillOperation::playerId),
                    SkillResetPlan.Mode.CODEC.fieldOf("mode").forGetter(RpgSkillOperation::mode),
                    Identifier.CODEC.fieldOf("target").forGetter(RpgSkillOperation::target),
                    Codec.LONG.fieldOf("cost").forGetter(RpgSkillOperation::cost),
                    Codec.LONG.fieldOf("timestamp").forGetter(RpgSkillOperation::timestampEpochMillis),
                    SkillResetPlan.CODEC.optionalFieldOf("plan").forGetter(RpgSkillOperation::plan),
                    Phase.CODEC.fieldOf("phase").forGetter(RpgSkillOperation::phase)
            ).apply(instance, RpgSkillOperation::new)).validate(RpgSkillOperation::validate);

    public RpgSkillOperation {
        plan = plan == null ? Optional.empty() : plan;
    }

    public RpgSkillOperation completed() {
        return new RpgSkillOperation(playerId, mode, target, cost, timestampEpochMillis, plan, Phase.COMPLETED);
    }

    public boolean matches(UUID player, SkillResetPlan resetPlan, long paymentCost) {
        return playerId.equals(player)
                && mode == resetPlan.mode()
                && target.equals(resetPlan.target())
                && cost == paymentCost
                && plan.equals(Optional.of(resetPlan));
    }

    private static DataResult<RpgSkillOperation> validate(RpgSkillOperation operation) {
        if (operation == null || operation.playerId == null || operation.playerId.equals(new UUID(0L, 0L))
                || operation.mode == null || operation.target == null || operation.cost < 1
                || operation.cost > RpgPlayerState.MAX_XP || operation.timestampEpochMillis < 0
                || operation.phase == null || (operation.phase == Phase.PENDING && operation.plan.isEmpty())) {
            return DataResult.error(() -> "RPG skill operation is invalid");
        }
        if (operation.plan.isPresent()
                && (operation.plan.orElseThrow().mode() != operation.mode
                || !operation.plan.orElseThrow().target().equals(operation.target))) {
            return DataResult.error(() -> "RPG skill operation plan does not match its target");
        }
        return DataResult.success(operation);
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
