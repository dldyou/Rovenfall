package org.dldyou.rovenfall.exploration;

import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
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

/** Atomic data-pack loader for the {@code rovenfall/discoveries} data path. */
public final class ExplorationDefinitionReloadListener
        extends SimplePreparableReloadListener<ExplorationDefinitionSnapshot> {
    public static final ListenerKey<ExplorationDefinitionReloadListener> KEY = ListenerKey.create(
            Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "exploration_definitions"));
    private static final FileToIdConverter FILES = FileToIdConverter.json(Rovenfall.MOD_ID + "/discoveries");
    public static final int MAX_FILE_BYTES = 65_536;
    private static final Logger LOGGER = LogUtils.getLogger();

    private final ExplorationDefinitionStore store = new ExplorationDefinitionStore();
    private final AtomicReference<List<ExplorationDefinitionSnapshot.Problem>> lastProblems =
            new AtomicReference<>(List.of());

    @Override
    protected ExplorationDefinitionSnapshot prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<Identifier, List<Resource>> resources = FILES.listMatchingResourceStacks(resourceManager);
        if (resources.size() > ExplorationDefinitionSnapshot.MAX_DEFINITIONS) {
            Identifier catalog = Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "exploration_definition_catalog");
            List<ExplorationDefinitionSnapshot.Problem> problems = List.of(
                    new ExplorationDefinitionSnapshot.Problem(catalog, catalog,
                            "exploration definition count exceeds "
                                    + ExplorationDefinitionSnapshot.MAX_DEFINITIONS));
            lastProblems.set(problems);
            throw new ExplorationDefinitionSnapshot.ValidationException(problems);
        }
        List<ExplorationDefinitionSnapshot.Source> candidates = new ArrayList<>();
        List<ExplorationDefinitionSnapshot.Problem> problems = new ArrayList<>();
        resources.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> load(entry.getKey(), entry.getValue(), candidates, problems));
        if (!problems.isEmpty()) {
            lastProblems.set(List.copyOf(problems));
            throw new ExplorationDefinitionSnapshot.ValidationException(problems);
        }
        try {
            return ExplorationDefinitionSnapshot.compile(candidates);
        } catch (ExplorationDefinitionSnapshot.ValidationException exception) {
            lastProblems.set(exception.problems());
            throw exception;
        }
    }

    private static void load(
            Identifier file,
            List<Resource> stack,
            List<ExplorationDefinitionSnapshot.Source> candidates,
            List<ExplorationDefinitionSnapshot.Problem> problems) {
        Identifier id = FILES.fileToId(file);
        if (stack.size() != 1) {
            problems.add(new ExplorationDefinitionSnapshot.Problem(file, id,
                    "duplicate exploration definition ID provided by packs "
                            + stack.stream().map(Resource::sourcePackId).toList()));
            return;
        }
        Resource resource = stack.getFirst();
        try (var input = resource.open()) {
            byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) {
                throw new IllegalArgumentException("file exceeds " + MAX_FILE_BYTES + " bytes");
            }
            var json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            ExplorationDefinition definition = ExplorationDefinition.CODEC.parse(JsonOps.INSTANCE, json)
                    .getOrThrow(IllegalArgumentException::new);
            candidates.add(new ExplorationDefinitionSnapshot.Source(
                    file, resource.sourcePackId(), id, definition));
        } catch (IOException | RuntimeException exception) {
            problems.add(new ExplorationDefinitionSnapshot.Problem(file, id,
                    exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage()));
        }
    }

    @Override
    protected void apply(
            ExplorationDefinitionSnapshot prepared, ResourceManager resourceManager, ProfilerFiller profiler) {
        store.install(prepared);
        lastProblems.set(List.of());
        LOGGER.info("Loaded {} Rovenfall exploration definitions", prepared.size());
    }

    public ExplorationDefinitionSnapshot snapshot() {
        return store.current();
    }

    public long revision() {
        return store.versioned().revision();
    }

    public VersionedSnapshot versioned() {
        var current = store.versioned();
        return new VersionedSnapshot(current.snapshot(), current.revision());
    }

    public List<ExplorationDefinitionSnapshot.Problem> lastProblems() {
        return lastProblems.get();
    }

    public void beginValidationAttempt() {
        lastProblems.set(List.of());
    }

    public static ExplorationDefinitionSnapshot snapshot(MinecraftServer server) {
        ExplorationDefinitionReloadListener listener = server.getServerResources().managers().getListener(KEY);
        return listener == null ? ExplorationDefinitionSnapshot.empty() : listener.snapshot();
    }

    public static long revision(MinecraftServer server) {
        ExplorationDefinitionReloadListener listener = server.getServerResources().managers().getListener(KEY);
        return listener == null ? 0 : listener.revision();
    }

    public static VersionedSnapshot versioned(MinecraftServer server) {
        ExplorationDefinitionReloadListener listener = server.getServerResources().managers().getListener(KEY);
        return listener == null
                ? new VersionedSnapshot(ExplorationDefinitionSnapshot.empty(), 0)
                : listener.versioned();
    }

    public static List<ExplorationDefinitionSnapshot.Problem> lastProblems(MinecraftServer server) {
        ExplorationDefinitionReloadListener listener = server.getServerResources().managers().getListener(KEY);
        return listener == null ? List.of() : listener.lastProblems();
    }

    public static void beginValidationAttempt(MinecraftServer server) {
        ExplorationDefinitionReloadListener listener = server.getServerResources().managers().getListener(KEY);
        if (listener != null) {
            listener.beginValidationAttempt();
        }
    }

    public record VersionedSnapshot(ExplorationDefinitionSnapshot snapshot, long revision) {
    }
}
