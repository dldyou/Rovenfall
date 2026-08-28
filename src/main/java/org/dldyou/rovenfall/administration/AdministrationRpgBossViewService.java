package org.dldyou.rovenfall.administration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import org.dldyou.rovenfall.mobs.MobContentReloadListener;
import org.dldyou.rovenfall.rpg.ActivityXpConfig;
import org.dldyou.rovenfall.rpg.RpgAdministrationViewService;
import org.dldyou.rovenfall.rpg.RpgDefinitionReloadListener;
import org.dldyou.rovenfall.rpg.RpgPlayerSavedData;
import org.dldyou.rovenfall.rpg.RpgPlayerState;

/** Bounded typed projections for RPG definitions, player progression, mobs, and bosses. */
final class AdministrationRpgBossViewService {
    static final int PAGE_SIZE = AdministrationReadViewService.MAX_PAGE_SIZE;
    static final int MAX_SCANNED_ROWS = AdministrationReadViewService.MAX_SCANNED_ROWS;

    private AdministrationRpgBossViewService() {
    }

    static Page<PlayerRow> players(
            MinecraftServer server, UUID actorId, boolean authorizationOverride, String query, int page) {
        if (!authorized(server, actorId, authorizationOverride, AdministrationReadViewService.Domain.RPG)) {
            return Page.denied(page);
        }
        RpgPlayerSavedData rpg = RpgPlayerSavedData.get(server);
        PlatformSavedData platform = PlatformSavedData.get(server);
        UUID exact = parseUuid(query);
        List<PlayerRow> source;
        boolean truncated;
        if (exact != null) {
            boolean known = platform.playerRecord(exact).isPresent()
                    || rpg.player(exact).isPresent()
                    || server.getPlayerList().getPlayer(exact) != null;
            source = known ? List.of(playerRow(server, exact, rpg.state(exact))) : List.of();
            truncated = false;
        } else {
            source = platform.playerRecords(MAX_SCANNED_ROWS).stream()
                    .map(entry -> playerRow(server, entry.getKey(), rpg.state(entry.getKey())))
                    .toList();
            truncated = platform.playerRecordCount() > MAX_SCANNED_ROWS;
        }
        return filterAndPage(source, query, page, truncated,
                row -> row.playerId() + " " + row.displayName() + " "
                        + row.activeCareer().map(Identifier::toString).orElse(""));
    }

    static Page<DefinitionRow> definitions(
            MinecraftServer server,
            UUID actorId,
            boolean authorizationOverride,
            Optional<DefinitionKind> kind,
            String query,
            int page) {
        if (!authorized(server, actorId, authorizationOverride, AdministrationReadViewService.Domain.RPG)
                || kind == null) {
            return Page.denied(page);
        }
        List<DefinitionRow> source = definitionRows(server).stream()
                .filter(row -> kind.map(value -> row.kind() == value).orElse(true))
                .toList();
        return filterAndPage(source, query, page, false,
                row -> row.kind().name() + " " + row.id() + " " + row.translationKey());
    }

    static Page<DefinitionRow> promotionCandidates(
            MinecraftServer server,
            UUID actorId,
            boolean authorizationOverride,
            UUID playerId,
            String query,
            int page) {
        if (!authorized(server, actorId, authorizationOverride, AdministrationReadViewService.Domain.RPG)
                || playerId == null) {
            return Page.denied(page);
        }
        RpgPlayerState state = RpgPlayerSavedData.get(server).state(playerId);
        List<DefinitionRow> source = RpgDefinitionReloadListener.snapshot(server).careers().entrySet().stream()
                .filter(entry -> !state.careers().containsKey(entry.getKey()))
                .map(entry -> new DefinitionRow(
                        DefinitionKind.CAREER, entry.getKey(), entry.getValue().translationKey()))
                .sorted(Comparator.comparing(DefinitionRow::id))
                .toList();
        return filterAndPage(source, query, page, false,
                row -> row.id() + " " + row.translationKey());
    }

    static RpgAdministrationViewService.ProgressionPage progression(
            MinecraftServer server, UUID actorId, boolean authorizationOverride, UUID playerId, int page) {
        if (!authorized(server, actorId, authorizationOverride, AdministrationReadViewService.Domain.RPG)) {
            return new RpgAdministrationViewService.ProgressionPage(
                    playerId, Optional.empty(), page, 0, 0, List.of());
        }
        return RpgAdministrationViewService.progression(
                RpgPlayerSavedData.get(server), RpgDefinitionReloadListener.snapshot(server),
                playerId, page, PAGE_SIZE);
    }

    static RpgAdministrationViewService.AwardPage history(
            MinecraftServer server,
            UUID actorId,
            boolean authorizationOverride,
            UUID playerId,
            boolean suspiciousOnly,
            int page) {
        if (!authorized(server, actorId, authorizationOverride, AdministrationReadViewService.Domain.RPG)) {
            return new RpgAdministrationViewService.AwardPage(page, 0, 0, List.of());
        }
        return RpgAdministrationViewService.awardHistory(
                RpgPlayerSavedData.get(server), playerId, Optional.empty(), suspiciousOnly,
                page, PAGE_SIZE, ActivityXpConfig.snapshot());
    }

    private static List<DefinitionRow> definitionRows(MinecraftServer server) {
        var rpg = RpgDefinitionReloadListener.snapshot(server);
        var mobs = MobContentReloadListener.snapshot(server);
        List<DefinitionRow> result = new ArrayList<>();
        rpg.activities().forEach((id, value) -> result.add(
                new DefinitionRow(DefinitionKind.ACTIVITY, id, value.translationKey())));
        rpg.careers().forEach((id, value) -> result.add(
                new DefinitionRow(DefinitionKind.CAREER, id, value.translationKey())));
        rpg.skills().forEach((id, value) -> result.add(
                new DefinitionRow(DefinitionKind.SKILL, id, value.translationKey())));
        mobs.mobs().forEach((id, value) -> result.add(
                new DefinitionRow(DefinitionKind.MOB, id, value.translationKey())));
        mobs.mutations().forEach((id, value) -> result.add(
                new DefinitionRow(DefinitionKind.MUTATION, id, value.translationKey())));
        mobs.arenas().forEach((id, value) -> result.add(
                new DefinitionRow(DefinitionKind.ARENA, id, "")));
        mobs.contributionRules().forEach((id, value) -> result.add(
                new DefinitionRow(DefinitionKind.CONTRIBUTION, id, "")));
        mobs.lootDefinitions().forEach((id, value) -> result.add(
                new DefinitionRow(DefinitionKind.REWARD, id, "")));
        mobs.bosses().forEach((id, value) -> result.add(
                new DefinitionRow(DefinitionKind.BOSS, id, value.translationKey())));
        result.sort(Comparator.comparing(DefinitionRow::kind).thenComparing(DefinitionRow::id));
        return List.copyOf(result);
    }

    private static PlayerRow playerRow(MinecraftServer server, UUID playerId, RpgPlayerState state) {
        String displayName = PlatformSavedData.get(server).playerRecord(playerId)
                .flatMap(PlayerRecord::displayName)
                .orElseGet(() -> {
                    var online = server.getPlayerList().getPlayer(playerId);
                    return online == null ? "" : online.getGameProfile().name();
                });
        int learnedSkills = state.careers().values().stream()
                .mapToInt(progress -> progress.learnedSkills().size()).sum();
        return new PlayerRow(
                playerId, displayName, state.activeCareer(), state.activityXp().size(),
                state.careers().size(), learnedSkills, state.provenance().size());
    }

    private static boolean authorized(
            MinecraftServer server,
            UUID actorId,
            boolean authorizationOverride,
            AdministrationReadViewService.Domain domain) {
        if (server == null || actorId == null || domain == null) {
            return false;
        }
        PlatformSavedData state = PlatformSavedData.get(server);
        return authorizationOverride || state.roleOf(actorId).filter(domain::allowedFor).isPresent();
    }

    private static UUID parseUuid(String query) {
        try {
            return query == null || query.isBlank() ? null : UUID.fromString(query.strip());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static <T> Page<T> filterAndPage(
            List<T> source,
            String query,
            int page,
            boolean truncated,
            Function<T, String> searchText) {
        if (query == null || query.length() > AdministrationReadViewService.MAX_QUERY_LENGTH || page < 0) {
            return Page.invalid(page);
        }
        String needle = query.strip().toLowerCase(Locale.ROOT);
        List<T> matches = source.stream()
                .filter(value -> needle.isEmpty()
                        || searchText.apply(value).toLowerCase(Locale.ROOT).contains(needle))
                .toList();
        int totalPages = matches.isEmpty() ? 0 : (matches.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        long offset = (long) page * PAGE_SIZE;
        List<T> entries = offset >= matches.size()
                ? List.of()
                : matches.subList((int) offset, Math.min(matches.size(), (int) offset + PAGE_SIZE));
        return new Page<>(Status.SUCCESS, page, totalPages, matches.size(), entries, truncated);
    }

    enum DefinitionKind {
        ACTIVITY,
        CAREER,
        SKILL,
        MOB,
        MUTATION,
        ARENA,
        CONTRIBUTION,
        REWARD,
        BOSS
    }

    enum Status {
        SUCCESS,
        UNAUTHORIZED,
        INVALID_REQUEST
    }

    record PlayerRow(
            UUID playerId,
            String displayName,
            Optional<Identifier> activeCareer,
            int activities,
            int careers,
            int learnedSkills,
            int evidenceEntries) {
        PlayerRow {
            displayName = displayName == null ? "" : displayName;
            activeCareer = activeCareer == null ? Optional.empty() : activeCareer;
        }
    }

    record DefinitionRow(DefinitionKind kind, Identifier id, String translationKey) {
        DefinitionRow {
            translationKey = translationKey == null ? "" : translationKey;
        }
    }

    record Page<T>(Status status, int page, int totalPages, int totalEntries, List<T> entries, boolean truncated) {
        Page {
            entries = List.copyOf(entries);
        }

        static <T> Page<T> denied(int page) {
            return new Page<>(Status.UNAUTHORIZED, page, 0, 0, List.of(), false);
        }

        static <T> Page<T> invalid(int page) {
            return new Page<>(Status.INVALID_REQUEST, page, 0, 0, List.of(), false);
        }
    }
}
