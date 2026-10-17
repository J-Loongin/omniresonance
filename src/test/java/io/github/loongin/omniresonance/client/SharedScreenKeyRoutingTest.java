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
    void childPageEntryPointsKeepTextKeysInTheFocusedEditor() {
        var ae = new Ae2InterfaceScreen(
                new Ae2InterfacePayloads.Frame(new UUID(801, 1), 0, true, null, "unbound", "", List.of(), 0, false),
                (key, scan) -> {
                    throw new AssertionError("Text keys must not evaluate close shortcuts");
                },
                request -> {
                    throw new AssertionError("Text keys must not send requests");
                });
        // No client/parent is needed: editor-owned keys must return before touching either.
        for (Screen screen : List.of(
                new ExchangeScreen(null, null),
                ae,
                new ResonanceNodeScreen(
                        null, new net.minecraft.world.entity.player.Inventory(null), Component.empty()))) {
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

    @Test
    void reboundTerminalKeyRemainsActiveInEveryOwnedPageButNotForeignScreens() {
        var activePage = new java.util.concurrent.atomic.AtomicReference<Class<?>>();
        var context = new net.neoforged.neoforge.client.settings.IKeyConflictContext() {
            public boolean isActive() {
                return NetworkTerminalClient.terminalContextActive(true, activePage.get());
            }

            public boolean conflicts(net.neoforged.neoforge.client.settings.IKeyConflictContext other) {
                return true;
            }
        };
        var mapping = new net.minecraft.client.KeyMapping(
                "omniresonance.test.close",
                context,
                net.neoforged.neoforge.client.settings.KeyModifier.NONE,
                com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_G,
                "test");
        for (Class<?> page : List.of(
                NetworkSetupScreen.class, ExchangeScreen.class, Ae2InterfaceScreen.class, ResonanceNodeScreen.class)) {
            activePage.set(page);
            assertTrue(
                    mapping.matches(GLFW.GLFW_KEY_G, 0) && mapping.isConflictContextAndModifierActive(),
                    "Terminal mapping inactive in " + page.getSimpleName());
            org.junit.jupiter.api.Assertions.assertFalse(mapping.matches(GLFW.GLFW_KEY_E, 0));
            org.junit.jupiter.api.Assertions.assertFalse(NetworkTerminalClient.terminalContextActive(false, page));
        }
        activePage.set(net.minecraft.client.gui.screens.PauseScreen.class);
        org.junit.jupiter.api.Assertions.assertFalse(mapping.isConflictContextAndModifierActive());
        activePage.set(net.minecraft.client.gui.screens.ConfirmScreen.class);
        org.junit.jupiter.api.Assertions.assertFalse(mapping.isConflictContextAndModifierActive());
        activePage.set(null);
        assertTrue(mapping.isConflictContextAndModifierActive());
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
