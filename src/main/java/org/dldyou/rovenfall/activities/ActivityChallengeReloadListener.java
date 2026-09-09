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

public final class ActivityChallengeReloadListener
        extends SimplePreparableReloadListener<Map<Identifier, ActivityChallengeDefinition>> {
    public static final ListenerKey<ActivityChallengeReloadListener> KEY = ListenerKey.create(
            Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "activity_challenges"));
    public static final int MAX_DEFINITIONS = 256;
    private static final FileToIdConverter FILES =
            FileToIdConverter.json(Rovenfall.MOD_ID + "/activity_challenges");
    private static final int MAX_FILE_BYTES = 65_536;
    private static final Logger LOGGER = LogUtils.getLogger();
    private volatile Map<Identifier, ActivityChallengeDefinition> definitions = Map.of();

    @Override
    protected Map<Identifier, ActivityChallengeDefinition> prepare(
            ResourceManager resourceManager, ProfilerFiller profiler) {
        var resources = FILES.listMatchingResourceStacks(resourceManager);
        if (resources.size() > MAX_DEFINITIONS) {
            throw new IllegalStateException("Activity challenge count exceeds " + MAX_DEFINITIONS);
        }
        Map<Identifier, ActivityChallengeDefinition> compiled = new LinkedHashMap<>();
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
            Map<Identifier, ActivityChallengeDefinition> compiled,
            List<String> problems) {
        Identifier definitionId = FILES.fileToId(file);
        if (definitionId.toString().length() > ActivityChallengeDefinition.MAX_DEFINITION_ID_LENGTH) {
            problems.add(definitionId + ": activity challenge ID is too long");
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
            ActivityChallengeDefinition definition = ActivityChallengeDefinition.CODEC.parse(JsonOps.INSTANCE, json)
                    .getOrThrow(IllegalArgumentException::new);
            if (compiled.putIfAbsent(definitionId, definition) != null) {
                problems.add(definitionId + ": duplicate activity challenge ID");
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
            Map<Identifier, ActivityChallengeDefinition> prepared,
            ResourceManager resourceManager,
            ProfilerFiller profiler) {
        definitions = prepared;
        LOGGER.info("Loaded {} validated Rovenfall activity challenges", prepared.size());
    }

    public Map<Identifier, ActivityChallengeDefinition> snapshot() {
        return definitions;
    }

    public static Optional<Map<Identifier, ActivityChallengeDefinition>> snapshot(MinecraftServer server) {
        if (server == null) {
            return Optional.empty();
        }
        ActivityChallengeReloadListener listener = server.getServerResources().managers().getListener(KEY);
        return listener == null ? Optional.empty() : Optional.of(listener.snapshot());
    }
}
