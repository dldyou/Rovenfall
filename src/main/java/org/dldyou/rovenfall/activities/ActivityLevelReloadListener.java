package org.dldyou.rovenfall.activities;

import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
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

public final class ActivityLevelReloadListener
        extends SimplePreparableReloadListener<Map<ActivityTrack, ActivityLevelDefinition>> {
    public static final ListenerKey<ActivityLevelReloadListener> KEY = ListenerKey.create(
            Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "activity_levels"));
    private static final FileToIdConverter FILES =
            FileToIdConverter.json(Rovenfall.MOD_ID + "/activity_levels");
    private static final int MAX_FILE_BYTES = 65_536;
    private static final Logger LOGGER = LogUtils.getLogger();
    private volatile Map<ActivityTrack, ActivityLevelDefinition> definitions = Map.of();

    @Override
    protected Map<ActivityTrack, ActivityLevelDefinition> prepare(
            ResourceManager resourceManager, ProfilerFiller profiler) {
        var resources = FILES.listMatchingResourceStacks(resourceManager);
        if (resources.size() > ActivityTrack.values().length) {
            throw new IllegalStateException("Activity level definition count exceeds fixed track count");
        }
        Map<ActivityTrack, ActivityLevelDefinition> compiled = new EnumMap<>(ActivityTrack.class);
        List<String> problems = new ArrayList<>();
        resources.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                load(entry.getKey(), entry.getValue(), compiled, problems));
        EnumSet<ActivityTrack> missing = EnumSet.allOf(ActivityTrack.class);
        missing.removeAll(compiled.keySet());
        if (!missing.isEmpty()) {
            problems.add("missing activity level definitions for " + missing);
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException(String.join(" | ", problems));
        }
        return Map.copyOf(compiled);
    }

    private static void load(
            Identifier file,
            List<Resource> stack,
            Map<ActivityTrack, ActivityLevelDefinition> compiled,
            List<String> problems) {
        Identifier definitionId = FILES.fileToId(file);
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
            ActivityLevelDefinition definition = ActivityLevelDefinition.CODEC.parse(JsonOps.INSTANCE, json)
                    .getOrThrow(IllegalArgumentException::new);
            if (compiled.putIfAbsent(definition.track(), definition) != null) {
                problems.add(definitionId + ": duplicate track " + definition.track().getSerializedName());
            }
        } catch (IOException | RuntimeException exception) {
            String cause = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            problems.add(definitionId + ": " + cause);
        }
    }

    @Override
    protected void apply(
            Map<ActivityTrack, ActivityLevelDefinition> prepared,
            ResourceManager resourceManager,
            ProfilerFiller profiler) {
        definitions = prepared;
        LOGGER.info("Loaded {} validated Rovenfall activity level curves", prepared.size());
    }

    public Optional<ActivityLevelDefinition> get(ActivityTrack track) {
        return Optional.ofNullable(definitions.get(track));
    }

    public int size() {
        return definitions.size();
    }

    public static Optional<ActivityLevelDefinition> get(MinecraftServer server, ActivityTrack track) {
        if (server == null || track == null) {
            return Optional.empty();
        }
        ActivityLevelReloadListener listener = server.getServerResources().managers().getListener(KEY);
        return listener == null ? Optional.empty() : listener.get(track);
    }
}
