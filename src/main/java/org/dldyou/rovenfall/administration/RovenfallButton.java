package org.dldyou.rovenfall.administration;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;

/** A native widget with Rovenfall visuals, focus behavior, sound, and narration. */
final class RovenfallButton extends Button {
    RovenfallButton(Builder builder) {
        super(builder);
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        RovenfallUiTheme.extractButton(
                graphics,
                getX(),
                getY(),
                getWidth(),
                getHeight(),
                active,
                isHovered(),
                isFocused());
        extractDefaultLabel(graphics.textRendererForWidget(this, GuiGraphicsExtractor.HoveredTextEffects.NONE));
    }
}
