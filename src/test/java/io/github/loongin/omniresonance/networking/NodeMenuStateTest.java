// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.loongin.omniresonance.network.TransferDirection;
import io.github.loongin.omniresonance.node.NodeForm;
import io.github.loongin.omniresonance.node.NodeMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class NodeMenuStateTest {
    private static final UUID NETWORK = new UUID(1, 1);
    private static final UUID NODE = new UUID(2, 2);
    private static final NodeNetworkSummary NETWORK_SUMMARY =
            new NodeNetworkSummary(NETWORK, "Main", NodeNetworkSummary.Role.OWNER);

    @Test
    void networkPageOwnsAtMostOneHundredTwentyEightAuthorizedSummaries() {
        List<NodeNetworkSummary> mutable = new ArrayList<>(List.of(NETWORK_SUMMARY));
        NodeNetworkPage page = new NodeNetworkPage(mutable, 1, false, false);
        mutable.clear();

        assertEquals(List.of(NETWORK_SUMMARY), page.entries());
        assertThrows(UnsupportedOperationException.class, () -> page.entries().clear());
        assertThrows(
                IllegalArgumentException.class,
                () -> new NodeNetworkPage(Collections.nCopies(129, NETWORK_SUMMARY), 129, false, false));
        assertThrows(IllegalArgumentException.class, () -> new NodeNetworkPage(List.of(), -1, false, false));
        assertThrows(
                IllegalArgumentException.class, () -> new NodeNetworkPage(List.of(NETWORK_SUMMARY), 0, false, false));
        assertThrows(IllegalArgumentException.class, () -> new NodeNetworkPage(List.of(), 0, true, false));
        assertThrows(IllegalArgumentException.class, () -> new NodeNetworkPage(List.of(), 0, false, true));
    }

    @Test
    void summariesRequireCanonicalNamesAndCompleteStableValues() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new NodeNetworkSummary(NETWORK, " Main ", NodeNetworkSummary.Role.OWNER));
        assertThrows(NullPointerException.class, () -> new NodeNetworkSummary(NETWORK, "Main", null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NodeMenuNodeSummary(
                        NETWORK,
                        "Main",
                        NODE,
                        "Node",
                        -1,
                        ResourceLocation.withDefaultNamespace("overworld"),
                        BlockPos.ZERO,
                        NodeForm.BLOCK,
                        Direction.DOWN,
                        true,
                        false,
                        NodeMode.UNCONFIGURED));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NodeMenuNodeSummary(
                        NETWORK,
                        "Main",
                        NODE,
                        " Node ",
                        0,
                        ResourceLocation.withDefaultNamespace("overworld"),
                        BlockPos.ZERO,
                        NodeForm.BLOCK,
                        Direction.DOWN,
                        true,
                        false,
                        NodeMode.UNCONFIGURED));
    }

    @Test
    void everyStateValidatesOnlyItsOwnBoundedPayload() {
        NodeNetworkPage page = new NodeNetworkPage(List.of(NETWORK_SUMMARY), 1, false, false);
        NodeMenuNodeSummary node = nodeSummary();
        NodeTunnelSummary tunnel = new NodeTunnelSummary(NODE, "Tunnel", 3, true, 2, 5, 1);
        NodeTunnelSummary disabledTunnel = new NodeTunnelSummary(new UUID(3, 3), "Off", 4, false, 0, 0, 0);
        NodeChannelSummary channel =
                new NodeChannelSummary(new UUID(4, 4), "Channel", 7, 2, 3, TransferDirection.OUTPUT);
        NodeTunnelPage tunnels = new NodeTunnelPage(List.of(tunnel), 1, false, false);
        NodeChannelPage channels = new NodeChannelPage(List.of(channel), 1, false, false);

        assertEquals(page, new NodeMenuState.BlankList(page).page());
        assertEquals(12, new NodeMenuState.BlankEdit(NETWORK_SUMMARY, 12).suggestedNodeNumber());
        assertEquals(node, new NodeMenuState.LinkedRoot(node).node());
        assertEquals(node, new NodeMenuState.LinkedRename(node).node());
        assertEquals(node, new NodeMenuState.LinkedMode(node).node());
        assertEquals(node, new NodeMenuState.ModeRoot(node).node());
        assertEquals(page, new NodeMenuState.NetworkSelection(node, page).page());
        NodeNetworkSummary target = new NodeNetworkSummary(new UUID(9, 9), "Target", NodeNetworkSummary.Role.ADMIN);
        assertEquals(target, new NodeMenuState.NetworkMoveEdit(node, target).target());
        assertEquals(7, new NodeMenuState.DirectTunnelList(node, tunnels, 7).revision());
        assertEquals(disabledTunnel, new NodeMenuState.RestrictedTunnel(node, disabledTunnel).tunnel());
        assertEquals(channels, new NodeMenuState.DirectChannelList(node, tunnel, channels).page());
        assertEquals(channel, new NodeMenuState.DirectBindingEdit(node, tunnel, channel).channel());
        assertEquals(channel, new NodeMenuState.DirectChannelRoot(node, tunnel, channel).channel());
        assertEquals(channel, new NodeMenuState.DirectChannelSettings(node, tunnel, channel).channel());
        NodeTunnelSwitchSummary switchSummary = new NodeTunnelSwitchSummary(new UUID(8, 8), "Target", 2);
        assertEquals(switchSummary, new NodeMenuState.DirectTunnelSwitch(node, switchSummary).summary());
        assertEquals(null, new NodeMenuState.DomainRoot(node, null).direction());
        assertEquals(TransferDirection.INPUT, new NodeMenuState.DomainEdit(node, TransferDirection.INPUT).direction());
        assertEquals("Channel 2", new NodeMenuState.DirectChannelEdit(node, tunnel, null, "Channel 2").suggestedName());
        assertEquals(channel, new NodeMenuState.DirectChannelEdit(node, tunnel, channel, null).existing());
        TopologyDeletionSummary deletion = new TopologyDeletionSummary(
                TopologyDeletionSummary.Kind.CHANNEL, channel.channelId(), channel.name(), 0, 2);
        assertEquals(deletion, new NodeMenuState.DirectChannelDelete(node, tunnel, deletion).summary());
        assertThrows(IllegalArgumentException.class, () -> new NodeMenuState.BlankEdit(NETWORK_SUMMARY, 0));
        assertThrows(IllegalArgumentException.class, () -> new NodeMenuState.BlankEdit(NETWORK_SUMMARY, -1));
        assertThrows(NullPointerException.class, () -> new NodeMenuState.BlankList(null));
        assertThrows(NullPointerException.class, () -> new NodeMenuState.LinkedRoot(null));
        assertThrows(NullPointerException.class, () -> new NodeMenuState.NetworkSelection(node, null));
        assertThrows(IllegalArgumentException.class, () -> new NodeMenuState.DirectTunnelList(node, tunnels, -1));
        assertThrows(
                IllegalArgumentException.class, () -> new NodeMenuState.DirectChannelEdit(node, tunnel, null, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NodeMenuState.DirectChannelDelete(
                        node,
                        tunnel,
                        new TopologyDeletionSummary(
                                TopologyDeletionSummary.Kind.TUNNEL, tunnel.tunnelId(), tunnel.name(), 1, 0)));
        assertThrows(NullPointerException.class, () -> new NodeTunnelSwitchSummary(null, "Target", 1));
        assertThrows(IllegalArgumentException.class, () -> new NodeTunnelSwitchSummary(NODE, " Target ", 1));
        assertThrows(IllegalArgumentException.class, () -> new NodeTunnelSwitchSummary(NODE, "Target", -1));
        assertThrows(NullPointerException.class, () -> new NodeMenuState.DirectTunnelSwitch(node, null));
    }

    @Test
    void nodeTopologyPagesOwnBoundedEntriesAndCounts() {
        NodeTunnelSummary tunnel = new NodeTunnelSummary(NODE, "Tunnel", 1, false, 4, 8, 2);
        NodeChannelSummary channel = new NodeChannelSummary(new UUID(5, 5), "Channel", 2, 3, 4, null);
        List<NodeTunnelSummary> mutableTunnels = new ArrayList<>(List.of(tunnel));
        List<NodeChannelSummary> mutableChannels = new ArrayList<>(List.of(channel));
        NodeTunnelPage tunnelPage = new NodeTunnelPage(mutableTunnels, 1, false, false);
        NodeChannelPage channelPage = new NodeChannelPage(mutableChannels, 1, false, false);
        mutableTunnels.clear();
        mutableChannels.clear();

        assertEquals(List.of(tunnel), tunnelPage.entries());
        assertEquals(List.of(channel), channelPage.entries());
        assertThrows(
                UnsupportedOperationException.class, () -> tunnelPage.entries().clear());
        assertThrows(
                UnsupportedOperationException.class, () -> channelPage.entries().clear());
        assertThrows(
                IllegalArgumentException.class,
                () -> new NodeTunnelPage(Collections.nCopies(129, tunnel), 129, false, false));
        assertThrows(IllegalArgumentException.class, () -> new NodeTunnelPage(List.of(tunnel), 65536, false, true));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NodeChannelPage(Collections.nCopies(129, channel), 129, false, false));
        assertThrows(IllegalArgumentException.class, () -> new NodeTunnelSummary(NODE, "Tunnel", 0, true, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new NodeChannelSummary(NODE, "Channel", 0, -1, 0, null));
    }

    private static NodeMenuNodeSummary nodeSummary() {
        return new NodeMenuNodeSummary(
                NETWORK,
                "Main",
                NODE,
                "Input",
                7,
                ResourceLocation.withDefaultNamespace("overworld"),
                new BlockPos(12, 64, -9),
                NodeForm.PANEL,
                Direction.WEST,
                true,
                false,
                NodeMode.DIRECT);
    }
}
