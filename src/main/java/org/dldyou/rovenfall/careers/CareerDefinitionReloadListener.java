package org.dldyou.rovenfall.careers;

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
import net.minecraft.core.registries.BuiltInRegistries;
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

public final class CareerDefinitionReloadListener
        extends SimplePreparableReloadListener<CareerCatalog> {
    public static final ListenerKey<CareerDefinitionReloadListener> KEY = ListenerKey.create(
            Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "career_definitions"));
    private static final FileToIdConverter FILES =
            FileToIdConverter.json(Rovenfall.MOD_ID + "/professions");
    private static final int MAX_FILE_BYTES = 65_536;
    private static final Logger LOGGER = LogUtils.getLogger();
    private volatile CareerCatalog catalog;

    @Override
    protected CareerCatalog prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        var resources = FILES.listMatchingResourceStacks(resourceManager);
        if (resources.size() > CareerCatalog.MAX_DEFINITIONS) {
            throw new IllegalStateException("Career definition count exceeds " + CareerCatalog.MAX_DEFINITIONS);
        }
        Map<Identifier, CareerDefinition> definitions = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        resources.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                load(entry.getKey(), entry.getValue(), definitions, problems));
        if (!problems.isEmpty()) {
            throw new IllegalStateException(String.join(" | ", problems));
        }
        CareerCatalog prepared = CareerCatalog.create(definitions).getOrThrow(IllegalStateException::new);
        prepared.activeSkillIds().forEach(skillId -> {
            Identifier effectId = prepared.skill(skillId).orElseThrow()
                    .definition().active().orElseThrow().effectId();
            if (!BuiltInRegistries.MOB_EFFECT.containsKey(effectId)) {
                throw new IllegalStateException(skillId + " references missing mob effect " + effectId);
            }
        });
        return prepared;
    }

    private static void load(
            Identifier file,
            List<Resource> stack,
            Map<Identifier, CareerDefinition> definitions,
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
            CareerDefinition definition = CareerDefinition.CODEC.parse(JsonOps.INSTANCE, json)
                    .getOrThrow(IllegalArgumentException::new);
            if (definitions.putIfAbsent(definitionId, definition) != null) {
                problems.add(definitionId + ": duplicate career ID");
            }
        } catch (IOException | RuntimeException exception) {
            String cause = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            problems.add(definitionId + ": " + cause);
        }
    }

    @Override
    protected void apply(CareerCatalog prepared, ResourceManager resourceManager, ProfilerFiller profiler) {
        catalog = prepared;
        LOGGER.info("Loaded {} validated Rovenfall career definitions", prepared.size());
    }

    public Optional<CareerCatalog> snapshot() {
        return Optional.ofNullable(catalog);
    }

    public static Optional<CareerCatalog> snapshot(MinecraftServer server) {
        if (server == null) {
            return Optional.empty();
        }
        CareerDefinitionReloadListener listener = server.getServerResources().managers().getListener(KEY);
        return listener == null ? Optional.empty() : listener.snapshot();
    }
}
