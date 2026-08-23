package org.dldyou.rovenfall.definition;

import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.neoforge.resource.ListenerKey;
import org.dldyou.rovenfall.Rovenfall;
import org.slf4j.Logger;

public final class TestDefinitionReloadListener extends SimplePreparableReloadListener<DefinitionSnapshot> {
    public static final ListenerKey<TestDefinitionReloadListener> KEY = ListenerKey.create(
            Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "test_definitions"));
    private static final FileToIdConverter FILES = FileToIdConverter.json(Rovenfall.MOD_ID + "/test_definitions");
    private static final int MAX_FILE_BYTES = 65_536;
    private static final Logger LOGGER = LogUtils.getLogger();

    private final DefinitionStore store = new DefinitionStore();

    @Override
    protected DefinitionSnapshot prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        var resources = FILES.listMatchingResourceStacks(resourceManager);
        if (resources.size() > DefinitionSnapshot.MAX_DEFINITIONS) {
            throw new DefinitionSnapshot.ValidationException(List.of(new DefinitionSnapshot.Problem(
                    Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "definition_catalog"),
                    Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "definition_catalog"),
                    "definition count exceeds " + DefinitionSnapshot.MAX_DEFINITIONS)));
        }

        List<DefinitionSnapshot.Source> candidates = new ArrayList<>();
        List<DefinitionSnapshot.Problem> problems = new ArrayList<>();
        resources.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> loadResource(entry.getKey(), entry.getValue(), candidates, problems));

        if (!problems.isEmpty()) {
            throw new DefinitionSnapshot.ValidationException(problems);
        }
        return DefinitionSnapshot.compile(candidates);
    }

    private static void loadResource(
            Identifier file,
            List<Resource> stack,
            List<DefinitionSnapshot.Source> candidates,
            List<DefinitionSnapshot.Problem> problems) {
        Identifier definitionId = FILES.fileToId(file);
        if (stack.size() != 1) {
            String packs = stack.stream().map(Resource::sourcePackId).toList().toString();
            problems.add(new DefinitionSnapshot.Problem(
                    file, definitionId, "duplicate definition ID provided by packs " + packs));
            return;
        }

        Resource resource = stack.getFirst();
        try (var input = resource.open()) {
            byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) {
                throw new IllegalArgumentException("file exceeds " + MAX_FILE_BYTES + " bytes");
            }
            var json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            TestDefinition definition = TestDefinition.CODEC.parse(JsonOps.INSTANCE, json)
                    .getOrThrow(IllegalArgumentException::new);
            candidates.add(new DefinitionSnapshot.Source(file, resource.sourcePackId(), definitionId, definition));
        } catch (IOException | RuntimeException exception) {
            String cause = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            problems.add(new DefinitionSnapshot.Problem(file, definitionId, cause));
        }
    }

    @Override
    protected void apply(DefinitionSnapshot prepared, ResourceManager resourceManager, ProfilerFiller profiler) {
        store.install(prepared);
        LOGGER.info("Loaded {} validated Rovenfall test definitions", prepared.size());
    }

    public DefinitionSnapshot snapshot() {
        return store.current();
    }
}
