package org.dldyou.rovenfall.administration;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import org.dldyou.rovenfall.Rovenfall;
import org.dldyou.rovenfall.mobs.MobContentReloadListener;
import org.dldyou.rovenfall.rpg.RpgDefinitionReloadListener;
import org.dldyou.rovenfall.quest.QuestDefinitionReloadListener;

/** Audited, role-gated entry point for the normal server datapack reload path. */
final class AdministrationContentReloadService {
    static final int MAX_REPORTED_PROBLEMS = 50;
    private static final int MAX_CAUSE_LENGTH = 256;
    private static final UUID ZERO_UUID = new UUID(0L, 0L);
    private static final Identifier REQUESTED = action("content_reload_requested");
    private static final Identifier COMPLETED = action("content_reload_completed");
    private static final Identifier FAILED = action("content_reload_failed");
    private static final Object LOCK = new Object();
    private static final Map<MinecraftServer, ReloadSnapshot> LAST = new WeakHashMap<>();

    private AdministrationContentReloadService() {
    }

    static Result request(
            MinecraftServer server,
            UUID actorId,
            boolean authorizationOverride,
            String reason,
            long timestampEpochMillis,
            UUID transactionId) {
        if (server == null || !server.isSameThread() || actorId == null || timestampEpochMillis < 0
                || transactionId == null || ZERO_UUID.equals(transactionId)) {
            return new Result(Status.INVALID_REQUEST, transactionId);
        }
        PlatformSavedData platform = PlatformSavedData.get(server);
        AdminRole role = platform.roleOf(actorId).orElse(null);
        if (!authorizationOverride && role != AdminRole.CONTENT_MANAGER && role != AdminRole.OWNER) {
            denied(platform, actorId, "unauthorized", timestampEpochMillis, transactionId);
            return new Result(Status.UNAUTHORIZED, transactionId);
        }
        String normalizedReason = reason == null ? "" : reason.strip();
        if (normalizedReason.isEmpty() || normalizedReason.length() > AdministrationService.MAX_REASON_LENGTH) {
            denied(platform, actorId, "invalid_reason", timestampEpochMillis, transactionId);
            return new Result(Status.INVALID_REASON, transactionId);
        }
        if (!platform.isWritable()) {
            return new Result(Status.READ_ONLY, transactionId);
        }

        AuditEntry requested = platform.auditTransaction(transactionId).orElse(null);
        AuditEntry completion = completionAudit(platform, transactionId).orElse(null);
        if (requested != null && !matches(requested, actorId, REQUESTED, normalizedReason)
                || completion != null && !matchesCompletion(completion, actorId, normalizedReason)) {
            return new Result(Status.TRANSACTION_CONFLICT, transactionId);
        }
        if (completion != null) {
            return new Result(Status.DUPLICATE, transactionId);
        }
        synchronized (LOCK) {
            ReloadSnapshot current = LAST.get(server);
            if (current != null && current.status() == Status.IN_PROGRESS) {
                return new Result(current.transactionId().equals(transactionId)
                        ? Status.IN_PROGRESS : Status.RELOAD_IN_PROGRESS, transactionId);
            }
        }

        String before = summary(server);
        if (requested == null) {
            platform.commitAudit(new AuditEntry(
                    timestampEpochMillis, actorId, REQUESTED, "all", Optional.empty(), Optional.empty(),
                    before, "validation_pending", normalizedReason, transactionId));
        }
        ReloadSnapshot inProgress = new ReloadSnapshot(
                Status.IN_PROGRESS, transactionId, timestampEpochMillis, 0L, List.of(), "validation_pending");
        synchronized (LOCK) {
            LAST.put(server, inProgress);
        }
        RpgDefinitionReloadListener.beginValidationAttempt(server);
        QuestDefinitionReloadListener.beginValidationAttempt(server);
        MobContentReloadListener.beginValidationAttempt(server);
        try {
            server.reloadResources(server.getPackRepository().getSelectedIds())
                    .whenCompleteAsync((unused, error) -> finish(
                            server, actorId, normalizedReason, timestampEpochMillis, transactionId, before, error),
                            server);
        } catch (RuntimeException exception) {
            finish(server, actorId, normalizedReason, timestampEpochMillis, transactionId, before, exception);
        }
        return new Result(Status.REQUESTED, transactionId);
    }

    static ReloadSnapshot snapshot(MinecraftServer server) {
        synchronized (LOCK) {
            return LAST.getOrDefault(server, ReloadSnapshot.idle());
        }
    }

    private static void finish(
            MinecraftServer server,
            UUID actorId,
            String reason,
            long requestedAtEpochMillis,
            UUID transactionId,
            String before,
            Throwable error) {
        List<Problem> problems = validationProblems(server);
        if (problems.isEmpty() && error != null) {
            problems = List.of(new Problem(
                    Source.SERVER,
                    Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "reload"),
                    Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "reload"),
                    safeCause(error)));
        }
        boolean succeeded = error == null && problems.isEmpty();
        long completedAt = Math.max(requestedAtEpochMillis, Instant.now().toEpochMilli());
        Status status = succeeded ? Status.SUCCESS : Status.FAILED;
        String detail = succeeded ? "completed" : "validation_failed";
        ReloadSnapshot completed = new ReloadSnapshot(
                status, transactionId, requestedAtEpochMillis, completedAt, problems, detail);
        synchronized (LOCK) {
            LAST.put(server, completed);
        }
        PlatformSavedData platform = PlatformSavedData.get(server);
        UUID completionId = completionId(transactionId);
        if (platform.auditTransaction(completionId).isEmpty() && platform.isWritable()) {
            platform.commitAudit(new AuditEntry(
                    completedAt, actorId, succeeded ? COMPLETED : FAILED, "all", Optional.empty(), Optional.empty(),
                    before, succeeded ? summary(server) : "rejected;problems=" + problems.size(),
                    reason, completionId));
        }
    }

    private static List<Problem> validationProblems(MinecraftServer server) {
        List<Problem> result = new ArrayList<>();
        for (var problem : RpgDefinitionReloadListener.lastProblems(server)) {
            if (result.size() == MAX_REPORTED_PROBLEMS) {
                break;
            }
            result.add(new Problem(Source.RPG, problem.file(), problem.definitionId(), safeCause(problem.cause())));
        }
        for (var problem : QuestDefinitionReloadListener.lastProblems(server)) {
            if (result.size() == MAX_REPORTED_PROBLEMS) {
                break;
            }
            result.add(new Problem(Source.QUEST, problem.file(), problem.definitionId(), safeCause(problem.cause())));
        }
        for (var problem : MobContentReloadListener.lastProblems(server)) {
            if (result.size() == MAX_REPORTED_PROBLEMS) {
                break;
            }
            result.add(new Problem(Source.MOB, problem.file(), problem.definitionId(), safeCause(problem.cause())));
        }
        return List.copyOf(result);
    }

    private static Optional<AuditEntry> completionAudit(PlatformSavedData platform, UUID transactionId) {
        return platform.auditTransaction(completionId(transactionId));
    }

    private static boolean matches(AuditEntry entry, UUID actorId, Identifier action, String reason) {
        return entry.actorId().equals(actorId) && entry.actionType().equals(action)
                && entry.target().equals("all") && entry.reason().equals(reason);
    }

    private static boolean matchesCompletion(AuditEntry entry, UUID actorId, String reason) {
        return entry.actorId().equals(actorId)
                && (entry.actionType().equals(COMPLETED) || entry.actionType().equals(FAILED))
                && entry.target().equals("all") && entry.reason().equals(reason);
    }

    private static String summary(MinecraftServer server) {
        var rpg = RpgDefinitionReloadListener.snapshot(server);
        var quests = QuestDefinitionReloadListener.snapshot(server);
        var mobs = MobContentReloadListener.snapshot(server);
        return "rpg_revision=" + RpgDefinitionReloadListener.revision(server)
                + ";activities=" + rpg.activities().size()
                + ";careers=" + rpg.careers().size()
                + ";skills=" + rpg.skills().size()
                + ";quest_revision=" + QuestDefinitionReloadListener.revision(server)
                + ";quests=" + quests.storyQuests().size()
                + ";contracts=" + quests.contractCount()
                + ";mob_content=" + mobs.size();
    }

    private static UUID completionId(UUID transactionId) {
        return UUID.nameUUIDFromBytes(("content-reload-completion:" + transactionId)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static void denied(
            PlatformSavedData platform,
            UUID actorId,
            String reason,
            long timestampEpochMillis,
            UUID transactionId) {
        if (platform != null && platform.isWritable() && actorId != null) {
            platform.appendDeniedAudit(new AuditEntry(
                    timestampEpochMillis, actorId, action("content_reload_denied"), "all",
                    Optional.empty(), Optional.empty(), "unchanged", "unchanged", reason,
                    transactionId == null || ZERO_UUID.equals(transactionId) ? UUID.randomUUID() : transactionId),
                    1_000L);
        }
    }

    private static String safeCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return safeCause(current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage());
    }

    private static String safeCause(String cause) {
        String value = cause == null ? "unknown" : cause.replace('\r', ' ').replace('\n', ' ').strip();
        if (value.isEmpty()) {
            return "unknown";
        }
        return value.length() <= MAX_CAUSE_LENGTH ? value : value.substring(0, MAX_CAUSE_LENGTH);
    }

    private static Identifier action(String path) {
        return Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, path);
    }

    enum Source {
        RPG,
        QUEST,
        MOB,
        SERVER
    }

    enum Status {
        IDLE,
        REQUESTED,
        IN_PROGRESS,
        SUCCESS,
        FAILED,
        DUPLICATE,
        RELOAD_IN_PROGRESS,
        UNAUTHORIZED,
        INVALID_REQUEST,
        INVALID_REASON,
        READ_ONLY,
        TRANSACTION_CONFLICT
    }

    record Problem(Source source, Identifier file, Identifier definitionId, String cause) {
    }

    record ReloadSnapshot(
            Status status,
            UUID transactionId,
            long requestedAtEpochMillis,
            long completedAtEpochMillis,
            List<Problem> problems,
            String detail) {
        ReloadSnapshot {
            problems = List.copyOf(problems);
        }

        static ReloadSnapshot idle() {
            return new ReloadSnapshot(Status.IDLE, ZERO_UUID, 0L, 0L, List.of(), "idle");
        }
    }

    record Result(Status status, UUID transactionId) {
    }
}
