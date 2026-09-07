// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import java.util.List;

/** Pure centered geometry for the two equal physical-node mode cards. */
final class NodeModeLayout {
    private NodeModeLayout() {}

    static List<TerminalLayout.Rect> cards(TerminalLayout.Rect content) {
        int gap = Math.min(12, content.width());
        int availableWidth = Math.max(0, content.width() - 24 - gap);
        int cardWidth = Math.min(220, availableWidth / 2);
        int cardHeight = Math.min(84, Math.max(0, content.height() - 24));
        int y = content.y() + Math.max(0, (content.height() - cardHeight) / 2);
        int x = content.x() + Math.max(0, (content.width() - cardWidth * 2 - gap) / 2);
        return List.of(
                new TerminalLayout.Rect(x, y, cardWidth, cardHeight),
                new TerminalLayout.Rect(x + cardWidth + gap, y, cardWidth, cardHeight));
    }
}
