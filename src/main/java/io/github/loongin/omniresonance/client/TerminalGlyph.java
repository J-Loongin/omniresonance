// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** One resource-managed four-times atlas for every owned GUI symbol; source geometry is never stretched from 20px. */
enum TerminalGlyph {
    NODES(20, 20),
    TUNNELS(20, 20),
    DOMAIN(20, 20),
    FILTERS(20, 20),
    ADMINS(20, 20),
    STATUS(20, 20),
    EXCHANGE(20, 20),
    SETTINGS(20, 20),
    DIRECT(20, 20),
    ADD(12, 12),
    SEARCH(12, 12),
    GEAR(12, 12),
    TITLE(11, 11),
    CHECK(7, 5),
    NEXT(4, 7),
    SORT(12, 12),
    HELP(12, 12);
    static final int ATLAS_WIDTH = 1024, ATLAS_HEIGHT = 512;
    private static final ResourceLocation ATLAS =
            ResourceLocation.fromNamespaceAndPath("omniresonance", "textures/gui/star_fissure/symbols.png");
    private final int width;
    private final int height;
    private final TerminalLayout.Rect source;

    TerminalGlyph(int width, int height) {
        this.width = width;
        this.height = height;
        source = new TerminalLayout.Rect(ordinal() % 8 * 96 + 8, ordinal() / 8 * 96 + 8, width * 4, height * 4);
    }

    TerminalLayout.Rect source() {
        return source;
    }

    void render(GuiGraphics graphics, int x, int y, int color) {
        graphics.flush();
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(
                (color >> 16 & 255) / 255f, (color >> 8 & 255) / 255f, (color & 255) / 255f, (color >>> 24) / 255f);
        try {
            graphics.blit(
                    ATLAS,
                    x,
                    y,
                    width,
                    height,
                    source.x(),
                    source.y(),
                    source.width(),
                    source.height(),
                    ATLAS_WIDTH,
                    ATLAS_HEIGHT);
        } finally {
            RenderSystem.setShaderColor(1, 1, 1, 1);
            RenderSystem.disableBlend();
        }
    }
}
