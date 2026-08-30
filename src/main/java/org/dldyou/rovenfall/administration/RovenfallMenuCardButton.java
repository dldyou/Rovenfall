package org.dldyou.rovenfall.administration;

import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

/** Native button behavior with an item icon and compact menu-card copy. */
final class RovenfallMenuCardButton extends Button {
    private final Font font;
    private final ItemStack item;
    private final Component summary;
    private final List<Component> detailLines;

    RovenfallMenuCardButton(
            Builder builder,
            Font font,
            ItemStack item,
            Component summary,
            List<Component> detailLines) {
        super(builder);
        this.font = font;
        this.item = item.copy();
        this.summary = summary;
        this.detailLines = List.copyOf(detailLines);
    }

    ItemStack item() {
        return item;
    }

    List<Component> detailLines() {
        return detailLines;
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        RovenfallUiTheme.extractButton(
                graphics, getX(), getY(), getWidth(), getHeight(), active, isHoveredOrFocused());
        int iconY = getY() + Math.max(4, (getHeight() - 16) / 2);
        graphics.item(item, getX() + 6, iconY);
        graphics.itemDecorations(font, item, getX() + 6, iconY);
        int textX = getX() + 27;
        int textWidth = Math.max(1, getWidth() - 32);
        graphics.enableScissor(textX, getY() + 2, getX() + getWidth() - 3, getY() + getHeight() - 2);
        try {
            graphics.text(font, firstLine(getMessage(), textWidth), textX, getY() + 7,
                    RovenfallUiTheme.TEXT_PRIMARY, false);
            if (getHeight() >= RovenfallPlayerMenuLayout.CARD_HEIGHT && !summary.getString().isBlank()) {
                graphics.text(font, firstLine(summary, textWidth), textX, getY() + 23,
                        RovenfallUiTheme.TEXT_MUTED, false);
            }
        } finally {
            graphics.disableScissor();
        }
    }

    private FormattedCharSequence firstLine(Component text, int width) {
        List<FormattedCharSequence> lines = font.split(text, width);
        return lines.isEmpty() ? FormattedCharSequence.EMPTY : lines.getFirst();
    }
}
