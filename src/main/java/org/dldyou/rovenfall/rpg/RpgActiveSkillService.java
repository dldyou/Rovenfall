package org.dldyou.rovenfall.rpg;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.Identifier;

/** Server-authoritative slot assignment and active-skill activation boundary. */
public final class RpgActiveSkillService {
    private static final UUID ZERO_UUID = new UUID(0L, 0L);

    public enum Status {
        SUCCESS,
        INVALID_REQUEST,
        READ_ONLY,
        INVALID_SLOT,
        UNKNOWN_SKILL,
        SKILL_NOT_ACTIVE,
        EFFECT_UNAVAILABLE,
        NOT_LEARNED,
        INACTIVE_CAREER,
        NOTHING_BOUND,
        DUPLICATE,
        STALE_DEFINITIONS,
        WRONG_DIMENSION,
        INVALID_TARGET,
        COOLDOWN,
        STATE_FULL
    }

    public record SlotResult(Status status, int slot, Optional<Identifier> skill, boolean committed) {
        public SlotResult {
            skill = skill == null ? Optional.empty() : skill;
        }
    }

    public record ActivationRequest(
            long definitionRevision,
            long requestId,
            int slot,
            Identifier dimension,
            int targetEntityId) {
    }

    public record ActivationResult(
            Status status,
            Optional<Identifier> skill,
            long cooldownUntil,
            boolean requestConsumed,
            boolean activated) {
        public ActivationResult {
            skill = skill == null ? Optional.empty() : skill;
        }
    }

    public interface EffectGateway {
        Identifier dimension();

        boolean validate(SkillDefinition.ActiveEffect effect, int targetEntityId);

        void apply(
                Identifier skillId,
                int rank,
                SkillDefinition.ActiveEffect effect,
                int targetEntityId,
                long gameTime);
    }

    private RpgActiveSkillService() {
    }

    public static SlotResult assignSlot(
            RpgPlayerSavedData state,
            RpgDefinitionSnapshot definitions,
            UUID playerId,
            int slot,
            Optional<Identifier> requestedSkill,
            int enabledSlots,
            long timestamp,
            UUID transactionId,
            String source) {
        if (state == null || definitions == null || playerId == null || ZERO_UUID.equals(playerId)
                || requestedSkill == null || slot < 0 || slot >= enabledSlots
                || enabledSlots < 1 || enabledSlots > RpgPlayerState.MAX_ACTIVE_SKILL_SLOTS
                || timestamp < 0 || transactionId == null || ZERO_UUID.equals(transactionId)
                || source == null || source.isBlank() || source.length() > 160) {
            return slotResult(Status.INVALID_REQUEST, slot, requestedSkill);
        }
        if (!state.isWritable()) {
            return slotResult(Status.READ_ONLY, slot, requestedSkill);
        }
        RpgPlayerState current = state.state(playerId);
        if (RpgSkillService.hasTransaction(current, transactionId)) {
            return slotResult(Status.DUPLICATE, slot, requestedSkill);
        }

        Identifier previous = current.activeSkillSlots().get(slot);
        if (requestedSkill.isEmpty()) {
            if (previous == null) {
                return slotResult(Status.NOTHING_BOUND, slot, Optional.empty());
            }
        } else {
            Identifier skillId = requestedSkill.orElseThrow();
            SkillDefinition definition = definitions.skill(skillId).orElse(null);
            if (definition == null) {
                return slotResult(Status.UNKNOWN_SKILL, slot, requestedSkill);
            }
            if (definition.kind() != SkillDefinition.Kind.ACTIVE) {
                return slotResult(Status.SKILL_NOT_ACTIVE, slot, requestedSkill);
            }
            if (definition.activeEffect().isEmpty()) {
                return slotResult(Status.EFFECT_UNAVAILABLE, slot, requestedSkill);
            }
            RpgPlayerState.CareerProgress progress = current.careers().get(definition.career());
            if (progress == null || progress.learnedSkills().getOrDefault(skillId, 0) < 1) {
                return slotResult(Status.NOT_LEARNED, slot, requestedSkill);
            }
            Set<Identifier> lineage = current.activeCareer()
                    .map(career -> RpgPassiveSkillService.activeLineage(career, definitions))
                    .orElse(Set.of());
            if (!lineage.contains(definition.career())) {
                return slotResult(Status.INACTIVE_CAREER, slot, requestedSkill);
            }
            if (skillId.equals(previous)) {
                return slotResult(Status.DUPLICATE, slot, requestedSkill);
            }
        }

        Map<Integer, Identifier> slots = new HashMap<>(current.activeSkillSlots());
        requestedSkill.ifPresent(skill -> slots.entrySet().removeIf(entry -> entry.getValue().equals(skill)));
        if (requestedSkill.isPresent()) {
            slots.put(slot, requestedSkill.orElseThrow());
        } else {
            slots.remove(slot);
        }
        Identifier evidenceTarget = requestedSkill.orElse(previous);
        List<RpgPlayerState.ProgressionProvenance> careerEvidence = CareerProgressionService.appendCareerEvidence(
                current,
                new RpgPlayerState.ProgressionProvenance(
                        RpgPlayerState.ProgressionProvenance.Kind.SKILL_SLOT,
                        evidenceTarget,
                        slot,
                        timestamp,
                        transactionId,
                        source,
                        Optional.ofNullable(previous)));
        RpgPlayerState candidate = copy(current, slots, current.cooldowns(), careerEvidence,
                current.lastActiveSkillRequestId());
        boolean committed = state.commit(playerId, candidate);
        return new SlotResult(committed ? Status.SUCCESS : Status.STATE_FULL,
                slot, requestedSkill, committed);
    }

    public static ActivationResult activate(
            RpgPlayerSavedData state,
            RpgDefinitionSnapshot definitions,
            long currentRevision,
            UUID playerId,
            ActivationRequest request,
            int enabledSlots,
            long gameTime,
            EffectGateway gateway) {
        if (state == null || definitions == null || currentRevision < 1 || playerId == null
                || ZERO_UUID.equals(playerId) || request == null || request.dimension() == null
                || enabledSlots < 1 || enabledSlots > RpgPlayerState.MAX_ACTIVE_SKILL_SLOTS
                || gameTime < 0 || gateway == null) {
            return activation(Status.INVALID_REQUEST);
        }
        if (!state.isWritable()) {
            return activation(Status.READ_ONLY);
        }
        RpgPlayerState current = state.state(playerId);
        if (request.definitionRevision() != currentRevision) {
            return activation(Status.STALE_DEFINITIONS);
        }
        if (current.lastActiveSkillRequestId() == Long.MAX_VALUE
                || request.requestId() != current.lastActiveSkillRequestId() + 1) {
            return activation(request.requestId() <= current.lastActiveSkillRequestId()
                    ? Status.DUPLICATE : Status.INVALID_REQUEST);
        }
        if (request.slot() < 0 || request.slot() >= enabledSlots) {
            return consume(state, playerId, current, request.requestId(), Status.INVALID_SLOT, Optional.empty(), 0);
        }
        if (!request.dimension().equals(gateway.dimension())) {
            return consume(state, playerId, current, request.requestId(), Status.WRONG_DIMENSION, Optional.empty(), 0);
        }

        Identifier skillId = current.activeSkillSlots().get(request.slot());
        if (skillId == null) {
            return consume(state, playerId, current, request.requestId(), Status.NOTHING_BOUND, Optional.empty(), 0);
        }
        SkillDefinition definition = definitions.skill(skillId).orElse(null);
        if (definition == null) {
            return consume(state, playerId, current, request.requestId(), Status.UNKNOWN_SKILL,
                    Optional.of(skillId), 0);
        }
        if (definition.kind() != SkillDefinition.Kind.ACTIVE) {
            return consume(state, playerId, current, request.requestId(), Status.SKILL_NOT_ACTIVE,
                    Optional.of(skillId), 0);
        }
        SkillDefinition.ActiveEffect effect = definition.activeEffect().orElse(null);
        if (effect == null) {
            return consume(state, playerId, current, request.requestId(), Status.EFFECT_UNAVAILABLE,
                    Optional.of(skillId), 0);
        }
        RpgPlayerState.CareerProgress progress = current.careers().get(definition.career());
        int rank = progress == null ? 0 : progress.learnedSkills().getOrDefault(skillId, 0);
        if (rank < 1 || rank > definition.maxRank()) {
            return consume(state, playerId, current, request.requestId(), Status.NOT_LEARNED,
                    Optional.of(skillId), 0);
        }
        Set<Identifier> lineage = current.activeCareer()
                .map(career -> RpgPassiveSkillService.activeLineage(career, definitions))
                .orElse(Set.of());
        if (!lineage.contains(definition.career())) {
            return consume(state, playerId, current, request.requestId(), Status.INACTIVE_CAREER,
                    Optional.of(skillId), 0);
        }
        long cooldown = current.cooldowns().getOrDefault(skillId, 0L);
        if (cooldown > gameTime) {
            return consume(state, playerId, current, request.requestId(), Status.COOLDOWN,
                    Optional.of(skillId), cooldown);
        }
        if (!gateway.validate(effect, request.targetEntityId())) {
            return consume(state, playerId, current, request.requestId(), Status.INVALID_TARGET,
                    Optional.of(skillId), 0);
        }

        final long cooldownUntil;
        try {
            cooldownUntil = Math.addExact(gameTime, definition.cooldownTicks().orElseThrow());
        } catch (ArithmeticException | java.util.NoSuchElementException exception) {
            return consume(state, playerId, current, request.requestId(), Status.INVALID_REQUEST,
                    Optional.of(skillId), 0);
        }
        Map<Identifier, Long> cooldowns = new HashMap<>(current.cooldowns());
        cooldowns.put(skillId, cooldownUntil);
        RpgPlayerState candidate = copy(current, current.activeSkillSlots(), cooldowns,
                current.careerProvenance(), request.requestId());
        if (!state.commit(playerId, candidate)) {
            return activation(Status.STATE_FULL);
        }
        gateway.apply(skillId, rank, effect, request.targetEntityId(), gameTime);
        return new ActivationResult(Status.SUCCESS, Optional.of(skillId), cooldownUntil, true, true);
    }

    private static ActivationResult consume(
            RpgPlayerSavedData state,
            UUID playerId,
            RpgPlayerState current,
            long requestId,
            Status status,
            Optional<Identifier> skill,
            long cooldownUntil) {
        RpgPlayerState candidate = copy(current, current.activeSkillSlots(), current.cooldowns(),
                current.careerProvenance(), requestId);
        if (!state.commit(playerId, candidate)) {
            return activation(Status.STATE_FULL);
        }
        return new ActivationResult(status, skill, cooldownUntil, true, false);
    }

    private static RpgPlayerState copy(
            RpgPlayerState current,
            Map<Integer, Identifier> slots,
            Map<Identifier, Long> cooldowns,
            List<RpgPlayerState.ProgressionProvenance> careerEvidence,
            long lastRequestId) {
        return new RpgPlayerState(
                current.activityXp(), current.careers(), current.activeCareer(), slots, cooldowns,
                current.explorationDiscoveries(), CareerProgressionService.activityEvidence(current),
                careerEvidence, lastRequestId);
    }

    private static SlotResult slotResult(Status status, int slot, Optional<Identifier> skill) {
        return new SlotResult(status, slot, skill, false);
    }

    private static ActivationResult activation(Status status) {
        return new ActivationResult(status, Optional.empty(), 0, false, false);
    }
}
