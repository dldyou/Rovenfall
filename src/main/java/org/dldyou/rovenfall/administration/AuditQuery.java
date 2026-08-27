package org.dldyou.rovenfall.administration;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.Identifier;

public record AuditQuery(
        long sinceEpochMillis,
        long untilEpochMillis,
        Optional<UUID> actorId,
        Optional<Identifier> actionType,
        Optional<String> targetPrefix,
        Optional<UUID> transactionId) {
    public static final long MAX_WINDOW_MILLIS = Duration.ofDays(30).toMillis();
    public static final int MAX_TEXT_LENGTH = 1_024;
    public static final int MAX_TARGET_PREFIX_LENGTH = 256;

    public AuditQuery {
        actorId = actorId == null ? Optional.empty() : actorId;
        actionType = actionType == null ? Optional.empty() : actionType;
        targetPrefix = targetPrefix == null ? Optional.empty() : targetPrefix;
        transactionId = transactionId == null ? Optional.empty() : transactionId;
        if (sinceEpochMillis < 0 || untilEpochMillis < sinceEpochMillis
                || untilEpochMillis - sinceEpochMillis > MAX_WINDOW_MILLIS) {
            throw new IllegalArgumentException("Invalid audit time window");
        }
        if (targetPrefix.filter(value -> value.isBlank() || value.length() > MAX_TARGET_PREFIX_LENGTH).isPresent()) {
            throw new IllegalArgumentException("Invalid target prefix");
        }
    }

    public static AuditQuery parse(
            String text, long defaultSinceEpochMillis, long defaultUntilEpochMillis, boolean requireExplicitWindow) {
        if (text == null || text.isBlank() || text.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("Invalid audit query");
        }

        Map<String, String> values = new HashMap<>();
        for (String token : text.strip().split("\\s+")) {
            int separator = token.indexOf('=');
            if (separator < 1 || separator == token.length() - 1) {
                throw new IllegalArgumentException("Invalid audit query token");
            }
            String key = token.substring(0, separator);
            String value = token.substring(separator + 1);
            if (!isSupportedKey(key) || values.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("Invalid or duplicate audit query key");
            }
        }
        if (requireExplicitWindow && (!values.containsKey("since") || !values.containsKey("until"))) {
            throw new IllegalArgumentException("Audit export requires an explicit time window");
        }

        try {
            long since = values.containsKey("since") ? Long.parseLong(values.get("since")) : defaultSinceEpochMillis;
            long until = values.containsKey("until") ? Long.parseLong(values.get("until")) : defaultUntilEpochMillis;
            Optional<UUID> actor = optionalUuid(values.get("actor"));
            Optional<Identifier> action = values.containsKey("action")
                    ? Optional.of(Identifier.parse(values.get("action")))
                    : Optional.empty();
            Optional<String> target = Optional.ofNullable(values.get("target"));
            Optional<UUID> transaction = optionalUuid(values.get("transaction"));
            return new AuditQuery(since, until, actor, action, target, transaction);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid audit query value", exception);
        }
    }

    public boolean matches(AuditEntry entry) {
        return entry.timestampEpochMillis() >= sinceEpochMillis
                && entry.timestampEpochMillis() <= untilEpochMillis
                && actorId.map(value -> value.equals(entry.actorId())).orElse(true)
                && actionType.map(value -> value.equals(entry.actionType())).orElse(true)
                && targetPrefix.map(entry.target()::startsWith).orElse(true)
                && transactionId.map(value -> value.equals(entry.transactionId())).orElse(true);
    }

    public String canonical() {
        StringBuilder value = new StringBuilder()
                .append("since=").append(sinceEpochMillis)
                .append(" until=").append(untilEpochMillis);
        actorId.ifPresent(actor -> value.append(" actor=").append(actor));
        actionType.ifPresent(action -> value.append(" action=").append(action));
        targetPrefix.ifPresent(target -> value.append(" target=").append(target));
        transactionId.ifPresent(transaction -> value.append(" transaction=").append(transaction));
        return value.toString();
    }

    private static boolean isSupportedKey(String key) {
        return key.equals("since") || key.equals("until") || key.equals("actor")
                || key.equals("action") || key.equals("target") || key.equals("transaction");
    }

    private static Optional<UUID> optionalUuid(String value) {
        return value == null ? Optional.empty() : Optional.of(UUID.fromString(value));
    }
}
