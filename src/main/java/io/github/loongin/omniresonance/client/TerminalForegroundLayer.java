// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import net.minecraft.client.gui.GuiGraphics;

/** A complete native GUI layer above item previews and ordinary widgets, with balanced render state. */
final class TerminalForegroundLayer {
    private TerminalForegroundLayer() {}

    static void ordered(Runnable body, Runnable foreground) {
        body.run();
        foreground.run();
    }

    static void render(GuiGraphics graphics, Runnable layer) {
        graphics.flush();
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(0, 0, 400);
            layer.run();
            graphics.flush();
        } finally {
            graphics.pose().popPose();
        }
    }
}
