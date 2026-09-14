// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

final class NetworkSetupKeyRoutingTest {
    @Test
    void remappedEnterClosesBeforeCollapsedMemberSearchCanConsumeIt() {
        for (int key : new int[] {GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER}) {
            KeyMapping inventory = inventoryKey();
            inventory.setKey(InputConstants.Type.KEYSYM.getOrCreate(key));
            ClientSearchState search = new ClientSearchState();
            int[] closes = {0};
            assertTrue(NetworkSetupScreen.routeKey(
                    null,
                    key,
                    0,
                    0,
                    () -> TerminalInteractionPolicy.inventoryShortcut(inventory, null, key, 0),
                    () -> closes[0]++,
                    () -> search.openFromKey(key, 0, true)));
            assertEquals(1, closes[0]);
            assertFalse(search.expanded());
        }
    }

    @Test
    void ordinaryEnterStillOpensEligibleMemberSearchWhenItIsNotAnExitShortcut() {
        for (int key : new int[] {GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER}) {
            KeyMapping inventory = inventoryKey();
            ClientSearchState search = new ClientSearchState();
            int[] closes = {0};
            assertTrue(NetworkSetupScreen.routeKey(
                    null,
                    key,
                    0,
                    0,
                    () -> TerminalInteractionPolicy.inventoryShortcut(inventory, null, key, 0),
                    () -> closes[0]++,
                    () -> search.openFromKey(key, 0, true)));
            assertEquals(0, closes[0]);
            assertTrue(search.expanded());
        }
    }

    @Test
    void focusedEditableFieldOwnsEnterBeforeBothExitAndSearchActions() {
        KeyMapping inventory = inventoryKey();
        inventory.setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_ENTER));
        Font font = new Font(
                id -> {
                    throw new AssertionError("Routing tests do not render glyphs");
                },
                false);
        TerminalEditBox field = new TerminalEditBox(font, 0, 0, 100, 20, Component.empty());
        field.setFocused(true);
        ClientSearchState search = new ClientSearchState();
        int[] closes = {0};
        assertTrue(NetworkSetupScreen.routeKey(
                field,
                GLFW.GLFW_KEY_ENTER,
                0,
                0,
                () -> TerminalInteractionPolicy.inventoryShortcut(inventory, field, GLFW.GLFW_KEY_ENTER, 0),
                () -> closes[0]++,
                () -> search.openFromKey(GLFW.GLFW_KEY_ENTER, 0, true)));
        assertEquals(0, closes[0]);
        assertFalse(search.expanded());
    }

    private static KeyMapping inventoryKey() {
        return new KeyMapping("omniresonance.test.terminal_routing", GLFW.GLFW_KEY_E, "key.categories.inventory");
    }
}
