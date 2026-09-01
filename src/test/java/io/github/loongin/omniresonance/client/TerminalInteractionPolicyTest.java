// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

final class TerminalInteractionPolicyTest {
    @Test
    void focusedShiftModifiedPrintableBindingPrioritizesText() {
        assertTrue(TerminalInteractionPolicy.prioritizeTextInput(true, true, "e", GLFW.GLFW_MOD_SHIFT));
    }

    @Test
    void focusedKeypadBindingPrioritizesText() {
        assertTrue(TerminalInteractionPolicy.prioritizeTextInput(true, true, "6", GLFW.GLFW_MOD_NUM_LOCK));
    }

    @Test
    void focusedUnicodePrintableBindingPrioritizesText() {
        assertTrue(TerminalInteractionPolicy.prioritizeTextInput(true, true, "é", 0));
    }

    @Test
    void focusedNonPrintableBindingDoesNotPrioritizeText() {
        assertFalse(TerminalInteractionPolicy.prioritizeTextInput(true, true, null, 0));
    }

    @Test
    void controlModifiedBindingDoesNotPrioritizeText() {
        assertFalse(TerminalInteractionPolicy.prioritizeTextInput(true, true, "f", GLFW.GLFW_MOD_CONTROL));
    }

    @Test
    void nonMatchingBindingDoesNotPrioritizeText() {
        assertFalse(TerminalInteractionPolicy.prioritizeTextInput(true, false, "e", GLFW.GLFW_MOD_SHIFT));
    }

    @Test
    void failedCreateRestoresDirtyDraft() {
        TerminalInteractionPolicy.DraftState failed = TerminalInteractionPolicy.DraftState.clear()
                .edited()
                .submitted()
                .completed(false);

        assertTrue(failed.dirty());
        assertFalse(failed.createPending());
    }

    @Test
    void successfulCreateClearsDraft() {
        TerminalInteractionPolicy.DraftState succeeded = TerminalInteractionPolicy.DraftState.clear()
                .edited()
                .submitted()
                .completed(true);

        assertFalse(succeeded.dirty());
        assertFalse(succeeded.createPending());
    }

    @Test
    void failedDraftRequiresConfirmationBeforeLeavingCreateOverlay() {
        TerminalInteractionPolicy.DraftState failed = TerminalInteractionPolicy.DraftState.clear()
                .edited()
                .submitted()
                .completed(false);

        assertEquals(
                TerminalInteractionPolicy.BackAction.CONFIRM_DRAFT,
                TerminalInteractionPolicy.backAction(false, false, true, failed, false));
    }

    @Test
    void pendingCreateBackHidesOnlyCreateOverlay() {
        TerminalInteractionPolicy.DraftState pending =
                TerminalInteractionPolicy.DraftState.clear().edited().submitted();

        assertEquals(
                TerminalInteractionPolicy.BackAction.HIDE_CREATE_OVERLAY,
                TerminalInteractionPolicy.backAction(false, false, true, pending, false));
    }

    @Test
    void dropdownClosesBeforeUnderlyingNavigation() {
        assertEquals(
                TerminalInteractionPolicy.BackAction.CLOSE_DROPDOWN,
                TerminalInteractionPolicy.backAction(
                        true,
                        false,
                        true,
                        TerminalInteractionPolicy.DraftState.clear().edited(),
                        true));
    }

    @Test
    void hiddenCreateFailureResurfacesBeforeCloseConfirmation() {
        TerminalInteractionPolicy.DraftState pending =
                TerminalInteractionPolicy.DraftState.clear().edited().submitted();
        assertEquals(
                TerminalInteractionPolicy.BackAction.HIDE_CREATE_OVERLAY,
                TerminalInteractionPolicy.backAction(false, false, true, pending, false));

        TerminalInteractionPolicy.CreateResult failed = TerminalInteractionPolicy.createResult(pending, false);

        assertTrue(failed.showCreateOverlay());
        assertEquals(
                TerminalInteractionPolicy.BackAction.CONFIRM_DRAFT,
                TerminalInteractionPolicy.backAction(
                        false, false, failed.showCreateOverlay(), failed.draftState(), false));
    }

    @Test
    void retryReplacementPreservesDraftAndOnboardingChoice() {
        TerminalInteractionPolicy.DraftState failed = TerminalInteractionPolicy.DraftState.clear()
                .edited()
                .submitted()
                .completed(false);

        TerminalInteractionPolicy.RetrySnapshot snapshot =
                TerminalInteractionPolicy.RetrySnapshot.capture("Factory Network", failed, true);

        assertEquals("Factory Network", snapshot.draft());
        assertTrue(snapshot.hasDraft());
        assertTrue(snapshot.draftState().dirty());
        assertFalse(snapshot.draftState().createPending());
        assertTrue(snapshot.onboardingSkipped());
    }
}
