package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import org.dldyou.rovenfall.economy.ShopInstance;
import org.junit.jupiter.api.Test;

final class AdminSearchServiceTest {
    private static final UUID VIEWER = id(1);
    private static final UUID PLAYER_A = id(10);
    private static final UUID PLAYER_B = id(20);

    @Test
    void authorizedViewerCanFilterAndPageEveryOperationsScopeWithoutMutation() {
        PlatformSavedData state = populatedState();
        long balanceBefore = state.economyBalance(PLAYER_A).orElseThrow();
        int auditBefore = state.auditCount();
        int claimsBefore = state.claimCount();
        int shopsBefore = state.shopInstanceCount();

        Map<AdminSearchService.Scope, Integer> expectedEntries = Map.of(
                AdminSearchService.Scope.PLAYERS, 3,
                AdminSearchService.Scope.BALANCES, 2,
                AdminSearchService.Scope.TRANSACTIONS, 3,
                AdminSearchService.Scope.CLAIMS, 1,
                AdminSearchService.Scope.SHOPS, 1,
                AdminSearchService.Scope.DENIED, 1,
                AdminSearchService.Scope.ALERTS, 1);
        for (var entry : expectedEntries.entrySet()) {
            AdminSearchService.Page result = search(state, entry.getKey(), "*", 0, 50);
            assertEquals(AdminSearchService.Status.SUCCESS, result.status(), entry.getKey().id());
            assertEquals(entry.getValue(), result.totalEntries(), entry.getKey().id());
            assertEquals(entry.getValue(), result.entries().size(), entry.getKey().id());
        }

        assertEquals(PLAYER_A, assertInstanceOf(AdminSearchService.PlayerRow.class,
                search(state, AdminSearchService.Scope.PLAYERS, PLAYER_A.toString(), 0, 10)
                        .entries().getFirst()).playerId());
        assertEquals(PLAYER_A, assertInstanceOf(AdminSearchService.PlayerRow.class,
                search(state, AdminSearchService.Scope.PLAYERS, "ravenminer", 0, 10)
                        .entries().getFirst()).playerId());
        assertEquals(2, search(state, AdminSearchService.Scope.TRANSACTIONS, "ravenminer", 0, 10)
                .totalEntries());
        assertEquals(1, search(state, AdminSearchService.Scope.CLAIMS, "ravenminer", 0, 10)
                .totalEntries());
        assertEquals(EconomyTransactionReceipt.Kind.CLAIM_PURCHASE,
                assertInstanceOf(AdminSearchService.TransactionRow.class,
                        search(state, AdminSearchService.Scope.TRANSACTIONS, "claim_purchase", 0, 10)
                                .entries().getFirst()).receipt().kind());
        assertEquals(PLAYER_A, assertInstanceOf(AdminSearchService.ClaimRow.class,
                search(state, AdminSearchService.Scope.CLAIMS, PLAYER_A.toString(), 0, 10)
                        .entries().getFirst()).claim().ownerId());
        assertEquals(identifier("ops_market"), assertInstanceOf(AdminSearchService.ShopRow.class,
                search(state, AdminSearchService.Scope.SHOPS, "foundation", 0, 10)
                        .entries().getFirst()).shopId());
        assertEquals("unauthorized", assertInstanceOf(AdminSearchService.DeniedRow.class,
                search(state, AdminSearchService.Scope.DENIED, "unauthorized", 0, 10)
                        .entries().getFirst()).entry().reason());
        assertEquals(EconomyAlert.Type.AMOUNT, assertInstanceOf(AdminSearchService.AlertRow.class,
                search(state, AdminSearchService.Scope.ALERTS, "amount", 0, 10)
                        .entries().getFirst()).alert().type());

        AdminSearchService.Page firstBalancePage = search(
                state, AdminSearchService.Scope.BALANCES, "*", 0, 1);
        AdminSearchService.Page secondBalancePage = search(
                state, AdminSearchService.Scope.BALANCES, "*", 1, 1);
        assertEquals(2, firstBalancePage.totalPages());
        assertEquals(PLAYER_A,
                assertInstanceOf(AdminSearchService.BalanceRow.class,
                        firstBalancePage.entries().getFirst()).playerId());
        assertEquals(PLAYER_B,
                assertInstanceOf(AdminSearchService.BalanceRow.class,
                        secondBalancePage.entries().getFirst()).playerId());
        assertThrows(UnsupportedOperationException.class,
                () -> firstBalancePage.entries().add(new AdminSearchService.BalanceRow(id(30), 1)));

        assertEquals(balanceBefore, state.economyBalance(PLAYER_A).orElseThrow());
        assertEquals(auditBefore, state.auditCount());
        assertEquals(claimsBefore, state.claimCount());
        assertEquals(shopsBefore, state.shopInstanceCount());
    }

    @Test
    void authorizationAndBoundedInputAreRejectedBeforeReadingRows() {
        PlatformSavedData state = populatedState();
        UUID stranger = id(999);

        assertEquals(AdminSearchService.Status.UNAUTHORIZED,
                AdminSearchService.search(state, stranger, false, AdminSearchService.Scope.PLAYERS,
                        "*", 0, 10).status());
        assertEquals(AdminSearchService.Status.SUCCESS,
                AdminSearchService.search(state, AdministrationService.SYSTEM_ACTOR, true,
                        AdminSearchService.Scope.PLAYERS, "*", 0, 10).status());
        assertEquals(AdminSearchService.Status.INVALID_SCOPE,
                AdminSearchService.search(state, VIEWER, false, null, "*", 0, 10).status());
        assertEquals(AdminSearchService.Status.INVALID_QUERY,
                AdminSearchService.search(state, VIEWER, false, AdminSearchService.Scope.PLAYERS,
                        "  ", 0, 10).status());
        assertEquals(AdminSearchService.Status.INVALID_QUERY,
                AdminSearchService.search(state, VIEWER, false, AdminSearchService.Scope.PLAYERS,
                        "x".repeat(AdminSearchService.MAX_QUERY_LENGTH + 1), 0, 10).status());
        assertEquals(AdminSearchService.Status.INVALID_PAGE,
                AdminSearchService.search(state, VIEWER, false, AdminSearchService.Scope.PLAYERS,
                        "*", -1, 10).status());
        assertEquals(AdminSearchService.Status.INVALID_PAGE,
                AdminSearchService.search(state, VIEWER, false, AdminSearchService.Scope.PLAYERS,
                        "*", 0, AdminSearchService.MAX_PAGE_SIZE + 1).status());
    }

    @Test
    void everySearchResultRendersAsLocalizedBookComponents() {
        PlatformSavedData state = populatedState();
        Map<AdminSearchService.Scope, String> rowKeys = Map.of(
                AdminSearchService.Scope.PLAYERS, "gui.rovenfall.admin.search.player",
                AdminSearchService.Scope.BALANCES, "gui.rovenfall.admin.search.balance",
                AdminSearchService.Scope.TRANSACTIONS, "gui.rovenfall.admin.search.transaction",
                AdminSearchService.Scope.CLAIMS, "gui.rovenfall.admin.search.claim",
                AdminSearchService.Scope.SHOPS, "gui.rovenfall.admin.search.shop",
                AdminSearchService.Scope.DENIED, "gui.rovenfall.admin.search.denied",
                AdminSearchService.Scope.ALERTS, "gui.rovenfall.admin.search.alert");

        for (var entry : rowKeys.entrySet()) {
            var pages = AdminSearchBookView.pages(search(state, entry.getKey(), "*", 0, 1));
            assertFalse(pages.isEmpty());
            assertEquals("gui.rovenfall.admin.search.summary",
                    assertInstanceOf(TranslatableContents.class, pages.getFirst().getContents()).getKey());
            assertEquals(entry.getValue(),
                    assertInstanceOf(TranslatableContents.class, pages.get(1).getContents()).getKey());
        }
    }

    private static PlatformSavedData populatedState() {
        PlatformSavedData state = new PlatformSavedData();
        PlayerRecordService.observeLogin(state, PLAYER_A, "RavenMiner", 900);
        assertEquals(AdministrationService.RoleChangeStatus.SUCCESS,
                AdministrationService.changeRole(
                        state, AdministrationService.SYSTEM_ACTOR, true, VIEWER,
                        AdminRole.VIEWER.getSerializedName(), "bootstrap viewer", 1_000, id(100)).status());
        assertEquals(EconomyService.TransactionStatus.SUCCESS,
                EconomyService.award(
                        state, PLAYER_A, EconomyConfig.DEFAULT_ALERT_AMOUNT, "scale fixture",
                        2_000, id(101), 0, Long.MAX_VALUE).status());
        assertEquals(EconomyService.TransactionStatus.SUCCESS,
                EconomyService.award(
                        state, PLAYER_B, 50, "second account",
                        3_000, id(102), 0, Long.MAX_VALUE).status());
        assertEquals(ClaimPurchaseService.Status.SUCCESS,
                ClaimPurchaseService.purchase(
                        state, PLAYER_A, Level.OVERWORLD, Level.OVERWORLD,
                        new BlockPos(160, 64, 160), ignored -> true, ignored -> false,
                        100, 25, 10, 4_000, id(103)).status());

        Identifier shopId = identifier("ops_market");
        UUID shopTransaction = id(104);
        state.commitShopMutation(
                shopId,
                Optional.of(new ShopInstance(
                        identifier("foundation"), Optional.empty(),
                        ShopInstance.AccessPolicy.publicAccess(), Map.of())),
                shopTransaction,
                5_000,
                new AuditEntry(
                        5_000, VIEWER, identifier("shop_instance_create"), shopId.toString(),
                        Optional.empty(), Optional.empty(), "none", "created", "fixture", shopTransaction));
        assertEquals(EconomyService.TransactionStatus.UNAUTHORIZED,
                EconomyService.adminGrant(
                        state, VIEWER, false, PLAYER_B, 1, "denied fixture",
                        6_000, id(105), 0, Long.MAX_VALUE).status());
        return state;
    }

    private static AdminSearchService.Page search(
            PlatformSavedData state,
            AdminSearchService.Scope scope,
            String query,
            int page,
            int pageSize) {
        return AdminSearchService.search(state, VIEWER, false, scope, query, page, pageSize);
    }

    private static Identifier identifier(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }

    private static UUID id(long value) {
        return new UUID(0L, value);
    }
}
