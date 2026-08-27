package org.dldyou.rovenfall.rpg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

final class ActivityXpAwardServiceTest {
    private static final Identifier ACTIVITY = id("combat");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final ActivityXpConfig.Limits LIMITS = new ActivityXpConfig.Limits(10, 2, 1_000, 100);

    @Test
    void awardsAtomicallyRecordsEvidenceAndRoundTrips() {
        RpgPlayerSavedData state = new RpgPlayerSavedData();
        RpgDefinitionSnapshot definitions = definitions();
        var result = ActivityXpAwardService.award(state, definitions, PLAYER, ACTIVITY, 5, 1_000,
                uuid(1), "combat:target", LIMITS);
        assertEquals(ActivityXpAwardService.Status.SUCCESS, result.status());
        assertEquals(5, state.state(PLAYER).activityXp().get(ACTIVITY));
        assertEquals("combat:target", state.state(PLAYER).provenance().getFirst().source());
        var encoded = RpgPlayerSavedData.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow();
        var loaded = RpgPlayerSavedData.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();
        assertEquals(state.snapshot().player(PLAYER), loaded.snapshot().player(PLAYER));
    }

    @Test
    void rejectsDuplicateCooldownRateUnknownAndReadOnlyWithoutMutation() {
        RpgPlayerSavedData state = new RpgPlayerSavedData();
        RpgDefinitionSnapshot definitions = definitions();
        assertEquals(ActivityXpAwardService.Status.SUCCESS,
                ActivityXpAwardService.award(state, definitions, PLAYER, ACTIVITY, 5, 1_000,
                        uuid(2), "combat:one", LIMITS).status());
        assertEquals(ActivityXpAwardService.Status.COOLDOWN,
                ActivityXpAwardService.award(state, definitions, PLAYER, ACTIVITY, 5, 1_050,
                        uuid(3), "combat:one", LIMITS).status());
        assertEquals(ActivityXpAwardService.Status.DUPLICATE,
                ActivityXpAwardService.award(state, definitions, PLAYER, ACTIVITY, 5, 1_000,
                        uuid(4), "combat:one", LIMITS).status());
        assertEquals(ActivityXpAwardService.Status.SUCCESS,
                ActivityXpAwardService.award(state, definitions, PLAYER, ACTIVITY, 5, 1_200,
                        uuid(5), "combat:two", LIMITS).status());
        int provenance = state.state(PLAYER).provenance().size();
        assertEquals(ActivityXpAwardService.Status.RATE_LIMIT,
                ActivityXpAwardService.award(state, definitions, PLAYER, ACTIVITY, 1, 1_300,
                        uuid(6), "combat:three", LIMITS).status());
        assertEquals(provenance, state.state(PLAYER).provenance().size());
        assertEquals(ActivityXpAwardService.Status.UNKNOWN_ACTIVITY,
                ActivityXpAwardService.award(state, definitions, PLAYER, id("missing"), 1, 2_000,
                        uuid(7), "missing", LIMITS).status());
        CompoundTag future = (CompoundTag) RpgPlayerSavedData.CODEC
                .encodeStart(NbtOps.INSTANCE, state).getOrThrow();
        future.putInt("schema_version", RpgPlayerSavedData.CURRENT_SCHEMA_VERSION + 1);
        RpgPlayerSavedData readOnly = RpgPlayerSavedData.CODEC.parse(NbtOps.INSTANCE, future).getOrThrow();
        assertTrue(!readOnly.isWritable());
        assertEquals(ActivityXpAwardService.Status.READ_ONLY,
                ActivityXpAwardService.award(readOnly, definitions, PLAYER, ACTIVITY, 1, 4_000,
                        uuid(8), "read-only", LIMITS).status());
    }

    @Test
    void publishesAllActivitySeamsAndExplicitFailClosedHolds() {
        assertEquals(7, RpgActivityEvents.mapping().size());
        assertTrue(RpgActivityEvents.mapping().get("mining").startsWith("HELD:"));
        assertTrue(RpgActivityEvents.mapping().get("farming").startsWith("HELD:"));
    }

    private static RpgDefinitionSnapshot definitions() {
        return RpgDefinitionSnapshot.compile(List.of(new RpgDefinitionSnapshot.ActivitySource(
                id("activities/combat"), "test", ACTIVITY,
                new ActivityDefinition("activity.rovenfall.combat", List.of(100L)) )), List.of(), List.of());
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("rovenfall", path); }
    private static UUID uuid(long least) { return new UUID(0, least); }
}
