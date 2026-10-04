// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

/** Fixed 27+9+4+1 sample geometry without changing the underlying inventory slot identities. */
final class TerminalSampleLayout {
    static final int CELL = 20;
    static final int PITCH = 22;
    static final int HEIGHT = 86;

    private TerminalSampleLayout() {}

    static TerminalLayout.Rect slot(TerminalLayout.Rect bounds, int displayIndex) {
        if (displayIndex < 0 || displayIndex >= 41) throw new IllegalArgumentException("Invalid sample slot");
        int gridX = bounds.x() + Math.max(0, (bounds.width() - 244) / 2) + PITCH;
        int gridY = bounds.y() + Math.max(0, (bounds.height() - HEIGHT) / 2);
        if (displayIndex < 27)
            return new TerminalLayout.Rect(
                    gridX + displayIndex % 9 * PITCH, gridY + displayIndex / 9 * PITCH, CELL, CELL);
        if (displayIndex < 36)
            return new TerminalLayout.Rect(gridX + (displayIndex - 27) * PITCH, gridY + 3 * PITCH, CELL, CELL);
        if (displayIndex < 40)
            return new TerminalLayout.Rect(gridX + 9 * PITCH + 4, gridY + (displayIndex - 36) * PITCH, CELL, CELL);
        return new TerminalLayout.Rect(gridX - PITCH, gridY + 3 * PITCH, CELL, CELL);
    }
}
