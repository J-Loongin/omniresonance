// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.networking.NetworkTerminalResponse;
import io.github.loongin.omniresonance.networking.NetworkTerminalState;
import io.github.loongin.omniresonance.networking.NodeMenuResponse;
import io.github.loongin.omniresonance.networking.NodeMenuState;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/** Pure presentation of authoritative reasons in the current editing context; ambiguous causes stay generic. */
final class ManagementErrorText {
    private ManagementErrorText() {}

    static Component terminal(
            NetworkTerminalResponse.Reason reason, @Nullable NetworkTerminalState state, boolean creatingNetwork) {
        String key = reason.translationKey();
        if (reason == NetworkTerminalResponse.Reason.INVALID_NAME) {
            if (state instanceof NetworkTerminalState.TunnelEdit) key = terminalKey("invalid_tunnel_name");
            else if (creatingNetwork || state instanceof NetworkTerminalState.NetworkRename)
                key = terminalKey("invalid_network_name");
            else if (state instanceof NetworkTerminalState.PresetEdit) key = terminalKey("invalid_preset_name");
        } else if (reason == NetworkTerminalResponse.Reason.QUOTA_REACHED) {
            if (creatingNetwork) key = terminalKey("network_quota");
            else if (state instanceof NetworkTerminalState.TunnelEdit
                    || state instanceof NetworkTerminalState.TunnelList) key = terminalKey("tunnel_quota");
            else if (state instanceof NetworkTerminalState.Filters
                    || state instanceof NetworkTerminalState.Preset
                    || state instanceof NetworkTerminalState.PresetEdit) key = terminalKey("filter_quota");
            else if (state instanceof NetworkTerminalState.Members
                    || state instanceof NetworkTerminalState.AdministratorCandidates
                    || state instanceof NetworkTerminalState.RemoveAdministrator)
                key = terminalKey("administrator_quota");
        }
        return Component.translatable(key);
    }

    static Component node(NodeMenuResponse.Reason reason, @Nullable NodeMenuState state) {
        String key = reason.translationKey();
        boolean channel = state instanceof NodeMenuState.DirectChannelEdit
                || state instanceof NodeMenuState.DirectChannelList
                || state instanceof NodeMenuState.DirectChannelSettings
                || state instanceof NodeMenuState.DirectChannelDelete;
        if (reason == NodeMenuResponse.Reason.INVALID_NAME) {
            if (channel) key = nodeKey("invalid_channel_name");
            else if (state instanceof NodeMenuState.BlankEdit
                    || state instanceof NodeMenuState.LinkedRename
                    || state instanceof NodeMenuState.NetworkMoveEdit) key = nodeKey("invalid_node_name");
        } else if (reason == NodeMenuResponse.Reason.QUOTA_REACHED) {
            if (channel) key = nodeKey("channel_quota");
            else if (state instanceof NodeMenuState.ResourceEdit || state instanceof NodeMenuState.DirectChannelRoot)
                key = nodeKey("configuration_quota");
        } else if (channel && reason == NodeMenuResponse.Reason.LOCKED) key = nodeKey("channel_locked");
        else if (channel && reason == NodeMenuResponse.Reason.STALE_REVISION) key = nodeKey("channel_stale_revision");
        return Component.translatable(key);
    }

    private static String terminalKey(String suffix) {
        return "omniresonance.terminal.error." + suffix;
    }

    private static String nodeKey(String suffix) {
        return "omniresonance.node_menu.error." + suffix;
    }
}
