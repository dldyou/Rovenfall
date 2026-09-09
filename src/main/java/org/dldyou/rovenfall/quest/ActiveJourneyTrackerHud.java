package org.dldyou.rovenfall.quest;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import org.dldyou.rovenfall.Rovenfall;
import org.dldyou.rovenfall.world.WorldTopology;

/** Code-drawn dark-fantasy card for the currently selected journey objective. */
public final class ActiveJourneyTrackerHud {
    static final int SCREEN_MARGIN = 8;
    static final int MIN_CARD_WIDTH = 140;
    static final int MAX_CARD_WIDTH = 260;
    static final int HORIZONTAL_PADDING = 8;
    static final int VERTICAL_PADDING = 7;
    static final int LINE_SPACING = 2;
    static final int MAP_CELLS = 17;
    static final int MAP_SAMPLE_STEP = 4;
    static final int MAP_PANEL_SIZE = 76;
    static final int PANEL_GAP = 6;
    private static final int BACKGROUND = 0xE6120D09;
    private static final int HEADER = 0xF02A1A11;
    private static final int GOLD = 0xFFB89445;
    private static final int GOLD_DARK = 0xFF6E4D24;
    private static final int TEXT_PRIMARY = 0xFFF1E4C5;
    private static final int TEXT_MUTED = 0xFFB9A783;
    private static final int MAP_UNLOADED = 0xFF171E1B;
    private static final int PLAYER_MARKER = 0xFFFFD866;
    private static final Identifier LAYER_ID = Identifier.fromNamespaceAndPath(
            Rovenfall.MOD_ID, "active_journey_tracker");
    private static MinimapSnapshot minimapSnapshot;
    private static long nextMinimapUpdate;

    private ActiveJourneyTrackerHud() {
    }

    static void register(IEventBus modBus) {
        modBus.addListener(ActiveJourneyTrackerHud::registerGuiLayers);
    }

    private static void registerGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.HOTBAR, LAYER_ID, ActiveJourneyTrackerHud::render);
    }

    private static void render(GuiGraphicsExtractor graphics, net.minecraft.client.DeltaTracker ignored) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gui.hud.isHidden() || minecraft.gui.screen() != null
                || minecraft.player == null || minecraft.level == null) {
            return;
        }
        ActiveJourneyTrackerClient.DisplayMode displayMode =
                ActiveJourneyTrackerClient.displayMode();
        if (displayMode == ActiveJourneyTrackerClient.DisplayMode.HIDDEN) {
            return;
        }
        ActiveJourneyTrackerPayloads.Snapshot snapshot = ActiveJourneyTrackerClient.current()
                .filter(ActiveJourneyTrackerPayloads.Snapshot::active)
                .orElse(null);

        Font font = minecraft.font;
        int panelWidth = Math.min(MAX_CARD_WIDTH,
                Math.max(1, graphics.guiWidth() - SCREEN_MARGIN * 2));
        int trackerY = SCREEN_MARGIN;
        if (displayMode == ActiveJourneyTrackerClient.DisplayMode.FULL) {
            renderEnvironment(graphics, minecraft, panelWidth, font);
            trackerY += MAP_PANEL_SIZE + PANEL_GAP;
        }

        List<Component> lines = snapshot == null
                ? List.of(
                        Component.translatable("hud.rovenfall.journey.tracker.panel"),
                        Component.translatable("hud.rovenfall.journey.tracker.none"))
                : lines(snapshot);
        int contentWidth = lines.stream().mapToInt(font::width).max().orElse(0);
        Bounds bounds = layoutAt(
                graphics.guiWidth(), contentWidth, lines.size(), font.lineHeight,
                trackerY);
        graphics.fill(bounds.x(), bounds.y(), bounds.right(), bounds.bottom(), BACKGROUND);
        graphics.outline(bounds.x(), bounds.y(), bounds.width(), bounds.height(), GOLD_DARK);
        graphics.fill(bounds.x() + 2, bounds.y() + 2, bounds.right() - 2,
                bounds.y() + VERTICAL_PADDING + font.lineHeight + 1, HEADER);
        graphics.fill(bounds.x() + 2, bounds.y() + VERTICAL_PADDING + font.lineHeight + 1,
                bounds.right() - 2, bounds.y() + VERTICAL_PADDING + font.lineHeight + 2, GOLD);

        int textX = bounds.x() + HORIZONTAL_PADDING;
        int textWidth = bounds.width() - HORIZONTAL_PADDING * 2;
        int y = bounds.y() + VERTICAL_PADDING;
        graphics.enableScissor(textX, bounds.y(), textX + textWidth, bounds.bottom());
        try {
            for (int index = 0; index < lines.size(); index++) {
                graphics.text(font, lines.get(index), textX, y,
                        index == 0 ? TEXT_PRIMARY : TEXT_MUTED, false);
                y += font.lineHeight + LINE_SPACING;
            }
        } finally {
            graphics.disableScissor();
        }
    }

    static List<Component> lines(ActiveJourneyTrackerPayloads.Snapshot snapshot) {
        List<Component> lines = new ArrayList<>(4);
        lines.add(Component.translatable(snapshot.titleTranslationKey()));
        lines.add(Component.translatable(
                "hud.rovenfall.journey.tracker.state",
                Component.translatable("hud.rovenfall.journey.tracker.kind."
                        + snapshot.journeyKind().name().toLowerCase(java.util.Locale.ROOT)),
                Component.translatable("hud.rovenfall.journey.tracker.status."
                        + snapshot.status().name().toLowerCase(java.util.Locale.ROOT))));
        lines.add(objective(snapshot));
        if (snapshot.journeyKind() == ActiveJourneyTrackerPayloads.JourneyKind.DAILY) {
            lines.add(Component.translatable("hud.rovenfall.journey.tracker.refresh.daily"));
        } else if (snapshot.journeyKind() == ActiveJourneyTrackerPayloads.JourneyKind.WEEKLY) {
            lines.add(Component.translatable("hud.rovenfall.journey.tracker.refresh.weekly"));
        }
        return List.copyOf(lines);
    }

    static Bounds layout(int screenWidth, int contentWidth, int lineCount, int lineHeight) {
        return layoutAt(screenWidth, contentWidth, lineCount, lineHeight, SCREEN_MARGIN);
    }

    private static Bounds layoutAt(
            int screenWidth,
            int contentWidth,
            int lineCount,
            int lineHeight,
            int y) {
        if (screenWidth < 1 || contentWidth < 0 || lineCount < 1 || lineHeight < 1) {
            throw new IllegalArgumentException("invalid active journey tracker layout request");
        }
        int availableWidth = Math.max(1, screenWidth - SCREEN_MARGIN * 2);
        int desiredWidth = Math.clamp(contentWidth + HORIZONTAL_PADDING * 2,
                MIN_CARD_WIDTH, MAX_CARD_WIDTH);
        int width = Math.min(availableWidth, desiredWidth);
        int height = VERTICAL_PADDING * 2 + lineCount * lineHeight
                + (lineCount - 1) * LINE_SPACING;
        int x = Math.max(0, screenWidth - SCREEN_MARGIN - width);
        return new Bounds(x, y, width, height);
    }

    private static void renderEnvironment(
            GuiGraphicsExtractor graphics,
            Minecraft minecraft,
            int panelWidth,
            Font font) {
        int right = graphics.guiWidth() - SCREEN_MARGIN;
        int left = right - panelWidth;
        int mapSize = Math.min(MAP_PANEL_SIZE, panelWidth);
        int mapX = right - mapSize;
        int top = SCREEN_MARGIN;
        renderMinimap(graphics, minimap(minecraft), mapX, top, mapSize, font);

        int infoRight = mapX - PANEL_GAP;
        if (infoRight - left < 48) {
            return;
        }
        graphics.fill(left, top, infoRight, top + MAP_PANEL_SIZE, BACKGROUND);
        graphics.outline(left, top, infoRight - left, MAP_PANEL_SIZE, GOLD_DARK);
        List<Component> information = locationLines(minecraft);
        int textX = left + HORIZONTAL_PADDING;
        int textWidth = infoRight - left - HORIZONTAL_PADDING * 2;
        int y = top + VERTICAL_PADDING;
        graphics.enableScissor(textX, top, textX + textWidth, top + MAP_PANEL_SIZE);
        try {
            for (int index = 0; index < information.size(); index++) {
                graphics.text(font, information.get(index), textX, y,
                        index == 0 ? TEXT_PRIMARY : TEXT_MUTED, false);
                y += font.lineHeight + LINE_SPACING;
            }
        } finally {
            graphics.disableScissor();
        }
    }

    private static void renderMinimap(
            GuiGraphicsExtractor graphics,
            MinimapSnapshot snapshot,
            int x,
            int y,
            int size,
            Font font) {
        graphics.fill(x, y, x + size, y + size, BACKGROUND);
        graphics.outline(x, y, size, size, GOLD_DARK);
        int cellSize = Math.max(2, Math.min(4, (size - 8) / MAP_CELLS));
        int mapSize = cellSize * MAP_CELLS;
        int mapX = x + (size - mapSize) / 2;
        int mapY = y + (size - mapSize) / 2;
        for (int row = 0; row < MAP_CELLS; row++) {
            for (int column = 0; column < MAP_CELLS; column++) {
                int color = snapshot.colors()[row * MAP_CELLS + column];
                graphics.fill(
                        mapX + column * cellSize,
                        mapY + row * cellSize,
                        mapX + (column + 1) * cellSize,
                        mapY + (row + 1) * cellSize,
                        color);
            }
        }
        int centerX = mapX + mapSize / 2;
        int centerY = mapY + mapSize / 2;
        graphics.fill(centerX - 2, centerY, centerX + 3, centerY + 1, PLAYER_MARKER);
        graphics.fill(centerX, centerY - 2, centerX + 1, centerY + 3, PLAYER_MARKER);
        graphics.text(font, "N", centerX - font.width("N") / 2, mapY + 1, TEXT_PRIMARY, true);
    }

    private static MinimapSnapshot minimap(Minecraft minecraft) {
        ClientLevel level = minecraft.level;
        BlockPos center = minecraft.player.blockPosition();
        long gameTime = level.getGameTime();
        if (minimapSnapshot == null
                || !minimapSnapshot.dimension().equals(level.dimension())
                || Math.abs(center.getX() - minimapSnapshot.centerX()) >= MAP_SAMPLE_STEP
                || Math.abs(center.getZ() - minimapSnapshot.centerZ()) >= MAP_SAMPLE_STEP
                || gameTime >= nextMinimapUpdate) {
            minimapSnapshot = sample(level, center);
            nextMinimapUpdate = gameTime + 10;
        }
        return minimapSnapshot;
    }

    private static MinimapSnapshot sample(ClientLevel level, BlockPos center) {
        int[] colors = new int[MAP_CELLS * MAP_CELLS];
        int radius = MAP_CELLS / 2;
        for (int row = 0; row < MAP_CELLS; row++) {
            for (int column = 0; column < MAP_CELLS; column++) {
                int x = center.getX() + (column - radius) * MAP_SAMPLE_STEP;
                int z = center.getZ() + (row - radius) * MAP_SAMPLE_STEP;
                BlockPos loadedCheck = new BlockPos(x, center.getY(), z);
                if (!level.hasChunkAt(loadedCheck)) {
                    colors[row * MAP_CELLS + column] = MAP_UNLOADED;
                    continue;
                }
                int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                BlockPos surface = new BlockPos(x, surfaceY, z);
                MapColor color = level.getBlockState(surface).getMapColor(level, surface);
                MapColor.Brightness brightness = surfaceY > center.getY() + 4
                        ? MapColor.Brightness.HIGH
                        : surfaceY < center.getY() - 4
                                ? MapColor.Brightness.LOW
                                : MapColor.Brightness.NORMAL;
                int resolved = color.calculateARGBColor(brightness);
                colors[row * MAP_CELLS + column] = resolved == 0 ? MAP_UNLOADED : resolved;
            }
        }
        return new MinimapSnapshot(level.dimension(), center.getX(), center.getZ(), colors);
    }

    static List<Component> locationLines(Minecraft minecraft) {
        BlockPos position = minecraft.player.blockPosition();
        Component biome = minecraft.level.getBiome(position).unwrapKey()
                .<Component>map(key -> Component.translatable(key.identifier().toLanguageKey("biome")))
                .orElseGet(() -> Component.translatable("hud.rovenfall.location.unknown"));
        return List.of(
                dimensionName(minecraft.level.dimension()),
                biome,
                Component.translatable(
                        "hud.rovenfall.location.coordinates",
                        position.getX(), position.getY(), position.getZ()),
                Component.translatable(
                        "hud.rovenfall.location.heading",
                        Component.translatable(directionKey(minecraft.player.getYRot()))));
    }

    private static Component dimensionName(ResourceKey<Level> dimension) {
        if (dimension.equals(WorldTopology.HUB)) {
            return Component.translatable("hud.rovenfall.location.hub");
        }
        if (dimension.equals(WorldTopology.WILDERNESS)) {
            return Component.translatable("hud.rovenfall.location.wilderness");
        }
        if (dimension.equals(Level.NETHER)) {
            return Component.translatable("hud.rovenfall.location.nether");
        }
        if (dimension.equals(Level.END)) {
            return Component.translatable("hud.rovenfall.location.end");
        }
        return Component.literal(dimension.identifier().toString());
    }

    static String directionKey(float yaw) {
        String[] directions = {
                "south", "south_west", "west", "north_west",
                "north", "north_east", "east", "south_east"
        };
        int index = Math.floorMod(Mth.floor(yaw / 45.0F + 0.5F), directions.length);
        return "hud.rovenfall.direction." + directions[index];
    }

    static void clearCache() {
        minimapSnapshot = null;
        nextMinimapUpdate = 0;
    }

    private static Component objective(ActiveJourneyTrackerPayloads.Snapshot snapshot) {
        String suffix = snapshot.objectiveKind().name().toLowerCase(java.util.Locale.ROOT);
        if (snapshot.objectiveKind() != ActiveJourneyTrackerPayloads.ObjectiveKind.ACTIVITY) {
            return Component.translatable(
                    "hud.rovenfall.journey.tracker.objective." + suffix,
                    snapshot.progress(), snapshot.requiredCount());
        }
        Component target = snapshot.activityTargetTranslationKey().isEmpty()
                ? Component.translatable("hud.rovenfall.journey.tracker.objective.activity_unknown")
                : Component.translatable(snapshot.activityTargetTranslationKey());
        return Component.translatable(
                "hud.rovenfall.journey.tracker.objective.activity",
                target, snapshot.progress(), snapshot.requiredCount());
    }

    record Bounds(int x, int y, int width, int height) {
        int right() {
            return x + width;
        }

        int bottom() {
            return y + height;
        }
    }

    private record MinimapSnapshot(
            ResourceKey<Level> dimension,
            int centerX,
            int centerZ,
            int[] colors) {
    }
}
