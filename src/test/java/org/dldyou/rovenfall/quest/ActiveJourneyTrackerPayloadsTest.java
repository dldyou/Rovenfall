package org.dldyou.rovenfall.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.Unpooled;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

final class ActiveJourneyTrackerPayloadsTest {
    @Test
    void fixedShapeRoundTripsWithinTheExplicitByteBound() {
        var snapshot = new ActiveJourneyTrackerPayloads.Snapshot(
                ActiveJourneyTrackerPayloads.PACKET_REVISION,
                true,
                ActiveJourneyTrackerPayloads.JourneyKind.WEEKLY,
                "a".repeat(ActiveJourneyTrackerPayloads.MAX_TRANSLATION_KEY_LENGTH),
                ActiveJourneyTrackerPayloads.JourneyStatus.IN_PROGRESS,
                ActiveJourneyTrackerPayloads.ObjectiveKind.ACTIVITY,
                "b".repeat(ActiveJourneyTrackerPayloads.MAX_TRANSLATION_KEY_LENGTH),
                999_999_999L,
                QuestDefinition.MAX_REQUIRED_COUNT);
        assertTrue(snapshot.isValid());

        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            ActiveJourneyTrackerPayloads.Snapshot.STREAM_CODEC.encode(buffer, snapshot);
            assertTrue(buffer.readableBytes() <= ActiveJourneyTrackerPayloads.MAX_PACKET_BYTES);
            assertEquals(snapshot, ActiveJourneyTrackerPayloads.Snapshot.STREAM_CODEC.decode(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsNonCanonicalInactiveAndMalformedActiveSnapshots() {
        assertTrue(ActiveJourneyTrackerPayloads.Snapshot.inactive().isValid());
        assertFalse(new ActiveJourneyTrackerPayloads.Snapshot(
                0, false,
                ActiveJourneyTrackerPayloads.JourneyKind.STORY, "",
                ActiveJourneyTrackerPayloads.JourneyStatus.AVAILABLE,
                ActiveJourneyTrackerPayloads.ObjectiveKind.ACTIVITY, "", 0, 0).isValid());
        assertFalse(new ActiveJourneyTrackerPayloads.Snapshot(
                ActiveJourneyTrackerPayloads.PACKET_REVISION, false,
                ActiveJourneyTrackerPayloads.JourneyKind.DAILY, "",
                ActiveJourneyTrackerPayloads.JourneyStatus.AVAILABLE,
                ActiveJourneyTrackerPayloads.ObjectiveKind.ACTIVITY, "", 0, 0).isValid());
        assertFalse(active("quest.rovenfall.test\nleak", 0, 1).isValid());
        assertFalse(active("quest.rovenfall.test", 2, 1).isValid());
        assertFalse(active("quest.rovenfall.test", 0, QuestDefinition.MAX_REQUIRED_COUNT + 1L).isValid());
        assertFalse(new ActiveJourneyTrackerPayloads.Snapshot(
                ActiveJourneyTrackerPayloads.PACKET_REVISION, true,
                ActiveJourneyTrackerPayloads.JourneyKind.STORY, "quest.rovenfall.test",
                ActiveJourneyTrackerPayloads.JourneyStatus.AVAILABLE,
                ActiveJourneyTrackerPayloads.ObjectiveKind.SHOP_TRADE,
                "activity.rovenfall.mining", 0, 1).isValid());
    }

    @Test
    void wireEnumsAreStableAndSnapshotHasNoTechnicalIdentityFields() {
        assertEquals(0, ActiveJourneyTrackerPayloads.JourneyKind.STORY.wireId());
        assertEquals(1, ActiveJourneyTrackerPayloads.JourneyKind.DAILY.wireId());
        assertEquals(2, ActiveJourneyTrackerPayloads.JourneyKind.WEEKLY.wireId());
        assertTrue(ActiveJourneyTrackerPayloads.JourneyKind.fromWireId(3).isEmpty());
        assertEquals(0, ActiveJourneyTrackerPayloads.JourneyStatus.AVAILABLE.wireId());
        assertEquals(1, ActiveJourneyTrackerPayloads.JourneyStatus.IN_PROGRESS.wireId());
        assertEquals(3, ActiveJourneyTrackerPayloads.ObjectiveKind.BOSS_DEFEAT.wireId());
        assertTrue(ActiveJourneyTrackerPayloads.ObjectiveKind.fromWireId(-1).isEmpty());

        assertEquals(List.of(
                        "packetRevision", "active", "journeyKind", "titleTranslationKey", "status",
                        "objectiveKind", "activityTargetTranslationKey", "progress", "requiredCount"),
                Arrays.stream(ActiveJourneyTrackerPayloads.Snapshot.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName)
                        .toList());
        assertTrue(Arrays.stream(ActiveJourneyTrackerPayloads.Snapshot.class.getRecordComponents())
                .noneMatch(component -> component.getType().getName().contains("UUID")
                        || component.getType().getName().contains("Identifier")
                        || component.getName().toLowerCase(java.util.Locale.ROOT).matches(
                                ".*(questid|contract|player|uuid|coordinate|position|window|reward|revision).*" )
                                && !component.getName().equals("packetRevision")));
    }

    private static ActiveJourneyTrackerPayloads.Snapshot active(
            String title, long progress, long requiredCount) {
        return new ActiveJourneyTrackerPayloads.Snapshot(
                ActiveJourneyTrackerPayloads.PACKET_REVISION,
                true,
                ActiveJourneyTrackerPayloads.JourneyKind.STORY,
                title,
                progress == 0
                        ? ActiveJourneyTrackerPayloads.JourneyStatus.AVAILABLE
                        : ActiveJourneyTrackerPayloads.JourneyStatus.IN_PROGRESS,
                ActiveJourneyTrackerPayloads.ObjectiveKind.SHOP_TRADE,
                "",
                progress,
                requiredCount);
    }
}
