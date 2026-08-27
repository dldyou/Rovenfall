package org.dldyou.rovenfall.rpg;

import static org.dldyou.rovenfall.PersistenceTestHarness.roundTrip;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

final class RpgActiveSkillServiceTest {
    private static final UUID PLAYER = new UUID(0, 1);
    private static final Identifier WARRIOR = id("warrior");
    private static final Identifier STRIKE = id("strike");
    private static final Identifier DIMENSION = Identifier.withDefaultNamespace("overworld");

    @Test
    void assignsOnlyLearnedActiveSkillsInTheCurrentCareerLineage() {
        RpgPlayerSavedData state = state(Map.of(), Map.of(), 0);

        var notLearned = RpgActiveSkillService.assignSlot(
                state, definitions(), PLAYER, 0, Optional.of(STRIKE), 4,
                1, uuid(10), "test");
        assertEquals(RpgActiveSkillService.Status.NOT_LEARNED, notLearned.status());
        assertTrue(state.state(PLAYER).activeSkillSlots().isEmpty());

        state = state(Map.of(STRIKE, 2), Map.of(), 0);
        var assigned = RpgActiveSkillService.assignSlot(
                state, definitions(), PLAYER, 0, Optional.of(STRIKE), 4,
                2, uuid(11), "test");
        assertEquals(RpgActiveSkillService.Status.SUCCESS, assigned.status());
        assertEquals(Map.of(0, STRIKE), state.state(PLAYER).activeSkillSlots());
        assertEquals(RpgPlayerState.ProgressionProvenance.Kind.SKILL_SLOT,
                state.state(PLAYER).careerProvenance().getLast().kind());

        var moved = RpgActiveSkillService.assignSlot(
                state, definitions(), PLAYER, 1, Optional.of(STRIKE), 4,
                3, uuid(12), "test");
        assertEquals(RpgActiveSkillService.Status.SUCCESS, moved.status());
        assertEquals(Map.of(1, STRIKE), state.state(PLAYER).activeSkillSlots());

        var cleared = RpgActiveSkillService.assignSlot(
                state, definitions(), PLAYER, 1, Optional.empty(), 4,
                4, uuid(13), "test");
        assertEquals(RpgActiveSkillService.Status.SUCCESS, cleared.status());
        assertTrue(state.state(PLAYER).activeSkillSlots().isEmpty());
    }

    @Test
    void activationPersistsCooldownAndConsumesEveryAuthoritativeRequestExactlyOnce() {
        RpgPlayerSavedData state = state(Map.of(STRIKE, 2), Map.of(0, STRIKE), 0);
        RecordingGateway gateway = new RecordingGateway(DIMENSION, true);

        var success = activate(state, 7, 1, DIMENSION, 42, 100, gateway);
        assertEquals(RpgActiveSkillService.Status.SUCCESS, success.status());
        assertTrue(success.activated());
        assertEquals(200, success.cooldownUntil());
        assertEquals(1, gateway.applications);
        assertEquals(1, state.state(PLAYER).lastActiveSkillRequestId());
        assertEquals(200, state.state(PLAYER).cooldowns().get(STRIKE));

        RpgPlayerState persisted = roundTrip(RpgPlayerSavedData.CODEC, state).state(PLAYER);
        assertEquals(1, persisted.lastActiveSkillRequestId());
        assertEquals(200, persisted.cooldowns().get(STRIKE));

        var replay = activate(state, 7, 1, DIMENSION, 42, 100, gateway);
        assertEquals(RpgActiveSkillService.Status.DUPLICATE, replay.status());
        assertFalse(replay.requestConsumed());
        assertEquals(1, gateway.applications);

        var cooldown = activate(state, 7, 2, DIMENSION, 42, 101, gateway);
        assertEquals(RpgActiveSkillService.Status.COOLDOWN, cooldown.status());
        assertTrue(cooldown.requestConsumed());
        assertEquals(2, state.state(PLAYER).lastActiveSkillRequestId());

        var wrongDimension = activate(state, 7, 3, id("other_dimension"), 42, 201, gateway);
        assertEquals(RpgActiveSkillService.Status.WRONG_DIMENSION, wrongDimension.status());
        assertEquals(3, state.state(PLAYER).lastActiveSkillRequestId());

        var stale = activate(state, 6, 4, DIMENSION, 42, 201, gateway);
        assertEquals(RpgActiveSkillService.Status.STALE_DEFINITIONS, stale.status());
        assertEquals(3, state.state(PLAYER).lastActiveSkillRequestId());

        gateway.valid = false;
        var invalidTarget = activate(state, 7, 4, DIMENSION, 42, 201, gateway);
        assertEquals(RpgActiveSkillService.Status.INVALID_TARGET, invalidTarget.status());
        assertEquals(4, state.state(PLAYER).lastActiveSkillRequestId());
        assertEquals(1, gateway.applications);
    }

    private static RpgActiveSkillService.ActivationResult activate(
            RpgPlayerSavedData state,
            long revision,
            long request,
            Identifier dimension,
            int target,
            long gameTime,
            RecordingGateway gateway) {
        return RpgActiveSkillService.activate(
                state,
                definitions(),
                7,
                PLAYER,
                new RpgActiveSkillService.ActivationRequest(revision, request, 0, dimension, target),
                4,
                gameTime,
                gateway);
    }

    private static RpgDefinitionSnapshot definitions() {
        var career = new RpgDefinitionSnapshot.CareerSource(
                id("careers/warrior"), "test", WARRIOR,
                new CareerDefinition("career.rovenfall.warrior", 1, List.of(),
                        List.of(10L), 0, List.of(), 1));
        var skill = new RpgDefinitionSnapshot.SkillSource(
                id("skills/strike"), "test", STRIKE,
                new SkillDefinition(
                        "skill.rovenfall.strike",
                        WARRIOR,
                        SkillDefinition.Kind.ACTIVE,
                        3,
                        1,
                        List.of(),
                        Optional.of(100),
                        Optional.empty(),
                        Optional.of(new SkillDefinition.ActiveEffect(
                                SkillDefinition.EffectType.DAMAGE_DEALT,
                                SkillDefinition.TargetType.LIVING_ENTITY,
                                2_500,
                                40,
                                6.0))));
        return RpgDefinitionSnapshot.compile(List.of(), List.of(career), List.of(skill));
    }

    private static RpgPlayerSavedData state(
            Map<Identifier, Integer> learned,
            Map<Integer, Identifier> slots,
            long requestId) {
        RpgPlayerSavedData data = new RpgPlayerSavedData();
        var progress = new RpgPlayerState.CareerProgress(100, 1, 10, learned);
        assertTrue(data.commit(PLAYER, new RpgPlayerState(
                Map.of(),
                Map.of(WARRIOR, progress),
                Optional.of(WARRIOR),
                slots,
                Map.of(),
                java.util.Set.of(),
                List.of(),
                List.of(),
                requestId)));
        return data;
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }

    private static UUID uuid(long least) {
        return new UUID(0, least);
    }

    private static final class RecordingGateway implements RpgActiveSkillService.EffectGateway {
        private final Identifier dimension;
        private boolean valid;
        private int applications;

        private RecordingGateway(Identifier dimension, boolean valid) {
            this.dimension = dimension;
            this.valid = valid;
        }

        @Override
        public Identifier dimension() {
            return dimension;
        }

        @Override
        public boolean validate(SkillDefinition.ActiveEffect effect, int targetEntityId) {
            return valid && targetEntityId == 42;
        }

        @Override
        public void apply(
                Identifier skillId,
                int rank,
                SkillDefinition.ActiveEffect effect,
                int targetEntityId,
                long gameTime) {
            applications++;
        }
    }
}
