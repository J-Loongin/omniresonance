// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.components.events.AbstractContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

class TerminalClickFocusTest {
    @Test
    void rebuildingDuringClickCannotLeaveTheRemovedEntryFocused() {
        var host = new Host();
        int[] opens = {0};
        var entry = new Control(() -> {
            opens[0]++;
            host.controls.clear();
            host.setFocused(null);
        });
        host.controls.add(entry);
        assertTrue(host.mouseClicked(1, 1, 0));
        host.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0);
        host.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0);
        assertEquals(1, opens[0], "Enter reactivated the removed page entry");
        assertNull(host.getFocused());
        assertFalse(host.isDragging());
    }

    @Test
    void survivingButtonsKeepKeyboardActivation() {
        var host = new Host();
        int[] actions = {0};
        var button = new Control(() -> actions[0]++);
        host.controls.add(button);
        assertTrue(host.mouseClicked(1, 1, 0));
        assertSame(button, host.getFocused());
        assertTrue(host.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0));
        assertEquals(2, actions[0]);
    }

    @Test
    void pageRestoredSearchFocusIsPreserved() {
        var host = new Host();
        var search = new Control(() -> {});
        host.controls.add(new Control(() -> {
            host.controls.clear();
            host.controls.add(search);
        }));
        host.afterDispatch = () -> host.setFocused(search);
        assertTrue(host.mouseClicked(1, 1, 0));
        assertSame(search, host.getFocused());
    }

    private static final class Host extends AbstractContainerEventHandler {
        final List<GuiEventListener> controls = new ArrayList<>();
        Runnable afterDispatch = () -> {};

        public List<? extends GuiEventListener> children() {
            return controls;
        }

        @Override
        public boolean mouseClicked(double x, double y, int button) {
            return TerminalInteractionPolicy.dispatchClick(this, () -> {
                boolean handled = super.mouseClicked(x, y, button);
                afterDispatch.run();
                return handled;
            });
        }
    }

    private static final class Control implements GuiEventListener {
        private final Runnable action;
        private boolean focused;

        Control(Runnable action) {
            this.action = action;
        }

        public boolean mouseClicked(double x, double y, int button) {
            action.run();
            return true;
        }

        public boolean keyPressed(int key, int scan, int modifiers) {
            if (key != GLFW.GLFW_KEY_ENTER && key != GLFW.GLFW_KEY_KP_ENTER) return false;
            action.run();
            return true;
        }

        public boolean isFocused() {
            return focused;
        }

        public void setFocused(boolean value) {
            focused = value;
        }
    }
}
