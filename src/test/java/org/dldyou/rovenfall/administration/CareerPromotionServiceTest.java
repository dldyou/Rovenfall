package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.PersistenceTestHarness;
import org.dldyou.rovenfall.activities.ActivityTrack;
import org.dldyou.rovenfall.careers.CareerCatalog;
import org.dldyou.rovenfall.careers.CareerDefinition;
import org.dldyou.rovenfall.careers.CareerPromotionReceipt;
import org.dldyou.rovenfall.careers.CareerState;
import org.dldyou.rovenfall.careers.PlayerCareerState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CareerPromotionServiceTest {
    private static final Identifier ADVENTURER = career("adventurer");
    private static final Identifier WARRIOR = career("warrior");
    private static final Identifier ARTISAN = career("artisan");
    private static final Identifier SCOUT = career("scout");
    private static final Identifier VANGUARD = career("vanguard");
    private static final Identifier SLAYER = career("slayer");
    private static final List<String> BUNDLED_CAREERS = List.of(
            "adventurer", "warrior", "artisan", "scout",
            "vanguard", "slayer", "architect", "cultivator", "pathfinder", "ranger");

    @TempDir
    Path temporaryDirectory;

    @Test
    void freeRootEvaluationIsPureAndPromotionIsIdempotent() throws Exception {
        CareerCatalog catalog = bundledCatalog();
        PlatformSavedData state = new PlatformSavedData();
        UUID player = id(1);
        CompoundTag before = encoded(state);

        var evaluation = CareerPromotionService.evaluate(state, catalog, player, ADVENTURER, Map.of());

        assertEquals(CareerPromotionService.Status.SUCCESS, evaluation.status());
        assertEquals(0, evaluation.promotionCost());
        assertFalse(evaluation.learned());
        assertEquals(before, encoded(state));
        assertEquals(0, state.auditCount());

        UUID transaction = id(101);
        var promoted = CareerPromotionService.promote(
                state, catalog, player, ADVENTURER, Map.of(), 1_000, transaction);

        assertEquals(CareerPromotionService.Status.SUCCESS, promoted.status());
        assertEquals(Optional.of(ADVENTURER), state.activeCareer(player));
        assertEquals(Set.of(ADVENTURER), state.playerCareerState(player).learnedCareers());
        assertTrue(state.economyBalance(player).isEmpty());
        assertEquals(EconomyTransactionReceipt.Kind.CAREER_PROMOTION,
                state.economyReceipt(transaction).orElseThrow().kind());
        assertEquals(0, state.careerPromotionReceipt(transaction).orElseThrow().promotionCost());
        assertEquals(1, state.auditCount());

        CompoundTag after = encoded(state);
        var retry = CareerPromotionService.promote(
                state, catalog, player, ADVENTURER, Map.of(), 2_000, transaction);
        assertEquals(CareerPromotionService.Status.DUPLICATE_TRANSACTION, retry.status());
        assertFalse(retry.auditRecorded());
        assertEquals(after, encoded(state));
    }

    @Test
    void requirementsFundsAndSiblingResetAreExplicitAndAtomic() throws Exception {
        CareerCatalog catalog = bundledCatalog();
        PlatformSavedData state = new PlatformSavedData();
        UUID player = id(2);

        var missingParent = CareerPromotionService.evaluate(state, catalog, player, WARRIOR, levels(2));
        assertEquals(CareerPromotionService.Status.PARENT_NOT_LEARNED, missingParent.status());
        assertEquals(List.of(new CareerPromotionService.ParentRequirement(ADVENTURER, false)),
                missingParent.parentRequirements());

        assertEquals(CareerPromotionService.Status.SUCCESS, CareerPromotionService.promote(
                state, catalog, player, ADVENTURER, Map.of(), 1_000, id(201)).status());
        var unmetActivity = CareerPromotionService.evaluate(state, catalog, player, WARRIOR, levels(0));
        assertEquals(CareerPromotionService.Status.ACTIVITY_REQUIREMENT_NOT_MET, unmetActivity.status());
        assertTrue(unmetActivity.parentRequirements().stream().allMatch(
                CareerPromotionService.ParentRequirement::met));
        assertTrue(unmetActivity.activityRequirements().stream().anyMatch(requirement -> !requirement.met()));
        assertEquals(CareerPromotionService.Status.ACCOUNT_NOT_FOUND,
                CareerPromotionService.evaluate(state, catalog, player, WARRIOR, levels(2)).status());

        assertEquals(EconomyService.TransactionStatus.SUCCESS,
                EconomyService.createAccount(state, player, 250, 1_000, 2_000, id(202)).status());
        var warrior = CareerPromotionService.promote(
                state, catalog, player, WARRIOR, levels(2), 3_000, id(203));
        assertEquals(CareerPromotionService.Status.SUCCESS, warrior.status());
        assertEquals(150, state.economyBalance(player).orElseThrow());
        assertEquals(Set.of(ADVENTURER, WARRIOR), state.playerCareerState(player).learnedCareers());
        assertEquals(1, state.playerCareerState(player).progress(WARRIOR).bonusSkillPoints());

        var artisanEvaluation = CareerPromotionService.evaluate(state, catalog, player, ARTISAN, levels(2));
        assertEquals(CareerPromotionService.Status.SUCCESS, artisanEvaluation.status());
        assertTrue(artisanEvaluation.requiresBranchReset());
        assertEquals(Set.of(WARRIOR), artisanEvaluation.resetCareers());

        UUID artisanTransaction = id(204);
        assertEquals(CareerPromotionService.Status.SUCCESS, CareerPromotionService.promote(
                state, catalog, player, ARTISAN, levels(2), 4_000, artisanTransaction).status());
        assertEquals(50, state.economyBalance(player).orElseThrow());
        assertEquals(Optional.of(ARTISAN), state.activeCareer(player));
        assertEquals(Set.of(ADVENTURER, ARTISAN), state.playerCareerState(player).learnedCareers());
        assertEquals(1, state.playerCareerState(player).progress(ARTISAN).bonusSkillPoints());
        assertEquals(Set.of(WARRIOR),
                state.careerPromotionReceipt(artisanTransaction).orElseThrow().resetCareers());

        PlayerCareerState beforeDenied = state.playerCareerState(player);
        var insufficient = CareerPromotionService.promote(
                state, catalog, player, WARRIOR, levels(2), 5_000, id(205));
        assertEquals(CareerPromotionService.Status.INSUFFICIENT_FUNDS, insufficient.status());
        assertEquals(beforeDenied, state.playerCareerState(player));
        assertEquals(50, state.economyBalance(player).orElseThrow());

        int audits = state.auditCount();
        var conflict = CareerPromotionService.promote(
                state, catalog, player, WARRIOR, levels(2), 6_000, artisanTransaction);
        assertEquals(CareerPromotionService.Status.TRANSACTION_ID_CONFLICT, conflict.status());
        assertEquals(audits + 1, state.auditCount());
        assertEquals(beforeDenied, state.playerCareerState(player));

        PlatformSavedData restored = PersistenceTestHarness.roundTrip(PlatformSavedData.CODEC, state);
        assertEquals(Optional.of(ARTISAN), restored.activeCareer(player));
        assertEquals(Set.of(ADVENTURER, ARTISAN), restored.playerCareerState(player).learnedCareers());
        assertEquals(50, restored.economyBalance(player).orElseThrow());
        assertEquals(Set.of(WARRIOR),
                restored.careerPromotionReceipt(artisanTransaction).orElseThrow().resetCareers());
    }

    @Test
    void bundledScoutPromotesFromExplorationAndHuntingProgress() throws Exception {
        CareerCatalog catalog = bundledCatalog();
        PlatformSavedData state = new PlatformSavedData();
        UUID player = id(6);

        assertEquals(CareerPromotionService.Status.SUCCESS, CareerPromotionService.promote(
                state, catalog, player, ADVENTURER, Map.of(), 1_000, id(601)).status());
        assertEquals(EconomyService.TransactionStatus.SUCCESS,
                EconomyService.createAccount(state, player, 100, 1_000, 2_000, id(602)).status());
        assertEquals(Map.of(ActivityTrack.EXPLORATION, 2, ActivityTrack.HUNTING, 2),
                catalog.definition(SCOUT).orElseThrow().activityLevelRequirements());

        var promoted = CareerPromotionService.promote(
                state, catalog, player, SCOUT, levels(2), 3_000, id(603));

        assertEquals(CareerPromotionService.Status.SUCCESS, promoted.status());
        assertEquals(Optional.of(SCOUT), state.activeCareer(player));
        assertEquals(0, state.economyBalance(player).orElseThrow());
        assertEquals(1, state.playerCareerState(player).progress(SCOUT).bonusSkillPoints());
    }

    @Test
    void bundledTierThreeSpecializationsRequireTheirParentAndSwitchAtomically() throws Exception {
        CareerCatalog catalog = bundledCatalog();
        PlatformSavedData state = new PlatformSavedData();
        UUID player = id(7);

        assertEquals(CareerPromotionService.Status.PARENT_NOT_LEARNED,
                CareerPromotionService.evaluate(state, catalog, player, VANGUARD, levels(5)).status());
        assertEquals(CareerPromotionService.Status.SUCCESS, CareerPromotionService.promote(
                state, catalog, player, ADVENTURER, Map.of(), 1_000, id(701)).status());
        assertEquals(EconomyService.TransactionStatus.SUCCESS,
                EconomyService.createAccount(state, player, 1_000, 1_000, 2_000, id(702)).status());
        assertEquals(CareerPromotionService.Status.SUCCESS, CareerPromotionService.promote(
                state, catalog, player, WARRIOR, levels(5), 3_000, id(703)).status());

        assertEquals(CareerPromotionService.Status.ACTIVITY_REQUIREMENT_NOT_MET,
                CareerPromotionService.evaluate(state, catalog, player, VANGUARD, levels(4)).status());
        assertEquals(CareerPromotionService.Status.SUCCESS, CareerPromotionService.promote(
                state, catalog, player, VANGUARD, levels(5), 4_000, id(704)).status());
        assertEquals(Optional.of(VANGUARD), state.activeCareer(player));
        assertEquals(2, state.playerCareerState(player).progress(VANGUARD).bonusSkillPoints());
        assertEquals(600, state.economyBalance(player).orElseThrow());

        var switchEvaluation = CareerPromotionService.evaluate(state, catalog, player, SLAYER, levels(5));
        assertEquals(CareerPromotionService.Status.SUCCESS, switchEvaluation.status());
        assertEquals(Set.of(VANGUARD), switchEvaluation.resetCareers());
        UUID switchTransaction = id(705);
        assertEquals(CareerPromotionService.Status.SUCCESS, CareerPromotionService.promote(
                state, catalog, player, SLAYER, levels(5), 5_000, switchTransaction).status());
        assertEquals(Optional.of(SLAYER), state.activeCareer(player));
        assertEquals(Set.of(ADVENTURER, WARRIOR, SLAYER),
                state.playerCareerState(player).learnedCareers());
        assertEquals(Set.of(VANGUARD),
                state.careerPromotionReceipt(switchTransaction).orElseThrow().resetCareers());
        assertEquals(300, state.economyBalance(player).orElseThrow());

        PlatformSavedData restored = PersistenceTestHarness.roundTrip(PlatformSavedData.CODEC, state);
        assertEquals(Optional.of(SLAYER), restored.activeCareer(player));
        assertEquals(Set.of(ADVENTURER, WARRIOR, SLAYER),
                restored.playerCareerState(player).learnedCareers());
        assertEquals(300, restored.economyBalance(player).orElseThrow());
    }

    @Test
    void schemaNineDefaultsCareersAndFutureSchemaRetainsThemReadOnly() throws Exception {
        CareerCatalog catalog = bundledCatalog();
        PlatformSavedData state = new PlatformSavedData();
        UUID player = id(3);
        UUID transaction = id(301);
        assertEquals(CareerPromotionService.Status.SUCCESS, CareerPromotionService.promote(
                state, catalog, player, ADVENTURER, Map.of(), 1_000, transaction).status());

        CompoundTag schemaNine = encoded(state);
        schemaNine.putInt("schema_version", 9);
        schemaNine.remove("career_state");
        PlatformSavedData migrated = PlatformSavedData.CODEC.parse(NbtOps.INSTANCE, schemaNine).getOrThrow();
        assertEquals(PlatformSavedData.CURRENT_SCHEMA_VERSION, migrated.schemaVersion());
        assertTrue(migrated.isWritable());
        assertTrue(migrated.activeCareer(player).isEmpty());

        CompoundTag future = encoded(state);
        future.putInt("schema_version", PlatformSavedData.CURRENT_SCHEMA_VERSION + 1);
        PlatformSavedData readOnly = PlatformSavedData.CODEC.parse(NbtOps.INSTANCE, future).getOrThrow();
        assertFalse(readOnly.isWritable());
        assertEquals(Optional.of(ADVENTURER), readOnly.activeCareer(player));
        assertTrue(readOnly.careerPromotionReceipt(transaction).isPresent());
        assertEquals(CareerPromotionService.Status.READ_ONLY_SCHEMA,
                CareerPromotionService.evaluate(readOnly, catalog, player, WARRIOR, levels(2)).status());

        CompoundTag before = encoded(readOnly);
        var denied = CareerPromotionService.promote(
                readOnly, catalog, player, WARRIOR, levels(2), 2_000, id(302));
        assertEquals(CareerPromotionService.Status.READ_ONLY_SCHEMA, denied.status());
        assertFalse(denied.auditRecorded());
        assertEquals(before, encoded(readOnly));
        var malformed = CareerPromotionService.promote(
                readOnly, catalog, player, WARRIOR, levels(2), 3_000, new UUID(0, 0));
        assertEquals(CareerPromotionService.Status.INVALID_TRANSACTION, malformed.status());
        assertFalse(malformed.auditRecorded());
        assertEquals(before, encoded(readOnly));
    }

    @Test
    void careerCodecsRejectBrokenActiveAndReceiptEvidence() {
        PlayerCareerState invalidActive = new PlayerCareerState(Optional.of(ADVENTURER), Map.of());
        assertTrue(PlayerCareerState.CODEC.encodeStart(JsonOps.INSTANCE, invalidActive).error().isPresent());

        CareerPromotionReceipt valid = new CareerPromotionReceipt(
                1_000, id(401), id(4), WARRIOR, 100, 1, Optional.of(ADVENTURER), Set.of(ARTISAN));
        CareerState mismatched = new CareerState(Map.of(), Map.of(id(402), valid), Map.of());
        assertTrue(CareerState.CODEC.encodeStart(JsonOps.INSTANCE, mismatched).error().isPresent());

        CareerPromotionReceipt zeroTransaction = new CareerPromotionReceipt(
                1_000, new UUID(0, 0), id(4), WARRIOR, 100, 1, Optional.empty(), Set.of());
        assertTrue(CareerPromotionReceipt.CODEC.encodeStart(JsonOps.INSTANCE, zeroTransaction)
                .error().isPresent());

        JsonObject duplicateReset = CareerPromotionReceipt.CODEC.encodeStart(JsonOps.INSTANCE, valid)
                .getOrThrow().getAsJsonObject();
        duplicateReset.getAsJsonArray("reset_careers").add(
                duplicateReset.getAsJsonArray("reset_careers").get(0).deepCopy());
        assertTrue(CareerPromotionReceipt.CODEC.parse(JsonOps.INSTANCE, duplicateReset).error().isPresent());
    }

    @Test
    void platformSnapshotRestoreRollsCareerStateBackWithItsPromotionReceipts() throws Exception {
        CareerCatalog catalog = bundledCatalog();
        PlatformSavedData state = new PlatformSavedData();
        UUID owner = id(5);
        UUID player = id(6);
        AdministrationService.changeRole(
                state,
                AdministrationService.SYSTEM_ACTOR,
                true,
                owner,
                "owner",
                "snapshot test",
                1_000,
                id(501));
        UUID rootTransaction = id(502);
        assertEquals(CareerPromotionService.Status.SUCCESS, CareerPromotionService.promote(
                state, catalog, player, ADVENTURER, Map.of(), 2_000, rootTransaction).status());

        PlatformSnapshotStore store = new PlatformSnapshotStore(temporaryDirectory.resolve("snapshots"));
        UUID snapshotId = id(503);
        store.write(snapshotId, state);

        assertEquals(EconomyService.TransactionStatus.SUCCESS,
                EconomyService.createAccount(state, player, 200, 1_000, 3_000, id(504)).status());
        UUID warriorTransaction = id(505);
        assertEquals(CareerPromotionService.Status.SUCCESS, CareerPromotionService.promote(
                state, catalog, player, WARRIOR, levels(2), 4_000, warriorTransaction).status());
        assertEquals(Optional.of(WARRIOR), state.activeCareer(player));

        var restored = AdministrationService.restoreSnapshot(
                state,
                store,
                owner,
                false,
                snapshotId,
                "restore career state",
                5_000,
                id(506),
                id(507));

        assertEquals(AdministrationService.SnapshotRestoreStatus.SUCCESS, restored.status());
        assertEquals(Optional.of(ADVENTURER), state.activeCareer(player));
        assertEquals(Set.of(ADVENTURER), state.playerCareerState(player).learnedCareers());
        assertTrue(state.careerPromotionReceipt(rootTransaction).isPresent());
        assertTrue(state.careerPromotionReceipt(warriorTransaction).isEmpty());
    }

    private static CareerCatalog bundledCatalog() throws Exception {
        Map<Identifier, CareerDefinition> definitions = new LinkedHashMap<>();
        for (String name : BUNDLED_CAREERS) {
            String path = "/data/rovenfall/rovenfall/professions/" + name + ".json";
            var stream = CareerPromotionServiceTest.class.getResourceAsStream(path);
            assertNotNull(stream, path);
            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                definitions.put(career(name), CareerDefinition.CODEC.parse(
                        JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow());
            }
        }
        return CareerCatalog.create(definitions).getOrThrow();
    }

    private static Map<ActivityTrack, Integer> levels(int level) {
        Map<ActivityTrack, Integer> levels = new EnumMap<>(ActivityTrack.class);
        for (ActivityTrack track : ActivityTrack.values()) {
            levels.put(track, level);
        }
        return Map.copyOf(levels);
    }

    private static CompoundTag encoded(PlatformSavedData state) {
        return (CompoundTag) PlatformSavedData.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow();
    }

    private static Identifier career(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }

    private static UUID id(long value) {
        return new UUID(0L, value);
    }
}
