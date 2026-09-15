// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

class TerminalIntervalBoxTest {
    private static Font font() {
        return new Font(
                id -> {
                    throw new AssertionError("No rendering");
                },
                false) {
            @Override
            public int width(String text) {
                return text.length();
            }

            @Override
            public String plainSubstrByWidth(String text, int width) {
                return text.substring(0, Math.clamp(width, 0, text.length()));
            }

            @Override
            public String plainSubstrByWidth(String text, int width, boolean tail) {
                return plainSubstrByWidth(text, width);
            }
        };
    }

    @Test
    void wheelAdjustsOnlyHoveredEditableValuesAndHonorsBothBounds() {
        var field = new TerminalIntervalBox(font(), 10, 20, 100, 20, Component.empty());
        field.setValue("1");
        assertTrue(field.mouseScrolled(20, 25, 0, 1));
        assertEquals("2", field.getValue());
        field.mouseScrolled(20, 25, 0, -1);
        field.mouseScrolled(20, 25, 0, -1);
        assertEquals("1", field.getValue());
        field.setValue("2147483647");
        field.mouseScrolled(20, 25, 0, 1);
        assertEquals("2147483647", field.getValue());
        assertFalse(field.mouseScrolled(120, 25, 0, -1));
        field.setEditable(false);
        assertFalse(field.mouseScrolled(20, 25, 0, -1));
        field.setEditable(true);
        field.active = false;
        assertFalse(field.mouseScrolled(20, 25, 0, -1));
    }

    @Test
    void invalidInputIsPreservedAndHoverConsumesWheelWithoutScrollingThePage() {
        var field = new TerminalIntervalBox(font(), 10, 20, 100, 20, Component.empty());
        for (String value : new String[] {"", "bad", "0", "-1", "2147483648"}) {
            field.setValue(value);
            assertTrue(field.mouseScrolled(20, 25, 0, 1));
            assertEquals(value, field.getValue());
        }
        assertFalse(field.mouseScrolled(20, 25, 1, 0));
    }
}
