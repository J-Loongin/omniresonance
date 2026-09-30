// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.compat.ae2.Ae2InterfacePayloads;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

class SharedScreenKeyRoutingTest {
    @Test
    void exchangeAndAeEntryPointsKeepTextKeysInTheFocusedEditor() {
        var ae = new Ae2InterfaceScreen(
                new Ae2InterfacePayloads.Frame(new UUID(801, 1), 0, true, null, "unbound", "", List.of(), 0, false),
                (key, scan) -> {
                    throw new AssertionError("Text keys must not evaluate close shortcuts");
                },
                request -> {
                    throw new AssertionError("Text keys must not send requests");
                });
        // No client/parent is needed: editor-owned keys must return before touching either.
        for (Screen screen : List.of(new ExchangeScreen(null, null), ae)) {
            var field = new CapturingField();
            screen.setFocused(field);
            for (int key : new int[] {GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_E}) {
                int before = field.calls;
                assertTrue(screen.keyPressed(key, 0, 0));
                assertEquals(before + 1, field.calls);
                assertEquals(key, field.lastKey);
            }
        }
    }

    private static final class CapturingField extends TerminalEditBox {
        private int calls, lastKey;

        CapturingField() {
            super(
                    new Font(
                            id -> {
                                throw new AssertionError("No glyph rendering");
                            },
                            false),
                    0,
                    0,
                    100,
                    20,
                    Component.empty());
        }

        @Override
        public boolean keyPressed(int key, int scan, int modifiers) {
            calls++;
            lastKey = key;
            return true;
        }
    }
}
