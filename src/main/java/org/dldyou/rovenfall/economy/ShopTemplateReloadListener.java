package org.dldyou.rovenfall.economy;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.neoforge.resource.ListenerKey;
import net.neoforged.neoforge.event.DefaultDataComponentsBoundEvent;
import org.dldyou.rovenfall.Rovenfall;
import org.slf4j.Logger;

public final class ShopTemplateReloadListener extends SimplePreparableReloadListener<ShopTemplateSnapshot> {
    public static final ListenerKey<ShopTemplateReloadListener> KEY = ListenerKey.create(
            Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "shop_templates"));
    private static final FileToIdConverter FILES = FileToIdConverter.json(Rovenfall.MOD_ID + "/shop_templates");
    private static final int MAX_FILE_BYTES = 65_536;
    private static final Logger LOGGER = LogUtils.getLogger();

    private final ShopTemplateStore store = new ShopTemplateStore();
    private final AtomicReference<ShopTemplateSnapshot> pending = new AtomicReference<>();

    @Override
    protected ShopTemplateSnapshot prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        RegistryOps<JsonElement> ops = RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, getRegistryLookup());
        var resources = FILES.listMatchingResourceStacks(resourceManager);
        if (resources.size() > ShopTemplateSnapshot.MAX_TEMPLATES) {
            Identifier catalog = Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "shop_template_catalog");
            throw new ShopTemplateSnapshot.ValidationException(List.of(new ShopTemplateSnapshot.Problem(
                    catalog, catalog, "template count exceeds " + ShopTemplateSnapshot.MAX_TEMPLATES)));
        }

        List<ShopTemplateSnapshot.Source> candidates = new ArrayList<>();
        List<ShopTemplateSnapshot.Problem> problems = new ArrayList<>();
        resources.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> loadResource(entry.getKey(), entry.getValue(), ops, candidates, problems));
        if (!problems.isEmpty()) {
            throw new ShopTemplateSnapshot.ValidationException(problems);
        }
        return ShopTemplateSnapshot.compile(candidates);
    }

    private void loadResource(
            Identifier file,
            List<Resource> stack,
            RegistryOps<JsonElement> ops,
            List<ShopTemplateSnapshot.Source> candidates,
            List<ShopTemplateSnapshot.Problem> problems) {
        Identifier templateId = FILES.fileToId(file);
        if (stack.size() != 1) {
            String packs = stack.stream().map(Resource::sourcePackId).toList().toString();
            problems.add(new ShopTemplateSnapshot.Problem(
                    file, templateId, "duplicate template ID provided by packs " + packs));
            return;
        }

        Resource resource = stack.getFirst();
        try (var input = resource.open()) {
            byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) {
                throw new IllegalArgumentException("file exceeds " + MAX_FILE_BYTES + " bytes");
            }
            var json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            ShopTemplate template = ShopTemplate.CODEC.parse(ops, json).getOrThrow(IllegalArgumentException::new);
            candidates.add(new ShopTemplateSnapshot.Source(file, resource.sourcePackId(), templateId, template));
        } catch (IOException | RuntimeException exception) {
            String cause = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            problems.add(new ShopTemplateSnapshot.Problem(file, templateId, cause));
        }
    }

    @Override
    protected void apply(ShopTemplateSnapshot prepared, ResourceManager resourceManager, ProfilerFiller profiler) {
        pending.set(prepared);
    }

    public void onDefaultDataComponentsBound(DefaultDataComponentsBoundEvent event) {
        if (event.getUpdateCause() != DefaultDataComponentsBoundEvent.UpdateCause.SERVER_DATA_LOAD) {
            return;
        }
        installPending();
    }

    boolean installPending() {
        ShopTemplateSnapshot prepared = pending.getAndSet(null);
        if (prepared == null) {
            return false;
        }
        try {
            prepared.validateBoundItems();
            store.install(prepared);
            LOGGER.info("Loaded {} validated Rovenfall shop templates", prepared.size());
            return true;
        } catch (ShopTemplateSnapshot.ValidationException exception) {
            for (ShopTemplateSnapshot.Problem problem : exception.problems()) {
                LOGGER.error("Rejected Rovenfall shop template file={} template={} cause={}",
                        problem.file(), problem.templateId(), problem.cause());
            }
            return false;
        }
    }

    public ShopTemplateSnapshot snapshot() {
        return store.current();
    }

    public static Optional<ShopTemplate> get(MinecraftServer server, Identifier id) {
        ShopTemplateReloadListener listener = server.getServerResources().managers().getListener(KEY);
        return listener == null ? Optional.empty() : listener.snapshot().get(id);
    }
}
