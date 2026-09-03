package org.dldyou.rovenfall.activities;

import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.neoforge.resource.ListenerKey;
import org.dldyou.rovenfall.Rovenfall;
import org.slf4j.Logger;

public final class DailyContractReloadListener
        extends SimplePreparableReloadListener<Map<Identifier, DailyContractDefinition>> {
    public static final ListenerKey<DailyContractReloadListener> KEY = ListenerKey.create(
            Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "daily_contracts"));
    public static final int MAX_DEFINITIONS = 256;
    private static final FileToIdConverter FILES =
            FileToIdConverter.json(Rovenfall.MOD_ID + "/daily_contracts");
    private static final int MAX_FILE_BYTES = 65_536;
    private static final Logger LOGGER = LogUtils.getLogger();
    private volatile Map<Identifier, DailyContractDefinition> definitions = Map.of();

    @Override
    protected Map<Identifier, DailyContractDefinition> prepare(
            ResourceManager resourceManager, ProfilerFiller profiler) {
        return loadSnapshot(resourceManager);
    }

    static Map<Identifier, DailyContractDefinition> loadSnapshot(ResourceManager resourceManager) {
        var resources = FILES.listMatchingResourceStacks(resourceManager);
        if (resources.size() > MAX_DEFINITIONS) {
            throw new IllegalStateException("Daily contract count exceeds " + MAX_DEFINITIONS);
        }
        var activityRewards = ActivityRewardReloadListener.loadSnapshot(resourceManager);
        Map<Identifier, DailyContractDefinition> compiled = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        resources.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                load(entry.getKey(), entry.getValue(), activityRewards, compiled, problems));
        if (!problems.isEmpty()) {
            throw new IllegalStateException(String.join(" | ", problems));
        }
        return Map.copyOf(compiled);
    }

    private static void load(
            Identifier file,
            List<Resource> stack,
            Map<ActivityRewardReloadListener.RewardKey, ActivityRewardReloadListener.ResolvedReward> activityRewards,
            Map<Identifier, DailyContractDefinition> compiled,
            List<String> problems) {
        Identifier definitionId = FILES.fileToId(file);
        if (definitionId.toString().length() > DailyContractDefinition.MAX_DEFINITION_ID_LENGTH) {
            problems.add(definitionId + ": daily contract ID is too long");
            return;
        }
        if (stack.size() != 1) {
            problems.add(definitionId + ": duplicate definition from packs "
                    + stack.stream().map(Resource::sourcePackId).toList());
            return;
        }
        try (var input = stack.getFirst().open()) {
            byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) {
                throw new IllegalArgumentException("file exceeds " + MAX_FILE_BYTES + " bytes");
            }
            var json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            DailyContractDefinition definition = DailyContractDefinition.CODEC.parse(JsonOps.INSTANCE, json)
                    .getOrThrow(IllegalArgumentException::new);
            var rewardKey = new ActivityRewardReloadListener.RewardKey(
                    definition.kind(), definition.targetId());
            if (!activityRewards.containsKey(rewardKey)) {
                throw new IllegalArgumentException("no activity reward exists for "
                        + definition.kind().getSerializedName() + " " + definition.targetId());
            }
            if (compiled.putIfAbsent(definitionId, definition) != null) {
                problems.add(definitionId + ": duplicate daily contract ID");
            }
        } catch (IOException | RuntimeException exception) {
            String cause = exception.getMessage() == null
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage();
            problems.add(definitionId + ": " + cause);
        }
    }

    @Override
    protected void apply(
            Map<Identifier, DailyContractDefinition> prepared,
            ResourceManager resourceManager,
            ProfilerFiller profiler) {
        definitions = prepared;
        LOGGER.info("Loaded {} validated Rovenfall daily contracts", prepared.size());
    }

    public Map<Identifier, DailyContractDefinition> snapshot() {
        return definitions;
    }

    public static Optional<Map<Identifier, DailyContractDefinition>> snapshot(MinecraftServer server) {
        if (server == null) {
            return Optional.empty();
        }
        DailyContractReloadListener listener = server.getServerResources().managers().getListener(KEY);
        return listener == null ? Optional.empty() : Optional.of(listener.snapshot());
    }
}
