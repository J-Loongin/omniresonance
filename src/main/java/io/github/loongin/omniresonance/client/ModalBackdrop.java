// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;

/** Screen-owned visual widgets detached from event routing until the next rebuild. */
final class ModalBackdrop {
    private final List<Renderable> widgets = new ArrayList<>();
    private final List<Renderable> foreground = new ArrayList<>();

    void clear() {
        widgets.clear();
        foreground.clear();
    }

    void retain(List<? extends GuiEventListener> children, Consumer<GuiEventListener> remove) {
        clear();
        for (GuiEventListener child : List.copyOf(children)) {
            if (child instanceof AbstractWidget widget) {
                widget.setFocused(false);
                widgets.add(widget);
                remove.accept(child);
            }
        }
    }

    void render(Consumer<Renderable> render) {
        widgets.forEach(render);
    }

    void captureForeground(List<Renderable> renderables) {
        foreground.addAll(renderables);
        renderables.clear();
    }

    void renderForeground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, Runnable panel) {
        graphics.flush();
        graphics.pose().pushPose();
        // Native item icons use positive GUI depth; the entire modal including its buttons must cover them.
        graphics.pose().translate(0, 0, 400);
        panel.run();
        for (Renderable widget : foreground) widget.render(graphics, mouseX, mouseY, partialTick);
        graphics.flush();
        graphics.pose().popPose();
    }
}
