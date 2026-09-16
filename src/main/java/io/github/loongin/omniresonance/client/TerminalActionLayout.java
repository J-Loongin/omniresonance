// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

/** Shared bottom-right action geometry for editors and modal confirmations; browsing reserves no action row. */
record TerminalActionLayout(TerminalLayout.Rect content, TerminalLayout.Rect secondary, TerminalLayout.Rect primary) {
    static final int HEIGHT = 20;
    static final int WIDTH = 80;
    static final int INSET = 8;
    static final int GAP = 6;

    static TerminalActionLayout of(TerminalLayout.Rect bounds) {
        return new TerminalActionLayout(
                new TerminalLayout.Rect(bounds.x(), bounds.y(), bounds.width(), Math.max(0, bounds.height() - 34)),
                button(bounds, 2, 0),
                button(bounds, 2, 1));
    }

    static TerminalLayout.Rect button(TerminalLayout.Rect bounds, int count, int index) {
        if (count < 1 || count > 3 || index < 0 || index >= count)
            throw new IllegalArgumentException("Invalid action slot");
        return toolbarButton(bounds, count, index);
    }

    /** Shared sizing for compact browsing toolbars with up to five parallel entries. */
    static TerminalLayout.Rect toolbarButton(TerminalLayout.Rect bounds, int count, int index) {
        if (count < 1 || count > 5 || index < 0 || index >= count)
            throw new IllegalArgumentException("Invalid toolbar action slot");
        int width = Math.min(WIDTH, Math.max(0, (bounds.width() - INSET * 2 - GAP * (count - 1)) / count));
        int height = Math.min(HEIGHT, Math.max(0, bounds.height() - INSET * 2));
        return new TerminalLayout.Rect(
                bounds.right() - INSET - (count - index) * width - (count - index - 1) * GAP,
                bounds.bottom() - INSET - height,
                width,
                height);
    }
}
