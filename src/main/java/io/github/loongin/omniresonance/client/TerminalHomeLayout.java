// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import java.util.ArrayList;
import java.util.List;

/** Pure fixed-window geometry for the seven terminal home modules. */
final class TerminalHomeLayout {
    static final List<String> MODULES =
            List.of("nodes", "tunnels", "domain", "filters", "admins", "status", "settings");

    private TerminalHomeLayout() {}

    static List<TerminalLayout.Rect> cards(TerminalLayout.Rect content, boolean compact) {
        int columns = compact ? 2 : 3;
        int rows = (MODULES.size() + columns - 1) / columns;
        int gap = TerminalLayout.GAP;
        int cardWidth = Math.max(0, (content.width() - gap * (columns - 1)) / columns);
        int cardHeight = Math.max(0, (content.height() - gap * (rows - 1)) / rows);
        List<TerminalLayout.Rect> cards = new ArrayList<>(MODULES.size());
        for (int index = 0; index < MODULES.size(); index++) {
            int row = index / columns;
            int column = index % columns;
            int rowStart = content.x();
            int entriesInRow = Math.min(columns, MODULES.size() - row * columns);
            rowStart += Math.max(0, (content.width() - (cardWidth * entriesInRow + gap * (entriesInRow - 1))) / 2);
            cards.add(new TerminalLayout.Rect(
                    rowStart + column * (cardWidth + gap),
                    content.y() + row * (cardHeight + gap),
                    cardWidth,
                    cardHeight));
        }
        return List.copyOf(cards);
    }
}
