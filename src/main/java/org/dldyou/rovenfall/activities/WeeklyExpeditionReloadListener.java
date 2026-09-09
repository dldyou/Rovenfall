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

public final class WeeklyExpeditionReloadListener
        extends SimplePreparableReloadListener<Map<Identifier, WeeklyExpeditionDefinition>> {
    public static final ListenerKey<WeeklyExpeditionReloadListener> KEY = ListenerKey.create(
            Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "weekly_expeditions"));
    public static final int MAX_DEFINITIONS = 128;
    private static final FileToIdConverter FILES =
            FileToIdConverter.json(Rovenfall.MOD_ID + "/weekly_expeditions");
    private static final int MAX_FILE_BYTES = 65_536;
    private static final Logger LOGGER = LogUtils.getLogger();
    private volatile Map<Identifier, WeeklyExpeditionDefinition> definitions = Map.of();

    @Override
    protected Map<Identifier, WeeklyExpeditionDefinition> prepare(
            ResourceManager resourceManager, ProfilerFiller profiler) {
        var resources = FILES.listMatchingResourceStacks(resourceManager);
        if (resources.size() > MAX_DEFINITIONS) {
            throw new IllegalStateException("Weekly expedition count exceeds " + MAX_DEFINITIONS);
        }
        var dailyContracts = DailyContractReloadListener.loadSnapshot(resourceManager);
        Map<Identifier, WeeklyExpeditionDefinition> compiled = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        resources.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                load(entry.getKey(), entry.getValue(), dailyContracts, compiled, problems));
        if (!problems.isEmpty()) {
            throw new IllegalStateException(String.join(" | ", problems));
        }
        return Map.copyOf(compiled);
    }

    private static void load(
            Identifier file,
            List<Resource> stack,
            Map<Identifier, DailyContractDefinition> dailyContracts,
            Map<Identifier, WeeklyExpeditionDefinition> compiled,
            List<String> problems) {
        Identifier definitionId = FILES.fileToId(file);
        if (definitionId.toString().length() > WeeklyExpeditionDefinition.MAX_DEFINITION_ID_LENGTH) {
            problems.add(definitionId + ": weekly expedition ID is too long");
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
            WeeklyExpeditionDefinition definition = WeeklyExpeditionDefinition.CODEC
                    .parse(JsonOps.INSTANCE, json)
                    .getOrThrow(IllegalArgumentException::new);
            var missing = definition.dailyContractRequirements().keySet().stream()
                    .filter(contractId -> !dailyContracts.containsKey(contractId))
                    .sorted()
                    .toList();
            if (!missing.isEmpty()) {
                throw new IllegalArgumentException("unknown daily contracts " + missing);
            }
            if (compiled.putIfAbsent(definitionId, definition) != null) {
                problems.add(definitionId + ": duplicate weekly expedition ID");
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
            Map<Identifier, WeeklyExpeditionDefinition> prepared,
            ResourceManager resourceManager,
            ProfilerFiller profiler) {
        definitions = prepared;
        LOGGER.info("Loaded {} validated Rovenfall weekly expeditions", prepared.size());
    }

    public Map<Identifier, WeeklyExpeditionDefinition> snapshot() {
        return definitions;
    }

    public static Optional<Map<Identifier, WeeklyExpeditionDefinition>> snapshot(MinecraftServer server) {
        if (server == null) {
            return Optional.empty();
        }
        WeeklyExpeditionReloadListener listener = server.getServerResources().managers().getListener(KEY);
        return listener == null ? Optional.empty() : Optional.of(listener.snapshot());
    }
}
