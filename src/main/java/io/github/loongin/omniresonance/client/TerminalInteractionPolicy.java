// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.networking.NetworkTerminalRequest;
import io.github.loongin.omniresonance.networking.NetworkTerminalState;
import java.util.Objects;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.events.GuiEventListener;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/** Pure input and navigation policy used by the terminal screen. */
final class TerminalInteractionPolicy {
    private TerminalInteractionPolicy() {}

    static boolean renderEmptyDirectory(boolean createOverlay) {
        return !createOverlay;
    }

    static boolean enterNetworkHome(boolean hasSelectedNetwork, boolean createOverlay, boolean requestPending) {
        return hasSelectedNetwork && !createOverlay && !requestPending;
    }

    static CreateTarget createTarget(boolean directoryReady, @Nullable NetworkTerminalState topologyState) {
        if (!directoryReady) {
            return CreateTarget.NONE;
        }
        if (topologyState == null || topologyState instanceof NetworkTerminalState.NetworkRoot) {
            return CreateTarget.NETWORK;
        }
        if (topologyState instanceof NetworkTerminalState.Members) return CreateTarget.ADMINISTRATOR;
        if (topologyState instanceof NetworkTerminalState.Filters) return CreateTarget.PRESET;
        return topologyState instanceof NetworkTerminalState.TunnelList ? CreateTarget.TUNNEL : CreateTarget.NONE;
    }

    static TerminalHeaderLayout.Action topBarAction(
            boolean directoryReady, @Nullable NetworkTerminalState topologyState) {
        if (topologyState instanceof NetworkTerminalState.Members) return TerminalHeaderLayout.Action.NONE;
        if (directoryReady && topologyState instanceof NetworkTerminalState.AdministratorCandidates)
            return TerminalHeaderLayout.Action.SEARCH;
        if (createTarget(directoryReady, topologyState) != CreateTarget.NONE) {
            return TerminalHeaderLayout.Action.CREATE;
        }
        return directoryReady
                        && (topologyState instanceof NetworkTerminalState.ChannelList
                                || topologyState instanceof NetworkTerminalState.Preset)
                ? TerminalHeaderLayout.Action.SETTINGS
                : TerminalHeaderLayout.Action.NONE;
    }

    static TerminalHeaderLayout.Action topBarAction(
            boolean directoryReady, @Nullable NetworkTerminalState state, @Nullable java.util.UUID actor) {
        if (state instanceof NetworkTerminalState.Members members) {
            return directoryReady && members.network().ownerId().equals(actor)
                    ? TerminalHeaderLayout.Action.CREATE
                    : TerminalHeaderLayout.Action.NONE;
        }
        return topBarAction(directoryReady, state);
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

    static boolean inventoryShortcut(
            KeyMapping mapping, @Nullable GuiEventListener focused, int keyCode, int scanCode) {
        return keyCode != GLFW.GLFW_KEY_ESCAPE
                && !(focused instanceof TerminalEditBox field && field.ownsKey(keyCode))
                && mapping.matches(keyCode, scanCode);
    }

    static boolean sameEditor(@Nullable NetworkTerminalState previous, @Nullable NetworkTerminalState next) {
        if (previous instanceof NetworkTerminalState.PresetEdit before
                && next instanceof NetworkTerminalState.PresetEdit after) {
            return before.network().id().equals(after.network().id())
                    && before.operation() == after.operation()
                    && before.originalRule().equals(after.originalRule())
                    && Objects.equals(
                            before.preset() == null ? null : before.preset().id(),
                            after.preset() == null ? null : after.preset().id());
        }
        if (previous instanceof NetworkTerminalState.NetworkRename before
                && next instanceof NetworkTerminalState.NetworkRename after) {
            return before.settings()
                    .network()
                    .id()
                    .equals(after.settings().network().id());
        }
        if (previous instanceof NetworkTerminalState.TunnelEdit before
                && next instanceof NetworkTerminalState.TunnelEdit after) {
            return before.network().id().equals(after.network().id())
                    && Objects.equals(
                            before.existing() == null ? null : before.existing().tunnelId(),
                            after.existing() == null ? null : after.existing().tunnelId());
        }
        return false;
    }

    static boolean submitsDraft(NetworkTerminalRequest request) {
        return request instanceof NetworkTerminalRequest.Create
                || request instanceof NetworkTerminalRequest.SavePresetEdit
                || request instanceof NetworkTerminalRequest.SaveResourceRule
                || request instanceof NetworkTerminalRequest.RenameNetwork
                || request instanceof NetworkTerminalRequest.RenameTunnel
                || request instanceof NetworkTerminalRequest.CreateTunnel;
    }

    static BackAction shortcutAction(boolean draftSubmitted, boolean dirty) {
        return new ClientDraftExit(dirty, draftSubmitted).requiresConfirmation()
                ? BackAction.CONFIRM_DRAFT
                : BackAction.CLOSE_SCREEN;
    }

    static CreateResult createResult(DraftState draftState, boolean success) {
        return new CreateResult(draftState.completed(success), !success);
    }

    static TopologyBackAction topologyBackAction(
            boolean requestPending, boolean discardConfirmationOpen, boolean dirtyEdit) {
        if (requestPending) {
            return TopologyBackAction.BLOCK;
        }
        if (discardConfirmationOpen) {
            return TopologyBackAction.CLOSE_CONFIRMATION;
        }
        return dirtyEdit ? TopologyBackAction.CONFIRM_DRAFT : TopologyBackAction.SEND_BACK;
    }

    enum TopologyBackAction {
        BLOCK,
        CLOSE_CONFIRMATION,
        CONFIRM_DRAFT,
        SEND_BACK
    }

    enum CreateTarget {
        NONE,
        NETWORK,
        TUNNEL,
        ADMINISTRATOR,
        PRESET
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
            return new ClientDraftExit(dirty, createPending).requiresConfirmation();
        }
    }
}
