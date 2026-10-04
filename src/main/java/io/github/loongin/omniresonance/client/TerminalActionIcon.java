// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import net.minecraft.client.gui.GuiGraphics;
import org.jetbrains.annotations.Nullable;

/** Shared native action tile geometry and four-times atlas symbol rendering. */
final class TerminalActionIcon {
    enum Symbol {
        ADD,
        SEARCH,
        SETTINGS
    }

    private TerminalActionIcon() {}

    static @Nullable TerminalLayout.Rect bounds(int x, int y, int width, int height) {
        return width < 18 || height < 18
                ? null
                : new TerminalLayout.Rect(x + (width - 18) / 2, y + (height - 18) / 2, 18, 18);
    }

    static TerminalGlyph glyph(Symbol symbol) {
        return switch (symbol) {
            case ADD -> TerminalGlyph.ADD;
            case SEARCH -> TerminalGlyph.SEARCH;
            case SETTINGS -> TerminalGlyph.GEAR;
        };
    }

    static TerminalTheme.ControlStyle style(boolean active, boolean hovered, boolean focused, boolean selected) {
        return TerminalTheme.previewStyle(active, hovered, focused, selected, false);
    }

    static void render(
            GuiGraphics graphics,
            int x,
            int y,
            int width,
            int height,
            boolean active,
            boolean hovered,
            boolean focused,
            boolean selected,
            Symbol symbol) {
        var bounds = bounds(x, y, width, height);
        if (bounds == null) return;
        var style = style(active, hovered, focused, selected);
        TerminalTheme.renderControl(graphics, bounds, style);
        glyph(symbol).render(graphics, bounds.x() + 3, bounds.y() + 3, style.text());
    }
}
