package org.dldyou.rovenfall.rpg;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.Identifier;

/** Pure server-side evaluation of learned passives in the active career lineage. */
public final class RpgPassiveSkillService {
    static final long MAX_DAMAGE_BONUS_BASIS_POINTS = 100_000L;
    static final long MAX_DAMAGE_REDUCTION_BASIS_POINTS = 9_000L;

    private RpgPassiveSkillService() {
    }

    public static float modifyDamage(
            RpgDefinitionSnapshot definitions,
            RpgPlayerState attacker,
            RpgPlayerState target,
            float amount) {
        if (definitions == null || !Float.isFinite(amount) || amount <= 0) {
            return amount;
        }
        long bonus = passiveBasisPoints(attacker, definitions, SkillDefinition.EffectType.DAMAGE_DEALT);
        long reduction = passiveBasisPoints(
                target, definitions, SkillDefinition.EffectType.DAMAGE_TAKEN_REDUCTION);
        bonus = Math.min(bonus, MAX_DAMAGE_BONUS_BASIS_POINTS);
        reduction = Math.min(reduction, MAX_DAMAGE_REDUCTION_BASIS_POINTS);
        double changed = amount * (10_000.0 + bonus) / 10_000.0;
        changed = changed * (10_000.0 - reduction) / 10_000.0;
        return (float) Math.min(changed, Float.MAX_VALUE);
    }

    static long passiveBasisPoints(
            RpgPlayerState player,
            RpgDefinitionSnapshot definitions,
            SkillDefinition.EffectType type) {
        if (player == null || player.activeCareer().isEmpty()) {
            return 0;
        }
        Set<Identifier> lineage = activeLineage(player.activeCareer().orElseThrow(), definitions);
        long result = 0;
        for (Identifier careerId : lineage) {
            RpgPlayerState.CareerProgress progress = player.careers().get(careerId);
            if (progress == null) {
                continue;
            }
            for (Map.Entry<Identifier, Integer> learned : progress.learnedSkills().entrySet()) {
                SkillDefinition definition = definitions.skill(learned.getKey()).orElse(null);
                if (definition == null || definition.kind() != SkillDefinition.Kind.PASSIVE
                        || !definition.career().equals(careerId)
                        || learned.getValue() < 1 || learned.getValue() > definition.maxRank()
                        || definition.passiveEffect().isEmpty()
                        || definition.passiveEffect().orElseThrow().type() != type) {
                    continue;
                }
                try {
                    result = Math.addExact(result, Math.multiplyExact(
                            (long) learned.getValue(),
                            definition.passiveEffect().orElseThrow().basisPointsPerRank()));
                } catch (ArithmeticException exception) {
                    return Long.MAX_VALUE;
                }
            }
        }
        return result;
    }

    static Set<Identifier> activeLineage(
            Identifier activeCareer, RpgDefinitionSnapshot definitions) {
        Set<Identifier> result = new HashSet<>();
        ArrayDeque<Identifier> remaining = new ArrayDeque<>();
        remaining.add(activeCareer);
        while (!remaining.isEmpty()) {
            Identifier careerId = remaining.removeFirst();
            if (!result.add(careerId)) {
                continue;
            }
            definitions.career(careerId).ifPresent(career -> remaining.addAll(career.parents()));
        }
        return result;
    }
}
