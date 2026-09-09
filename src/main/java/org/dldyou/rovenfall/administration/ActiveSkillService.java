package org.dldyou.rovenfall.administration;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.Rovenfall;
import org.dldyou.rovenfall.careers.ActiveSkillState;
import org.dldyou.rovenfall.careers.CareerActiveSkillDefinition;
import org.dldyou.rovenfall.careers.CareerCatalog;
import org.dldyou.rovenfall.careers.CareerSkillDefinition;
import org.dldyou.rovenfall.careers.PlayerCareerState;

public final class ActiveSkillService {
    private static final long DENIED_AUDIT_INTERVAL_MILLIS = 1_000L;
    private static final Identifier EQUIP = action("career_active_skill_equip");
    private static final Identifier CLEAR = action("career_active_skill_clear");
    private static final Identifier USE = action("career_active_skill_use");
    private static final Identifier DENIED = action("career_active_skill_denied");

    private ActiveSkillService() {
    }

    public static Evaluation evaluateEquip(
            PlatformSavedData state,
            CareerCatalog catalog,
            UUID playerId,
            int slot,
            Identifier skillId) {
        if (state == null || catalog == null || playerId == null || skillId == null || !validSlot(slot)) {
            return evaluation(Status.INVALID_REQUEST, Operation.EQUIP, slot, skillId,
                    Optional.empty(), Optional.empty(), 0, 0);
        }
        PlayerCareerState careers = state.playerCareerState(playerId);
        Optional<CareerCatalog.SkillBinding> binding = catalog.skill(skillId);
        if (!state.isWritable()) {
            return evaluation(Status.READ_ONLY_SCHEMA, Operation.EQUIP, slot, skillId,
                    binding, activeDefinition(binding), 0, 0);
        }
        Status status = availabilityStatus(catalog, careers, binding, skillId);
        if (status == Status.SUCCESS && careers.activeSkills().equippedSkills().contains(skillId)) {
            status = Status.ALREADY_EQUIPPED;
        }
        return evaluation(status, Operation.EQUIP, slot, skillId,
                binding, activeDefinition(binding), 0, 0);
    }

    public static Evaluation evaluateClear(
            PlatformSavedData state,
            UUID playerId,
            int slot) {
        if (state == null || playerId == null || !validSlot(slot)) {
            return evaluation(Status.INVALID_REQUEST, Operation.CLEAR, slot, null,
                    Optional.empty(), Optional.empty(), 0, 0);
        }
        Optional<Identifier> skillId = state.playerCareerState(playerId).activeSkills().slot(slot);
        Status status = !state.isWritable()
                ? Status.READ_ONLY_SCHEMA
                : skillId.isEmpty() ? Status.SLOT_EMPTY : Status.SUCCESS;
        return evaluation(status, Operation.CLEAR, slot, skillId.orElse(null),
                Optional.empty(), Optional.empty(), 0, 0);
    }

    public static Evaluation evaluateUse(
            PlatformSavedData state,
            CareerCatalog catalog,
            UUID playerId,
            int slot,
            long timestampEpochMillis,
            Predicate<Identifier> effectExists) {
        return evaluateUse(state, catalog, playerId, slot, timestampEpochMillis, effectExists, ignored -> true);
    }

    public static Evaluation evaluateUse(
            PlatformSavedData state,
            CareerCatalog catalog,
            UUID playerId,
            int slot,
            long timestampEpochMillis,
            Predicate<Identifier> effectExists,
            Predicate<CareerActiveSkillDefinition> effectApplicable) {
        if (state == null || catalog == null || playerId == null || !validSlot(slot)
                || timestampEpochMillis < 0 || effectExists == null || effectApplicable == null) {
            return evaluation(Status.INVALID_REQUEST, Operation.USE, slot, null,
                    Optional.empty(), Optional.empty(), 0, 0);
        }
        PlayerCareerState careers = state.playerCareerState(playerId);
        Optional<Identifier> retainedSkill = careers.activeSkills().slot(slot);
        if (!state.isWritable()) {
            Optional<CareerCatalog.SkillBinding> binding = retainedSkill.flatMap(catalog::skill);
            return evaluation(Status.READ_ONLY_SCHEMA, Operation.USE, slot, retainedSkill.orElse(null),
                    binding, activeDefinition(binding), 0, 0);
        }
        if (retainedSkill.isEmpty()) {
            return evaluation(Status.SLOT_EMPTY, Operation.USE, slot, null,
                    Optional.empty(), Optional.empty(), 0, 0);
        }
        Identifier skillId = retainedSkill.orElseThrow();
        Optional<CareerCatalog.SkillBinding> binding = catalog.skill(skillId);
        Status status = availabilityStatus(catalog, careers, binding, skillId);
        Optional<CareerActiveSkillDefinition> active = activeDefinition(binding);
        if (status == Status.SUCCESS && !effectExists.test(active.orElseThrow().effectId())) {
            status = Status.EFFECT_UNAVAILABLE;
        }
        long readyAt = careers.activeSkills().cooldownReadyAt(skillId);
        long retryAfter = 0;
        if (status == Status.SUCCESS && readyAt > timestampEpochMillis) {
            status = Status.COOLDOWN;
            retryAfter = readyAt - timestampEpochMillis;
        }
        if (status == Status.SUCCESS && !effectApplicable.test(active.orElseThrow())) {
            status = Status.EFFECT_NOT_APPLICABLE;
        }
        return evaluation(status, Operation.USE, slot, skillId, binding, active, readyAt, retryAfter);
    }

    public static Result equip(
            PlatformSavedData state,
            CareerCatalog catalog,
            UUID playerId,
            int slot,
            Identifier skillId,
            long timestampEpochMillis) {
        if (timestampEpochMillis < 0) {
            Evaluation invalid = evaluation(Status.INVALID_REQUEST, Operation.EQUIP, slot, skillId,
                    Optional.empty(), Optional.empty(), 0, 0);
            return denied(state, playerId, invalid, timestampEpochMillis);
        }
        Evaluation evaluation = evaluateEquip(state, catalog, playerId, slot, skillId);
        if (!evaluation.allowed()) {
            return denied(state, playerId, evaluation, timestampEpochMillis);
        }
        PlayerCareerState before = state.playerCareerState(playerId);
        PlayerCareerState after = before.equipActiveSkill(slot, skillId);
        state.commitActiveSkillSlot(
                playerId,
                slot,
                Optional.of(skillId),
                after,
                audit(timestampEpochMillis, playerId, EQUIP, skillId.toString(), before, after, "slot=" + slot));
        return new Result(Status.SUCCESS, evaluation, true);
    }

    public static Result clear(
            PlatformSavedData state,
            UUID playerId,
            int slot,
            long timestampEpochMillis) {
        if (timestampEpochMillis < 0) {
            Evaluation invalid = evaluation(Status.INVALID_REQUEST, Operation.CLEAR, slot, null,
                    Optional.empty(), Optional.empty(), 0, 0);
            return denied(state, playerId, invalid, timestampEpochMillis);
        }
        Evaluation evaluation = evaluateClear(state, playerId, slot);
        if (!evaluation.allowed()) {
            return denied(state, playerId, evaluation, timestampEpochMillis);
        }
        PlayerCareerState before = state.playerCareerState(playerId);
        PlayerCareerState after = before.clearActiveSkillSlot(slot);
        Identifier skillId = evaluation.skillId().orElseThrow();
        state.commitActiveSkillSlot(
                playerId,
                slot,
                Optional.empty(),
                after,
                audit(timestampEpochMillis, playerId, CLEAR, skillId.toString(), before, after, "slot=" + slot));
        return new Result(Status.SUCCESS, evaluation, true);
    }

    public static Result use(
            PlatformSavedData state,
            CareerCatalog catalog,
            UUID playerId,
            int slot,
            long timestampEpochMillis,
            Predicate<Identifier> effectExists) {
        return use(state, catalog, playerId, slot, timestampEpochMillis, effectExists, ignored -> true);
    }

    public static Result use(
            PlatformSavedData state,
            CareerCatalog catalog,
            UUID playerId,
            int slot,
            long timestampEpochMillis,
            Predicate<Identifier> effectExists,
            Predicate<CareerActiveSkillDefinition> effectApplicable) {
        Evaluation evaluation = evaluateUse(
                state, catalog, playerId, slot, timestampEpochMillis, effectExists, effectApplicable);
        if (!evaluation.allowed()) {
            return denied(state, playerId, evaluation, timestampEpochMillis);
        }
        CareerActiveSkillDefinition active = evaluation.activeDefinition().orElseThrow();
        long readyAt;
        try {
            readyAt = Math.addExact(
                    timestampEpochMillis,
                    Math.multiplyExact((long) active.cooldownSeconds(), 1_000L));
        } catch (ArithmeticException exception) {
            Evaluation invalid = evaluation(Status.INVALID_REQUEST, Operation.USE, slot,
                    evaluation.skillId().orElse(null), evaluation.binding(), evaluation.activeDefinition(), 0, 0);
            return denied(state, playerId, invalid, timestampEpochMillis);
        }
        Identifier skillId = evaluation.skillId().orElseThrow();
        PlayerCareerState before = state.playerCareerState(playerId);
        PlayerCareerState after;
        try {
            after = before.recordActiveSkillUse(skillId, timestampEpochMillis, readyAt);
        } catch (IllegalStateException exception) {
            Evaluation full = evaluation(Status.COOLDOWN_CAP_REACHED, Operation.USE, slot, skillId,
                    evaluation.binding(), evaluation.activeDefinition(), 0, 0);
            return denied(state, playerId, full, timestampEpochMillis);
        }
        state.commitActiveSkillUse(
                playerId,
                skillId,
                timestampEpochMillis,
                readyAt,
                after,
                audit(timestampEpochMillis, playerId, USE, skillId.toString(), before, after, "slot=" + slot));
        Evaluation committed = evaluation(Status.SUCCESS, Operation.USE, slot, skillId,
                evaluation.binding(), evaluation.activeDefinition(), readyAt, 0);
        return new Result(Status.SUCCESS, committed, true);
    }

    private static Status availabilityStatus(
            CareerCatalog catalog,
            PlayerCareerState careers,
            Optional<CareerCatalog.SkillBinding> binding,
            Identifier skillId) {
        if (binding.isEmpty()) {
            return Status.SKILL_NOT_FOUND;
        }
        CareerCatalog.SkillBinding retained = binding.orElseThrow();
        if (retained.definition().active().isEmpty()) {
            return Status.SKILL_NOT_ACTIVE;
        }
        if (careers.activeCareer().isEmpty()) {
            return Status.NO_ACTIVE_CAREER;
        }
        if (careers.progress(retained.careerId()).skillRank(skillId) < 1) {
            return Status.SKILL_NOT_UNLOCKED;
        }
        if (retained.definition().scope() == CareerSkillDefinition.Scope.GLOBAL) {
            return Status.SUCCESS;
        }
        Identifier activeCareer = careers.activeCareer().orElseThrow();
        return activeCareer.equals(retained.careerId()) || catalog.ancestors(activeCareer).contains(retained.careerId())
                ? Status.SUCCESS
                : Status.CAREER_NOT_ACTIVE_LINEAGE;
    }

    private static Optional<CareerActiveSkillDefinition> activeDefinition(
            Optional<CareerCatalog.SkillBinding> binding) {
        return binding.flatMap(value -> value.definition().active());
    }

    private static Result denied(
            PlatformSavedData state,
            UUID playerId,
            Evaluation evaluation,
            long timestampEpochMillis) {
        if (state == null || !state.isWritable() || playerId == null || timestampEpochMillis < 0) {
            return new Result(evaluation.status(), evaluation, false);
        }
        boolean recorded = state.appendDeniedAudit(new AuditEntry(
                timestampEpochMillis,
                playerId,
                DENIED,
                evaluation.skillId().map(Identifier::toString).orElse("slot:" + evaluation.slot()),
                Optional.empty(),
                Optional.empty(),
                "unchanged",
                "unchanged",
                evaluation.status().id(),
                UUID.randomUUID()), DENIED_AUDIT_INTERVAL_MILLIS);
        return new Result(evaluation.status(), evaluation, recorded);
    }

    private static AuditEntry audit(
            long timestampEpochMillis,
            UUID playerId,
            Identifier action,
            String target,
            PlayerCareerState before,
            PlayerCareerState after,
            String reason) {
        return new AuditEntry(
                timestampEpochMillis,
                playerId,
                action,
                target,
                Optional.empty(),
                Optional.empty(),
                stateSummary(before),
                stateSummary(after),
                reason,
                UUID.randomUUID());
    }

    private static String stateSummary(PlayerCareerState state) {
        ActiveSkillState skills = state.activeSkills();
        return "slots=" + skills.slotOne().map(Identifier::toString).orElse("-") + ","
                + skills.slotTwo().map(Identifier::toString).orElse("-") + ","
                + skills.slotThree().map(Identifier::toString).orElse("-") + ","
                + skills.slotFour().map(Identifier::toString).orElse("-")
                + ";cooldowns=" + skills.cooldownReadyAtEpochMillis().size();
    }

    private static Evaluation evaluation(
            Status status,
            Operation operation,
            int slot,
            Identifier skillId,
            Optional<CareerCatalog.SkillBinding> binding,
            Optional<CareerActiveSkillDefinition> activeDefinition,
            long readyAtEpochMillis,
            long retryAfterMillis) {
        return new Evaluation(
                status,
                operation,
                slot,
                Optional.ofNullable(skillId),
                binding,
                activeDefinition,
                readyAtEpochMillis,
                retryAfterMillis);
    }

    private static boolean validSlot(int slot) {
        return slot >= 1 && slot <= ActiveSkillState.SLOT_COUNT;
    }

    private static Identifier action(String path) {
        return Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, path);
    }

    public enum Operation {
        EQUIP,
        CLEAR,
        USE
    }

    public enum Status {
        SUCCESS,
        INVALID_REQUEST,
        READ_ONLY_SCHEMA,
        NO_ACTIVE_CAREER,
        SKILL_NOT_FOUND,
        SKILL_NOT_ACTIVE,
        SKILL_NOT_UNLOCKED,
        CAREER_NOT_ACTIVE_LINEAGE,
        ALREADY_EQUIPPED,
        SLOT_EMPTY,
        EFFECT_UNAVAILABLE,
        EFFECT_NOT_APPLICABLE,
        COOLDOWN_CAP_REACHED,
        COOLDOWN;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public String translationKey() {
            return "active_skill_status.rovenfall." + id();
        }
    }

    public record Evaluation(
            Status status,
            Operation operation,
            int slot,
            Optional<Identifier> skillId,
            Optional<CareerCatalog.SkillBinding> binding,
            Optional<CareerActiveSkillDefinition> activeDefinition,
            long readyAtEpochMillis,
            long retryAfterMillis) {
        public Evaluation {
            skillId = skillId == null ? Optional.empty() : skillId;
            binding = binding == null ? Optional.empty() : binding;
            activeDefinition = activeDefinition == null ? Optional.empty() : activeDefinition;
        }

        public boolean allowed() {
            return status == Status.SUCCESS;
        }

        public long retryAfterSeconds() {
            return retryAfterMillis == 0 ? 0 : 1 + (retryAfterMillis - 1) / 1_000;
        }
    }

    public record Result(Status status, Evaluation evaluation, boolean auditRecorded) {
    }
}
