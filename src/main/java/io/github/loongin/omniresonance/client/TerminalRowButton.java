// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/** Left-aligned management row with a quiet surface and a scoped focus/selection marker. */
final class TerminalRowButton extends TerminalClickButton {
    private boolean selected;
    private boolean readOnly;
    private boolean navigationIndicator;
    private int textInset = 7;
    private @Nullable Component statusPrefix;
    private @Nullable Component statusSuffix;

    TerminalRowButton(int x, int y, int width, int height, Component message, OnPress onPress) {
        super(x, y, width, height, TerminalText.body(message), onPress);
    }

    TerminalRowButton(TerminalLayout.Rect bounds, Component message, OnPress onPress) {
        this(bounds.x(), bounds.y(), bounds.width(), bounds.height(), message, onPress);
    }

    /** Uses the native inactive-input path without presenting readable information as disabled. */
    void setReadOnly() {
        readOnly = true;
        active = false;
    }

    int textColor() {
        return active || readOnly ? TerminalTheme.TEXT : TerminalTheme.DISABLED_TEXT;
    }

    boolean highlighted() {
        return !readOnly && active && (selected || isHoveredOrFocused());
    }

    void setSelected(boolean selected) {
        this.selected = selected;
    }

    void setNavigationIndicator(boolean value) {
        navigationIndicator = value;
    }

    void setTextInset(int value) {
        textInset = value;
    }

    void statusSuffix(Component prefix, Component suffix) {
        statusPrefix = TerminalText.body(prefix);
        statusSuffix = TerminalText.body(suffix);
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        var style = TerminalTheme.controlStyle(
                active || readOnly,
                active && !readOnly && isHovered,
                active && !readOnly && isFocused(),
                active && !readOnly && selected,
                false,
                false);
        boolean mark = active && !readOnly && selected && getWidth() >= 26;
        boolean arrow = !mark && navigationIndicator && getWidth() >= 26;
        TerminalTheme.renderControl(graphics, new TerminalLayout.Rect(getX(), getY(), getWidth(), getHeight()), style);
        if (mark)
            TerminalTheme.renderSelectionMark(graphics, getRight() - 13, getY() + (getHeight() - 6) / 2, style.text());
        if (arrow) TerminalGlyph.NEXT.render(graphics, getRight() - 12, getY() + (getHeight() - 7) / 2, style.text());
        int right = getRight() - textInset - (mark || arrow ? 12 : 0);
        Font font = TerminalText.font(Minecraft.getInstance());
        if (statusPrefix != null && statusSuffix != null) {
            var shown = TerminalText.statusText(
                    statusPrefix.getString(),
                    statusSuffix.getString(),
                    Math.max(0, right - getX() - textInset),
                    font::width);
            int y = getY() + (getHeight() - 8) / 2;
            graphics.drawString(font, shown.prefix(), getX() + textInset, y, textColor(), false);
            graphics.drawString(font, shown.status(), right - font.width(shown.status()), y, textColor(), false);
            return;
        }
        String text = TerminalText.ellipsize(font, getMessage().getString(), Math.max(0, right - getX() - textInset));
        graphics.drawString(font, text, getX() + textInset, getY() + (getHeight() - 8) / 2, textColor(), false);
    }
}
