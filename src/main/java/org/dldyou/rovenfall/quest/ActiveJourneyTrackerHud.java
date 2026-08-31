package org.dldyou.rovenfall.quest;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import org.dldyou.rovenfall.Rovenfall;

/** Code-drawn dark-fantasy card for the currently selected journey objective. */
public final class ActiveJourneyTrackerHud {
    static final int SCREEN_MARGIN = 8;
    static final int MIN_CARD_WIDTH = 140;
    static final int MAX_CARD_WIDTH = 260;
    static final int HORIZONTAL_PADDING = 8;
    static final int VERTICAL_PADDING = 7;
    static final int LINE_SPACING = 2;
    private static final int BACKGROUND = 0xE6120D09;
    private static final int HEADER = 0xF02A1A11;
    private static final int GOLD = 0xFFB89445;
    private static final int GOLD_DARK = 0xFF6E4D24;
    private static final int TEXT_PRIMARY = 0xFFF1E4C5;
    private static final int TEXT_MUTED = 0xFFB9A783;
    private static final Identifier LAYER_ID = Identifier.fromNamespaceAndPath(
            Rovenfall.MOD_ID, "active_journey_tracker");

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
        ActiveJourneyTrackerPayloads.Snapshot snapshot = ActiveJourneyTrackerClient.current()
                .filter(ActiveJourneyTrackerPayloads.Snapshot::active)
                .orElse(null);
        if (snapshot == null) {
            return;
        }

        List<Component> lines = lines(snapshot);
        Font font = minecraft.font;
        int contentWidth = lines.stream().mapToInt(font::width).max().orElse(0);
        Bounds bounds = layout(graphics.guiWidth(), contentWidth, lines.size(), font.lineHeight);
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
        return new Bounds(x, SCREEN_MARGIN, width, height);
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
}
