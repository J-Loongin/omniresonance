// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TerminalDialogLayoutTest {
    @Test
    void presetImpactStartsBelowTheReservedInputFeedbackRow() {
        var dialog = new TerminalLayout.Rect(0, 0, 300, 162);
        var field = new TerminalLayout.Rect(12, 54, 276, 20);
        var feedback = TerminalDialogLayout.inputFeedback(field, 134);
        assertTrue(TerminalFilterView.impactTop(dialog) >= feedback.bottom() + 6);
    }

    @Test
    void fieldFeedbackFollowsTheInputAndStaysAboveActions() {
        for (int y : new int[] {35, 48, 52, 54}) {
            var dialog = TerminalDialogLayout.editor(new TerminalLayout.Rect(0, 0, 380, 200));
            var field = new TerminalLayout.Rect(dialog.x() + 12, dialog.y() + y, dialog.width() - 24, 20);
            int footer = TerminalActionLayout.of(dialog).primary().y();
            var feedback = TerminalDialogLayout.inputFeedback(field, footer);
            assertEquals(field.x(), feedback.x());
            assertEquals(field.width(), feedback.width());
            assertEquals(field.bottom() + 4, feedback.y());
            assertTrue(feedback.height() >= 9);
            assertTrue(feedback.bottom() <= footer - 2);
        }
    }

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
