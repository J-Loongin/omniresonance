// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;

/**
 * Client-Screen-owned visible result widgets, bounded by its viewport layout. Replaced on filtering/scrolling and
 * cleared on screen rebuild; never retains the whole catalog or replaces an unrelated input widget.
 */
final class TerminalResultRows {
    private final List<TerminalRowButton> rows = new ArrayList<>();
    private final ContainerEventHandler owner;
    private final Consumer<TerminalRowButton> addWidget;
    private final Consumer<GuiEventListener> removeWidget;

    TerminalResultRows(
            ContainerEventHandler owner,
            Consumer<TerminalRowButton> addWidget,
            Consumer<GuiEventListener> removeWidget) {
        this.owner = owner;
        this.addWidget = addWidget;
        this.removeWidget = removeWidget;
    }

    void add(TerminalRowButton row) {
        rows.add(row);
        addWidget.accept(row);
    }

    void clear() {
        for (TerminalRowButton row : rows) {
            if (owner.getFocused() == row) {
                owner.setFocused(null);
            }
            removeWidget.accept(row);
        }
        rows.clear();
    }
}
