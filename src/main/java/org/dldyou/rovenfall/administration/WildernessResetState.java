package org.dldyou.rovenfall.administration;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.util.StringRepresentable;

public record WildernessResetState(
        Optional<Warning> warning,
        Optional<Operation> activeOperation,
        List<Evidence> evidence) {
    static final int MAX_EVIDENCE = 64;
    static final WildernessResetState EMPTY = new WildernessResetState(Optional.empty(), Optional.empty(), List.of());

    static final Codec<WildernessResetState> CODEC = RecordCodecBuilder.<WildernessResetState>create(instance -> instance.group(
            Warning.CODEC.optionalFieldOf("warning").forGetter(WildernessResetState::warning),
            Operation.CODEC.optionalFieldOf("active_operation").forGetter(WildernessResetState::activeOperation),
            Evidence.CODEC.listOf(0, MAX_EVIDENCE).optionalFieldOf("evidence", List.of())
                    .forGetter(WildernessResetState::evidence)
    ).apply(instance, WildernessResetState::new)).validate(state -> state.isValid()
            ? DataResult.success(state)
            : DataResult.error(() -> "Invalid Wilderness reset state"));

    public WildernessResetState {
        warning = warning == null ? Optional.empty() : warning;
        activeOperation = activeOperation == null ? Optional.empty() : activeOperation;
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    private boolean isValid() {
        return evidence.size() <= MAX_EVIDENCE
                && warning.map(Warning::isValid).orElse(true)
                && activeOperation.map(Operation::isValid).orElse(true)
                && evidence.stream().allMatch(Evidence::isValid);
    }

    WildernessResetState withWarning(Warning value) {
        return new WildernessResetState(Optional.of(value), activeOperation, evidence);
    }

    WildernessResetState withOperation(Operation value) {
        return new WildernessResetState(Optional.empty(), Optional.of(value), evidence);
    }

    WildernessResetState complete(Evidence value) {
        var retained = new java.util.ArrayList<>(evidence);
        retained.add(value);
        while (retained.size() > MAX_EVIDENCE) {
            retained.removeFirst();
        }
        return new WildernessResetState(Optional.empty(), Optional.empty(), retained);
    }

    WildernessResetState clearActive() {
        return new WildernessResetState(warning, Optional.empty(), evidence);
    }

    public enum Kind implements StringRepresentable {
        RESET("reset"),
        RESTORE("restore");

        static final Codec<Kind> CODEC = StringRepresentable.fromEnum(Kind::values);
        private final String serializedName;

        Kind(String serializedName) {
            this.serializedName = serializedName;
        }

        @Override
        public String getSerializedName() {
            return serializedName;
        }
    }

    public enum Result implements StringRepresentable {
        COMPLETED("completed"),
        FAILED("failed");

        static final Codec<Result> CODEC = StringRepresentable.fromEnum(Result::values);
        private final String serializedName;

        Result(String serializedName) {
            this.serializedName = serializedName;
        }

        @Override
        public String getSerializedName() {
            return serializedName;
        }
    }

    public record Warning(UUID warningId, UUID actorId, long issuedAtEpochMillis, long expiresAtEpochMillis, String reason) {
        static final Codec<Warning> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.STRING_CODEC.fieldOf("warning_id").forGetter(Warning::warningId),
                UUIDUtil.STRING_CODEC.fieldOf("actor_id").forGetter(Warning::actorId),
                Codec.LONG.fieldOf("issued_at").forGetter(Warning::issuedAtEpochMillis),
                Codec.LONG.fieldOf("expires_at").forGetter(Warning::expiresAtEpochMillis),
                Codec.STRING.fieldOf("reason").forGetter(Warning::reason)
        ).apply(instance, Warning::new));

        boolean isValid() {
            return warningId != null && actorId != null && issuedAtEpochMillis >= 0
                    && expiresAtEpochMillis > issuedAtEpochMillis && validReason(reason);
        }
    }

    public record Operation(
            Kind kind,
            UUID transactionId,
            UUID snapshotId,
            UUID recoverySnapshotId,
            UUID actorId,
            long requestedAtEpochMillis,
            String reason,
            long fileCount,
            long byteCount,
            String sha256,
            long recoveryFileCount,
            long recoveryByteCount,
            String recoverySha256) {
        static final Codec<Operation> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Kind.CODEC.fieldOf("kind").forGetter(Operation::kind),
                UUIDUtil.STRING_CODEC.fieldOf("transaction_id").forGetter(Operation::transactionId),
                UUIDUtil.STRING_CODEC.fieldOf("snapshot_id").forGetter(Operation::snapshotId),
                UUIDUtil.STRING_CODEC.fieldOf("recovery_snapshot_id").forGetter(Operation::recoverySnapshotId),
                UUIDUtil.STRING_CODEC.fieldOf("actor_id").forGetter(Operation::actorId),
                Codec.LONG.fieldOf("requested_at").forGetter(Operation::requestedAtEpochMillis),
                Codec.STRING.fieldOf("reason").forGetter(Operation::reason),
                Codec.LONG.fieldOf("file_count").forGetter(Operation::fileCount),
                Codec.LONG.fieldOf("byte_count").forGetter(Operation::byteCount),
                Codec.STRING.fieldOf("sha256").forGetter(Operation::sha256),
                Codec.LONG.fieldOf("recovery_file_count").forGetter(Operation::recoveryFileCount),
                Codec.LONG.fieldOf("recovery_byte_count").forGetter(Operation::recoveryByteCount),
                Codec.STRING.fieldOf("recovery_sha256").forGetter(Operation::recoverySha256)
        ).apply(instance, Operation::new));

        boolean isValid() {
            return kind != null && transactionId != null && snapshotId != null && recoverySnapshotId != null && actorId != null
                    && requestedAtEpochMillis >= 0 && validReason(reason) && fileCount >= 0 && byteCount >= 0
                    && sha256 != null && sha256.matches("[0-9a-f]{64}")
                    && recoveryFileCount >= 0 && recoveryByteCount >= 0
                    && recoverySha256 != null && recoverySha256.matches("[0-9a-f]{64}");
        }
    }

    public record Evidence(Operation operation, Result result, long completedAtEpochMillis, String detail) {
        static final Codec<Evidence> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Operation.CODEC.fieldOf("operation").forGetter(Evidence::operation),
                Result.CODEC.fieldOf("result").forGetter(Evidence::result),
                Codec.LONG.fieldOf("completed_at").forGetter(Evidence::completedAtEpochMillis),
                Codec.STRING.fieldOf("detail").forGetter(Evidence::detail)
        ).apply(instance, Evidence::new));

        boolean isValid() {
            return operation != null && operation.isValid() && result != null && completedAtEpochMillis >= 0
                    && detail != null && detail.length() <= 128;
        }
    }

    private static boolean validReason(String reason) {
        return reason != null && !reason.isBlank() && reason.length() <= AdministrationService.MAX_REASON_LENGTH;
    }
}
