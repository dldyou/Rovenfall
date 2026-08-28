package org.dldyou.rovenfall.rpg;

import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
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
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.dldyou.rovenfall.Rovenfall;
import org.slf4j.Logger;

public final class RpgDefinitionReloadListener extends SimplePreparableReloadListener<RpgDefinitionSnapshot> {
    public static final ListenerKey<RpgDefinitionReloadListener> KEY = ListenerKey.create(
            Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "rpg_definitions"));
    private static final FileToIdConverter ACTIVITIES = FileToIdConverter.json(Rovenfall.MOD_ID + "/activities");
    private static final FileToIdConverter CAREERS = FileToIdConverter.json(Rovenfall.MOD_ID + "/careers");
    private static final FileToIdConverter SKILLS = FileToIdConverter.json(Rovenfall.MOD_ID + "/skills");
    private static final int MAX_FILE_BYTES = 65_536;
    private static final Logger LOGGER = LogUtils.getLogger();

    private final RpgDefinitionStore store = new RpgDefinitionStore();
    private final AtomicReference<List<RpgDefinitionSnapshot.Problem>> lastProblems =
            new AtomicReference<>(List.of());

    @Override
    protected RpgDefinitionSnapshot prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        List<RpgDefinitionSnapshot.ActivitySource> activities = new ArrayList<>();
        List<RpgDefinitionSnapshot.CareerSource> careers = new ArrayList<>();
        List<RpgDefinitionSnapshot.SkillSource> skills = new ArrayList<>();
        List<RpgDefinitionSnapshot.Problem> problems = new ArrayList<>();

        load(resourceManager, ACTIVITIES, "activity", ActivityDefinition.CODEC,
                RpgDefinitionSnapshot.ActivitySource::new, activities, problems);
        load(resourceManager, CAREERS, "career", CareerDefinition.CODEC,
                RpgDefinitionSnapshot.CareerSource::new, careers, problems);
        load(resourceManager, SKILLS, "skill", SkillDefinition.CODEC,
                RpgDefinitionSnapshot.SkillSource::new, skills, problems);
        if (!problems.isEmpty()) {
            lastProblems.set(List.copyOf(problems));
            throw new RpgDefinitionSnapshot.ValidationException(problems);
        }
        try {
            return RpgDefinitionSnapshot.compile(activities, careers, skills);
        } catch (RpgDefinitionSnapshot.ValidationException exception) {
            lastProblems.set(exception.problems());
            throw exception;
        }
    }

    private static <T, S> void load(
            ResourceManager resourceManager,
            FileToIdConverter files,
            String kind,
            Codec<T> codec,
            SourceFactory<T, S> factory,
            List<S> candidates,
            List<RpgDefinitionSnapshot.Problem> problems) {
        Map<Identifier, List<Resource>> resources = files.listMatchingResourceStacks(resourceManager);
        if (resources.size() > RpgDefinitionSnapshot.MAX_DEFINITIONS_PER_KIND) {
            Identifier catalog = Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "rpg_definition_catalog");
            problems.add(new RpgDefinitionSnapshot.Problem(catalog, catalog,
                    kind + " definition count exceeds " + RpgDefinitionSnapshot.MAX_DEFINITIONS_PER_KIND));
            return;
        }
        resources.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            Identifier file = entry.getKey();
            Identifier definitionId = files.fileToId(file);
            List<Resource> stack = entry.getValue();
            if (stack.size() != 1) {
                String packs = stack.stream().map(Resource::sourcePackId).toList().toString();
                problems.add(new RpgDefinitionSnapshot.Problem(file, definitionId,
                        "duplicate " + kind + " definition ID provided by packs " + packs));
                return;
            }

            Resource resource = stack.getFirst();
            try (var input = resource.open()) {
                byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
                if (bytes.length > MAX_FILE_BYTES) {
                    throw new IllegalArgumentException("file exceeds " + MAX_FILE_BYTES + " bytes");
                }
                var json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
                T definition = codec.parse(JsonOps.INSTANCE, json).getOrThrow(IllegalArgumentException::new);
                candidates.add(factory.create(file, resource.sourcePackId(), definitionId, definition));
            } catch (IOException | RuntimeException exception) {
                String cause = exception.getMessage() == null
                        ? exception.getClass().getSimpleName()
                        : exception.getMessage();
                problems.add(new RpgDefinitionSnapshot.Problem(file, definitionId, cause));
            }
        });
    }

    @Override
    protected void apply(RpgDefinitionSnapshot prepared, ResourceManager resourceManager, ProfilerFiller profiler) {
        store.install(prepared);
        lastProblems.set(List.of());
        RpgActiveSkillRuntime.clearAll();
        LOGGER.info("Loaded {} activities, {} careers, and {} skills for Rovenfall RPG definitions",
                prepared.activities().size(), prepared.careers().size(), prepared.skills().size());
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            RpgSkillNetwork.syncAll(server);
        }
    }

    public RpgDefinitionSnapshot snapshot() {
        return store.current();
    }

    public long revision() {
        return store.revision();
    }

    public List<RpgDefinitionSnapshot.Problem> lastProblems() {
        return lastProblems.get();
    }

    public void beginValidationAttempt() {
        lastProblems.set(List.of());
    }

    RpgDefinitionStore.VersionedSnapshot versioned() {
        return store.versioned();
    }

    public static RpgDefinitionSnapshot snapshot(MinecraftServer server) {
        RpgDefinitionReloadListener listener = server.getServerResources().managers().getListener(KEY);
        return listener == null ? RpgDefinitionSnapshot.empty() : listener.snapshot();
    }

    public static long revision(MinecraftServer server) {
        RpgDefinitionReloadListener listener = server.getServerResources().managers().getListener(KEY);
        return listener == null ? 0 : listener.revision();
    }

    public static List<RpgDefinitionSnapshot.Problem> lastProblems(MinecraftServer server) {
        RpgDefinitionReloadListener listener = server.getServerResources().managers().getListener(KEY);
        return listener == null ? List.of() : listener.lastProblems();
    }

    public static void beginValidationAttempt(MinecraftServer server) {
        RpgDefinitionReloadListener listener = server.getServerResources().managers().getListener(KEY);
        if (listener != null) {
            listener.beginValidationAttempt();
        }
    }

    static RpgDefinitionStore.VersionedSnapshot versioned(MinecraftServer server) {
        RpgDefinitionReloadListener listener = server.getServerResources().managers().getListener(KEY);
        return listener == null
                ? new RpgDefinitionStore.VersionedSnapshot(RpgDefinitionSnapshot.empty(), 0)
                : listener.versioned();
    }

    @FunctionalInterface
    private interface SourceFactory<T, S> {
        S create(Identifier file, String packId, Identifier id, T definition);
    }
}
