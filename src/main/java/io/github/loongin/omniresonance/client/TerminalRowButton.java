// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/** Left-aligned management row with a quiet surface and a scoped focus/selection marker. */
final class TerminalRowButton extends TerminalClickButton {
    interface Icon {
        int width();

        void render(GuiGraphics graphics, int right, int y, boolean hovered);
    }

    private @Nullable Icon icon;

    void icon(Icon icon) {
        this.icon = icon;
        setTooltip(null);
    }

    @Nullable
    Icon icon() {
        return icon;
    }

    int textLeftInset() {
        return textInset;
    }

    int textRightEdge() {
        return getRight()
                - textInset
                - (selectionMarked() || navigationIndicator && getWidth() >= 26 ? 12 : 0)
                - (icon == null ? 0 : icon.width() + 6);
    }

    int iconRight() {
        return getRight() - textInset - (selectionMarked() || navigationIndicator && getWidth() >= 26 ? 12 : 0);
    }

    private boolean selected;
    private boolean retainedSelection;
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

    boolean selectionMarked() {
        return !readOnly && selected && (active || retainedSelection) && getWidth() >= 26;
    }

    void setRetainedSelection(boolean retainedSelection) {
        this.retainedSelection = retainedSelection;
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
        boolean mark = selectionMarked();
        boolean arrow = !mark && navigationIndicator && getWidth() >= 26;
        TerminalTheme.renderControl(graphics, new TerminalLayout.Rect(getX(), getY(), getWidth(), getHeight()), style);
        if (mark)
            TerminalTheme.renderSelectionMark(graphics, getRight() - 13, getY() + (getHeight() - 6) / 2, style.text());
        if (arrow) TerminalGlyph.NEXT.render(graphics, getRight() - 12, getY() + (getHeight() - 7) / 2, style.text());
        int right = iconRight();
        if (icon != null) {
            icon.render(graphics, right, getY() + (getHeight() - 16) / 2, isHovered);
        }
        right = textRightEdge();
        int left = getX() + textLeftInset();
        Font font = TerminalText.font(Minecraft.getInstance());
        if (statusPrefix != null && statusSuffix != null) {
            var shown = TerminalText.statusText(
                    statusPrefix.getString(), statusSuffix.getString(), Math.max(0, right - left), font::width);
            int y = getY() + (getHeight() - 8) / 2;
            graphics.drawString(font, shown.prefix(), left, y, textColor(), false);
            graphics.drawString(font, shown.status(), right - font.width(shown.status()), y, textColor(), false);
            return;
        }
        String text = TerminalText.ellipsize(font, getMessage().getString(), Math.max(0, right - left));
        graphics.drawString(font, text, left, getY() + (getHeight() - 8) / 2, textColor(), false);
    }
}
