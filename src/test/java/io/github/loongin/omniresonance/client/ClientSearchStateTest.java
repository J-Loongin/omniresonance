// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ClientSearchStateTest {
    @Test
    void sharedFieldSurvivesRebuildWithItsCaretSelectionAndFocus() {
        var font =
                new net.minecraft.client.gui.Font(
                        id -> {
                            throw new AssertionError("No rendering");
                        },
                        false) {
                    @Override
                    public int width(String value) {
                        return value.length();
                    }

                    @Override
                    public String plainSubstrByWidth(String value, int width) {
                        return value.substring(0, Math.min(value.length(), Math.max(0, width)));
                    }

                    @Override
                    public String plainSubstrByWidth(String value, int width, boolean reverse) {
                        return plainSubstrByWidth(value, width);
                    }
                };
        var search = new ClientSearchState();
        search.open();
        var box = search.field(
                font,
                new TerminalLayout.Rect(0, 0, 120, 20),
                net.minecraft.network.chat.Component.empty(),
                256,
                text -> search.edit(text, 1));
        box.setValue("abcdef");
        box.setFocused(true);
        box.setCursorPosition(3);
        box.setHighlightPos(1);
        var rebuilt = search.field(
                font,
                new TerminalLayout.Rect(4, 8, 140, 20),
                net.minecraft.network.chat.Component.empty(),
                256,
                text -> search.edit(text, 2));
        org.junit.jupiter.api.Assertions.assertSame(box, rebuilt);
        assertEquals(3, rebuilt.getCursorPosition());
        assertEquals("bc", rebuilt.getHighlighted());
        org.junit.jupiter.api.Assertions.assertTrue(rebuilt.isFocused());
        search.close(3);
        search.open();
        org.junit.jupiter.api.Assertions.assertNotSame(
                box,
                search.field(
                        font,
                        new TerminalLayout.Rect(0, 0, 120, 20),
                        net.minecraft.network.chat.Component.empty(),
                        256,
                        ignored -> {}));
    }

    @Test
    void sharedShortcutAlwaysInvokesTheSameToggleActionAndRespectsEligibility() {
        int[] calls = {0};
        for (int key : new int[] {org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER}) {
            org.junit.jupiter.api.Assertions.assertTrue(
                    ClientSearchState.handleToggleKey(key, 0, true, () -> calls[0]++));
            org.junit.jupiter.api.Assertions.assertTrue(
                    ClientSearchState.handleToggleKey(key, 0, true, () -> calls[0]++));
            org.junit.jupiter.api.Assertions.assertFalse(
                    ClientSearchState.handleToggleKey(key, 0, false, () -> calls[0]++));
            org.junit.jupiter.api.Assertions.assertFalse(
                    ClientSearchState.handleToggleKey(key, org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT, true, () -> calls[0]++));
        }
        assertEquals(4, calls[0]);
    }

    @Test
    void normalizedQueriesKeepTheExistingUnicodeAndLengthBoundaries() {
        assertEquals("é", ClientSearchState.normalizedQuery("e\u0301"));
        assertEquals("", ClientSearchState.normalizedQuery(""));
        assertEquals("😀".repeat(64), ClientSearchState.normalizedQuery("😀".repeat(64)));
        assertNull(ClientSearchState.normalizedQuery("😀".repeat(65)));
        assertNull(ClientSearchState.normalizedQuery("a\nb"));
        assertNull(ClientSearchState.normalizedQuery("§a"));
        assertNull(ClientSearchState.normalizedQuery("\uD800"));
    }
}
