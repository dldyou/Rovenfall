package org.dldyou.rovenfall.mobs;

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

public final class MobMutationReloadListener extends SimplePreparableReloadListener<MobMutationCatalog> {
    public static final ListenerKey<MobMutationReloadListener> KEY = ListenerKey.create(
            Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "mob_mutations"));
    private static final FileToIdConverter FILES = FileToIdConverter.json(Rovenfall.MOD_ID + "/mob_mutations");
    private static final int MAX_FILE_BYTES = 65_536;
    private static final Logger LOGGER = LogUtils.getLogger();
    private volatile MobMutationCatalog catalog;

    @Override
    protected MobMutationCatalog prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        var resources = FILES.listMatchingResourceStacks(resourceManager);
        if (resources.size() > MobMutationCatalog.MAX_DEFINITIONS) {
            throw new IllegalStateException("Mob mutation count exceeds " + MobMutationCatalog.MAX_DEFINITIONS);
        }
        Map<Identifier, MobMutationDefinition> definitions = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        resources.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                load(entry.getKey(), entry.getValue(), definitions, problems));
        definitions.forEach((id, definition) -> {
            definition.eligibleEntityTypes().stream()
                    .filter(type -> !BuiltInRegistries.ENTITY_TYPE.containsKey(type))
                    .forEach(type -> problems.add(id + ": missing entity type " + type));
            definition.attributes().stream()
                    .map(MobMutationDefinition.AttributeChange::attributeId)
                    .filter(attribute -> !BuiltInRegistries.ATTRIBUTE.containsKey(attribute))
                    .forEach(attribute -> problems.add(id + ": missing attribute " + attribute));
        });
        if (!problems.isEmpty()) {
            throw new IllegalStateException(String.join(" | ", problems));
        }
        return MobMutationCatalog.create(definitions).getOrThrow(IllegalStateException::new);
    }

    private static void load(
            Identifier file,
            List<Resource> stack,
            Map<Identifier, MobMutationDefinition> definitions,
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
            MobMutationDefinition definition = MobMutationDefinition.CODEC.parse(JsonOps.INSTANCE, json)
                    .getOrThrow(IllegalArgumentException::new);
            if (definitions.putIfAbsent(definitionId, definition) != null) {
                problems.add(definitionId + ": duplicate mutation ID");
            }
        } catch (IOException | RuntimeException exception) {
            String cause = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            problems.add(definitionId + ": " + cause);
        }
    }

    @Override
    protected void apply(MobMutationCatalog prepared, ResourceManager resourceManager, ProfilerFiller profiler) {
        catalog = prepared;
        LOGGER.info("Loaded {} validated Rovenfall mob mutations", prepared.size());
    }

    public static Optional<MobMutationCatalog> snapshot(MinecraftServer server) {
        if (server == null) {
            return Optional.empty();
        }
        MobMutationReloadListener listener = server.getServerResources().managers().getListener(KEY);
        return listener == null ? Optional.empty() : Optional.ofNullable(listener.catalog);
    }
}
