package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import org.dldyou.rovenfall.PersistenceTestHarness;
import org.dldyou.rovenfall.activities.ActivityTrack;
import org.dldyou.rovenfall.careers.CareerCatalog;
import org.dldyou.rovenfall.careers.CareerDefinition;
import org.dldyou.rovenfall.careers.CareerSkillDefinition;
import org.dldyou.rovenfall.careers.CareerSkillEffect;
import org.dldyou.rovenfall.claims.Claim;
import org.dldyou.rovenfall.claims.ClaimKey;
import org.dldyou.rovenfall.claims.ClaimRole;
import org.dldyou.rovenfall.economy.ShopInstance;
import org.junit.jupiter.api.Test;

final class TargetedReversalServiceTest {
    private static final UUID PLAYER = uuid(1);
    private static final UUID MODERATOR = uuid(2);
    private static final UUID OWNER = uuid(3);
    private static final UUID ECONOMY_MANAGER = uuid(4);
    private static final UUID CONTENT_MANAGER = uuid(5);
    private static final ClaimKey CLAIM = new ClaimKey(Level.OVERWORLD, 10, 10);
    private static final Identifier CAREER = id("scout");
    private static final Identifier SKILL = id("trail_sense");

    @Test
    void claimPermissionAndPurchaseReverseInDependencyOrderWithLeastPrivilege() {
        PlatformSavedData state = new PlatformSavedData();
        bootstrap(state, MODERATOR, AdminRole.MODERATOR, 100);
        bootstrap(state, OWNER, AdminRole.OWNER, 101);
        assertEquals(EconomyService.TransactionStatus.SUCCESS,
                EconomyService.award(state, PLAYER, 1_000, "seed", 2_000, uuid(102), 0, Long.MAX_VALUE)
                        .status());
        UUID purchaseId = uuid(103);
        assertEquals(ClaimPurchaseService.Status.SUCCESS,
                ClaimPurchaseService.purchase(
                        state, PLAYER, Level.OVERWORLD, Level.OVERWORLD, new BlockPos(160, 64, 160),
                        ignored -> true, ignored -> false, 100, 25, 10, 3_000, purchaseId).status());

        UUID permissionId = uuid(104);
        assertEquals(ClaimManagementService.Status.SUCCESS,
                ClaimManagementService.setRole(
                        state, PLAYER, false, CLAIM, uuid(50), ClaimRole.BUILDER,
                        "temporary builder", 4_000, permissionId).status());
        assertEquals(ClaimRole.BUILDER, state.claim(CLAIM).orElseThrow().roleOf(uuid(50)));

        UUID permissionReversal = uuid(105);
        assertEquals(TargetedReversalService.Status.SUCCESS,
                reverse(state, MODERATOR, permissionId, permissionReversal, 5_000).status());
        assertEquals(ClaimRole.VISITOR, state.claim(CLAIM).orElseThrow().roleOf(uuid(50)));
        assertEquals(TargetedReversalState.Domain.CLAIM_PERMISSION,
                state.claimReversalEvidence(permissionId).orElseThrow().domain());
        assertEquals(Optional.of(permissionReversal),
                state.claimReversalEvidence(permissionId).orElseThrow().reversedBy());
        int auditsAfterPermissionReversal = state.auditCount();
        assertEquals(TargetedReversalService.Status.DUPLICATE_TRANSACTION,
                reverse(state, MODERATOR, permissionId, permissionReversal, 5_500).status());
        assertEquals(auditsAfterPermissionReversal, state.auditCount());

        long balanceAfterPurchase = state.economyBalance(PLAYER).orElseThrow();
        UUID deniedReversal = uuid(106);
        assertEquals(TargetedReversalService.Status.UNAUTHORIZED,
                reverse(state, MODERATOR, purchaseId, deniedReversal, 6_000).status());
        assertTrue(state.claim(CLAIM).isPresent());
        assertEquals(balanceAfterPurchase, state.economyBalance(PLAYER).orElseThrow());
        assertFalse(state.hasTransaction(deniedReversal, 6_000));

        UUID purchaseReversal = uuid(107);
        var reversed = reverse(state, OWNER, purchaseId, purchaseReversal, 7_000);
        assertEquals(TargetedReversalService.Status.SUCCESS, reversed.status());
        assertEquals(Optional.of(TargetedReversalState.Domain.CLAIM), reversed.domain());
        assertTrue(state.claim(CLAIM).isEmpty());
        assertEquals(1_000, state.economyBalance(PLAYER).orElseThrow());
        assertEquals(Optional.of(purchaseReversal),
                state.claimReversalEvidence(purchaseId).orElseThrow().reversedBy());
    }

    @Test
    void shopEvidenceSurvivesPersistenceAndRefusesToOverwriteLaterChanges() {
        PlatformSavedData state = new PlatformSavedData();
        bootstrap(state, ECONOMY_MANAGER, AdminRole.ECONOMY_MANAGER, 200);
        Identifier shopId = id("operations_market");
        ShopInstance original = new ShopInstance(
                id("foundation"), Optional.empty(), ShopInstance.AccessPolicy.publicAccess(), Map.of());
        UUID createId = uuid(201);
        commitShop(state, ECONOMY_MANAGER, shopId, Optional.of(original), createId, 2_000);

        PlatformSavedData persisted = PersistenceTestHarness.roundTrip(PlatformSavedData.CODEC, state);
        UUID reversalId = uuid(202);
        assertEquals(TargetedReversalService.Status.SUCCESS,
                reverse(persisted, ECONOMY_MANAGER, createId, reversalId, 3_000).status());
        assertTrue(persisted.shopInstance(shopId).isEmpty());

        PlatformSavedData stale = new PlatformSavedData();
        bootstrap(stale, ECONOMY_MANAGER, AdminRole.ECONOMY_MANAGER, 210);
        UUID staleCreate = uuid(211);
        commitShop(stale, ECONOMY_MANAGER, shopId, Optional.of(original), staleCreate, 2_000);
        ShopInstance changed = original.withAccessPolicy(new ShopInstance.AccessPolicy(24));
        commitShop(stale, ECONOMY_MANAGER, shopId, Optional.of(changed), uuid(212), 3_000);
        int auditBefore = stale.auditCount();

        assertEquals(TargetedReversalService.Status.CURRENT_STATE_MISMATCH,
                reverse(stale, ECONOMY_MANAGER, staleCreate, uuid(213), 4_000).status());
        assertEquals(changed, stale.shopInstance(shopId).orElseThrow());
        assertEquals(auditBefore + 1, stale.auditCount());
        assertFalse(stale.hasTransaction(uuid(213), 4_000));
    }

    @Test
    void skillResetReversalRestoresExactRanksAndCurrencyThenAllowsEarlierInverses() {
        PlatformSavedData state = new PlatformSavedData();
        bootstrap(state, CONTENT_MANAGER, AdminRole.CONTENT_MANAGER, 300);
        CareerCatalog catalog = careerCatalog();
        UUID promotionId = uuid(301);
        assertEquals(CareerPromotionService.Status.SUCCESS,
                CareerPromotionService.promote(
                        state, catalog, PLAYER, CAREER, Map.of(), 2_000, promotionId).status());
        UUID unlockId = uuid(302);
        assertEquals(CareerSkillService.Status.SUCCESS,
                CareerSkillService.unlock(state, catalog, PLAYER, SKILL, 3_000, unlockId).status());
        assertEquals(EconomyService.TransactionStatus.SUCCESS,
                EconomyService.createAccount(state, PLAYER, 10, 100, 4_000, uuid(303)).status());
        UUID resetId = uuid(304);
        assertEquals(CareerSkillService.Status.SUCCESS,
                CareerSkillService.reset(state, catalog, PLAYER, CAREER, 5_000, resetId).status());
        assertEquals(0, state.playerCareerState(PLAYER).progress(CAREER).skillRank(SKILL));
        assertEquals(5, state.economyBalance(PLAYER).orElseThrow());

        PlatformSavedData persisted = PersistenceTestHarness.roundTrip(PlatformSavedData.CODEC, state);
        UUID resetReversal = uuid(305);
        assertEquals(TargetedReversalService.Status.SUCCESS,
                reverse(persisted, CONTENT_MANAGER, resetId, resetReversal, 6_000).status());
        assertEquals(1, persisted.playerCareerState(PLAYER).progress(CAREER).skillRank(SKILL));
        assertEquals(1, persisted.playerCareerState(PLAYER).progress(CAREER).spentSkillPoints());
        assertEquals(10, persisted.economyBalance(PLAYER).orElseThrow());

        assertEquals(TargetedReversalService.Status.SUCCESS,
                reverse(persisted, CONTENT_MANAGER, unlockId, uuid(306), 7_000).status());
        assertEquals(0, persisted.playerCareerState(PLAYER).progress(CAREER).skillRank(SKILL));
        assertEquals(TargetedReversalService.Status.SUCCESS,
                reverse(persisted, CONTENT_MANAGER, promotionId, uuid(307), 8_000).status());
        assertTrue(persisted.activeCareer(PLAYER).isEmpty());
        assertEquals(10, persisted.economyBalance(PLAYER).orElseThrow());
    }

    @Test
    void schemaThirteenMigratesWithoutInventingReversalEvidence() {
        PlatformSavedData state = new PlatformSavedData();
        bootstrap(state, ECONOMY_MANAGER, AdminRole.ECONOMY_MANAGER, 400);
        Identifier shopId = id("legacy_market");
        UUID originalId = uuid(401);
        commitShop(state, ECONOMY_MANAGER, shopId, Optional.of(new ShopInstance(
                id("foundation"), Optional.empty(), ShopInstance.AccessPolicy.publicAccess(), Map.of())),
                originalId, 2_000);

        CompoundTag schemaThirteen = (CompoundTag) PlatformSavedData.CODEC
                .encodeStart(NbtOps.INSTANCE, state).getOrThrow();
        schemaThirteen.putInt("schema_version", 13);
        schemaThirteen.remove("targeted_reversals");
        PlatformSavedData migrated = PlatformSavedData.CODEC.parse(NbtOps.INSTANCE, schemaThirteen).getOrThrow();

        assertEquals(PlatformSavedData.CURRENT_SCHEMA_VERSION, migrated.schemaVersion());
        assertTrue(migrated.isWritable());
        assertTrue(migrated.shopInstance(shopId).isPresent());
        assertEquals(TargetedReversalService.Status.ORIGINAL_NOT_REVERSIBLE,
                reverse(migrated, ECONOMY_MANAGER, originalId, uuid(402), 3_000).status());
        assertTrue(migrated.shopInstance(shopId).isPresent());
    }

    private static TargetedReversalService.Result reverse(
            PlatformSavedData state,
            UUID actor,
            UUID original,
            UUID reversal,
            long timestamp) {
        return TargetedReversalService.reverse(
                state, actor, false, original, "operator correction", timestamp, reversal);
    }

    private static void commitShop(
            PlatformSavedData state,
            UUID actor,
            Identifier shopId,
            Optional<ShopInstance> shop,
            UUID transactionId,
            long timestamp) {
        state.commitShopMutation(
                shopId,
                shop,
                transactionId,
                timestamp,
                new AuditEntry(
                        timestamp, actor, id("shop_instance_fixture"), shopId.toString(),
                        Optional.empty(), Optional.empty(), "before", "after", "fixture", transactionId));
    }

    private static CareerCatalog careerCatalog() {
        CareerSkillDefinition skill = new CareerSkillDefinition(
                "career_skill.rovenfall.trail_sense",
                List.of(),
                1,
                1,
                CareerSkillDefinition.Scope.CAREER,
                List.of(new CareerSkillEffect(
                        CareerSkillEffect.Type.ACTIVITY_EXPERIENCE_BONUS,
                        Optional.of(ActivityTrack.EXPLORATION),
                        500)));
        CareerDefinition career = new CareerDefinition(
                "career.rovenfall.scout",
                1,
                List.of(),
                Map.of(),
                List.of(ActivityTrack.EXPLORATION),
                List.of(0L, 10L),
                0,
                Map.of(SKILL, skill),
                2,
                5);
        return CareerCatalog.create(Map.of(CAREER, career)).getOrThrow();
    }

    private static void bootstrap(PlatformSavedData state, UUID actor, AdminRole role, long transaction) {
        assertEquals(AdministrationService.RoleChangeStatus.SUCCESS,
                AdministrationService.changeRole(
                        state, AdministrationService.SYSTEM_ACTOR, true, actor, role.getSerializedName(),
                        "bootstrap", 1_000, uuid(transaction)).status());
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }
}
