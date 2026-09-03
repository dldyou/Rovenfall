package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.waypoints.WaypointStyleAssets;
import org.dldyou.rovenfall.exploration.ExplorationJournalView;
import org.dldyou.rovenfall.quest.QuestDefinition;
import org.dldyou.rovenfall.quest.QuestJourneyView;
import org.dldyou.rovenfall.quest.QuestPlayerState;
import org.dldyou.rovenfall.world.WorldTopology;
import org.junit.jupiter.api.Test;

final class PlayerQuestMenuTest {
    @Test
    void mapsOnlyDeclaredQuestBoardActions() {
        assertEquals(PlayerQuestMenu.Action.SELECT,
                PlayerQuestMenu.actionAt(PlayerQuestMenu.Page.LIST, 10));
        assertEquals(PlayerQuestMenu.Action.NONE,
                PlayerQuestMenu.actionAt(PlayerQuestMenu.Page.DETAIL, 10));
        assertEquals(PlayerQuestMenu.Action.BACK,
                PlayerQuestMenu.actionAt(PlayerQuestMenu.Page.LIST, 45));
        assertEquals(PlayerQuestMenu.Action.CONTRACTS,
                PlayerQuestMenu.actionAt(PlayerQuestMenu.Page.LIST, 46));
        assertEquals(PlayerQuestMenu.Action.CONTRACTS,
                PlayerQuestMenu.actionAt(PlayerQuestMenu.Page.CONTRACTS, 46));
        assertEquals(PlayerQuestMenu.Action.NONE,
                PlayerQuestMenu.actionAt(PlayerQuestMenu.Page.DETAIL, 46));
        assertEquals(PlayerQuestMenu.Action.EXPLORATION,
                PlayerQuestMenu.actionAt(PlayerQuestMenu.Page.LIST, 47));
        assertEquals(PlayerQuestMenu.Action.EXPLORATION,
                PlayerQuestMenu.actionAt(PlayerQuestMenu.Page.CONTRACTS, 47));
        assertEquals(PlayerQuestMenu.Action.PREVIOUS,
                PlayerQuestMenu.actionAt(PlayerQuestMenu.Page.LIST, 48));
        assertEquals(PlayerQuestMenu.Action.GUIDE,
                PlayerQuestMenu.actionAt(PlayerQuestMenu.Page.LIST, 49));
        assertEquals(PlayerQuestMenu.Action.TRACK_STORY,
                PlayerQuestMenu.actionAt(PlayerQuestMenu.Page.DETAIL, 49));
        assertEquals(PlayerQuestMenu.Action.NEXT,
                PlayerQuestMenu.actionAt(PlayerQuestMenu.Page.LIST, 50));
        assertEquals(PlayerQuestMenu.Action.CLEAR_TRACKER,
                PlayerQuestMenu.actionAt(PlayerQuestMenu.Page.LIST, 51));
        assertEquals(PlayerQuestMenu.Action.FILTER_STORY,
                PlayerQuestMenu.actionAt(PlayerQuestMenu.Page.LIST, 0));
        assertEquals(PlayerQuestMenu.Action.FILTER_STORY,
                PlayerQuestMenu.actionAt(PlayerQuestMenu.Page.LIST, 7));
        assertEquals(PlayerQuestMenu.Action.NONE,
                PlayerQuestMenu.actionAt(PlayerQuestMenu.Page.DETAIL, 7));
        assertEquals(PlayerQuestMenu.Action.REFRESH,
                PlayerQuestMenu.actionAt(PlayerQuestMenu.Page.LIST, 53));
        assertEquals(PlayerQuestMenu.Action.NONE,
                PlayerQuestMenu.actionAt(PlayerQuestMenu.Page.LIST, 5));
        assertEquals(PlayerQuestMenu.Action.TRACK_CONTRACT,
                PlayerQuestMenu.actionAt(PlayerQuestMenu.Page.CONTRACTS, 20));
        assertEquals(PlayerQuestMenu.Action.NONE,
                PlayerQuestMenu.actionAt(PlayerQuestMenu.Page.CONTRACTS, 48));
        assertEquals(PlayerQuestMenu.Action.REFRESH,
                PlayerQuestMenu.actionAt(PlayerQuestMenu.Page.CONTRACTS, 53));
        assertEquals(PlayerQuestMenu.Action.CLEAR_TRACKER,
                PlayerQuestMenu.actionAt(PlayerQuestMenu.Page.CONTRACTS, 51));
        assertEquals(PlayerQuestMenu.Action.FILTER_ALL,
                PlayerQuestMenu.actionAt(PlayerQuestMenu.Page.EXPLORATION_LIST, 1));
        assertEquals(PlayerQuestMenu.Action.FILTER_HUB,
                PlayerQuestMenu.actionAt(PlayerQuestMenu.Page.EXPLORATION_LIST, 2));
        assertEquals(PlayerQuestMenu.Action.FILTER_WILDERNESS,
                PlayerQuestMenu.actionAt(PlayerQuestMenu.Page.EXPLORATION_LIST, 3));
        assertEquals(PlayerQuestMenu.Action.SELECT,
                PlayerQuestMenu.actionAt(PlayerQuestMenu.Page.EXPLORATION_LIST, 10));
        assertEquals(PlayerQuestMenu.Action.PREVIOUS,
                PlayerQuestMenu.actionAt(PlayerQuestMenu.Page.EXPLORATION_LIST, 48));
        assertEquals(PlayerQuestMenu.Action.CLEAR_NAVIGATION,
                PlayerQuestMenu.actionAt(PlayerQuestMenu.Page.EXPLORATION_LIST, 49));
        assertEquals(PlayerQuestMenu.Action.NEXT,
                PlayerQuestMenu.actionAt(PlayerQuestMenu.Page.EXPLORATION_LIST, 50));
        assertEquals(PlayerQuestMenu.Action.CLEAR_NAVIGATION,
                PlayerQuestMenu.actionAt(PlayerQuestMenu.Page.EXPLORATION_DETAIL, 48));
        assertEquals(PlayerQuestMenu.Action.NAVIGATE,
                PlayerQuestMenu.actionAt(PlayerQuestMenu.Page.EXPLORATION_DETAIL, 49));
        assertEquals(PlayerQuestMenu.Action.NONE,
                PlayerQuestMenu.actionAt(PlayerQuestMenu.Page.EXPLORATION_DETAIL, 47));
    }

    @Test
    void rejectsAChangedDefinitionStateOrWritableRoot() {
        QuestPlayerState rendered = state(1);

        assertTrue(PlayerQuestMenu.isCurrent(4, rendered, true, 4, rendered, true));
        assertFalse(PlayerQuestMenu.isCurrent(4, rendered, true, 5, rendered, true));
        assertFalse(PlayerQuestMenu.isCurrent(4, rendered, true, 4, state(2), true));
        assertFalse(PlayerQuestMenu.isCurrent(4, rendered, true, 4, rendered, false));
    }

    @Test
    void pagingStaysInsideTheBoundedTwentyEightEntryWindow() {
        assertEquals(0, PlayerQuestMenu.boundedPage(-1, 0));
        assertEquals(0, PlayerQuestMenu.boundedPage(99, PlayerQuestMenu.PAGE_SIZE));
        assertEquals(1, PlayerQuestMenu.boundedPage(99, PlayerQuestMenu.PAGE_SIZE + 1));
    }

    @Test
    void mapsOnlyExactRequestSlotsAndRecognizesTheServerOwnedSelection() {
        Identifier storyId = Identifier.parse("rovenfall:first_steps");
        QuestPlayerState story = withTracked(
                state(0), QuestPlayerState.TrackedJourney.story(storyId, 1));
        QuestPlayerState.ContractKey contractKey = new QuestPlayerState.ContractKey(
                new QuestPlayerState.ContractWindow(QuestDefinition.Cadence.DAILY, 20_000),
                Identifier.parse("rovenfall:daily_market_delivery"));
        QuestPlayerState contract = withTracked(
                state(0), QuestPlayerState.TrackedJourney.contract(contractKey, 1));

        assertEquals(0, PlayerQuestMenu.contractOffset(20));
        assertEquals(1, PlayerQuestMenu.contractOffset(22));
        assertEquals(2, PlayerQuestMenu.contractOffset(24));
        assertEquals(-1, PlayerQuestMenu.contractOffset(21));
        assertEquals(0, PlayerQuestMenu.storyFilterOffset(0));
        assertEquals(4, PlayerQuestMenu.storyFilterOffset(7));
        assertEquals(-1, PlayerQuestMenu.storyFilterOffset(5));
        assertTrue(PlayerQuestMenu.tracksStory(story, storyId));
        assertFalse(PlayerQuestMenu.tracksStory(contract, storyId));
        assertTrue(PlayerQuestMenu.tracksContract(contract, contractKey));
        assertFalse(PlayerQuestMenu.tracksContract(story, contractKey));
    }

    @Test
    void onlyOpeningTheRequestsPageMayInitializeAssignments() {
        assertFalse(PlayerQuestMenu.shouldEnsureAssignments(PlayerQuestMenu.Page.LIST));
        assertFalse(PlayerQuestMenu.shouldEnsureAssignments(PlayerQuestMenu.Page.DETAIL));
        assertTrue(PlayerQuestMenu.shouldEnsureAssignments(PlayerQuestMenu.Page.CONTRACTS));
        assertFalse(PlayerQuestMenu.shouldEnsureAssignments(PlayerQuestMenu.Page.EXPLORATION_LIST));
        assertFalse(PlayerQuestMenu.shouldEnsureAssignments(PlayerQuestMenu.Page.EXPLORATION_DETAIL));
    }

    @Test
    void explorationUsesItsOwnNativeWaypointMarker() throws ReflectiveOperationException {
        ExplorationJournalView.GuidanceTarget target = new ExplorationJournalView.GuidanceTarget(
                WorldTopology.HUB, new BlockPos(40, 70, -17));
        var navigation = PlayerQuestMenu.explorationNavigationPacket(target);

        assertEquals(PlayerQuestMenu.EXPLORATION_MARKER_ID,
                navigation.waypoint().id().left().orElseThrow());
        assertFalse(PlayerQuestMenu.EXPLORATION_MARKER_ID.equals(PlayerClaimMenu.NAVIGATION_MARKER_ID));
        assertFalse(PlayerQuestMenu.EXPLORATION_MARKER_ID.equals(PlayerPortalMenu.NAVIGATION_MARKER_ID));
        assertEquals(WaypointStyleAssets.BOWTIE, navigation.waypoint().icon().style);
        assertEquals(Optional.of(0x68D391), navigation.waypoint().icon().color);
        var chunkField = navigation.waypoint().getClass().getDeclaredField("chunkPos");
        chunkField.setAccessible(true);
        assertEquals(new ChunkPos(2, -2), chunkField.get(navigation.waypoint()));

        var clear = PlayerQuestMenu.clearExplorationNavigationPacket();
        assertEquals(PlayerQuestMenu.EXPLORATION_MARKER_ID,
                clear.waypoint().id().left().orElseThrow());
        assertTrue(PlayerQuestMenu.explorationPage(PlayerQuestMenu.Page.EXPLORATION_LIST));
        assertTrue(PlayerQuestMenu.explorationPage(PlayerQuestMenu.Page.EXPLORATION_DETAIL));
        assertFalse(PlayerQuestMenu.explorationPage(PlayerQuestMenu.Page.LIST));
    }

    @Test
    void everyJourneyStatusHasANaturalLocalizedKey() {
        assertEquals("gui.rovenfall.quest.status.locked",
                PlayerQuestMenu.statusKey(QuestJourneyView.Status.PREREQUISITE_LOCKED));
        assertEquals("gui.rovenfall.quest.status.reward_pending",
                PlayerQuestMenu.statusKey(QuestJourneyView.Status.PENDING));
        for (QuestJourneyView.Status status : QuestJourneyView.Status.values()) {
            assertTrue(PlayerQuestMenu.statusKey(status).startsWith("gui.rovenfall.quest.status."));
        }
    }

    private static QuestPlayerState state(long progress) {
        return new QuestPlayerState(Map.of(
                Identifier.parse("rovenfall:first_steps"),
                new QuestPlayerState.QuestEntry(
                        1,
                        Map.of(Identifier.parse("rovenfall:first_steps/activity"), progress),
                        Optional.empty())));
    }

    private static QuestPlayerState withTracked(
            QuestPlayerState state,
            QuestPlayerState.TrackedJourney trackedJourney) {
        return new QuestPlayerState(
                state.quests(), state.processedEvidence(), state.contracts(),
                state.initializedContractWindows(), Optional.of(trackedJourney));
    }
}
