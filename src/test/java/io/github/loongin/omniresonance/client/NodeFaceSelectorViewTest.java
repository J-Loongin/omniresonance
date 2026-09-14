// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class NodeFaceSelectorViewTest {
    @Test
    void sixActualCardButtonsEditOnlyTheSharedDraftAndBackHasNoSaveAction() {
        var widgets = new java.util.ArrayList<net.minecraft.client.gui.components.AbstractWidget>();
        var draft = new NodeWorkingFacesDraft(
                io.github.loongin.omniresonance.network.WorkingFaces.explicit(0),
                io.github.loongin.omniresonance.node.NodeForm.BLOCK,
                net.minecraft.core.Direction.NORTH);
        draft.open();
        int[] changes = {0};
        var body = TerminalLayout.calculate(320, 240).content();
        NodeFaceSelectorView.build(
                NodeFaceSelectorView.layout(body, 0, false),
                draft,
                net.minecraft.core.Direction.NORTH,
                java.util.List.of(),
                true,
                widgets::add,
                () -> changes[0]++,
                draft::close);
        assertEquals(7, widgets.size());
        for (int index = 0; index < 6; index++) {
            var widget = widgets.get(index);
            assertTrue(widget.visible && widget.active);
            assertTrue(widget.getX() >= body.x() && widget.getRight() <= body.right());
            assertTrue(widget.getY() >= body.y() && widget.getBottom() <= body.bottom());
            ((net.minecraft.client.gui.components.Button) widget).onPress();
        }
        assertEquals(6, changes[0]);
        assertEquals(63, draft.value().mask());
        ((net.minecraft.client.gui.components.Button) widgets.get(6)).onPress();
        assertTrue(!draft.openNow());
        assertEquals(63, draft.value().mask());
    }

    @Test
    void everyDirectionAndActionFitsAtSmallAndLargeSupportedSizes() {
        for (int[] size : new int[][] {{320, 240}, {427, 240}, {960, 540}}) {
            var body = TerminalLayout.calculate(size[0], size[1]).content();
            for (boolean panel : new boolean[] {false, true}) {
                var last = NodeFaceSelectorView.layout(body, Integer.MAX_VALUE, panel);
                assertEquals(0, last.firstRow());
                assertEquals(panel ? 1 : 2, last.visibleRows());
                for (int scroll = 0; scroll <= last.firstRow(); scroll++) {
                    var layout = NodeFaceSelectorView.layout(body, scroll, panel);
                    for (int row = layout.firstRow(); row < layout.firstRow() + layout.visibleRows(); row++) {
                        for (int column = 0; column < (panel ? 1 : 3); column++) {
                            var card = layout.card(row, column);
                            assertTrue(card.x() >= body.x() && card.right() <= body.right());
                            assertTrue(card.y() >= layout.actions().bottom() && card.bottom() <= body.bottom() - 14);
                        }
                    }
                    assertTrue(layout.actions().bottom() <= body.bottom());
                }
            }
        }
    }
}
