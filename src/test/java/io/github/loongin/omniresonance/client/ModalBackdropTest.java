// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

final class ModalBackdropTest {
    @Test
    void detachesFocusedDraftAndRetainsItForRenderingWhileFirstModalClickIsDelivered() {
        Host host = new Host();
        Widget draft = host.add(new Widget());
        draft.setMessage(Component.literal("unsaved value"));
        host.setFocused(draft);
        ModalBackdrop backdrop = new ModalBackdrop();
        backdrop.retain(host.children(), host::remove);
        Widget confirm = host.add(new Widget());
        assertFalse(draft.isFocused());
        assertEquals(List.of(confirm), host.children());
        backdrop.captureForeground(host.renderableList());
        assertTrue(host.renderableList().isEmpty());
        assertEquals(List.of(confirm), host.children());
        assertTrue(host.mouseClicked(2, 2, 0));
        assertEquals(1, confirm.clicks);
        assertEquals(0, draft.clicks);
        List<Renderable> rendered = new ArrayList<>();
        backdrop.render(rendered::add);
        assertEquals(List.of(draft), rendered);
        assertEquals("unsaved value", draft.getMessage().getString());
        backdrop.clear();
        rendered.clear();
        backdrop.render(rendered::add);
        assertTrue(rendered.isEmpty());
    }

    private static final class Host extends Screen {
        Host() {
            super(Component.empty());
        }

        Widget add(Widget widget) {
            return addRenderableWidget(widget);
        }

        List<Renderable> renderableList() {
            return renderables;
        }

        void remove(net.minecraft.client.gui.components.events.GuiEventListener widget) {
            removeWidget(widget);
        }
    }

    private static final class Widget extends AbstractWidget {
        int clicks;

        Widget() {
            super(0, 0, 20, 20, Component.empty());
        }

        @Override
        public boolean mouseClicked(double x, double y, int button) {
            clicks++;
            return true;
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int x, int y, float tick) {}

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {}
    }
}
