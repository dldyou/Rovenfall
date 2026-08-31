package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.quest.QuestJourneyView;
import org.dldyou.rovenfall.quest.QuestPlayerState;
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
        assertEquals(PlayerQuestMenu.Action.PREVIOUS,
                PlayerQuestMenu.actionAt(PlayerQuestMenu.Page.LIST, 48));
        assertEquals(PlayerQuestMenu.Action.GUIDE,
                PlayerQuestMenu.actionAt(PlayerQuestMenu.Page.LIST, 49));
        assertEquals(PlayerQuestMenu.Action.NEXT,
                PlayerQuestMenu.actionAt(PlayerQuestMenu.Page.LIST, 50));
        assertEquals(PlayerQuestMenu.Action.REFRESH,
                PlayerQuestMenu.actionAt(PlayerQuestMenu.Page.LIST, 53));
        assertEquals(PlayerQuestMenu.Action.NONE,
                PlayerQuestMenu.actionAt(PlayerQuestMenu.Page.LIST, 0));
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
}
