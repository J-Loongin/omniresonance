// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.loongin.omniresonance.networking.NetworkSummary;
import io.github.loongin.omniresonance.networking.NetworkTerminalResponse;
import io.github.loongin.omniresonance.networking.NetworkTerminalState;
import io.github.loongin.omniresonance.networking.NodeMenuNodeSummary;
import io.github.loongin.omniresonance.networking.NodeMenuResponse;
import io.github.loongin.omniresonance.networking.NodeMenuState;
import io.github.loongin.omniresonance.networking.NodeTunnelSummary;
import io.github.loongin.omniresonance.node.NodeForm;
import io.github.loongin.omniresonance.node.NodeMode;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

final class ManagementErrorTextTest {
    private static String key(Component text) {
        return ((TranslatableContents) text.getContents()).getKey();
    }

    @Test
    void tunnelAndNetworkFailuresNameTheActualCreationTarget() {
        var network = new NetworkSummary(new UUID(1, 1), new UUID(2, 2), "Network");
        var tunnel = new NetworkTerminalState.TunnelEdit(network, null, "Tunnel");
        assertEquals(
                "omniresonance.terminal.error.invalid_tunnel_name",
                key(ManagementErrorText.terminal(NetworkTerminalResponse.Reason.INVALID_NAME, tunnel, false)));
        assertEquals(
                "omniresonance.terminal.error.tunnel_quota",
                key(ManagementErrorText.terminal(NetworkTerminalResponse.Reason.QUOTA_REACHED, tunnel, false)));
        assertEquals(
                "omniresonance.terminal.error.network_quota",
                key(ManagementErrorText.terminal(NetworkTerminalResponse.Reason.QUOTA_REACHED, null, true)));
        assertEquals(
                NetworkTerminalResponse.Reason.QUOTA_REACHED.translationKey(),
                key(ManagementErrorText.terminal(NetworkTerminalResponse.Reason.QUOTA_REACHED, null, false)));
    }

    @Test
    void channelErrorsDoNotDescribeANodeNameOrNodeConfigurationLimit() {
        var node = new NodeMenuNodeSummary(
                new UUID(1, 1),
                "Network",
                new UUID(2, 2),
                "Node",
                0,
                ResourceLocation.withDefaultNamespace("overworld"),
                BlockPos.ZERO,
                NodeForm.BLOCK,
                Direction.NORTH,
                true,
                false,
                NodeMode.DIRECT);
        var state = new NodeMenuState.DirectChannelEdit(
                node, new NodeTunnelSummary(new UUID(3, 3), "Tunnel", 0, true, 1, 0, 0), null, "Channel");
        assertEquals(
                "omniresonance.node_menu.error.invalid_channel_name",
                key(ManagementErrorText.node(NodeMenuResponse.Reason.INVALID_NAME, state)));
        assertEquals(
                "omniresonance.node_menu.error.channel_quota",
                key(ManagementErrorText.node(NodeMenuResponse.Reason.QUOTA_REACHED, state)));
        assertEquals(
                "omniresonance.node_menu.error.channel_locked",
                key(ManagementErrorText.node(NodeMenuResponse.Reason.LOCKED, state)));
        assertEquals(
                "omniresonance.node_menu.error.channel_stale_revision",
                key(ManagementErrorText.node(NodeMenuResponse.Reason.STALE_REVISION, state)));
    }
}
