// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import net.minecraft.client.gui.GuiGraphics;

/** Semantic module identity; every production pictogram uses the common refined atlas. */
enum TerminalModuleIcon {
    NODES,
    TUNNELS,
    DOMAIN,
    FILTERS,
    ADMINS,
    STATUS,
    EXCHANGE,
    SETTINGS,
    DIRECT;

    static TerminalModuleIcon forModule(String module) {
        return valueOf(module.toUpperCase(java.util.Locale.ROOT));
    }

    TerminalGlyph glyph() {
        return TerminalGlyph.valueOf(name());
    }

    void render(GuiGraphics graphics, int x, int y, int color) {
        glyph().render(graphics, x, y, color);
    }
}
