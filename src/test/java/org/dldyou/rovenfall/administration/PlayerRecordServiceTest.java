package org.dldyou.rovenfall.administration;

import static org.dldyou.rovenfall.PersistenceTestHarness.roundTrip;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import org.junit.jupiter.api.Test;

final class PlayerRecordServiceTest {
    @Test
    void observedLoginsAreMonotonicAndRoundTripThroughTheCodec() {
        PlatformSavedData state = new PlatformSavedData();
        UUID playerId = id(1);

        assertFalse(PlayerRecordService.observeLogin(state, playerId, -1));
        assertTrue(state.playerRecord(playerId).isEmpty());
        assertFalse(state.isDirty());
        assertTrue(PlayerRecordService.observeLogin(state, playerId, 2_000));
        assertFalse(PlayerRecordService.observeLogin(state, playerId, 1_000));
        assertTrue(PlayerRecordService.observeLogin(state, playerId, 3_000));

        PlayerRecord record = state.playerRecord(playerId).orElseThrow();
        assertEquals(2_000, record.firstSeenEpochMillis());
        assertEquals(3_000, record.lastSeenEpochMillis());
        assertEquals(Optional.empty(), record.displayName());
        assertEquals(record, roundTrip(PlayerRecord.CODEC, record));

        PlatformSavedData loaded = roundTrip(PlatformSavedData.CODEC, state);
        assertEquals(record, loaded.playerRecord(playerId).orElseThrow());
    }

    @Test
    void displayNamesArePersistedWithoutBreakingLegacyRecords() {
        PlatformSavedData state = new PlatformSavedData();
        UUID playerId = id(4);

        assertTrue(PlayerRecordService.observeLogin(state, playerId, "Alice", 2_000));
        assertTrue(PlayerRecordService.observeLogin(state, playerId, "AliceTwo", 1_000));
        assertFalse(PlayerRecordService.observeLogin(state, playerId, " ", 1_000));
        assertFalse(PlayerRecordService.observeLogin(
                state, playerId, "x".repeat(PlayerRecord.MAX_DISPLAY_NAME_LENGTH + 1), 1_000));

        PlayerRecord record = state.playerRecord(playerId).orElseThrow();
        assertEquals(2_000, record.firstSeenEpochMillis());
        assertEquals(2_000, record.lastSeenEpochMillis());
        assertEquals(Optional.of("AliceTwo"), record.displayName());
        assertEquals(record, roundTrip(PlayerRecord.CODEC, record));
        assertEquals(record, roundTrip(PlatformSavedData.CODEC, state).playerRecord(playerId).orElseThrow());

        CompoundTag legacy = new CompoundTag();
        legacy.putLong("first_seen", 1_000);
        legacy.putLong("last_seen", 2_000);
        assertEquals(Optional.empty(), PlayerRecord.CODEC.parse(NbtOps.INSTANCE, legacy)
                .getOrThrow().displayName());
    }

    @Test
    void playerRecordCodecRejectsInvalidTimestampRanges() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("first_seen", 2_000);
        tag.putLong("last_seen", 1_000);

        assertTrue(PlayerRecord.CODEC.parse(NbtOps.INSTANCE, tag).error().isPresent());
    }

    @Test
    void versionOneMigratesRolesAndAuditsWithNoInventedPlayerRecords() {
        PlatformSavedData original = new PlatformSavedData();
        UUID owner = id(2);
        AdministrationService.changeRole(
                original, AdministrationService.SYSTEM_ACTOR, true, owner, "owner",
                "bootstrap", 1_000, id(102));

        CompoundTag versionOne = (CompoundTag) PlatformSavedData.CODEC
                .encodeStart(NbtOps.INSTANCE, original).getOrThrow();
        versionOne.putInt("schema_version", 1);
        versionOne.remove("player_records");
        PlatformSavedData migrated = PlatformSavedData.CODEC.parse(NbtOps.INSTANCE, versionOne).getOrThrow();

        assertEquals(PlatformSavedData.CURRENT_SCHEMA_VERSION, migrated.schemaVersion());
        assertTrue(migrated.isWritable());
        assertEquals(AdminRole.OWNER, migrated.roleOf(owner).orElseThrow());
        assertEquals(1, migrated.auditCount());
        assertEquals(0, migrated.playerRecordCount());
    }

    @Test
    void unknownSchemaRetainsRecordsReadOnly() {
        PlatformSavedData original = new PlatformSavedData();
        UUID playerId = id(3);
        PlayerRecordService.observeLogin(original, playerId, 2_000);

        CompoundTag future = (CompoundTag) PlatformSavedData.CODEC
                .encodeStart(NbtOps.INSTANCE, original).getOrThrow();
        future.putInt("schema_version", PlatformSavedData.CURRENT_SCHEMA_VERSION + 1);
        PlatformSavedData loaded = PlatformSavedData.CODEC.parse(NbtOps.INSTANCE, future).getOrThrow();

        assertFalse(loaded.isWritable());
        assertEquals(new PlayerRecord(2_000, 2_000), loaded.playerRecord(playerId).orElseThrow());
        assertFalse(PlayerRecordService.observeLogin(loaded, playerId, 3_000));
        assertEquals(new PlayerRecord(2_000, 2_000), loaded.playerRecord(playerId).orElseThrow());
        assertFalse(loaded.isDirty());
    }

    private static UUID id(long value) {
        return new UUID(0L, value);
    }
}
