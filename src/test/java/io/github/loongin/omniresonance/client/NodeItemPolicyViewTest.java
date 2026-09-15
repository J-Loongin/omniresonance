// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NodeItemPolicyViewTest {
    @Test
    void pairedRowsScrollAboveFixedActionsAtEverySupportedSize() {
        for (int[] size : new int[][] {{320, 240}, {427, 240}, {960, 540}}) {
            var body = TerminalLayout.calculate(size[0], size[1]).content();
            var first = NodeItemPolicyView.layout(body, -20);
            assertEquals(0, first.firstRow());
            var last = NodeItemPolicyView.layout(body, Integer.MAX_VALUE);
            assertEquals(4, last.firstRow() + last.visibleRows());
            assertTrue(last.actions().width() <= 80);
            for (var layout : new NodeItemPolicyView.Layout[] {first, last}) {
                for (int row = layout.firstRow(); row < layout.firstRow() + layout.visibleRows(); row++) {
                    var rect = layout.row(row);
                    assertTrue(rect.x() >= body.x() && rect.right() <= body.right());
                    assertTrue(rect.y() >= layout.form().y());
                    assertTrue(rect.bottom() <= layout.actions().y() - 6);
                    assertTrue(rect.bottom() <= body.bottom() - 14);
                }
                assertTrue(layout.actions().bottom() <= body.bottom());
            }
        }
    }
}
