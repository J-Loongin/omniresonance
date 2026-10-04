// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import org.jetbrains.annotations.Nullable;

/** Screen-owned visual widgets detached from event routing until the next rebuild. */
final class ModalBackdrop {
    private record RetainedWidget(AbstractWidget widget, boolean active) {}

    private final List<RetainedWidget> widgets = new ArrayList<>();
    private final List<Renderable> foreground = new ArrayList<>();

    static void buildLayers(Runnable page, @Nullable Runnable dialog) {
        page.run();
        if (dialog != null) dialog.run();
    }

    static TerminalLayout.Rect shadeBounds(TerminalLayout layout) {
        var window = layout.window();
        int top = layout.titleBar().bottom();
        return new TerminalLayout.Rect(window.x(), top, window.width(), Math.max(0, window.bottom() - top));
    }

    static void renderShade(GuiGraphics graphics, TerminalLayout layout) {
        var shade = shadeBounds(layout);
        graphics.fill(shade.x(), shade.y(), shade.right(), shade.bottom(), TerminalTheme.MODAL_DIM);
    }

    void open(
            List<? extends GuiEventListener> children,
            Consumer<GuiEventListener> remove,
            List<Renderable> renderables,
            Runnable dialog) {
        retain(children, remove);
        dialog.run();
        captureForeground(renderables);
    }

    void clear() {
        for (var entry : widgets) entry.widget().active = entry.active();
        widgets.clear();
        foreground.clear();
    }

    void retain(List<? extends GuiEventListener> children, Consumer<GuiEventListener> remove) {
        clear();
        for (GuiEventListener child : List.copyOf(children)) {
            if (child instanceof AbstractWidget widget) {
                widget.setFocused(false);
                widgets.add(new RetainedWidget(widget, widget.active));
                widget.active = false;
                remove.accept(child);
            }
        }
    }

    void render(Consumer<Renderable> render) {
        for (var entry : widgets) render.accept(entry.widget());
    }

    void captureForeground(List<Renderable> renderables) {
        foreground.addAll(renderables);
        renderables.clear();
    }

    void addForeground(Renderable widget) {
        foreground.add(widget);
    }

    void renderForeground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, Runnable panel) {
        TerminalForegroundLayer.render(graphics, () -> {
            panel.run();
            for (Renderable widget : foreground) widget.render(graphics, mouseX, mouseY, partialTick);
        });
    }
}
