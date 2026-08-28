package org.dldyou.rovenfall.administration;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;
import org.dldyou.rovenfall.rpg.RpgPlayerState;
import org.dldyou.rovenfall.rpg.RpgItemCost;
import org.dldyou.rovenfall.rpg.SkillResetPlan;

/** Durable evidence used to finish a paid RPG mutation after an interrupted save. */
public record RpgSkillOperation(
        UUID playerId,
        SkillResetPlan.Mode mode,
        Identifier target,
        long cost,
        long timestampEpochMillis,
        Optional<SkillResetPlan> plan,
        Phase phase,
        Kind kind,
        List<RpgItemCost> itemCosts,
        List<Long> itemCountsBefore,
        List<Long> itemCountsAfter) {
    public static final Codec<RpgSkillOperation> CODEC = RecordCodecBuilder.<RpgSkillOperation>create(instance ->
            instance.group(
                    UUIDUtil.STRING_CODEC.fieldOf("player").forGetter(RpgSkillOperation::playerId),
                    SkillResetPlan.Mode.CODEC.fieldOf("mode").forGetter(RpgSkillOperation::mode),
                    Identifier.CODEC.fieldOf("target").forGetter(RpgSkillOperation::target),
                    Codec.LONG.fieldOf("cost").forGetter(RpgSkillOperation::cost),
                    Codec.LONG.fieldOf("timestamp").forGetter(RpgSkillOperation::timestampEpochMillis),
                    SkillResetPlan.CODEC.optionalFieldOf("plan").forGetter(RpgSkillOperation::plan),
                    Phase.CODEC.fieldOf("phase").forGetter(RpgSkillOperation::phase),
                    Kind.CODEC.optionalFieldOf("kind", Kind.SKILL_RESET).forGetter(RpgSkillOperation::kind),
                    RpgItemCost.LIST_CODEC.optionalFieldOf("item_costs", List.of())
                            .forGetter(RpgSkillOperation::itemCosts),
                    Codec.LONG.listOf(0, RpgItemCost.MAX_ENTRIES).optionalFieldOf("item_counts_before", List.of())
                            .forGetter(RpgSkillOperation::itemCountsBefore),
                    Codec.LONG.listOf(0, RpgItemCost.MAX_ENTRIES).optionalFieldOf("item_counts_after", List.of())
                            .forGetter(RpgSkillOperation::itemCountsAfter)
            ).apply(instance, RpgSkillOperation::new)).validate(RpgSkillOperation::validate);

    public RpgSkillOperation {
        plan = plan == null ? Optional.empty() : plan;
        kind = kind == null ? Kind.SKILL_RESET : kind;
        itemCosts = itemCosts == null ? List.of() : List.copyOf(itemCosts);
        itemCountsBefore = itemCountsBefore == null ? List.of() : List.copyOf(itemCountsBefore);
        itemCountsAfter = itemCountsAfter == null ? List.of() : List.copyOf(itemCountsAfter);
    }

    public RpgSkillOperation(
            UUID playerId,
            SkillResetPlan.Mode mode,
            Identifier target,
            long cost,
            long timestampEpochMillis,
            Optional<SkillResetPlan> plan,
            Phase phase,
            Kind kind,
            List<RpgItemCost> itemCosts) {
        this(playerId, mode, target, cost, timestampEpochMillis, plan, phase, kind,
                itemCosts, List.of(), List.of());
    }

    public RpgSkillOperation(
            UUID playerId,
            SkillResetPlan.Mode mode,
            Identifier target,
            long cost,
            long timestampEpochMillis,
            Optional<SkillResetPlan> plan,
            Phase phase,
            Kind kind) {
        this(playerId, mode, target, cost, timestampEpochMillis, plan, phase, kind,
                List.of(), List.of(), List.of());
    }

    public RpgSkillOperation(
            UUID playerId,
            SkillResetPlan.Mode mode,
            Identifier target,
            long cost,
            long timestampEpochMillis,
            Optional<SkillResetPlan> plan,
            Phase phase) {
        this(playerId, mode, target, cost, timestampEpochMillis, plan, phase, Kind.SKILL_RESET,
                List.of(), List.of(), List.of());
    }

    public static RpgSkillOperation careerPromotion(
            UUID playerId,
            Identifier careerId,
            long cost,
            long timestampEpochMillis,
            Phase phase) {
        return careerPromotion(playerId, careerId, cost, List.of(), List.of(), List.of(),
                timestampEpochMillis, phase);
    }

    public static RpgSkillOperation careerPromotion(
            UUID playerId,
            Identifier careerId,
            long cost,
            List<RpgItemCost> itemCosts,
            long timestampEpochMillis,
            Phase phase) {
        return careerPromotion(playerId, careerId, cost, itemCosts, List.of(), List.of(),
                timestampEpochMillis, phase);
    }

    public static RpgSkillOperation careerPromotion(
            UUID playerId,
            Identifier careerId,
            long cost,
            List<RpgItemCost> itemCosts,
            List<Long> itemCountsBefore,
            List<Long> itemCountsAfter,
            long timestampEpochMillis,
            Phase phase) {
        return new RpgSkillOperation(
                playerId, SkillResetPlan.Mode.FULL, careerId, cost, timestampEpochMillis,
                Optional.empty(), phase, Kind.CAREER_PROMOTION,
                itemCosts, itemCountsBefore, itemCountsAfter);
    }

    public RpgSkillOperation completed() {
        return new RpgSkillOperation(
                playerId, mode, target, cost, timestampEpochMillis, plan, Phase.COMPLETED, kind,
                itemCosts, itemCountsBefore, itemCountsAfter);
    }

    public boolean matches(UUID player, SkillResetPlan resetPlan, long paymentCost) {
        return matches(player, resetPlan, paymentCost, List.of());
    }

    public boolean matches(
            UUID player, SkillResetPlan resetPlan, long paymentCost, List<RpgItemCost> paymentItems) {
        return matches(player, resetPlan, paymentCost, paymentItems, List.of(), List.of());
    }

    public boolean matches(
            UUID player,
            SkillResetPlan resetPlan,
            long paymentCost,
            List<RpgItemCost> paymentItems,
            List<Long> countsBefore,
            List<Long> countsAfter) {
        return kind == Kind.SKILL_RESET
                && playerId.equals(player)
                && mode == resetPlan.mode()
                && target.equals(resetPlan.target())
                && cost == paymentCost
                && itemCosts.equals(paymentItems)
                && itemCountsBefore.equals(countsBefore)
                && itemCountsAfter.equals(countsAfter)
                && plan.equals(Optional.of(resetPlan));
    }

    public boolean matchesPromotion(UUID player, Identifier careerId, long paymentCost) {
        return matchesPromotion(player, careerId, paymentCost, List.of());
    }

    public boolean matchesPromotion(
            UUID player, Identifier careerId, long paymentCost, List<RpgItemCost> paymentItems) {
        return matchesPromotion(player, careerId, paymentCost, paymentItems, List.of(), List.of());
    }

    public boolean matchesPromotion(
            UUID player,
            Identifier careerId,
            long paymentCost,
            List<RpgItemCost> paymentItems,
            List<Long> countsBefore,
            List<Long> countsAfter) {
        return kind == Kind.CAREER_PROMOTION
                && playerId.equals(player)
                && target.equals(careerId)
                && cost == paymentCost
                && itemCosts.equals(paymentItems)
                && itemCountsBefore.equals(countsBefore)
                && itemCountsAfter.equals(countsAfter);
    }

    public boolean hasInventoryEvidence() {
        if (itemCosts.isEmpty()) {
            return itemCountsBefore.isEmpty() && itemCountsAfter.isEmpty();
        }
        if (itemCountsBefore.size() != itemCosts.size() || itemCountsAfter.size() != itemCosts.size()) {
            return false;
        }
        for (int index = 0; index < itemCosts.size(); index++) {
            long before = itemCountsBefore.get(index);
            long after = itemCountsAfter.get(index);
            if (before < itemCosts.get(index).count() || after != before - itemCosts.get(index).count()) {
                return false;
            }
        }
        return true;
    }

    private static DataResult<RpgSkillOperation> validate(RpgSkillOperation operation) {
        if (operation == null || operation.playerId == null || operation.playerId.equals(new UUID(0L, 0L))
                || operation.mode == null || operation.target == null || operation.cost < 0
                || operation.cost > RpgPlayerState.MAX_XP || operation.timestampEpochMillis < 0
                || operation.phase == null || operation.kind == null || operation.itemCosts == null
                || operation.itemCosts.size() > RpgItemCost.MAX_ENTRIES
                || operation.itemCosts.stream().anyMatch(item -> item == null || item.item() == null
                        || item.count() < 1 || item.count() > RpgItemCost.MAX_COUNT)
                || operation.itemCosts.stream().map(RpgItemCost::item).distinct().count()
                        != operation.itemCosts.size()
                || operation.itemCountsBefore == null || operation.itemCountsAfter == null
                || operation.itemCountsBefore.stream().anyMatch(count -> count == null || count < 0)
                || operation.itemCountsAfter.stream().anyMatch(count -> count == null || count < 0)
                || operation.itemCountsBefore.size() > RpgItemCost.MAX_ENTRIES
                || operation.itemCountsAfter.size() > RpgItemCost.MAX_ENTRIES
                || operation.itemCosts.isEmpty()
                        && (!operation.itemCountsBefore.isEmpty() || !operation.itemCountsAfter.isEmpty())
                || !operation.itemCountsBefore.isEmpty() && !operation.hasInventoryEvidence()
                || operation.cost == 0 && operation.itemCosts.isEmpty()
                || (operation.kind == Kind.SKILL_RESET
                && operation.phase == Phase.PENDING && operation.plan.isEmpty())
                || (operation.kind == Kind.CAREER_PROMOTION
                && (operation.mode != SkillResetPlan.Mode.FULL || operation.plan.isPresent()))) {
            return DataResult.error(() -> "Paid RPG operation is invalid");
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
        ITEMS_CONSUMED("items_consumed"),
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

    public enum Kind implements StringRepresentable {
        SKILL_RESET("skill_reset"),
        CAREER_PROMOTION("career_promotion");

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
