// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

final class TerminalResultRowsTest {
    @Test
    void replacingResultsKeepsTheInputInstanceFocusCursorAndSelection() {
        Host host = new Host();
        TerminalSearchBox field = field();
        field.setValue("alpha beta gamma");
        field.setCursorPosition(10);
        field.setHighlightPos(6);
        host.add(field);
        host.setFocused(field);
        host.rows.add(row("old"));
        host.rows.clear();
        TerminalRowButton latest = row("new");
        host.rows.add(latest);

        assertEquals(List.of(field, latest), host.children());
        assertEquals(List.of(field, latest), host.renderables);
        assertSame(field, host.getFocused());
        assertTrue(field.isFocused());
        assertEquals(10, field.getCursorPosition());
        assertEquals("beta", field.getHighlighted());
        assertTrue(host.charTyped('X', 0));
        assertEquals("alpha X gamma", field.getValue());
    }

    @Test
    void removedResultsCannotKeepKeyboardFocusOrAccumulateInTheScreen() {
        Host host = new Host();
        TerminalRowButton old = row("old");
        host.rows.add(old);
        host.setFocused(old);
        host.rows.clear();
        assertNull(host.getFocused());
        assertFalse(old.isFocused());
        assertFalse(host.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0));
        for (int refresh = 0; refresh < 100; refresh++) {
            host.rows.add(row("current"));
            assertEquals(1, host.children().size());
            assertEquals(1, host.renderables.size());
            host.rows.clear();
        }
        assertTrue(host.children().isEmpty());
        assertTrue(host.renderables.isEmpty());
    }

    @Test
    void resultReplacementDoesNotStealFocusFromAnUnrelatedButton() {
        Host host = new Host();
        TerminalRowButton header = row("header");
        host.add(header);
        host.setFocused(header);
        host.rows.add(row("old"));
        host.rows.clear();
        host.rows.add(row("new"));
        assertSame(header, host.getFocused());
    }

    private static TerminalRowButton row(String name) {
        return new TerminalRowButton(0, 0, 100, 20, Component.literal(name), ignored -> {
            throw new AssertionError("Refreshing results must not activate a row");
        });
    }

    private static TerminalSearchBox field() {
        Font metrics =
                new Font(
                        id -> {
                            throw new AssertionError("Input-state tests do not render glyphs");
                        },
                        false) {
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
        return new TerminalSearchBox(metrics, 0, 0, 16, 20, Component.empty());
    }

    private static final class Host extends Screen {
        private final TerminalResultRows rows =
                new TerminalResultRows(this, this::addRenderableWidget, this::removeWidget);

        private Host() {
            super(Component.empty());
        }

        private void add(AbstractWidget widget) {
            addRenderableWidget(widget);
        }
    }
}
