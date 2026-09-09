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

public final class ActivityRewardReloadListener
        extends SimplePreparableReloadListener<Map<ActivityRewardReloadListener.RewardKey,
        ActivityRewardReloadListener.ResolvedReward>> {
    public static final ListenerKey<ActivityRewardReloadListener> KEY = ListenerKey.create(
            Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "activity_rewards"));
    public static final int MAX_DEFINITIONS = 4_096;
    private static final FileToIdConverter FILES =
            FileToIdConverter.json(Rovenfall.MOD_ID + "/activity_rewards");
    private static final int MAX_FILE_BYTES = 65_536;
    private static final Logger LOGGER = LogUtils.getLogger();
    private volatile Map<RewardKey, ResolvedReward> rewards = Map.of();

    @Override
    protected Map<RewardKey, ResolvedReward> prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        return loadSnapshot(resourceManager);
    }

    static Map<RewardKey, ResolvedReward> loadSnapshot(ResourceManager resourceManager) {
        var resources = FILES.listMatchingResourceStacks(resourceManager);
        if (resources.size() > MAX_DEFINITIONS) {
            throw new IllegalStateException("Activity reward count exceeds " + MAX_DEFINITIONS);
        }
        Map<RewardKey, ResolvedReward> compiled = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        resources.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                load(entry.getKey(), entry.getValue(), compiled, problems));
        if (!problems.isEmpty()) {
            throw new IllegalStateException(String.join(" | ", problems));
        }
        return Map.copyOf(compiled);
    }

    private static void load(
            Identifier file,
            List<Resource> stack,
            Map<RewardKey, ResolvedReward> compiled,
            List<String> problems) {
        Identifier definitionId = FILES.fileToId(file);
        if (stack.size() != 1) {
            problems.add(definitionId + ": duplicate definition from packs "
                    + stack.stream().map(Resource::sourcePackId).toList());
            return;
        }
        Resource resource = stack.getFirst();
        try (var input = resource.open()) {
            byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) {
                throw new IllegalArgumentException("file exceeds " + MAX_FILE_BYTES + " bytes");
            }
            var json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            ActivityRewardDefinition definition = ActivityRewardDefinition.CODEC.parse(JsonOps.INSTANCE, json)
                    .getOrThrow(IllegalArgumentException::new);
            RewardKey key = new RewardKey(definition.kind(), definition.targetId());
            ResolvedReward previous = compiled.putIfAbsent(key, new ResolvedReward(definitionId, definition));
            if (previous != null) {
                problems.add(definitionId + ": reward key already defined by " + previous.id());
            }
        } catch (IOException | RuntimeException exception) {
            String cause = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            problems.add(definitionId + ": " + cause);
        }
    }

    @Override
    protected void apply(
            Map<RewardKey, ResolvedReward> prepared,
            ResourceManager resourceManager,
            ProfilerFiller profiler) {
        rewards = prepared;
        LOGGER.info("Loaded {} validated Rovenfall activity rewards", prepared.size());
    }

    public Optional<ResolvedReward> get(ActivityKind kind, Identifier targetId) {
        return Optional.ofNullable(rewards.get(new RewardKey(kind, targetId)));
    }

    public int size() {
        return rewards.size();
    }

    public static Optional<ResolvedReward> get(
            MinecraftServer server, ActivityKind kind, Identifier targetId) {
        if (server == null || kind == null || targetId == null) {
            return Optional.empty();
        }
        ActivityRewardReloadListener listener = server.getServerResources().managers().getListener(KEY);
        return listener == null ? Optional.empty() : listener.get(kind, targetId);
    }

    public record RewardKey(ActivityKind kind, Identifier targetId) {
    }

    public record ResolvedReward(Identifier id, ActivityRewardDefinition definition) {
    }
}
