// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import java.util.ArrayList;
import java.util.List;

/** Pure fixed-window geometry for the eight planned terminal home modules. */
final class TerminalHomeLayout {
    private TerminalHomeLayout() {}

    static List<TerminalLayout.Rect> cards(TerminalLayout.Rect content, boolean compact) {
        int columns = compact ? 2 : 3;
        int rows = compact ? 4 : 3;
        int gap = TerminalLayout.GAP;
        int cardWidth = Math.max(0, (content.width() - gap * (columns - 1)) / columns);
        int cardHeight = Math.max(0, (content.height() - gap * (rows - 1)) / rows);
        List<TerminalLayout.Rect> cards = new ArrayList<>(8);
        for (int index = 0; index < 8; index++) {
            int row = index / columns;
            int column = index % columns;
            int rowStart = content.x();
            if (!compact && row == 2) {
                rowStart += Math.max(0, (content.width() - (cardWidth * 2 + gap)) / 2);
            }
            cards.add(new TerminalLayout.Rect(
                    rowStart + column * (cardWidth + gap),
                    content.y() + row * (cardHeight + gap),
                    cardWidth,
                    cardHeight));
        }
        return List.copyOf(cards);
    }
}
