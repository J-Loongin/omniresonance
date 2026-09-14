// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.events.AbstractContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

final class NodeTunnelSearchFocusTest {
    @Test
    void focusedEditableFieldsOwnBothDefaultAndRemappedInventoryKeys() {
        TerminalEditBox field = field();
        field.setFocused(true);
        for (int key : new int[] {GLFW.GLFW_KEY_E, GLFW.GLFW_KEY_I}) {
            assertTrue(field.ownsKey(key));
            assertTrue(field.keyPressed(key, 0, 0));
        }
        field.setEditable(false);
        assertFalse(field.ownsKey(GLFW.GLFW_KEY_E));
        field.setEditable(true);
        field.setFocused(false);
        assertFalse(field.ownsKey(GLFW.GLFW_KEY_I));
        field.setFocused(true);
        assertFalse(field.ownsKey(GLFW.GLFW_KEY_ESCAPE));
    }

    @Test
    void mouseToggleFocusesTheFieldAfterVanillaRestoresTheDetachedClickedButton() {
        NodeTunnelSearch search = new NodeTunnelSearch();
        Host host = new Host();
        TerminalEditBox field = field();
        host.children = List.of(toggle(() -> {
            search.open();
            host.children = List.of(field);
            host.setFocused(field);
        }));

        assertTrue(host.mouseClicked(0, 0, 0));
        assertFalse(field.isFocused(), "Vanilla assigns focus after the callback rebuilds the children");
        search.finishToggleClick(false, host, field);

        assertSame(field, host.getFocused());
        assertTrue(field.isFocused());
        assertTrue(field.keyPressed(GLFW.GLFW_KEY_E, 0, 0), "The first letter must not close the inventory");
    }

    @Test
    void closingRemovesFocusFromTheDetachedToggleInsteadOfLeavingAnInvisibleControlActive() {
        NodeTunnelSearch search = new NodeTunnelSearch();
        search.open();
        Host host = new Host();
        host.children = List.of(toggle(() -> {
            search.close(1);
            host.children = List.of();
            host.setFocused(null);
        }));

        assertTrue(host.mouseClicked(0, 0, 0));
        search.finishToggleClick(true, host, null);
        assertNull(host.getFocused());
    }

    @Test
    void ordinaryClicksDoNotStealFocusBackToTheOpenSearchField() {
        NodeTunnelSearch search = new NodeTunnelSearch();
        search.open();
        Host host = new Host();
        GuiEventListener other = toggle(() -> {});
        host.children = List.of(other);

        assertTrue(host.mouseClicked(0, 0, 0));
        search.finishToggleClick(true, host, field());
        assertSame(other, host.getFocused());
    }

    private static GuiEventListener toggle(Runnable action) {
        return new GuiEventListener() {
            private boolean focused;

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                action.run();
                return true;
            }

            @Override
            public void setFocused(boolean focused) {
                this.focused = focused;
            }

            @Override
            public boolean isFocused() {
                return focused;
            }
        };
    }

    private static TerminalEditBox field() {
        Font metrics = new Font(
                id -> {
                    throw new AssertionError("Focus tests do not render glyphs");
                },
                false);
        return new TerminalEditBox(metrics, 0, 0, 100, 20, Component.empty());
    }

    private static final class Host extends AbstractContainerEventHandler {
        private List<GuiEventListener> children = List.of();

        @Override
        public List<? extends GuiEventListener> children() {
            return children;
        }
    }
}
