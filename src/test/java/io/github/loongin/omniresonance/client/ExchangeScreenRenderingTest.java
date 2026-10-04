// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import org.junit.jupiter.api.Test;

/** Verifies the actual vanilla rendering hooks without requiring an OpenGL context. */
class ExchangeScreenRenderingTest {
    @Test
    void exchangeUsesTheTerminalBackgroundHookBeforeVanillaRendersWidgets() throws Exception {
        // Vanilla draws the replaced background hook before widgets. A modal foreground follows that
        // entire parent pass; it must not precede the default blur or duplicate ordinary widget rendering.
        assertEquals(
                ExchangeScreen.class,
                ExchangeScreen.class
                        .getMethod("renderBackground", GuiGraphics.class, int.class, int.class, float.class)
                        .getDeclaringClass());
        assertEquals(
                ExchangeScreen.class,
                ExchangeScreen.class
                        .getMethod("render", GuiGraphics.class, int.class, int.class, float.class)
                        .getDeclaringClass());
    }

    @Test
    void productionRenderEntryDrawsTheWholeParentBeforeShadeAndModalWidgets() {
        var events = new ArrayList<String>();
        ExchangeScreen.renderPageLayers(
                () -> events.addAll(List.of("terminal_background", "parent_widgets")),
                () -> events.addAll(List.of("content_shade", "modal_panel", "modal_widgets")));
        assertEquals(
                List.of("terminal_background", "parent_widgets", "content_shade", "modal_panel", "modal_widgets"),
                events);
    }

    @Test
    void ordinaryPageRenderingRunsExactlyOnceWithoutAModal() {
        var events = new ArrayList<String>();
        ExchangeScreen.renderPageLayers(() -> events.add("page"), null);
        assertEquals(List.of("page"), events);
    }
}
