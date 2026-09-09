package org.dldyou.rovenfall.careers;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.Identifier;

public record ActiveSkillState(
        Optional<Identifier> slotOne,
        Optional<Identifier> slotTwo,
        Optional<Identifier> slotThree,
        Optional<Identifier> slotFour,
        Map<Identifier, Long> cooldownReadyAtEpochMillis) {
    public static final int SLOT_COUNT = 4;
    public static final int MAX_COOLDOWNS = 1_024;
    private static final Codec<Long> COOLDOWN_CODEC = Codec.LONG.validate(value -> value < 0
            ? DataResult.error(() -> "active skill cooldown timestamp must be non-negative")
            : DataResult.success(value));
    private static final Codec<Map<Identifier, Long>> COOLDOWNS_CODEC = Codec.unboundedMap(
            Identifier.CODEC, COOLDOWN_CODEC).validate(values -> values.size() > MAX_COOLDOWNS
                    ? DataResult.error(() -> "active skill cooldown count exceeds " + MAX_COOLDOWNS)
                    : DataResult.success(values));
    public static final Codec<ActiveSkillState> CODEC = RecordCodecBuilder
            .<ActiveSkillState>create(instance -> instance.group(
                    Identifier.CODEC.optionalFieldOf("slot_1").forGetter(ActiveSkillState::slotOne),
                    Identifier.CODEC.optionalFieldOf("slot_2").forGetter(ActiveSkillState::slotTwo),
                    Identifier.CODEC.optionalFieldOf("slot_3").forGetter(ActiveSkillState::slotThree),
                    Identifier.CODEC.optionalFieldOf("slot_4").forGetter(ActiveSkillState::slotFour),
                    COOLDOWNS_CODEC.optionalFieldOf("cooldowns", Map.of())
                            .forGetter(ActiveSkillState::cooldownReadyAtEpochMillis)
            ).apply(instance, ActiveSkillState::new))
            .validate(ActiveSkillState::validate);

    public ActiveSkillState {
        slotOne = slotOne == null ? Optional.empty() : slotOne;
        slotTwo = slotTwo == null ? Optional.empty() : slotTwo;
        slotThree = slotThree == null ? Optional.empty() : slotThree;
        slotFour = slotFour == null ? Optional.empty() : slotFour;
        cooldownReadyAtEpochMillis = cooldownReadyAtEpochMillis == null
                ? Map.of()
                : Map.copyOf(cooldownReadyAtEpochMillis);
    }

    public static ActiveSkillState empty() {
        return new ActiveSkillState(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Map.of());
    }

    public Optional<Identifier> slot(int slot) {
        return switch (slot) {
            case 1 -> slotOne;
            case 2 -> slotTwo;
            case 3 -> slotThree;
            case 4 -> slotFour;
            default -> throw new IllegalArgumentException("Active skill slot must be between 1 and 4");
        };
    }

    public Set<Identifier> equippedSkills() {
        Set<Identifier> equipped = new HashSet<>();
        slotOne.ifPresent(equipped::add);
        slotTwo.ifPresent(equipped::add);
        slotThree.ifPresent(equipped::add);
        slotFour.ifPresent(equipped::add);
        return Set.copyOf(equipped);
    }

    public ActiveSkillState equip(int slot, Identifier skillId) {
        if (skillId == null || equippedSkills().stream().anyMatch(id -> id.equals(skillId)
                && !slot(slot).equals(Optional.of(skillId)))) {
            throw new IllegalArgumentException("Active skill is already equipped in another slot");
        }
        return withSlot(slot, Optional.of(skillId));
    }

    public ActiveSkillState clear(int slot) {
        return withSlot(slot, Optional.empty());
    }

    public long cooldownReadyAt(Identifier skillId) {
        return cooldownReadyAtEpochMillis.getOrDefault(skillId, 0L);
    }

    public ActiveSkillState recordUse(Identifier skillId, long usedAtEpochMillis, long readyAtEpochMillis) {
        if (skillId == null || !equippedSkills().contains(skillId)
                || usedAtEpochMillis < 0 || readyAtEpochMillis < usedAtEpochMillis
                || cooldownReadyAt(skillId) > usedAtEpochMillis) {
            throw new IllegalArgumentException("Active skill use is invalid or still cooling down");
        }
        Map<Identifier, Long> updated = new HashMap<>();
        cooldownReadyAtEpochMillis.forEach((id, readyAt) -> {
            if (readyAt > usedAtEpochMillis || id.equals(skillId)) {
                updated.put(id, readyAt);
            }
        });
        if (readyAtEpochMillis > usedAtEpochMillis) {
            updated.put(skillId, readyAtEpochMillis);
        } else {
            updated.remove(skillId);
        }
        if (updated.size() > MAX_COOLDOWNS) {
            throw new IllegalStateException("Active skill cooldown capacity is exhausted");
        }
        return new ActiveSkillState(slotOne, slotTwo, slotThree, slotFour, updated);
    }

    public ActiveSkillState removeSkills(Set<Identifier> skillIds) {
        if (skillIds == null || skillIds.isEmpty()) {
            return this;
        }
        Map<Identifier, Long> updatedCooldowns = new HashMap<>(cooldownReadyAtEpochMillis);
        skillIds.forEach(updatedCooldowns::remove);
        return new ActiveSkillState(
                slotOne.filter(id -> !skillIds.contains(id)),
                slotTwo.filter(id -> !skillIds.contains(id)),
                slotThree.filter(id -> !skillIds.contains(id)),
                slotFour.filter(id -> !skillIds.contains(id)),
                updatedCooldowns);
    }

    private ActiveSkillState withSlot(int slot, Optional<Identifier> skillId) {
        return switch (slot) {
            case 1 -> new ActiveSkillState(skillId, slotTwo, slotThree, slotFour, cooldownReadyAtEpochMillis);
            case 2 -> new ActiveSkillState(slotOne, skillId, slotThree, slotFour, cooldownReadyAtEpochMillis);
            case 3 -> new ActiveSkillState(slotOne, slotTwo, skillId, slotFour, cooldownReadyAtEpochMillis);
            case 4 -> new ActiveSkillState(slotOne, slotTwo, slotThree, skillId, cooldownReadyAtEpochMillis);
            default -> throw new IllegalArgumentException("Active skill slot must be between 1 and 4");
        };
    }

    private static DataResult<ActiveSkillState> validate(ActiveSkillState state) {
        if (state == null || state.slotOne == null || state.slotTwo == null
                || state.slotThree == null || state.slotFour == null
                || state.cooldownReadyAtEpochMillis == null
                || state.cooldownReadyAtEpochMillis.size() > MAX_COOLDOWNS) {
            return DataResult.error(() -> "active skill state is invalid");
        }
        int equippedCount = (int) java.util.stream.Stream.of(
                state.slotOne, state.slotTwo, state.slotThree, state.slotFour).filter(Optional::isPresent).count();
        if (state.equippedSkills().size() != equippedCount) {
            return DataResult.error(() -> "active skill cannot occupy more than one slot");
        }
        return DataResult.success(state);
    }
}
