package org.dldyou.rovenfall.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

final class ContractJourneyViewTest {
    private static final long NOW = Instant.parse("2026-08-31T12:00:00Z").toEpochMilli();

    @Test
    void projectsOnlyTheCurrentBoundedRosterWithoutInventingAssignments() {
        QuestPlayerState.ContractWindow daily = RepeatableContractService.windowAt(
                QuestDefinition.Cadence.DAILY, NOW);
        QuestPlayerState.ContractWindow weekly = RepeatableContractService.windowAt(
                QuestDefinition.Cadence.WEEKLY, NOW);
        QuestPlayerState.ContractWindow oldDaily = RepeatableContractService.windowAt(
                QuestDefinition.Cadence.DAILY, NOW - RepeatableContractService.DAY_MILLIS);
        QuestPlayerState.ContractKey mining = key(daily, "daily_mining");
        QuestPlayerState.ContractKey market = key(daily, "daily_market");
        QuestPlayerState.ContractKey removed = key(weekly, "weekly_removed");
        QuestPlayerState state = new QuestPlayerState(
                Map.of(), Map.of(),
                Map.of(
                        mining, progress("daily_mining", 1, 7),
                        market, progress("daily_market", 1, 0),
                        removed, pending(1, 12),
                        key(oldDaily, "old_daily"), progress("old_daily", 1, 1)),
                Set.of(daily, weekly, oldDaily));
        QuestDefinitionSnapshot definitions = QuestDefinitionSnapshot.compile(List.of(
                contract("daily_mining", 1, QuestDefinition.Cadence.DAILY, 10, 20),
                contract("daily_market", 2, QuestDefinition.Cadence.DAILY, 3, 15),
                contract("old_daily", 1, QuestDefinition.Cadence.DAILY, 1, 1)));

        ContractJourneyView view = ContractJourneyView.create(definitions, state, 12, false, NOW);

        assertEquals(12, view.definitionRevision());
        assertFalse(view.writable());
        assertEquals(3, view.entries().size());
        assertEquals(List.of(market, mining, removed),
                view.entries().stream().map(ContractJourneyView.ContractRow::key).toList());
        assertEquals(QuestJourneyView.Status.IN_PROGRESS, view.entries().get(1).status());
        assertEquals(7, view.entries().get(1).objective().orElseThrow().progress());
        assertEquals(new QuestJourneyView.RewardPreview(20, Optional.empty(), 0),
                view.entries().get(1).rewardPreview().orElseThrow());
        assertEquals(QuestJourneyView.Status.DEFINITION_CHANGED, view.entries().get(0).status());
        assertTrue(view.entries().get(0).objective().isEmpty());
        assertEquals(QuestJourneyView.Status.UNRESOLVED, view.entries().get(2).status());
        assertEquals(new QuestJourneyView.RewardPreview(12, Optional.empty(), 0),
                view.entries().get(2).rewardPreview().orElseThrow());
        assertThrows(UnsupportedOperationException.class, view.entries()::clear);
    }

    @Test
    void rejectsInvalidProjectionRequests() {
        assertThrows(IllegalArgumentException.class, () -> ContractJourneyView.create(
                null, QuestPlayerState.EMPTY, 0, true, NOW));
        assertThrows(IllegalArgumentException.class, () -> ContractJourneyView.create(
                QuestDefinitionSnapshot.empty(), QuestPlayerState.EMPTY, 0, true, -1));
        assertThrows(IllegalArgumentException.class, () -> new ContractJourneyView(
                0, true, List.of(row(1), row(2), row(3), row(4))));
    }

    private static ContractJourneyView.ContractRow row(long day) {
        return new ContractJourneyView.ContractRow(
                key(new QuestPlayerState.ContractWindow(QuestDefinition.Cadence.DAILY, day), "row_" + day),
                Optional.empty(), Optional.empty(), QuestJourneyView.Status.AVAILABLE,
                Optional.empty(), Optional.empty());
    }

    private static QuestDefinitionSnapshot.Source contract(
            String path,
            int version,
            QuestDefinition.Cadence cadence,
            int required,
            long currency) {
        return new QuestDefinitionSnapshot.Source(
                id("rovenfall/quests/contracts/" + path + ".json"), "test", id(path),
                new QuestDefinition(
                        "quest.rovenfall.contract." + path,
                        "quest.rovenfall.contract." + path + ".description",
                        version,
                        List.of(),
                        List.of(new QuestDefinition.Objective(
                                id(path + "/objective"), QuestDefinition.Kind.SHOP_TRADE,
                                Optional.empty(), required)),
                        new QuestDefinition.Rewards(currency, Optional.empty()),
                        Optional.of(new QuestDefinition.Contract(cadence))));
    }

    private static QuestPlayerState.QuestEntry progress(String contract, int version, long progress) {
        return new QuestPlayerState.QuestEntry(
                version, Map.of(id(contract + "/objective"), progress), Optional.empty());
    }

    private static QuestPlayerState.QuestEntry pending(int version, long currency) {
        return new QuestPlayerState.QuestEntry(
                version, Map.of(),
                Optional.of(new QuestPlayerState.RewardOperation(
                        version, new UUID(0, 90), currency, Optional.empty(), 0, 1_000,
                        QuestPlayerState.RewardOperation.Phase.CAPTURED)),
                Optional.empty());
    }

    private static QuestPlayerState.ContractKey key(
            QuestPlayerState.ContractWindow window, String template) {
        return new QuestPlayerState.ContractKey(window, id(template));
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }
}
