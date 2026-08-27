package org.dldyou.rovenfall.administration;

import static org.dldyou.rovenfall.PersistenceTestHarness.roundTrip;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.dldyou.rovenfall.claims.ClaimKey;
import org.dldyou.rovenfall.claims.ClaimRole;
import org.dldyou.rovenfall.economy.ShopInstance;
import org.dldyou.rovenfall.rpg.ActivityDefinition;
import org.dldyou.rovenfall.rpg.ActivityXpAwardService;
import org.dldyou.rovenfall.rpg.RpgAdministrativeMutationService;
import org.dldyou.rovenfall.rpg.RpgDefinitionSnapshot;
import org.dldyou.rovenfall.rpg.RpgPlayerSavedData;
import org.dldyou.rovenfall.world.WorldTopology;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RecoveryRehearsalTest {
    private static final UUID OWNER = uuid(1);
    private static final UUID PLAYER = uuid(2);
    private static final UUID TRUSTED = uuid(3);
    private static final Identifier SHOP = id("recovery_shop");
    private static final Identifier OFFER = id("bread");
    private static final Identifier COMBAT = id("combat");
    private static final BlockPos CLAIM_POSITION = new BlockPos(16, 70, 16);
    private static final ClaimKey CLAIM = ClaimKey.at(Level.OVERWORLD, CLAIM_POSITION);
    private static final Holder<Item> BREAD = Holder.direct(
            Items.BREAD, DataComponentMap.builder().set(DataComponents.MAX_STACK_SIZE, 64).build());
    @TempDir
    Path temporaryDirectory;

    @Test
    void platformSnapshotSourceStaysImmutableWhileAuthoritativeStateAndEvidenceAreRestored() throws Exception {
        PlatformSavedData source = platformFixture();
        PlatformSnapshotStore store = new PlatformSnapshotStore(temporaryDirectory.resolve("platform"));
        UUID sourceSnapshotId = uuid(100);
        store.write(sourceSnapshotId, source);
        Path sourcePath = temporaryDirectory.resolve("platform").resolve(sourceSnapshotId + ".nbt");
        String sourceHash = sha256(sourcePath);
        Projection expected = projection(source);

        PlatformSavedData drill = store.read(sourceSnapshotId);
        NonNullList<ItemStack> inventory = NonNullList.withSize(36, ItemStack.EMPTY);
        UUID grantId = uuid(110);
        assertEquals(EconomyService.TransactionStatus.SUCCESS, EconomyService.adminGrant(
                drill, OWNER, false, PLAYER, 25, "rehearsal grant", 2_000, grantId, 0,
                Long.MAX_VALUE).status());
        assertEquals(EconomyReversalService.Status.SUCCESS, EconomyReversalService.reverse(
                drill, PLAYER, inventory, OWNER, false, grantId,
                EconomyTransactionReceipt.CompensationDecision.NONE, "rehearsal reversal",
                2_100, uuid(111), Long.MAX_VALUE).status());

        PlatformSavedData shopDrill = shopFixture();
        UUID purchaseId = uuid(120);
        assertEquals(ShopTradeService.Status.SUCCESS, ShopTradeService.trade(
                shopDrill, PLAYER, Level.OVERWORLD, Vec3.ZERO, inventory,
                new ShopTradeService.TradeRequest(
                        SHOP, OFFER, ShopTradeService.Direction.BUY, 1, exactBread(4), 12, purchaseId),
                0, 2_200, Long.MAX_VALUE).status());
        UUID shopReversalId = uuid(121);
        assertEquals(EconomyReversalService.Status.SUCCESS, EconomyReversalService.reverse(
                shopDrill, PLAYER, inventory, OWNER, false, purchaseId,
                EconomyTransactionReceipt.CompensationDecision.NONE, "strict shop reversal",
                2_300, shopReversalId, Long.MAX_VALUE).status());
        assertEquals(0, ShopTradeService.countExact(inventory, exactBread(4)));
        assertEquals(100, shopDrill.economyBalance(PLAYER).orElseThrow());
        assertEquals(10, shopDrill.shopInstance(SHOP).orElseThrow()
                .offers().get(OFFER).stock().current());
        assertEquals(EconomyReversalService.Status.DUPLICATE_TRANSACTION, EconomyReversalService.reverse(
                shopDrill, PLAYER, inventory, OWNER, false, purchaseId,
                EconomyTransactionReceipt.CompensationDecision.NONE, "strict shop reversal",
                2_400, shopReversalId, Long.MAX_VALUE).status());
        assertEquals(Optional.of(shopReversalId),
                shopDrill.economyReceipt(purchaseId).orElseThrow().reversedBy());

        assertEquals(ClaimManagementService.Status.SUCCESS, ClaimManagementService.setRole(
                drill, PLAYER, false, CLAIM, TRUSTED, ClaimRole.USER, "rehearsal correction",
                2_500, uuid(130)).status());
        assertEquals(ClaimManagementService.Status.SUCCESS, ClaimManagementService.removeRole(
                drill, PLAYER, false, CLAIM, TRUSTED, "rehearsal correction rollback",
                2_600, uuid(131)).status());
        assertEquals(expected, projection(drill));

        UUID unrestoredGrantId = uuid(140);
        assertEquals(EconomyService.TransactionStatus.SUCCESS, EconomyService.adminGrant(
                drill, OWNER, false, PLAYER, 50, "restore target mutation", 2_700,
                unrestoredGrantId, 0, Long.MAX_VALUE).status());
        UUID safetySnapshotId = uuid(141);
        UUID restoreId = uuid(142);
        assertEquals(AdministrationService.SnapshotRestoreStatus.SUCCESS,
                AdministrationService.restoreSnapshot(
                        drill, store, OWNER, false, sourceSnapshotId, "recovery rehearsal restore",
                        2_800, restoreId, safetySnapshotId).status());

        assertEquals(expected, projection(drill));
        assertEquals(950, store.read(safetySnapshotId).economyBalance(PLAYER).orElseThrow());
        assertEquals(Optional.of(restoreId),
                drill.economyReceipt(unrestoredGrantId).orElseThrow().invalidatedByRestore());
        assertEquals(sourceHash, sha256(sourcePath));
        assertTrue(drill.auditPage(0, 50).entries().stream().anyMatch(entry ->
                entry.actionType().equals(id("economy_transaction_reversal"))));
        assertTrue(drill.auditPage(0, 50).entries().stream().anyMatch(entry ->
                entry.actionType().equals(id("claim_role_remove"))));
        assertTrue(drill.auditPage(0, 50).entries().stream().anyMatch(entry ->
                entry.actionType().equals(id("platform_snapshot_restore"))));
    }

    @Test
    void pendingRpgOperationRecoversOnceAfterPersistenceAndRetryIsIdempotent() {
        PlatformSavedData platform = platformWithOwner();
        RpgPlayerSavedData rpg = new RpgPlayerSavedData();
        RpgDefinitionSnapshot definitions = definitions();
        assertEquals(ActivityXpAwardService.Status.SUCCESS, ActivityXpAwardService.award(
                rpg, definitions, PLAYER, COMBAT, 10, 1_000, uuid(200), "rehearsal:seed").status());
        UUID transactionId = uuid(201);
        var pending = new RpgAdminOperation(
                OWNER, PLAYER, RpgAdminOperation.Action.XP_ADJUST, COMBAT,
                5, 10, Optional.empty(), "pending recovery rehearsal", 1_100,
                RpgAdminOperation.Phase.PENDING);
        assertEquals(PlatformSavedData.RpgAdminOperationBeginStatus.SUCCESS,
                platform.beginRpgAdminOperation(transactionId, pending).status());
        assertEquals(RpgAdministrativeMutationService.Status.SUCCESS,
                RpgAdministrativeMutationService.adjustActivityXp(
                        rpg, definitions, PLAYER, COMBAT, 5, 10, 1_100, transactionId,
                        "admin:" + OWNER).status());

        PlatformSavedData recoveredPlatform = roundTrip(PlatformSavedData.CODEC, platform);
        RpgPlayerSavedData recoveredRpg = roundTrip(RpgPlayerSavedData.CODEC, rpg);
        assertEquals(RpgAdministrationService.Status.SUCCESS, RpgAdministrationService.adjustActivityXp(
                recoveredPlatform, recoveredRpg, definitions, OWNER, false, PLAYER, COMBAT,
                5, "pending recovery rehearsal", 1_100, transactionId).status());
        assertEquals(15, recoveredRpg.state(PLAYER).activityXp().get(COMBAT));
        assertEquals(RpgAdminOperation.Phase.COMPLETED,
                recoveredPlatform.rpgAdminOperation(transactionId).orElseThrow().phase());
        int auditCount = recoveredPlatform.auditCount();

        assertEquals(RpgAdministrationService.Status.DUPLICATE, RpgAdministrationService.adjustActivityXp(
                recoveredPlatform, recoveredRpg, definitions, OWNER, false, PLAYER, COMBAT,
                5, "pending recovery rehearsal", 1_100, transactionId).status());
        assertEquals(15, recoveredRpg.state(PLAYER).activityXp().get(COMBAT));
        assertEquals(auditCount, recoveredPlatform.auditCount());
    }

    @Test
    void wildernessRestorePreservesSnapshotShaAndRestoresItsContentOnAnIsolatedWorld() throws Exception {
        Path wilderness = temporaryDirectory.resolve("dimensions/rovenfall/wilderness");
        Path region = wilderness.resolve("region/r.0.0.mca");
        Files.createDirectories(region.getParent());
        Files.writeString(region, "authoritative-wilderness");
        WildernessResetStore store = new WildernessResetStore(temporaryDirectory.resolve("wilderness-operations"));
        UUID snapshotId = uuid(300);
        WildernessResetStore.SnapshotEvidence sourceEvidence = store.createSnapshot(snapshotId, wilderness);

        Files.writeString(region, "mutated-wilderness");
        UUID recoverySnapshotId = uuid(301);
        WildernessResetStore.SnapshotEvidence recoveryEvidence =
                store.createSnapshot(recoverySnapshotId, wilderness);
        var operation = new WildernessResetState.Operation(
                WildernessResetState.Kind.RESTORE, uuid(302), snapshotId, recoverySnapshotId, OWNER,
                2_000, "isolated recovery rehearsal",
                sourceEvidence.fileCount(), sourceEvidence.byteCount(), sourceEvidence.sha256(),
                recoveryEvidence.fileCount(), recoveryEvidence.byteCount(), recoveryEvidence.sha256());

        store.prepareRestore(operation);
        store.writePending(operation);
        WildernessResetStore.LifecycleResult result = store.applyPending(wilderness).orElseThrow();

        assertTrue(result.succeeded());
        assertEquals("authoritative-wilderness", Files.readString(region));
        assertEquals(sourceEvidence, store.snapshotEvidence(snapshotId));
        assertEquals(sourceEvidence.sha256(), store.snapshotEvidence(snapshotId).sha256());
    }

    private static PlatformSavedData platformFixture() {
        PlatformSavedData state = platformWithOwner();
        assertEquals(EconomyService.TransactionStatus.SUCCESS,
                EconomyService.award(state, PLAYER, 1_000, "rehearsal funds", 200, uuid(11),
                        0, Long.MAX_VALUE).status());
        assertEquals(ClaimPurchaseService.Status.SUCCESS, ClaimPurchaseService.purchase(
                state, PLAYER, WorldTopology.HUB, Level.OVERWORLD, CLAIM_POSITION,
                ignored -> true, ignored -> false, 100, 0, 4, 300, uuid(12)).status());
        return state;
    }

    private static PlatformSavedData shopFixture() {
        PlatformSavedData state = platformWithOwner();
        ShopInstance shop = new ShopInstance(
                id("recovery_template"), Optional.empty(), ShopInstance.AccessPolicy.publicAccess(), Map.of(
                OFFER, new ShopInstance.Offer(
                        exactBread(4), Optional.of(12L), Optional.of(6L), ShopInstance.Stock.finite(10, 10))));
        UUID shopTransaction = uuid(10);
        state.commitShopMutation(SHOP, Optional.of(shop), shopTransaction, 100,
                new AuditEntry(100, AdministrationService.SYSTEM_ACTOR, id("seed_shop"), SHOP.toString(),
                        Optional.empty(), Optional.empty(), "none", "shop", "rehearsal fixture", shopTransaction));
        assertEquals(EconomyService.TransactionStatus.SUCCESS,
                EconomyService.award(state, PLAYER, 100, "rehearsal shop funds", 200, uuid(11),
                        0, Long.MAX_VALUE).status());
        return state;
    }

    private static PlatformSavedData platformWithOwner() {
        PlatformSavedData state = new PlatformSavedData();
        assertEquals(AdministrationService.RoleChangeStatus.SUCCESS, AdministrationService.changeRole(
                state, AdministrationService.SYSTEM_ACTOR, true, OWNER, "owner",
                "rehearsal fixture", 1, uuid(9)).status());
        return state;
    }

    private static Projection projection(PlatformSavedData state) {
        var claim = state.claim(CLAIM).orElseThrow();
        return new Projection(
                state.economyBalance(PLAYER).orElseThrow(),
                claim.ownerId(), claim.trustedRoles());
    }

    private static RpgDefinitionSnapshot definitions() {
        return RpgDefinitionSnapshot.compile(
                List.of(new RpgDefinitionSnapshot.ActivitySource(
                        id("activities/combat"), "rehearsal", COMBAT,
                        new ActivityDefinition("activity.rovenfall.combat", List.of(10L, 20L)))),
                List.of(), List.of());
    }

    private static ItemStack exactBread(int count) {
        ItemStack stack = new ItemStack(BREAD, count);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Recovery bread"));
        return stack;
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }

    private static UUID uuid(long value) {
        return new UUID(0, value);
    }

    private record Projection(long balance, UUID claimOwner, Map<UUID, ClaimRole> trustedRoles) {
    }

}
