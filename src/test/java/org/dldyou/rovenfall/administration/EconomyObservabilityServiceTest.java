package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

final class EconomyObservabilityServiceTest {
    private static final UUID VIEWER = id(1);
    private static final UUID PLAYER = id(2);

    @Test
    void queriesAreRoleGatedBoundedSortedAndReadOnly() {
        PlatformSavedData state = stateWithViewer();
        EconomyService.award(state, PLAYER, 5, "first", 1_000, id(100), 0, Long.MAX_VALUE);
        EconomyService.award(state, id(3), 7, "second", 2_000, id(101), 0, Long.MAX_VALUE);
        int audits = state.auditCount();

        var balances = EconomyObservabilityService.balances(state, VIEWER, false, 0, 1);
        var transactions = EconomyObservabilityService.transactions(state, VIEWER, false, 0, 2);

        assertEquals(EconomyObservabilityService.Status.SUCCESS, balances.status());
        assertEquals(2, balances.totalEntries());
        assertEquals(1, balances.entries().size());
        assertEquals(EconomyObservabilityService.Status.SUCCESS, transactions.status());
        assertEquals(id(101), transactions.entries().getFirst().transactionId());
        assertEquals(EconomyObservabilityService.Status.UNAUTHORIZED,
                EconomyObservabilityService.balances(state, id(999), false, 0, 10).status());
        assertEquals(EconomyObservabilityService.Status.INVALID_PAGE,
                EconomyObservabilityService.alerts(
                        state, VIEWER, false, 0, EconomyObservabilityService.MAX_PAGE_SIZE + 1).status());
        assertEquals(audits, state.auditCount());
        assertEquals(5, state.economyBalance(PLAYER).orElseThrow());
    }

    @Test
    void amountAndRateAlertsAreDeterministicAcrossReloadAndNeverPunish() {
        PlatformSavedData state = stateWithViewer();
        assertEquals(EconomyService.TransactionStatus.SUCCESS,
                EconomyService.award(
                        state, PLAYER, EconomyConfig.DEFAULT_ALERT_AMOUNT, "large", 1_000,
                        id(200), 0, Long.MAX_VALUE).status());
        assertEquals(EconomyAlert.Type.AMOUNT,
                EconomyObservabilityService.alerts(state, VIEWER, false, 0, 10).entries().getFirst().type());
        assertEquals(EconomyConfig.DEFAULT_ALERT_AMOUNT, state.economyBalance(PLAYER).orElseThrow());

        EconomyTransactionReceipt candidate = plainReceipt(1_500, PLAYER, 1);
        EconomyConfig.AlertThresholds thresholds = new EconomyConfig.AlertThresholds(Long.MAX_VALUE, 2, 60);
        List<EconomyAlert> before = EconomyMonitoringService.evaluate(state, id(201), candidate, thresholds);
        PlatformSavedData decoded = PlatformSavedData.CODEC.parse(
                NbtOps.INSTANCE, PlatformSavedData.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow()).getOrThrow();
        List<EconomyAlert> after = EconomyMonitoringService.evaluate(decoded, id(201), candidate, thresholds);

        assertEquals(before, after);
        assertEquals(List.of(EconomyAlert.Type.RATE), before.stream().map(EconomyAlert::type).toList());
        assertEquals(EconomyConfig.DEFAULT_ALERT_AMOUNT, decoded.economyBalance(PLAYER).orElseThrow());
    }

    @Test
    void bookRowsUseTranslationComponentsForSemanticValues() {
        EconomyTransactionReceipt receipt = plainReceipt(1_000, PLAYER, 5);
        var page = new EconomyObservabilityService.Page<>(
                EconomyObservabilityService.Status.SUCCESS, 0, 1, 1,
                List.of(new EconomyObservabilityService.TransactionRow(id(300), receipt)));

        List<Component> pages = EconomyBookView.transactions(page);
        TranslatableContents row = (TranslatableContents) pages.get(1).getContents();
        Object[] arguments = row.getArgs();

        assertEquals("gui.rovenfall.admin.economy.transaction", row.getKey());
        assertEquals("economy_transaction_kind.rovenfall.award",
                ((TranslatableContents) ((Component) arguments[1]).getContents()).getKey());
        assertEquals("gui.rovenfall.admin.economy.none",
                ((TranslatableContents) ((Component) arguments[4]).getContents()).getKey());
        assertEquals("gui.rovenfall.admin.economy.none",
                ((TranslatableContents) ((Component) arguments[5]).getContents()).getKey());
        assertFalse(pages.stream().anyMatch(component -> component.getString().contains("none")));
    }

    private static PlatformSavedData stateWithViewer() {
        PlatformSavedData state = new PlatformSavedData();
        AdministrationService.changeRole(
                state, AdministrationService.SYSTEM_ACTOR, true, VIEWER, "viewer", "bootstrap", 100, id(9_000));
        return state;
    }

    private static EconomyTransactionReceipt plainReceipt(long timestamp, UUID player, long amount) {
        return new EconomyTransactionReceipt(
                timestamp, AdministrationService.SYSTEM_ACTOR, player,
                EconomyTransactionReceipt.Kind.AWARD, amount,
                Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), 0,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                EconomyTransactionReceipt.CompensationDecision.NONE);
    }

    private static UUID id(long value) {
        return new UUID(0L, value);
    }
}
