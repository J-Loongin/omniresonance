// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TerminalDialogLayoutTest {
    @Test
    void confirmationGrowsWithExplanationAndRemainsInsideTheStableWindow() {
        for (int[] size : new int[][] {{320, 240}, {427, 240}, {960, 540}}) {
            var parent = TerminalLayout.calculate(size[0], size[1]).content();
            var shortDialog = TerminalDialogLayout.confirmation(parent, 1);
            var longer = TerminalDialogLayout.confirmation(parent, 4);
            assertEquals(260, shortDialog.width());
            assertTrue(shortDialog.height() < longer.height());
            assertTrue(shortDialog.height() < parent.height() / 2);
            for (var dialog : new TerminalLayout.Rect[] {shortDialog, longer, TerminalDialogLayout.editor(parent)}) {
                assertTrue(dialog.x() >= parent.x() && dialog.right() <= parent.right());
                assertTrue(dialog.y() >= parent.y() && dialog.bottom() <= parent.bottom());
                assertTrue(Math.abs(dialog.x() * 2 + dialog.width() - (parent.x() * 2 + parent.width())) <= 1);
                assertTrue(TerminalActionLayout.of(dialog).primary().y() >= dialog.y() + 44);
            }
            assertTrue(TerminalActionLayout.of(longer).primary().y() >= longer.y() + 34 + 40 + 6);
            var editor = TerminalDialogLayout.editor(parent);
            assertTrue(editor.y() + 54 + 20 + 6
                    < TerminalActionLayout.of(editor).primary().y());
        }
    }
}
