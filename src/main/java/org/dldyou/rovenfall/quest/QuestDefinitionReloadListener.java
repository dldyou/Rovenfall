package org.dldyou.rovenfall.quest;

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

public final class QuestDefinitionReloadListener
        extends SimplePreparableReloadListener<QuestDefinitionSnapshot> {
    public static final ListenerKey<QuestDefinitionReloadListener> KEY = ListenerKey.create(
            Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "quest_definitions"));
    private static final FileToIdConverter FILES = FileToIdConverter.json(Rovenfall.MOD_ID + "/quests");
    private static final int MAX_FILE_BYTES = 65_536;
    private static final Logger LOGGER = LogUtils.getLogger();

    private final QuestDefinitionStore store = new QuestDefinitionStore();
    private final AtomicReference<List<QuestDefinitionSnapshot.Problem>> lastProblems =
            new AtomicReference<>(List.of());

    @Override
    protected QuestDefinitionSnapshot prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<Identifier, List<Resource>> resources = FILES.listMatchingResourceStacks(resourceManager);
        if (resources.size() > QuestDefinitionSnapshot.MAX_DEFINITIONS) {
            Identifier catalog = Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "quest_definition_catalog");
            List<QuestDefinitionSnapshot.Problem> problems = List.of(new QuestDefinitionSnapshot.Problem(
                    catalog, catalog,
                    "quest definition count exceeds " + QuestDefinitionSnapshot.MAX_DEFINITIONS));
            lastProblems.set(problems);
            throw new QuestDefinitionSnapshot.ValidationException(problems);
        }

        List<QuestDefinitionSnapshot.Source> candidates = new ArrayList<>();
        List<QuestDefinitionSnapshot.Problem> problems = new ArrayList<>();
        resources.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> load(entry.getKey(), entry.getValue(), candidates, problems));
        if (!problems.isEmpty()) {
            lastProblems.set(List.copyOf(problems));
            throw new QuestDefinitionSnapshot.ValidationException(problems);
        }
        try {
            return QuestDefinitionSnapshot.compile(candidates);
        } catch (QuestDefinitionSnapshot.ValidationException exception) {
            lastProblems.set(exception.problems());
            throw exception;
        }
    }

    private static void load(
            Identifier file,
            List<Resource> stack,
            List<QuestDefinitionSnapshot.Source> candidates,
            List<QuestDefinitionSnapshot.Problem> problems) {
        Identifier questId = FILES.fileToId(file);
        if (stack.size() != 1) {
            String packs = stack.stream().map(Resource::sourcePackId).toList().toString();
            problems.add(new QuestDefinitionSnapshot.Problem(
                    file, questId, "duplicate quest definition ID provided by packs " + packs));
            return;
        }

        Resource resource = stack.getFirst();
        try (var input = resource.open()) {
            byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) {
                throw new IllegalArgumentException("file exceeds " + MAX_FILE_BYTES + " bytes");
            }
            var json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            QuestDefinition definition = QuestDefinition.CODEC.parse(JsonOps.INSTANCE, json)
                    .getOrThrow(IllegalArgumentException::new);
            candidates.add(new QuestDefinitionSnapshot.Source(
                    file, resource.sourcePackId(), questId, definition));
        } catch (IOException | RuntimeException exception) {
            String cause = exception.getMessage() == null
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage();
            problems.add(new QuestDefinitionSnapshot.Problem(file, questId, cause));
        }
    }

    @Override
    protected void apply(
            QuestDefinitionSnapshot prepared, ResourceManager resourceManager, ProfilerFiller profiler) {
        store.install(prepared);
        lastProblems.set(List.of());
        LOGGER.info("Loaded {} Rovenfall quest definitions", prepared.size());
    }

    public QuestDefinitionSnapshot snapshot() {
        return store.current();
    }

    public long revision() {
        return store.revision();
    }

    public List<QuestDefinitionSnapshot.Problem> lastProblems() {
        return lastProblems.get();
    }

    public void beginValidationAttempt() {
        lastProblems.set(List.of());
    }

    public static QuestDefinitionSnapshot snapshot(MinecraftServer server) {
        QuestDefinitionReloadListener listener = server.getServerResources().managers().getListener(KEY);
        return listener == null ? QuestDefinitionSnapshot.empty() : listener.snapshot();
    }

    public static long revision(MinecraftServer server) {
        QuestDefinitionReloadListener listener = server.getServerResources().managers().getListener(KEY);
        return listener == null ? 0 : listener.revision();
    }

    public static List<QuestDefinitionSnapshot.Problem> lastProblems(MinecraftServer server) {
        QuestDefinitionReloadListener listener = server.getServerResources().managers().getListener(KEY);
        return listener == null ? List.of() : listener.lastProblems();
    }

    public static void beginValidationAttempt(MinecraftServer server) {
        QuestDefinitionReloadListener listener = server.getServerResources().managers().getListener(KEY);
        if (listener != null) {
            listener.beginValidationAttempt();
        }
    }
}
