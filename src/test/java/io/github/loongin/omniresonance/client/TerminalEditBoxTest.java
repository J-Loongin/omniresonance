// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.lwjgl.glfw.GLFW;

final class TerminalEditBoxTest {
    @Test
    void typingTestConsumesKeyEventsBeforeContainerShortcutsAndInsertsCharactersOnce() {
        TerminalEditBox field = field();
        field.setFocused(true);
        int[] keys = {GLFW.GLFW_KEY_T, GLFW.GLFW_KEY_E, GLFW.GLFW_KEY_S, GLFW.GLFW_KEY_T};
        String text = "test";
        for (int index = 0; index < keys.length; index++) {
            assertTrue(field.keyPressed(keys[index], 0, 0), "The focused field must stop container shortcut fallback");
            assertEquals(text.substring(0, index), field.getValue(), "Key events must not insert characters");
            assertTrue(field.charTyped(text.charAt(index), 0));
        }
        assertEquals("test", field.getValue());
    }

    @ParameterizedTest
    @ValueSource(
            ints = {
                GLFW.GLFW_KEY_E,
                GLFW.GLFW_KEY_R,
                GLFW.GLFW_KEY_Q,
                GLFW.GLFW_KEY_F,
                GLFW.GLFW_KEY_1,
                GLFW.GLFW_KEY_KP_1,
                GLFW.GLFW_KEY_F6
            })
    void reboundInventoryAndSlotKeysCannotEscapeFocusedInput(int keyCode) {
        TerminalEditBox field = field();
        field.setFocused(true);
        assertTrue(field.keyPressed(keyCode, 0, 0));
        assertEquals("", field.getValue());
    }

    @Test
    void escapeAndTabRemainAvailableToScreenNavigation() {
        TerminalEditBox field = field();
        field.setFocused(true);
        assertFalse(field.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0));
        assertFalse(field.keyPressed(GLFW.GLFW_KEY_TAB, 0, 0));
        assertFalse(field.keyPressed(GLFW.GLFW_KEY_TAB, 0, GLFW.GLFW_MOD_SHIFT));
    }

    @ParameterizedTest
    @ValueSource(ints = {GLFW.GLFW_MOD_SHIFT, GLFW.GLFW_MOD_CONTROL, GLFW.GLFW_MOD_SUPER, GLFW.GLFW_MOD_ALT})
    void modifierBindingsRemainOwnedByFocusedTextInput(int modifiers) {
        TerminalEditBox field = field();
        field.setFocused(true);
        assertTrue(field.ownsKey(GLFW.GLFW_KEY_E));
        assertTrue(field.keyPressed(GLFW.GLFW_KEY_E, 0, modifiers));
        assertEquals("", field.getValue());
    }

    @Test
    void unicodeCharactersKeepUsingTheNativeCharacterEvent() {
        TerminalEditBox field = field();
        field.setFocused(true);
        assertTrue(field.keyPressed(GLFW.GLFW_KEY_UNKNOWN, 0, 0));
        assertTrue(field.charTyped('é', 0));
        assertTrue(field.charTyped('中', 0));
        assertEquals("é中", field.getValue());
        field.setVisible(false);
        assertFalse(field.ownsKey(GLFW.GLFW_KEY_E));
        assertFalse(field.keyPressed(GLFW.GLFW_KEY_E, 0, 0));
    }

    @Test
    void unfocusedDisabledAndReadOnlyFieldsDoNotSuppressMenuKeys() {
        TerminalEditBox field = field();
        assertFalse(field.keyPressed(GLFW.GLFW_KEY_E, 0, 0));
        field.setFocused(true);
        field.active = false;
        assertFalse(field.keyPressed(GLFW.GLFW_KEY_E, 0, 0));
        field.active = true;
        field.setEditable(false);
        assertFalse(field.keyPressed(GLFW.GLFW_KEY_E, 0, 0));
    }

    @Test
    void styledInsetAlignsCursorCoordinatesWithTheVisibleTextWidth() {
        TerminalEditBox field = field();
        field.setValue("abcdef");
        assertEquals(22, field.getInnerWidth());
        assertEquals(14, field.getScreenX(0));
        assertEquals(17, field.getScreenX(3));
    }

    private static TerminalEditBox field() {
        Font metrics =
                new Font(
                        id -> {
                            throw new AssertionError("Test only measures plain text");
                        },
                        false) {
                    @Override
                    public int width(String text) {
                        return text.length();
                    }

                    @Override
                    public String plainSubstrByWidth(String text, int maximumWidth) {
                        return text.substring(0, Math.max(0, Math.min(text.length(), maximumWidth)));
                    }

                    @Override
                    public String plainSubstrByWidth(String text, int maximumWidth, boolean tail) {
                        int length = Math.max(0, Math.min(text.length(), maximumWidth));
                        return tail ? text.substring(text.length() - length) : text.substring(0, length);
                    }
                };
        return new TerminalEditBox(metrics, 10, 20, 30, 20, Component.empty());
    }
}
