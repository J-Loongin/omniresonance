// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import java.util.Objects;
import net.minecraft.util.StringUtil;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/** Pure input and navigation policy used by the terminal screen. */
final class TerminalInteractionPolicy {
    private TerminalInteractionPolicy() {}

    static boolean prioritizeTextInput(
            boolean canConsumeInput, boolean bindingMatches, @Nullable String printableKeyName, int modifiers) {
        if (!canConsumeInput || !bindingMatches || printableKeyName == null || printableKeyName.isEmpty()) {
            return false;
        }
        int nonTextModifiers = GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_ALT | GLFW.GLFW_MOD_SUPER;
        if ((modifiers & nonTextModifiers) != 0) {
            return false;
        }
        for (int index = 0; index < printableKeyName.length(); index++) {
            char character = printableKeyName.charAt(index);
            if (!StringUtil.isAllowedChatCharacter(character) || character == '§') {
                return false;
            }
        }
        return true;
    }

    static BackAction backAction(
            boolean dropdownOpen,
            boolean confirmationOpen,
            boolean createOverlayOpen,
            DraftState draftState,
            boolean compactDetailsOpen) {
        if (dropdownOpen) {
            return BackAction.CLOSE_DROPDOWN;
        }
        if (confirmationOpen) {
            return BackAction.CLOSE_CONFIRMATION;
        }
        if (createOverlayOpen) {
            if (draftState.createPending()) {
                return BackAction.HIDE_CREATE_OVERLAY;
            }
            return draftState.requiresConfirmation() ? BackAction.CONFIRM_DRAFT : BackAction.HIDE_CREATE_OVERLAY;
        }
        if (compactDetailsOpen) {
            return BackAction.SHOW_COMPACT_LIST;
        }
        return BackAction.CLOSE_SCREEN;
    }

    static CreateResult createResult(DraftState draftState, boolean success) {
        return new CreateResult(draftState.completed(success), !success);
    }

    enum BackAction {
        CLOSE_DROPDOWN,
        CLOSE_CONFIRMATION,
        HIDE_CREATE_OVERLAY,
        CONFIRM_DRAFT,
        SHOW_COMPACT_LIST,
        CLOSE_SCREEN
    }

    record CreateResult(DraftState draftState, boolean showCreateOverlay) {}

    record RetrySnapshot(String draft, DraftState draftState, boolean onboardingSkipped) {
        RetrySnapshot {
            Objects.requireNonNull(draft, "draft");
            Objects.requireNonNull(draftState, "draftState");
            if (draftState.createPending()) {
                throw new IllegalArgumentException("A pending create cannot be transferred to a replacement view");
            }
        }

        static RetrySnapshot capture(String draft, DraftState draftState, boolean onboardingSkipped) {
            return new RetrySnapshot(draft, draftState, onboardingSkipped);
        }

        boolean hasDraft() {
            return draftState.dirty();
        }
    }

    record DraftState(boolean dirty, boolean createPending) {
        static DraftState clear() {
            return new DraftState(false, false);
        }

        DraftState edited() {
            return new DraftState(true, createPending);
        }

        DraftState submitted() {
            return new DraftState(true, true);
        }

        DraftState completed(boolean success) {
            return success ? clear() : new DraftState(true, false);
        }

        boolean requiresConfirmation() {
            return dirty && !createPending;
        }
    }
}
