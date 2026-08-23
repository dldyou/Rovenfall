package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Lifecycle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.dldyou.rovenfall.economy.ShopInstance;
import org.dldyou.rovenfall.economy.ShopTemplate;
import org.dldyou.rovenfall.economy.ShopTemplateSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ShopInstanceServiceTest {
    private static final DataComponentMap TEST_ITEM_COMPONENTS = DataComponentMap.builder()
            .set(DataComponents.MAX_STACK_SIZE, 64)
            .build();
    private static final Holder<Item> TEST_BREAD = Holder.direct(Items.BREAD, TEST_ITEM_COMPONENTS);
    private static final Holder<Item> TEST_DIAMOND = Holder.direct(Items.DIAMOND, TEST_ITEM_COMPONENTS);

    @TempDir
    Path temporaryDirectory;

    @Test
    void economyManagerCreatesIndependentExactCatalogAndEconomyCollisionIsRejected() {
        PlatformSavedData state = new PlatformSavedData();
        UUID actor = id(1);
        bootstrap(state, actor, AdminRole.ECONOMY_MANAGER);
        UUID transactionId = id(101);
        Identifier shopId = identifier("market");

        var result = create(state, templates(), actor, shopId, transactionId, 2_000);

        assertEquals(ShopInstanceService.Status.SUCCESS, result.status());
        ShopInstance shop = state.shopInstance(shopId).orElseThrow();
        assertEquals(identifier("foundation"), shop.templateId());
        assertEquals(1, shop.offers().size());
        ShopInstance.Offer offer = shop.offers().get(identifier("bread"));
        assertEquals(4, offer.item().getCount());
        assertEquals(Component.literal("Foundation bread"), offer.item().get(DataComponents.CUSTOM_NAME));
        assertEquals(10, offer.stock().current());
        assertEquals(20, offer.stock().maximum());
        assertEquals(3_200, offer.stock().nextRestockGameTime());
        ItemStack leaked = offer.item();
        leaked.setCount(1);
        assertEquals(4, state.shopInstance(shopId).orElseThrow().offers().get(identifier("bread")).item().getCount());

        assertTrue(state.hasTransaction(transactionId, 3_000));
        assertEquals(EconomyService.TransactionStatus.TRANSACTION_ID_CONFLICT,
                EconomyService.award(state, id(2), 1, "shared retry", 3_000, transactionId, 0, 100).status());
        assertTrue(state.economyBalance(id(2)).isEmpty());
        assertEquals("transaction_id_conflict", state.auditPage(0, 1).entries().getFirst().reason());

        AuditEntry audit = state.auditPage(0, 2).entries().get(1);
        assertEquals("rovenfall:shop_instance_create", audit.actionType().toString());
        assertEquals(shopId.toString(), audit.target());
        assertEquals("none", audit.beforeValue());
        assertTrue(audit.afterValue().contains("template=rovenfall:foundation"));
        assertEquals(transactionId, audit.transactionId());
    }

    @Test
    void onlyEconomyManagerOwnerAndNativeOwnerOverrideCanCreateOrDelete() {
        for (AdminRole role : AdminRole.values()) {
            PlatformSavedData state = new PlatformSavedData();
            UUID actor = id(10 + role.ordinal());
            bootstrap(state, actor, role);
            Identifier shopId = identifier("role_" + role.getSerializedName());

            var result = create(state, templates(), actor, shopId, id(200 + role.ordinal()), 2_000);
            boolean allowed = role == AdminRole.ECONOMY_MANAGER || role == AdminRole.OWNER;
            assertEquals(allowed ? ShopInstanceService.Status.SUCCESS : ShopInstanceService.Status.UNAUTHORIZED,
                    result.status(), role.getSerializedName());
            assertEquals(allowed, state.shopInstance(shopId).isPresent());
        }

        PlatformSavedData consoleState = new PlatformSavedData();
        var console = ShopInstanceService.create(
                consoleState, templates(), AdministrationService.SYSTEM_ACTOR, true,
                identifier("console"), identifier("foundation"), Optional.empty(), key -> true,
                ShopInstance.AccessPolicy.publicAccess(), 1_000, "console create", 2_000, id(250));
        assertEquals(ShopInstanceService.Status.SUCCESS, console.status());
        assertEquals(ShopInstanceService.Status.SUCCESS,
                ShopInstanceService.delete(consoleState, AdministrationService.SYSTEM_ACTOR, true,
                        identifier("console"), "console delete", 3_500, id(251)).status());
    }

    @Test
    void editsValidateAfterAuthorizationAndRemainAtomicWhenTemplateProvenanceDisappears() {
        PlatformSavedData state = new PlatformSavedData();
        UUID owner = id(30);
        UUID viewer = id(31);
        bootstrap(state, owner, AdminRole.OWNER);
        bootstrap(state, viewer, AdminRole.VIEWER);
        Identifier shopId = identifier("editable");
        create(state, templates(), owner, shopId, id(301), 3_000);
        int auditCount = state.auditCount();

        ShopInstance.Offer invalidOffer = new ShopInstance.Offer(
                new ItemStack(TEST_BREAD), Optional.empty(), Optional.empty(), ShopInstance.Stock.unlimitedStock());
        assertEquals(ShopInstanceService.Status.UNAUTHORIZED,
                ShopInstanceService.putOffer(state, viewer, false, shopId, identifier("secret"), invalidOffer,
                        "viewer attempt", 4_500, id(302)).status());
        assertEquals(auditCount + 1, state.auditCount());
        assertFalse(state.shopInstance(shopId).orElseThrow().offers().containsKey(identifier("secret")));

        assertEquals(ShopInstanceService.Status.INVALID_REQUEST,
                ShopInstanceService.setBinding(state, owner, false, shopId,
                        Optional.of(new ShopInstance.Binding(Level.NETHER, new BlockPos(1, 2, 3))),
                        key -> false, "unknown dimension", 6_000, id(303)).status());
        assertTrue(state.shopInstance(shopId).orElseThrow().binding().isEmpty());

        assertEquals(ShopInstanceService.Status.SUCCESS,
                ShopInstanceService.setBinding(state, owner, false, shopId,
                        Optional.of(new ShopInstance.Binding(Level.NETHER, new BlockPos(1, 2, 3))),
                        key -> true, "bind", 7_500, id(304)).status());
        AuditEntry bindingAudit = state.auditPage(0, 1).entries().getFirst();
        assertEquals("none", bindingAudit.beforeValue());
        assertEquals("minecraft:the_nether@1, 2, 3", bindingAudit.afterValue());
        assertEquals(ShopInstanceService.Status.SUCCESS,
                ShopInstanceService.setAccessPolicy(state, owner, false, shopId,
                        new ShopInstance.AccessPolicy(24), "range", 9_000, id(305)).status());
        AuditEntry accessAudit = state.auditPage(0, 1).entries().getFirst();
        assertEquals("max_distance=8", accessAudit.beforeValue());
        assertEquals("max_distance=24", accessAudit.afterValue());

        ItemStack exact = new ItemStack(TEST_DIAMOND, 2);
        exact.set(DataComponents.CUSTOM_NAME, Component.literal("Exact diamond"));
        ShopInstance.Stock restocking = new ShopInstance.Stock(
                false, 3, 9, Optional.of(2L), Optional.of(400L), 10_000);
        assertEquals(ShopInstanceService.Status.SUCCESS,
                ShopInstanceService.putOffer(state, owner, false, shopId, identifier("diamond"),
                        new ShopInstance.Offer(exact, Optional.of(50L), Optional.empty(), restocking),
                        "add exact", 10_500, id(306)).status());
        exact.setCount(1);
        ShopInstance.Offer stored = state.shopInstance(shopId).orElseThrow().offers().get(identifier("diamond"));
        assertEquals(2, stored.item().getCount());
        assertEquals(Component.literal("Exact diamond"), stored.item().get(DataComponents.CUSTOM_NAME));

        AuditEntry offerAudit = state.auditPage(0, 1).entries().getFirst();
        assertEquals("rovenfall:editable/rovenfall:diamond", offerAudit.target());
        assertEquals("none", offerAudit.beforeValue());
        assertTrue(offerAudit.afterValue().contains("item=minecraft:diamondx2"));
        assertTrue(offerAudit.afterValue().contains("restock_interval=400"));

        assertEquals(ShopInstanceService.Status.SUCCESS,
                ShopInstanceService.setRestockPolicy(
                        state, owner, false, shopId, identifier("diamond"), Optional.empty(), Optional.empty(),
                        11_000, "clear restock", 12_000, id(307)).status());
        assertTrue(state.shopInstance(shopId).orElseThrow().offers().get(identifier("diamond"))
                .stock().restockAmount().isEmpty());
        assertEquals(identifier("shop_instance_offer_restock_clear"),
                state.auditPage(0, 1).entries().getFirst().actionType());
        assertEquals(ShopInstanceService.Status.SUCCESS,
                ShopInstanceService.setRestockPolicy(
                        state, owner, false, shopId, identifier("diamond"), Optional.of(1L), Optional.of(200L),
                        12_000, "set restock", 13_500, id(308)).status());
        assertEquals(12_200, state.shopInstance(shopId).orElseThrow().offers().get(identifier("diamond"))
                .stock().nextRestockGameTime());
        assertEquals(identifier("shop_instance_offer_restock_set"),
                state.auditPage(0, 1).entries().getFirst().actionType());

        assertEquals(ShopInstanceService.Status.SUCCESS,
                ShopInstanceService.removeOffer(state, owner, false, shopId, identifier("bread"),
                        "remove template offer", 15_000, id(309)).status());
        assertFalse(state.shopInstance(shopId).orElseThrow().offers().containsKey(identifier("bread")));
    }

    @Test
    void authorizedInvalidInputsAndRetriesNeverPartiallyMutateOrCommitTheirIds() {
        PlatformSavedData state = new PlatformSavedData();
        UUID owner = id(60);
        bootstrap(state, owner, AdminRole.OWNER);
        Identifier shopId = identifier("validation");
        create(state, templates(), owner, shopId, id(601), 2_000);
        ShopInstance original = state.shopInstance(shopId).orElseThrow();

        ShopInstance.Offer emptyItem = new ShopInstance.Offer(
                ItemStack.EMPTY, Optional.of(1L), Optional.empty(), ShopInstance.Stock.unlimitedStock());
        ShopInstance.Offer zeroPrice = new ShopInstance.Offer(
                new ItemStack(TEST_BREAD), Optional.of(0L), Optional.empty(), ShopInstance.Stock.unlimitedStock());
        ShopInstance.Offer highPrice = new ShopInstance.Offer(
                new ItemStack(TEST_BREAD), Optional.of(ShopTemplateSnapshot.MAX_PRICE + 1), Optional.empty(),
                ShopInstance.Stock.unlimitedStock());
        ShopInstance.Offer badStock = new ShopInstance.Offer(
                new ItemStack(TEST_BREAD), Optional.of(1L), Optional.empty(),
                new ShopInstance.Stock(false, 11, 10, Optional.empty(), Optional.empty(), 0));
        List<ShopInstance.Offer> invalidOffers = List.of(emptyItem, zeroPrice, highPrice, badStock);
        for (int index = 0; index < invalidOffers.size(); index++) {
            UUID transaction = id(610 + index);
            assertEquals(ShopInstanceService.Status.INVALID_REQUEST,
                    ShopInstanceService.putOffer(state, owner, false, shopId, identifier("invalid_" + index),
                            invalidOffers.get(index), "invalid", 4_000 + index * 1_500L, transaction).status());
            assertFalse(state.hasTransaction(transaction, 10_000));
            assertEquals(original.offers(), state.shopInstance(shopId).orElseThrow().offers());
        }

        UUID accessTransaction = id(620);
        assertEquals(ShopInstanceService.Status.INVALID_REQUEST,
                ShopInstanceService.setAccessPolicy(state, owner, false, shopId,
                        new ShopInstance.AccessPolicy(0), "invalid access", 11_000, accessTransaction).status());
        assertFalse(state.hasTransaction(accessTransaction, 11_000));
        assertEquals(ShopInstance.DEFAULT_ACCESS_DISTANCE,
                state.shopInstance(shopId).orElseThrow().accessPolicy().maxDistance());

        UUID bindingTransaction = id(621);
        assertEquals(ShopInstanceService.Status.INVALID_REQUEST,
                ShopInstanceService.setBinding(state, owner, false, shopId,
                        Optional.of(new ShopInstance.Binding(null, BlockPos.ZERO)), key -> true,
                        "invalid binding", 12_500, bindingTransaction).status());
        assertFalse(state.hasTransaction(bindingTransaction, 12_500));
        assertTrue(state.shopInstance(shopId).orElseThrow().binding().isEmpty());

        UUID restockTransaction = id(622);
        assertEquals(ShopInstanceService.Status.INVALID_REQUEST,
                ShopInstanceService.setRestockPolicy(state, owner, false, shopId, identifier("bread"),
                        Optional.of(1L), Optional.empty(), 13_000, "mismatch", 14_000, restockTransaction).status());
        assertFalse(state.hasTransaction(restockTransaction, 14_000));

        UUID successTransaction = id(623);
        int beforeSuccessAudit = state.auditCount();
        assertEquals(ShopInstanceService.Status.SUCCESS,
                ShopInstanceService.setAccessPolicy(state, owner, false, shopId,
                        new ShopInstance.AccessPolicy(16), "first", 15_500, successTransaction).status());
        assertEquals(ShopInstanceService.Status.DUPLICATE_TRANSACTION,
                ShopInstanceService.setAccessPolicy(state, owner, false, shopId,
                        new ShopInstance.AccessPolicy(32), "retry", 17_000, successTransaction).status());
        assertEquals(16, state.shopInstance(shopId).orElseThrow().accessPolicy().maxDistance());
        assertEquals(beforeSuccessAudit + 1, state.auditCount());
    }

    @Test
    void nonEconomyRolesCannotEditOrDeleteExistingShops() {
        for (AdminRole deniedRole : List.of(AdminRole.VIEWER, AdminRole.MODERATOR, AdminRole.CONTENT_MANAGER)) {
            PlatformSavedData state = new PlatformSavedData();
            UUID owner = id(700 + deniedRole.ordinal());
            UUID actor = id(710 + deniedRole.ordinal());
            bootstrap(state, owner, AdminRole.OWNER);
            bootstrap(state, actor, deniedRole);
            Identifier shopId = identifier("denied_" + deniedRole.getSerializedName());
            create(state, templates(), owner, shopId, id(720 + deniedRole.ordinal()), 3_000);

            assertEquals(ShopInstanceService.Status.UNAUTHORIZED,
                    ShopInstanceService.setAccessPolicy(state, actor, false, shopId,
                            new ShopInstance.AccessPolicy(20), "denied edit", 5_000,
                            id(730 + deniedRole.ordinal())).status());
            assertEquals(ShopInstanceService.Status.UNAUTHORIZED,
                    ShopInstanceService.delete(state, actor, false, shopId, "denied delete", 7_000,
                            id(740 + deniedRole.ordinal())).status());
            assertTrue(state.shopInstance(shopId).isPresent());
            assertEquals(ShopInstance.DEFAULT_ACCESS_DISTANCE,
                    state.shopInstance(shopId).orElseThrow().accessPolicy().maxDistance());
        }
    }

    @Test
    void dependencyLeaseBlocksMutationAndRestoreUntilReleased() throws Exception {
        PlatformSavedData state = new PlatformSavedData();
        UUID owner = id(40);
        bootstrap(state, owner, AdminRole.OWNER);
        Identifier shopId = identifier("locked");
        create(state, templates(), owner, shopId, id(401), 2_000);
        ShopInstanceService.removeOffer(
                state, owner, false, shopId, identifier("bread"), "empty snapshot shop", 3_000, id(407));
        PlatformSnapshotStore store = new PlatformSnapshotStore(temporaryDirectory.resolve("snapshots"));
        UUID snapshotId = id(402);
        store.write(snapshotId, state);
        UUID safetyId = id(403);

        try (var lease = ShopInstanceService.tryAcquireDependencyLock(state, shopId).orElseThrow()) {
            assertTrue(ShopInstanceService.tryAcquireDependencyLock(state, shopId).isEmpty());
            assertEquals(ShopInstanceService.Status.DEPENDENCY_LOCKED,
                    ShopInstanceService.delete(state, owner, false, shopId, "blocked delete", 4_000, id(404)).status());
            var restore = AdministrationService.restoreSnapshot(
                    state, store, owner, false, snapshotId, "blocked restore", 5_500, id(405), safetyId);
            assertEquals(AdministrationService.SnapshotRestoreStatus.DEPENDENCY_LOCKED, restore.status());
            assertFalse(Files.exists(temporaryDirectory.resolve("snapshots").resolve(safetyId + ".nbt")));
        }

        assertEquals(ShopInstanceService.Status.SUCCESS,
                ShopInstanceService.delete(state, owner, false, shopId, "released delete", 7_000, id(406)).status());
        assertTrue(ShopInstanceService.tryAcquireDependencyLock(state, shopId).isEmpty());
    }

    @Test
    void snapshotRestoreRestoresShopAndMergesRecentShopTransactions() throws Exception {
        PlatformSavedData state = new PlatformSavedData();
        UUID owner = id(50);
        bootstrap(state, owner, AdminRole.OWNER);
        Identifier shopId = identifier("restore");
        UUID createTransaction = id(501);
        create(state, templates(), owner, shopId, createTransaction, 2_000);
        ShopInstanceService.removeOffer(
                state, owner, false, shopId, identifier("bread"), "empty snapshot shop", 2_500, id(506));
        PlatformSnapshotStore store = new PlatformSnapshotStore(temporaryDirectory.resolve("restore"));
        UUID sourceSnapshot = id(502);
        store.write(sourceSnapshot, state);

        UUID deleteTransaction = id(503);
        ShopInstanceService.delete(state, owner, false, shopId, "remove live", 3_500, deleteTransaction);
        assertTrue(state.shopInstance(shopId).isEmpty());

        var result = AdministrationService.restoreSnapshot(
                state, store, owner, false, sourceSnapshot, "restore shop", 5_000, id(504), id(505));

        assertEquals(AdministrationService.SnapshotRestoreStatus.SUCCESS, result.status());
        assertTrue(state.shopInstance(shopId).isPresent());
        assertTrue(state.hasTransaction(createTransaction, 5_000));
        assertTrue(state.hasTransaction(deleteTransaction, 5_000));
    }

    @Test
    void schemaThreeMigratesEmptyShopStateAndCodecsEnforceCollectionBounds() {
        PlatformSavedData original = new PlatformSavedData();
        CompoundTag versionThree = (CompoundTag) PlatformSavedData.CODEC
                .encodeStart(NbtOps.INSTANCE, original).getOrThrow();
        versionThree.putInt("schema_version", 3);
        versionThree.remove("shop_instances");

        PlatformSavedData migrated = PlatformSavedData.CODEC.parse(NbtOps.INSTANCE, versionThree).getOrThrow();
        assertEquals(PlatformSavedData.CURRENT_SCHEMA_VERSION, migrated.schemaVersion());
        assertTrue(migrated.isWritable());
        assertEquals(0, migrated.shopInstanceCount());

        ShopInstance valid = new ShopInstance(
                identifier("foundation"), Optional.empty(), ShopInstance.AccessPolicy.publicAccess(), Map.of());
        var oneShopCodec = PlatformSavedData.boundedShopInstancesCodec(1);
        Map<Identifier, ShopInstance> oneShop = Map.of(identifier("one"), valid);
        var encodedShops = oneShopCodec.encodeStart(NbtOps.INSTANCE, oneShop).getOrThrow();
        assertEquals(oneShop, oneShopCodec.parse(NbtOps.INSTANCE, encodedShops).getOrThrow());
        assertTrue(oneShopCodec.encodeStart(
                NbtOps.INSTANCE, Map.of(identifier("one"), valid, identifier("two"), valid)).error().isPresent());

        ListTag duplicateShops = ((ListTag) encodedShops).copy();
        duplicateShops.add(encodedShops.asList().orElseThrow().getFirst().copy());
        assertTrue(PlatformSavedData.boundedShopInstancesCodec(2)
                .parse(NbtOps.INSTANCE, duplicateShops).error().isPresent());

        TestItemCodec testCodec = testItemCodec();
        Map<Identifier, ShopInstance.Offer> tooMany = new LinkedHashMap<>();
        for (int index = 0; index < ShopInstance.MAX_OFFERS; index++) {
            tooMany.put(identifier("offer_" + index), new ShopInstance.Offer(
                    new ItemStack(testCodec.item()), Optional.of(1L), Optional.empty(),
                    ShopInstance.Stock.unlimitedStock()));
        }
        ShopInstance atOfferLimit = new ShopInstance(
                identifier("foundation"), Optional.empty(), ShopInstance.AccessPolicy.publicAccess(), tooMany);
        ShopInstance.CODEC.encodeStart(testCodec.ops(), atOfferLimit).getOrThrow();
        tooMany.put(identifier("offer_over_limit"), new ShopInstance.Offer(
                new ItemStack(testCodec.item()), Optional.of(1L), Optional.empty(),
                ShopInstance.Stock.unlimitedStock()));
        ShopInstance oversized = new ShopInstance(
                identifier("foundation"), Optional.empty(), ShopInstance.AccessPolicy.publicAccess(), tooMany);
        assertTrue(ShopInstance.CODEC.encodeStart(testCodec.ops(), oversized).error().isPresent());
    }

    private static ShopInstanceService.MutationResult create(
            PlatformSavedData state,
            ShopTemplateSnapshot templates,
            UUID actor,
            Identifier shopId,
            UUID transactionId,
            long timestamp) {
        return ShopInstanceService.create(
                state, templates, actor, false, shopId, identifier("foundation"), Optional.empty(), key -> true,
                ShopInstance.AccessPolicy.publicAccess(), 2_000, "create test", timestamp, transactionId);
    }

    private static ShopTemplateSnapshot templates() {
        DataComponentPatch patch = DataComponentPatch.builder()
                .set(DataComponents.CUSTOM_NAME, Component.literal("Foundation bread"))
                .build();
        var item = new ItemStackTemplate(TEST_BREAD, 4, patch);
        var stock = new ShopTemplate.StockPolicy(
                false, Optional.of(10L), Optional.of(20L), Optional.of(2L), Optional.of(1_200L));
        var offer = new ShopTemplate.Offer(
                identifier("bread"), item, Optional.of(12L), Optional.of(6L), stock);
        var template = new ShopTemplate("shop_template.rovenfall.test", List.of(offer));
        return ShopTemplateSnapshot.compile(List.of(new ShopTemplateSnapshot.Source(
                identifier("foundation.json"), "test", identifier("foundation"), template)));
    }

    private static TestItemCodec testItemCodec() {
        ResourceKey<Item> itemKey = ResourceKey.create(
                Registries.ITEM, Identifier.withDefaultNamespace("bread"));
        MappedRegistry<Item> registry = new MappedRegistry<>(Registries.ITEM, Lifecycle.stable());
        Holder.Reference<Item> holder = registry.register(itemKey, Items.BREAD, RegistrationInfo.BUILT_IN);
        holder.bindComponents(TEST_ITEM_COMPONENTS);
        registry.freeze();
        RegistryAccess access = new RegistryAccess.ImmutableRegistryAccess(List.of(registry));
        return new TestItemCodec(holder, RegistryOps.create(NbtOps.INSTANCE, access));
    }

    private record TestItemCodec(Holder<Item> item, RegistryOps<Tag> ops) {
    }

    private static void bootstrap(PlatformSavedData state, UUID actor, AdminRole role) {
        AdministrationService.changeRole(
                state, AdministrationService.SYSTEM_ACTOR, true, actor, role.getSerializedName(),
                "bootstrap", 1_000, UUID.randomUUID());
    }

    private static Identifier identifier(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }

    private static UUID id(long value) {
        return new UUID(0, value);
    }
}
