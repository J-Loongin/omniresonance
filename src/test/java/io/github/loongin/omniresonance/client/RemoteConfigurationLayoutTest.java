// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class RemoteConfigurationLayoutTest {
    @Test
    void remoteEditingRestoresTheWholeCanvasEvenWhenOpenedFromTheRightDetailPane() {
        for (int width : new int[] {320, 427, 640, 960}) {
            var outer = TerminalLayout.terminal(width, 360);
            var nodes = TerminalNodeLayout.calculate(outer.content(), false);
            var embedded = new TerminalLayout(outer.window(), outer.titleBar(), nodes.detail(), outer.compact());
            var editor = embedded.fullWindowContent();
            assertEquals(outer.window(), editor.window());
            assertEquals(outer.titleBar(), editor.titleBar());
            assertEquals(outer.content(), editor.content());
            assertTrue(editor.content().width() > nodes.detail().width());
        }
    }
}
