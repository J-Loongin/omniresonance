// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

final class TerminalRowButtonTest {
    @Test
    void readOnlyRowsKeepNormalTextButRejectClicksKeysAndFocusHighlights() {
        int[] actions = {0};
        TerminalRowButton row =
                new TerminalRowButton(10, 20, 200, 20, Component.literal("Channel"), button -> actions[0]++);
        row.setReadOnly();
        row.setFocused(true);
        row.setSelected(true);

        assertEquals(TerminalTheme.TEXT, row.textColor());
        assertFalse(row.highlighted());
        assertFalse(row.mouseClicked(30, 30, GLFW.GLFW_MOUSE_BUTTON_LEFT));
        assertFalse(row.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0));
        assertFalse(row.keyPressed(GLFW.GLFW_KEY_SPACE, 0, 0));
        assertEquals(0, actions[0]);
    }

    @Test
    void actionableAndDisabledRowsKeepTheirExistingContrastAndFocusBehavior() {
        int[] actions = {0};
        TerminalRowButton row =
                new TerminalRowButton(10, 20, 200, 20, Component.literal("Tunnel"), button -> actions[0]++);
        row.setFocused(true);
        assertEquals(TerminalTheme.TEXT, row.textColor());
        assertTrue(row.highlighted());
        row.onPress();
        assertEquals(1, actions[0]);

        row.active = false;
        assertEquals(TerminalTheme.MUTED, row.textColor());
        assertFalse(row.highlighted());
        assertFalse(row.mouseClicked(30, 30, GLFW.GLFW_MOUSE_BUTTON_LEFT));
        assertEquals(1, actions[0]);
    }
}
