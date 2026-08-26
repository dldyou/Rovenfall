package org.dldyou.rovenfall.mobs;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.neoforge.resource.ListenerKey;
import org.dldyou.rovenfall.Rovenfall;
import org.slf4j.Logger;

public final class MobContentReloadListener extends SimplePreparableReloadListener<MobContentSnapshot> {
    public static final ListenerKey<MobContentReloadListener> KEY = ListenerKey.create(
            Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "mob_content"));
    private static final FileToIdConverter FILES = FileToIdConverter.json(Rovenfall.MOD_ID + "/mob_content");
    private static final int MAX_FILE_BYTES = 262_144;
    private static final Logger LOGGER = LogUtils.getLogger();

    private final MobContentStore store = new MobContentStore();

    @Override
    protected MobContentSnapshot prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        RegistryOps<JsonElement> ops = RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, getRegistryLookup());
        var resources = FILES.listMatchingResourceStacks(resourceManager);
        if (resources.size() > MobContentSnapshot.MAX_CATALOGS) {
            Identifier catalog = Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "mob_content_catalog");
            throw new MobContentSnapshot.ValidationException(List.of(new MobContentSnapshot.Problem(
                    catalog, catalog, "catalog count exceeds " + MobContentSnapshot.MAX_CATALOGS)));
        }

        List<MobContentSnapshot.Source> candidates = new ArrayList<>();
        List<MobContentSnapshot.Problem> problems = new ArrayList<>();
        resources.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> loadResource(entry.getKey(), entry.getValue(), ops, candidates, problems));
        if (!problems.isEmpty()) {
            throw new MobContentSnapshot.ValidationException(problems);
        }
        return MobContentSnapshot.compile(candidates);
    }

    private void loadResource(
            Identifier file,
            List<Resource> stack,
            RegistryOps<JsonElement> ops,
            List<MobContentSnapshot.Source> candidates,
            List<MobContentSnapshot.Problem> problems) {
        Identifier catalogId = FILES.fileToId(file);
        if (stack.size() != 1) {
            String packs = stack.stream().map(Resource::sourcePackId).toList().toString();
            problems.add(new MobContentSnapshot.Problem(
                    file, catalogId, "duplicate catalog provided by packs " + packs));
            return;
        }

        Resource resource = stack.getFirst();
        try (var input = resource.open()) {
            byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) {
                throw new IllegalArgumentException("file exceeds " + MAX_FILE_BYTES + " bytes");
            }
            var json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            MobContentCatalog catalog = MobContentCatalog.CODEC.parse(ops, json)
                    .getOrThrow(IllegalArgumentException::new);
            candidates.add(new MobContentSnapshot.Source(file, resource.sourcePackId(), catalogId, catalog));
        } catch (IOException | RuntimeException exception) {
            String cause = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            problems.add(new MobContentSnapshot.Problem(file, catalogId, cause));
        }
    }

    @Override
    protected void apply(MobContentSnapshot prepared, ResourceManager resourceManager, ProfilerFiller profiler) {
        try {
            store.install(prepared.validateRuntimeBindings(runtimeBindings()));
            LOGGER.info("Loaded {} validated Rovenfall mob content definitions", prepared.size());
        } catch (MobContentSnapshot.ValidationException exception) {
            for (MobContentSnapshot.Problem problem : exception.problems()) {
                LOGGER.error("Rejected Rovenfall mob content file={} definition={} cause={}",
                        problem.file(), problem.definitionId(), problem.cause());
            }
        }
    }

    private MobContentSnapshot.RuntimeBindings runtimeBindings() {
        // Issue #30 will switch this single seam to strict(...) once the Wilderness dimension is registered.
        // Until then only the canonical Wilderness key may be unbound; Hub/unknown dimensions and every loot table fail.
        return MobContentSnapshot.RuntimeBindings.awaitingWildernessRegistration(getRegistryLookup());
    }

    public MobContentSnapshot snapshot() {
        return store.current();
    }

    public static Optional<MobContentCatalog.MobDefinition> mob(MinecraftServer server, Identifier id) {
        return snapshot(server).mob(id);
    }

    public static Optional<MobContentCatalog.MutationDefinition> mutation(MinecraftServer server, Identifier id) {
        return snapshot(server).mutation(id);
    }

    public static Optional<MobContentCatalog.BossDefinition> boss(MinecraftServer server, Identifier id) {
        return snapshot(server).boss(id);
    }

    public static MobContentSnapshot snapshot(MinecraftServer server) {
        MobContentReloadListener listener = server.getServerResources().managers().getListener(KEY);
        return listener == null ? MobContentSnapshot.empty() : listener.snapshot();
    }
}
