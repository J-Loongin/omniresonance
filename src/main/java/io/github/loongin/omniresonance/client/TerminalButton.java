// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** Terminal-themed button with a text state in addition to its shared colors. */
final class TerminalButton extends TerminalClickButton {
    private final boolean primary;
    private boolean selected;
    private boolean danger;

    TerminalButton(int x, int y, int width, int height, Component message, OnPress onPress, boolean primary) {
        super(x, y, width, height, TerminalText.body(message), onPress);
        this.primary = primary;
        if (message.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents text) {
            String key = text.getKey();
            danger = key.contains(".delete")
                    || key.contains(".discard")
                    || key.contains(".terminate")
                    || key.contains(".unpair")
                    || key.contains(".pair_close")
                    || key.contains(".revoke")
                    || key.contains(".remove")
                    || key.endsWith(".binding.exit")
                    || key.endsWith(".disable");
        }
    }

    void setSelected(boolean selected) {
        this.selected = selected;
    }

    void setDanger(boolean danger) {
        this.danger = danger;
    }

    TerminalTheme.ControlStyle controlStyle() {
        return TerminalTheme.controlStyle(active, isHovered, isFocused(), selected, primary, danger);
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        var style = controlStyle();
        TerminalTheme.renderControl(graphics, new TerminalLayout.Rect(getX(), getY(), getWidth(), getHeight()), style);
        int color = style.text();
        Font font = TerminalText.font(Minecraft.getInstance());
        Component shown = getMessage();
        int shownWidth = font.width(shown);
        int available = Math.max(0, getWidth() - 8);
        if (shownWidth > available) {
            shown = TerminalText.body(Component.literal(TerminalText.ellipsize(font, shown.getString(), available)));
            shownWidth = font.width(shown);
        }
        TerminalText.drawControlText(
                shown,
                shownWidth,
                new TerminalLayout.Rect(getX(), getY(), getWidth(), getHeight()),
                color,
                (text, x, y, tint, shadow) -> graphics.drawString(font, text, x, y, tint, shadow));
    }
}
