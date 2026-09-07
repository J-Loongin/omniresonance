// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

final class TerminalSearchBoxTest {
    @Test
    void rightClickClearsOnceAndKeepsSearchOpenAndReadyForTyping() {
        TerminalSearchBox field = field();
        field.setValue("query");
        NodeTunnelSearch search = new NodeTunnelSearch();
        search.open();
        search.edit("query", 0);
        search.handled();
        AtomicInteger changes = new AtomicInteger();
        field.setResponder(value -> {
            changes.incrementAndGet();
            search.edit(value, 10);
        });

        assertTrue(field.mouseClicked(20, 25, 1));
        assertEquals("", field.getValue());
        assertTrue(field.isFocused());
        assertTrue(field.keyPressed(GLFW.GLFW_KEY_E, 0, 0));
        assertTrue(search.expanded());
        assertEquals("", search.draft());
        assertTrue(search.due(11));
        int notificationsAfterClear = changes.get();
        assertTrue(notificationsAfterClear > 0);
        search.handled();
        assertTrue(field.mouseClicked(20, 25, 1));
        assertEquals(notificationsAfterClear, changes.get(), "Empty clicks must not notify or enqueue another search");
        assertFalse(search.due(100));
    }

    @Test
    void outsideDisabledHiddenAndReadOnlyClicksNeverEraseContent() {
        TerminalSearchBox field = field();
        field.setValue("keep");
        assertFalse(field.mouseClicked(9, 25, 1));
        field.active = false;
        assertFalse(field.mouseClicked(20, 25, 1));
        field.active = true;
        field.setVisible(false);
        assertFalse(field.mouseClicked(20, 25, 1));
        field.setVisible(true);
        field.setEditable(false);
        assertFalse(field.mouseClicked(20, 25, 1));
        assertEquals("keep", field.getValue());
        assertFalse(field.isFocused());
    }

    @Test
    void ordinaryNameFieldsDoNotAcquireTheSearchClearBehavior() {
        TerminalEditBox name = new TerminalEditBox(metrics(), 10, 20, 100, 20, Component.empty());
        name.setValue("My node");
        assertFalse(name.mouseClicked(20, 25, 1));
        assertEquals("My node", name.getValue());
    }

    private static TerminalSearchBox field() {
        return new TerminalSearchBox(metrics(), 10, 20, 100, 20, Component.empty());
    }

    private static Font metrics() {
        return new Font(
                id -> {
                    throw new AssertionError("Text editing tests do not render glyphs");
                },
                false) {
            @Override
            public int width(String text) {
                return text.length();
            }

            @Override
            public String plainSubstrByWidth(String text, int width) {
                return text.substring(0, Math.max(0, Math.min(width, text.length())));
            }

            @Override
            public String plainSubstrByWidth(String text, int width, boolean tail) {
                int length = Math.max(0, Math.min(width, text.length()));
                return tail ? text.substring(text.length() - length) : text.substring(0, length);
            }
        };
    }
}
