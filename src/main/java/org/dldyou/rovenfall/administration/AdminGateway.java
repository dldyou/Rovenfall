package org.dldyou.rovenfall.administration;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigInteger;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.dldyou.rovenfall.Rovenfall;
import org.dldyou.rovenfall.claims.Claim;
import org.dldyou.rovenfall.claims.ClaimKey;
import org.dldyou.rovenfall.economy.ShopInstance;

/** Server-thread adapter between the loopback HTTP bridge and administration domain services. */
final class AdminGateway {
    static final int DEFAULT_PAGE_SIZE = 25;
    static final long ACTION_PREVIEW_TTL_MILLIS = 120_000L;
    static final int MAX_ACTION_PREVIEWS = 128;
    private static final long RECENT_WINDOW_MILLIS = 86_400_000L;
    private static final Map<UUID, PreparedAction> ACTION_PREVIEWS = new LinkedHashMap<>();

    private AdminGateway() {
    }

    static Response handle(
            MinecraftServer server,
            String method,
            String path,
            Map<String, String> query,
            JsonObject body) {
        if (server == null || !server.isSameThread()) {
            return error(503, "SERVER_UNAVAILABLE", "The Minecraft server is unavailable.");
        }
        PlatformSavedData state = PlatformSavedData.get(server);
        try {
            if ("GET".equals(method) && "/api/v1/dashboard".equals(path)) {
                return ok(dashboard(state, onlineIds(server), System.currentTimeMillis()));
            }
            if ("GET".equals(method) && "/api/v1/search".equals(path)) {
                return search(state, query);
            }
            if ("GET".equals(method) && "/api/v1/audit".equals(path)) {
                return audit(state, query);
            }
            if ("GET".equals(method) && path.startsWith("/api/v1/players/")) {
                String rawId = path.substring("/api/v1/players/".length());
                return player(state, server, parseUuid(rawId, "playerId"));
            }
            if ("POST".equals(method) && "/api/v1/actions/preview".equals(path)) {
                return previewAction(state, body, playerId -> server.getPlayerList().getPlayer(playerId));
            }
            if ("POST".equals(method) && "/api/v1/actions/confirm".equals(path)) {
                return confirmAction(state, body, playerId -> server.getPlayerList().getPlayer(playerId));
            }
        } catch (BadRequest exception) {
            return error(400, exception.code, exception.getMessage());
        } catch (RuntimeException exception) {
            return error(500, "INTERNAL_ERROR", "The operation could not be completed.");
        }
        return error(404, "NOT_FOUND", "The requested API route does not exist.");
    }

    static Map<String, Object> dashboard(PlatformSavedData state, Set<UUID> onlinePlayers, long now) {
        long cutoff = Math.max(0L, now - RECENT_WINDOW_MILLIS);
        List<EconomyTransactionReceipt> recentTransactions = state.economyReceiptsView().values().stream()
                .filter(receipt -> receipt.timestampEpochMillis() >= cutoff)
                .toList();
        BigInteger recentVolume = recentTransactions.stream()
                .map(receipt -> BigInteger.valueOf(receipt.amount()))
                .reduce(BigInteger.ZERO, BigInteger::add);
        long recentDenied = state.auditEntriesView().stream()
                .filter(entry -> entry.timestampEpochMillis() >= cutoff)
                .filter(entry -> outcome(entry).equals("denied"))
                .count();
        long recentAlerts = state.economyAlertsView().stream()
                .filter(alert -> alert.timestampEpochMillis() >= cutoff)
                .count();
        int playerCount = AdminSearchService.search(
                state, AdministrationService.SYSTEM_ACTOR, true,
                AdminSearchService.Scope.PLAYERS, "*", 0, 1).totalEntries();
        List<Map<String, Object>> recentAudit = state.auditEntriesView().stream()
                .sorted(Comparator.comparingLong(AuditEntry::timestampEpochMillis).reversed()
                        .thenComparing(AuditEntry::transactionId))
                .limit(10)
                .map(entry -> auditRow(state, entry))
                .toList();

        return map(
                "generatedAt", now,
                "serverTimeZone", ZoneId.systemDefault().getId(),
                "writable", state.isWritable(),
                "onlinePlayers", onlinePlayers == null ? 0 : onlinePlayers.size(),
                "knownPlayers", playerCount,
                "recentTransactions", recentTransactions.size(),
                "recentVolume", recentVolume.toString(),
                "claims", state.claimsView().size(),
                "shops", state.shopInstancesView().size(),
                "recentAlerts", recentAlerts,
                "recentDenied", recentDenied,
                "recentAudit", recentAudit);
    }

    private static Response search(PlatformSavedData state, Map<String, String> query) {
        AdminSearchService.Scope scope = AdminSearchService.Scope.parse(query.get("scope"))
                .orElseThrow(() -> new BadRequest("INVALID_SCOPE", "Unknown search scope."));
        String text = query.getOrDefault("query", "*");
        int page = parsePage(query.get("page"), 0, "page");
        int pageSize = parsePage(query.get("pageSize"), DEFAULT_PAGE_SIZE, "pageSize");
        AdminSearchService.Page result = AdminSearchService.search(
                state, AdministrationService.SYSTEM_ACTOR, true, scope, text, page, pageSize);
        if (result.status() != AdminSearchService.Status.SUCCESS) {
            throw new BadRequest(result.status().name(), "Search parameters are invalid.");
        }
        List<Map<String, Object>> entries = result.entries().stream()
                .map(row -> searchRow(state, row))
                .toList();
        return ok(map(
                "scope", scope.id(),
                "query", result.query(),
                "page", result.page(),
                "pageSize", pageSize,
                "totalPages", result.totalPages(),
                "totalEntries", result.totalEntries(),
                "entries", entries));
    }

    private static Response audit(PlatformSavedData state, Map<String, String> query) {
        String text = normalizedFilter(query.get("query"));
        String playerFilter = normalizedFilter(query.get("player"));
        String actionFilter = normalizedFilter(query.get("action"));
        String outcomeFilter = normalizedFilter(query.get("outcome"));
        long from = parseLong(query.get("from"), 0L, "from");
        long to = parseLong(query.get("to"), Long.MAX_VALUE, "to");
        if (from < 0 || to < from) {
            throw new BadRequest("INVALID_TIME_RANGE", "Audit time range is invalid.");
        }
        int page = parsePage(query.get("page"), 0, "page");
        int pageSize = parsePage(query.get("pageSize"), DEFAULT_PAGE_SIZE, "pageSize");
        if (!outcomeFilter.isEmpty() && !Set.of("success", "denied", "failed", "no_change")
                .contains(outcomeFilter)) {
            throw new BadRequest("INVALID_OUTCOME", "Unknown audit outcome.");
        }

        List<AuditEntry> matching = state.auditEntriesView().stream()
                .filter(entry -> entry.timestampEpochMillis() >= from && entry.timestampEpochMillis() <= to)
                .filter(entry -> actionFilter.isEmpty()
                        || entry.actionType().toString().toLowerCase(Locale.ROOT).contains(actionFilter))
                .filter(entry -> outcomeFilter.isEmpty() || outcome(entry).equals(outcomeFilter))
                .filter(entry -> playerFilter.isEmpty() || auditPlayerText(state, entry).contains(playerFilter))
                .filter(entry -> text.isEmpty() || auditText(state, entry).contains(text))
                .sorted(Comparator.comparingLong(AuditEntry::timestampEpochMillis).reversed()
                        .thenComparing(AuditEntry::transactionId))
                .toList();
        int total = matching.size();
        int totalPages = total == 0 ? 0 : (total + pageSize - 1) / pageSize;
        long offset = (long) page * pageSize;
        List<Map<String, Object>> entries = offset >= total
                ? List.of()
                : matching.subList((int) offset, Math.min(total, (int) offset + pageSize)).stream()
                        .map(entry -> auditRow(state, entry))
                        .toList();
        return ok(map(
                "page", page,
                "pageSize", pageSize,
                "totalPages", totalPages,
                "totalEntries", total,
                "entries", entries));
    }

    private static Response player(PlatformSavedData state, MinecraftServer server, UUID playerId) {
        PlayerRecord record = state.playerRecord(playerId).orElse(null);
        boolean known = record != null || state.economyBalance(playerId).isPresent()
                || state.roleOf(playerId).isPresent() || state.claimCount(playerId) > 0;
        if (!known) {
            return error(404, "PLAYER_NOT_FOUND", "No server record exists for this UUID.");
        }
        List<Map<String, Object>> transactions = state.economyReceiptsView().entrySet().stream()
                .filter(entry -> entry.getValue().playerId().equals(playerId))
                .sorted(Comparator.<Map.Entry<UUID, EconomyTransactionReceipt>>comparingLong(
                                entry -> entry.getValue().timestampEpochMillis()).reversed())
                .limit(10)
                .map(entry -> transactionRow(state, entry.getKey(), entry.getValue()))
                .toList();
        List<Map<String, Object>> claims = state.claimsView().entrySet().stream()
                .filter(entry -> entry.getValue().ownerId().equals(playerId))
                .sorted(Comparator.comparing(entry -> entry.getKey().auditTarget()))
                .limit(20)
                .map(entry -> claimRow(state, entry.getKey(), entry.getValue()))
                .toList();
        List<Map<String, Object>> audit = state.auditEntriesView().stream()
                .filter(entry -> auditPlayerText(state, entry).contains(playerId.toString()))
                .sorted(Comparator.comparingLong(AuditEntry::timestampEpochMillis).reversed())
                .limit(10)
                .map(entry -> auditRow(state, entry))
                .toList();
        return ok(map(
                "playerId", playerId.toString(),
                "name", playerName(state, playerId),
                "online", server.getPlayerList().getPlayer(playerId) != null,
                "role", state.roleOf(playerId).map(AdminRole::getSerializedName).orElse("none"),
                "firstSeen", record == null ? 0L : record.firstSeenEpochMillis(),
                "lastSeen", record == null ? 0L : record.lastSeenEpochMillis(),
                "balance", Long.toString(state.economyBalance(playerId).orElse(0L)),
                "activityExperience", Long.toString(state.activityProgress(playerId).experience().values().stream()
                        .mapToLong(Long::longValue).sum()),
                "activeCareer", state.activeCareer(playerId).map(Identifier::toString).orElse(""),
                "learnedCareers", state.playerCareerState(playerId).learnedCareers().size(),
                "claimCount", state.claimCount(playerId),
                "transactions", transactions,
                "claims", claims,
                "audit", audit));
    }

    static Response action(
            PlatformSavedData state,
            JsonObject body,
            Function<UUID, ServerPlayer> onlinePlayerLookup) {
        if (body == null) {
            throw new BadRequest("INVALID_BODY", "A JSON request body is required.");
        }
        String type = requiredString(body, "type").toLowerCase(Locale.ROOT);
        String reason = requiredString(body, "reason");
        if (reason.length() > AdministrationService.MAX_REASON_LENGTH) {
            throw new BadRequest("INVALID_REASON", "reason is too long.");
        }
        UUID transactionId = parseUuid(requiredString(body, "transactionId"), "transactionId");
        long timestamp = System.currentTimeMillis();

        return switch (type) {
            case "set_role" -> {
                UUID targetId = parseUuid(requiredString(body, "playerId"), "playerId");
                String role = requiredString(body, "role").toLowerCase(Locale.ROOT);
                AdministrationService.RoleChangeResult result = AdministrationService.changeRole(
                        state, AdministrationService.SYSTEM_ACTOR, true, targetId, role, reason,
                        timestamp, transactionId);
                boolean successful = result.status() == AdministrationService.RoleChangeStatus.SUCCESS
                        || result.status() == AdministrationService.RoleChangeStatus.NO_CHANGE;
                yield operationResponse(successful, result.status().name(), transactionId,
                        map("playerId", targetId.toString(), "role", role,
                                "auditRecorded", result.auditRecorded()));
            }
            case "grant_balance", "debit_balance" -> {
                UUID targetId = parseUuid(requiredString(body, "playerId"), "playerId");
                long amount = parsePositiveLong(requiredString(body, "amount"), "amount");
                EconomyService.TransactionResult result = type.equals("grant_balance")
                        ? EconomyService.adminGrant(
                                state, AdministrationService.SYSTEM_ACTOR, true, targetId, amount, reason,
                                timestamp, transactionId, initialBalance(), maximumBalance())
                        : EconomyService.adminDebit(
                                state, AdministrationService.SYSTEM_ACTOR, true, targetId, amount, reason,
                                timestamp, transactionId, initialBalance(), maximumBalance());
                boolean successful = result.status() == EconomyService.TransactionStatus.SUCCESS
                        || result.status() == EconomyService.TransactionStatus.DUPLICATE_TRANSACTION;
                yield operationResponse(successful, result.status().name(), transactionId,
                        map("playerId", targetId.toString(),
                                "beforeBalance", Long.toString(result.beforeBalance()),
                                "balance", Long.toString(result.balance()),
                                "auditRecorded", result.auditRecorded()));
            }
            case "reverse" -> reverse(state, body, onlinePlayerLookup, reason, timestamp, transactionId);
            default -> throw new BadRequest("INVALID_ACTION", "Unknown administration action.");
        };
    }

    static Response previewAction(
            PlatformSavedData state,
            JsonObject body,
            Function<UUID, ServerPlayer> onlinePlayerLookup) {
        long now = System.currentTimeMillis();
        UUID transactionId = UUID.randomUUID();
        JsonObject preparedBody = prepareActionBody(body, transactionId);
        ActionPreview snapshot = describeAction(state, preparedBody, onlinePlayerLookup);
        purgeActionPreviews(now);
        while (ACTION_PREVIEWS.size() >= MAX_ACTION_PREVIEWS) {
            ACTION_PREVIEWS.remove(ACTION_PREVIEWS.keySet().iterator().next());
        }
        UUID previewId = UUID.randomUUID();
        long expiresAt = now + ACTION_PREVIEW_TTL_MILLIS;
        ACTION_PREVIEWS.put(previewId, new PreparedAction(
                transactionId, expiresAt, preparedBody, snapshot.stateKey(), snapshot.target(),
                snapshot.typedConfirmationRequired()));
        return ok(map(
                "ok", true,
                "previewId", previewId.toString(),
                "transactionId", transactionId.toString(),
                "expiresAt", expiresAt,
                "requiresTypedConfirmation", snapshot.typedConfirmationRequired(),
                "details", snapshot.details()));
    }

    static Response confirmAction(
            PlatformSavedData state,
            JsonObject body,
            Function<UUID, ServerPlayer> onlinePlayerLookup) {
        if (body == null) {
            throw new BadRequest("INVALID_BODY", "A JSON request body is required.");
        }
        UUID previewId = parseUuid(requiredString(body, "previewId"), "previewId");
        PreparedAction prepared = ACTION_PREVIEWS.get(previewId);
        if (prepared == null) {
            return error(409, "PREVIEW_NOT_FOUND", "The action preview no longer exists.");
        }
        long now = System.currentTimeMillis();
        if (prepared.expiresAtEpochMillis() < now) {
            ACTION_PREVIEWS.remove(previewId);
            return rejectedPreview(state, prepared, "PREVIEW_EXPIRED", "expired_preview", now);
        }
        if (prepared.typedConfirmationRequired()
                && !optionalString(body, "confirmation").orElse("").equalsIgnoreCase("execute")) {
            return error(400, "CONFIRMATION_REQUIRED", "Type EXECUTE to confirm this action.");
        }

        ACTION_PREVIEWS.remove(previewId);
        ActionPreview current;
        try {
            current = describeAction(state, prepared.body(), onlinePlayerLookup);
        } catch (BadRequest exception) {
            return rejectedPreview(state, prepared, "STALE_PREVIEW", "invalidated_preview", now);
        }
        if (!Objects.equals(current.stateKey(), prepared.stateKey())) {
            return rejectedPreview(state, prepared, "STALE_PREVIEW", "changed_after_preview", now);
        }
        return action(state, prepared.body(), onlinePlayerLookup);
    }

    static void clearActionPreviews() {
        ACTION_PREVIEWS.clear();
    }

    private static JsonObject prepareActionBody(JsonObject body, UUID transactionId) {
        if (body == null) {
            throw new BadRequest("INVALID_BODY", "A JSON request body is required.");
        }
        JsonObject prepared = body.deepCopy();
        String type = requiredString(prepared, "type").toLowerCase(Locale.ROOT);
        String reason = requiredString(prepared, "reason");
        if (reason.length() > AdministrationService.MAX_REASON_LENGTH) {
            throw new BadRequest("INVALID_REASON", "reason is too long.");
        }
        if (!Set.of("set_role", "grant_balance", "debit_balance", "reverse").contains(type)) {
            throw new BadRequest("INVALID_ACTION", "Unknown administration action.");
        }
        prepared.addProperty("type", type);
        prepared.addProperty("reason", reason);
        prepared.addProperty("transactionId", transactionId.toString());
        return prepared;
    }

    private static ActionPreview describeAction(
            PlatformSavedData state,
            JsonObject body,
            Function<UUID, ServerPlayer> onlinePlayerLookup) {
        String type = requiredString(body, "type").toLowerCase(Locale.ROOT);
        String reason = requiredString(body, "reason");
        return switch (type) {
            case "set_role" -> {
                UUID playerId = parseUuid(requiredString(body, "playerId"), "playerId");
                String role = requiredString(body, "role").toLowerCase(Locale.ROOT);
                if (AdminRole.parse(role).isEmpty()) {
                    throw new BadRequest("INVALID_ROLE", "Unknown administration role.");
                }
                String before = state.roleOf(playerId).map(AdminRole::getSerializedName).orElse("none");
                yield new ActionPreview(
                        new ActionState(type, playerId, before, false),
                        "player:" + playerId,
                        map("type", type, "playerId", playerId.toString(), "playerName", playerName(state, playerId),
                                "beforeValue", before, "afterValue", role, "reason", reason,
                                "actorRole", AdminRole.OWNER.getSerializedName(), "onlineRequired", false),
                        true);
            }
            case "grant_balance", "debit_balance" -> {
                UUID playerId = parseUuid(requiredString(body, "playerId"), "playerId");
                long amount = parsePositiveLong(requiredString(body, "amount"), "amount");
                long before = state.economyBalance(playerId).orElse(initialBalance());
                BigInteger after = BigInteger.valueOf(before).add(BigInteger.valueOf(
                        type.equals("grant_balance") ? amount : -amount));
                yield new ActionPreview(
                        new ActionState(type, playerId, state.economyBalance(playerId), false),
                        "player:" + playerId,
                        map("type", type, "playerId", playerId.toString(), "playerName", playerName(state, playerId),
                                "amount", Long.toString(amount), "beforeValue", Long.toString(before),
                                "afterValue", after.toString(), "reason", reason,
                                "actorRole", AdminRole.OWNER.getSerializedName(), "onlineRequired", false),
                        type.equals("debit_balance"));
            }
            case "reverse" -> describeReversal(state, body, onlinePlayerLookup, reason);
            default -> throw new BadRequest("INVALID_ACTION", "Unknown administration action.");
        };
    }

    private static ActionPreview describeReversal(
            PlatformSavedData state,
            JsonObject body,
            Function<UUID, ServerPlayer> onlinePlayerLookup,
            String reason) {
        UUID originalId = parseUuid(requiredString(body, "originalTransactionId"), "originalTransactionId");
        String compensation = optionalString(body, "compensation").orElse("none");
        if (!Set.of("none", "refund_without_items_or_stock").contains(compensation)) {
            throw new BadRequest("INVALID_COMPENSATION", "Unknown compensation decision.");
        }
        EconomyTransactionReceipt receipt = state.economyReceipt(originalId).orElse(null);
        if (receipt != null) {
            boolean online = onlinePlayerLookup != null && onlinePlayerLookup.apply(receipt.playerId()) != null;
            ReverseState key = new ReverseState(receipt, state.economyBalance(receipt.playerId()), online);
            return new ActionPreview(
                    new ActionState("reverse", receipt.playerId(), key, online),
                    "transaction:" + originalId,
                    map("type", "reverse", "playerId", receipt.playerId().toString(),
                            "playerName", playerName(state, receipt.playerId()),
                            "originalTransactionId", originalId.toString(),
                            "transactionKind", receipt.kind().getSerializedName(),
                            "amount", Long.toString(receipt.amount()), "beforeValue", "active",
                            "afterValue", "reversed", "compensation", compensation, "reason", reason,
                            "actorRole", AdminRole.OWNER.getSerializedName(),
                            "onlineRequired", true, "online", online),
                    true);
        }
        Object evidence = state.claimReversalEvidence(originalId).<Object>map(value -> value)
                .or(() -> state.shopReversalEvidence(originalId).map(value -> (Object) value))
                .or(() -> state.careerReversalEvidence(originalId).map(value -> (Object) value))
                .orElseThrow(() -> new BadRequest("INVALID_TRANSACTION", "No reversible transaction exists."));
        String domain = evidence instanceof TargetedReversalState.ClaimEvidence value
                ? value.domain().getSerializedName()
                : evidence instanceof TargetedReversalState.ShopEvidence ? "shop"
                : ((TargetedReversalState.CareerEvidence) evidence).domain().getSerializedName();
        return new ActionPreview(
                new ActionState("reverse", originalId, evidence, false),
                "transaction:" + originalId,
                map("type", "reverse", "originalTransactionId", originalId.toString(), "domain", domain,
                        "beforeValue", "active", "afterValue", "reversed", "compensation", compensation,
                        "reason", reason, "actorRole", AdminRole.OWNER.getSerializedName(),
                        "onlineRequired", false),
                true);
    }

    private static void purgeActionPreviews(long now) {
        ACTION_PREVIEWS.entrySet().removeIf(entry -> entry.getValue().expiresAtEpochMillis() < now);
    }

    private static Response rejectedPreview(
            PlatformSavedData state,
            PreparedAction prepared,
            String status,
            String cause,
            long timestamp) {
        String reason = optionalString(prepared.body(), "reason").orElse("administration action");
        boolean audited = state.appendDeniedAudit(new AuditEntry(
                timestamp,
                AdministrationService.SYSTEM_ACTOR,
                Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "admin_web_preview_denied"),
                prepared.target(),
                Optional.empty(),
                Optional.empty(),
                "unchanged",
                "unchanged",
                cause + ": " + reason,
                prepared.transactionId()), 1_000L);
        return operationResponse(false, status, prepared.transactionId(), map("auditRecorded", audited));
    }

    private static Response reverse(
            PlatformSavedData state,
            JsonObject body,
            Function<UUID, ServerPlayer> onlinePlayerLookup,
            String reason,
            long timestamp,
            UUID reversalId) {
        UUID originalId = parseUuid(requiredString(body, "originalTransactionId"), "originalTransactionId");
        EconomyTransactionReceipt receipt = state.economyReceipt(originalId).orElse(null);
        if (receipt != null) {
            ServerPlayer player = onlinePlayerLookup == null ? null : onlinePlayerLookup.apply(receipt.playerId());
            if (player == null) {
                boolean audited = state.appendDeniedAudit(new AuditEntry(
                        timestamp,
                        AdministrationService.SYSTEM_ACTOR,
                        Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "admin_web_reversal_denied"),
                        "transaction:" + originalId,
                        Optional.empty(),
                        Optional.empty(),
                        "unchanged",
                        "unchanged",
                        "target_offline: " + reason,
                        reversalId), 1_000L);
                return operationResponse(false, "TARGET_OFFLINE", reversalId,
                        map("playerId", receipt.playerId().toString(), "auditRecorded", audited));
            }
            String decisionId = optionalString(body, "compensation").orElse("none");
            EconomyTransactionReceipt.CompensationDecision decision = switch (decisionId) {
                case "none" -> EconomyTransactionReceipt.CompensationDecision.NONE;
                case "refund_without_items_or_stock" ->
                        EconomyTransactionReceipt.CompensationDecision.REFUND_WITHOUT_ITEMS_OR_STOCK;
                default -> throw new BadRequest("INVALID_COMPENSATION", "Unknown compensation decision.");
            };
            EconomyReversalService.Result result = EconomyReversalService.reverse(
                    state, player, AdministrationService.SYSTEM_ACTOR, true, originalId, decision,
                    reason, timestamp, reversalId);
            boolean successful = result.status() == EconomyReversalService.Status.SUCCESS
                    || result.status() == EconomyReversalService.Status.DUPLICATE_TRANSACTION;
            return operationResponse(successful, result.status().name(), reversalId,
                    map("playerId", receipt.playerId().toString(), "auditRecorded", result.auditRecorded()));
        }

        TargetedReversalService.Result result = TargetedReversalService.reverse(
                state, AdministrationService.SYSTEM_ACTOR, true, originalId, reason, timestamp, reversalId);
        boolean successful = result.status() == TargetedReversalService.Status.SUCCESS
                || result.status() == TargetedReversalService.Status.DUPLICATE_TRANSACTION;
        return operationResponse(successful, result.status().name(), reversalId,
                map("domain", result.domain().map(value -> value.getSerializedName()).orElse(""),
                        "auditRecorded", result.auditRecorded()));
    }

    private static Response operationResponse(
            boolean successful, String status, UUID transactionId, Map<String, Object> details) {
        Map<String, Object> body = map(
                "ok", successful,
                "status", status.toLowerCase(Locale.ROOT),
                "transactionId", transactionId.toString(),
                "details", details);
        if (!successful) {
            body.put("error", map(
                    "code", status,
                    "message", "The administration operation was rejected."));
        }
        return new Response(successful ? 200 : 409, body);
    }

    private static Map<String, Object> searchRow(PlatformSavedData state, AdminSearchService.Row row) {
        if (row instanceof AdminSearchService.PlayerRow value) {
            return map(
                    "type", "player",
                    "playerId", value.playerId().toString(),
                    "name", playerName(state, value.playerId()),
                    "role", value.adminRole().map(AdminRole::getSerializedName).orElse("none"),
                    "firstSeen", value.record().map(PlayerRecord::firstSeenEpochMillis).orElse(0L),
                    "lastSeen", value.record().map(PlayerRecord::lastSeenEpochMillis).orElse(0L),
                    "balance", value.balance().map(String::valueOf).orElse("0"),
                    "activityExperience", Long.toString(value.totalActivityExperience()),
                    "activeCareer", value.activeCareer().map(Identifier::toString).orElse(""),
                    "learnedCareers", value.learnedCareers(),
                    "claims", value.claims());
        }
        if (row instanceof AdminSearchService.BalanceRow value) {
            return map("type", "balance", "playerId", value.playerId().toString(),
                    "name", playerName(state, value.playerId()), "balance", Long.toString(value.balance()));
        }
        if (row instanceof AdminSearchService.TransactionRow value) {
            return transactionRow(state, value.transactionId(), value.receipt());
        }
        if (row instanceof AdminSearchService.ClaimRow value) {
            return claimRow(state, value.key(), value.claim());
        }
        if (row instanceof AdminSearchService.ShopRow value) {
            return shopRow(value.shopId(), value.shop());
        }
        if (row instanceof AdminSearchService.DeniedRow value) {
            return auditRow(state, value.entry());
        }
        if (row instanceof AdminSearchService.AlertRow value) {
            EconomyAlert alert = value.alert();
            return map(
                    "type", "alert",
                    "timestamp", alert.timestampEpochMillis(),
                    "playerId", alert.playerId().toString(),
                    "playerName", playerName(state, alert.playerId()),
                    "transactionId", alert.transactionId().toString(),
                    "alertType", alert.type().getSerializedName(),
                    "observedValue", Long.toString(alert.observedValue()),
                    "threshold", Long.toString(alert.threshold()));
        }
        throw new IllegalStateException("Unsupported administrator search row");
    }

    private static Map<String, Object> transactionRow(
            PlatformSavedData state, UUID id, EconomyTransactionReceipt receipt) {
        String item = receipt.item()
                .map(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())
                .orElse("");
        return map(
                "type", "transaction",
                "transactionId", id.toString(),
                "timestamp", receipt.timestampEpochMillis(),
                "actorId", receipt.actorId().toString(),
                "actorName", playerName(state, receipt.actorId()),
                "playerId", receipt.playerId().toString(),
                "playerName", playerName(state, receipt.playerId()),
                "kind", receipt.kind().getSerializedName(),
                "amount", Long.toString(receipt.amount()),
                "claim", receipt.claim().map(ClaimKey::auditTarget).orElse(""),
                "shopId", receipt.shopId().map(Identifier::toString).orElse(""),
                "offerId", receipt.offerId().map(Identifier::toString).orElse(""),
                "item", item,
                "quantity", receipt.quantity(),
                "originalTransactionId", receipt.originalTransactionId().map(UUID::toString).orElse(""),
                "reversedBy", receipt.reversedBy().map(UUID::toString).orElse(""),
                "invalidatedByRestore", receipt.invalidatedByRestore().map(UUID::toString).orElse(""),
                "compensation", receipt.compensationDecision().getSerializedName());
    }

    private static Map<String, Object> claimRow(PlatformSavedData state, ClaimKey key, Claim claim) {
        return map(
                "type", "claim",
                "key", key.auditTarget(),
                "dimension", key.dimension().identifier().toString(),
                "chunkX", key.chunkX(),
                "chunkZ", key.chunkZ(),
                "ownerId", claim.ownerId().toString(),
                "ownerName", playerName(state, claim.ownerId()),
                "purchasePrice", Long.toString(claim.purchasePrice()),
                "trustedPlayers", claim.trustedRoles().size(),
                "pendingTransferTo", claim.pendingTransferTo().map(UUID::toString).orElse(""));
    }

    private static Map<String, Object> shopRow(Identifier id, ShopInstance shop) {
        ShopInstance.Binding binding = shop.binding().orElse(null);
        return map(
                "type", "shop",
                "shopId", id.toString(),
                "templateId", shop.templateId().toString(),
                "dimension", binding == null ? "" : binding.dimension().identifier().toString(),
                "position", binding == null ? "" : binding.position().toShortString(),
                "maxDistance", shop.accessPolicy().maxDistance(),
                "offers", shop.offers().size());
    }

    private static Map<String, Object> auditRow(PlatformSavedData state, AuditEntry entry) {
        return map(
                "type", "audit",
                "timestamp", entry.timestampEpochMillis(),
                "actorId", entry.actorId().toString(),
                "actorName", playerName(state, entry.actorId()),
                "action", entry.actionType().toString(),
                "target", entry.target(),
                "dimension", entry.dimension().map(Identifier::toString).orElse(""),
                "position", entry.position().map(value -> value.toShortString()).orElse(""),
                "before", entry.beforeValue(),
                "after", entry.afterValue(),
                "reason", entry.reason(),
                "transactionId", entry.transactionId().toString(),
                "outcome", outcome(entry));
    }

    private static String auditText(PlatformSavedData state, AuditEntry entry) {
        return String.join("\n",
                Long.toString(entry.timestampEpochMillis()),
                entry.actionType().toString(),
                entry.target(),
                entry.actorId().toString(),
                playerName(state, entry.actorId()),
                entry.transactionId().toString(),
                entry.reason(),
                entry.beforeValue(),
                entry.afterValue(),
                entry.dimension().map(Identifier::toString).orElse(""),
                entry.position().map(value -> value.toShortString()).orElse(""))
                .toLowerCase(Locale.ROOT);
    }

    private static String auditPlayerText(PlatformSavedData state, AuditEntry entry) {
        List<String> values = new ArrayList<>();
        values.add(entry.actorId().toString());
        values.add(playerName(state, entry.actorId()));
        values.add(entry.target());
        values.add(entry.beforeValue());
        values.add(entry.afterValue());
        EconomyTransactionReceipt receipt = state.economyReceipt(entry.transactionId()).orElse(null);
        if (receipt != null) {
            values.add(receipt.playerId().toString());
            values.add(playerName(state, receipt.playerId()));
        }
        return String.join("\n", values).toLowerCase(Locale.ROOT);
    }

    private static String outcome(AuditEntry entry) {
        String action = entry.actionType().getPath().toLowerCase(Locale.ROOT);
        if (action.contains("denied")) {
            return "denied";
        }
        if (action.contains("failed")) {
            return "failed";
        }
        if (action.contains("no_change")) {
            return "no_change";
        }
        return "success";
    }

    private static String playerName(PlatformSavedData state, UUID playerId) {
        if (AdministrationService.SYSTEM_ACTOR.equals(playerId)) {
            return "SYSTEM";
        }
        return state.playerRecord(playerId).map(PlayerRecord::lastKnownName).filter(value -> !value.isBlank())
                .orElse("");
    }

    private static Set<UUID> onlineIds(MinecraftServer server) {
        TreeSet<UUID> ids = new TreeSet<>();
        server.getPlayerList().getPlayers().forEach(player -> ids.add(player.getUUID()));
        return Set.copyOf(ids);
    }

    private static int parsePage(String value, int defaultValue, String field) {
        long parsed = parseLong(value, defaultValue, field);
        int maximum = field.equals("pageSize") ? AdminSearchService.MAX_PAGE_SIZE : Integer.MAX_VALUE;
        int minimum = field.equals("pageSize") ? 1 : 0;
        if (parsed < minimum || parsed > maximum) {
            throw new BadRequest("INVALID_PAGE", field + " is outside the supported range.");
        }
        return (int) parsed;
    }

    private static long parseLong(String value, long defaultValue, String field) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new BadRequest("INVALID_NUMBER", field + " must be an integer.");
        }
    }

    private static long parsePositiveLong(String value, String field) {
        long parsed = parseLong(value, -1L, field);
        if (parsed < 1) {
            throw new BadRequest("INVALID_AMOUNT", field + " must be positive.");
        }
        return parsed;
    }

    private static UUID parseUuid(String value, String field) {
        try {
            UUID parsed = UUID.fromString(value);
            if (AdministrationService.SYSTEM_ACTOR.equals(parsed)) {
                throw new IllegalArgumentException("zero UUID");
            }
            return parsed;
        } catch (IllegalArgumentException exception) {
            throw new BadRequest("INVALID_UUID", field + " must be a non-zero UUID.");
        }
    }

    private static String requiredString(JsonObject body, String field) {
        return optionalString(body, field)
                .orElseThrow(() -> new BadRequest("MISSING_FIELD", field + " is required."));
    }

    private static Optional<String> optionalString(JsonObject body, String field) {
        JsonElement value = body.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            return Optional.empty();
        }
        String normalized = value.getAsString().strip();
        return normalized.isEmpty() || normalized.length() > 512 ? Optional.empty() : Optional.of(normalized);
    }

    private static String normalizedFilter(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (normalized.length() > AdminSearchService.MAX_QUERY_LENGTH) {
            throw new BadRequest("INVALID_QUERY", "Filter is too long.");
        }
        return normalized;
    }

    private static long initialBalance() {
        try {
            return EconomyConfig.initialBalance();
        } catch (IllegalStateException exception) {
            return EconomyConfig.DEFAULT_INITIAL_BALANCE;
        }
    }

    private static long maximumBalance() {
        try {
            return EconomyConfig.maximumBalance();
        } catch (IllegalStateException exception) {
            return EconomyConfig.DEFAULT_MAXIMUM_BALANCE;
        }
    }

    private static Response ok(Object body) {
        return new Response(200, body);
    }

    private static Response error(int status, String code, String message) {
        return new Response(status, map("ok", false, "error", map("code", code, "message", message)));
    }

    private static Map<String, Object> map(Object... values) {
        if (values.length % 2 != 0) {
            throw new IllegalArgumentException("Map entries must be key/value pairs");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put((String) values[index], values[index + 1]);
        }
        return result;
    }

    record Response(int status, Object body) {
    }

    private record PreparedAction(
            UUID transactionId,
            long expiresAtEpochMillis,
            JsonObject body,
            Object stateKey,
            String target,
            boolean typedConfirmationRequired) {
    }

    private record ActionPreview(
            Object stateKey,
            String target,
            Map<String, Object> details,
            boolean typedConfirmationRequired) {
    }

    private record ActionState(String type, UUID targetId, Object value, boolean online) {
    }

    private record ReverseState(
            EconomyTransactionReceipt receipt,
            Optional<Long> balance,
            boolean online) {
    }

    private static final class BadRequest extends RuntimeException {
        private final String code;

        private BadRequest(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
