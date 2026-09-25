// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.junit.jupiter.api.Test;

/** Verifies the actual vanilla rendering hooks without requiring an OpenGL context. */
class ExchangeScreenRenderingTest {
    @Test
    void exchangeUsesTheTerminalBackgroundHookBeforeVanillaRendersWidgets() throws Exception {
        // Screen.render calls renderBackground before widgets. Its default background applies blur;
        // custom terminal text must replace that hook, never be drawn before a call to super.render.
        assertEquals(
                ExchangeScreen.class,
                ExchangeScreen.class
                        .getMethod("renderBackground", GuiGraphics.class, int.class, int.class, float.class)
                        .getDeclaringClass());
        assertEquals(
                Screen.class,
                ExchangeScreen.class
                        .getMethod("render", GuiGraphics.class, int.class, int.class, float.class)
                        .getDeclaringClass());
    }
}
