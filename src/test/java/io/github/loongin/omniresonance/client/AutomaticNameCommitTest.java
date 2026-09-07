// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.networking.NetworkSummary;
import io.github.loongin.omniresonance.networking.NetworkTerminalState;
import io.github.loongin.omniresonance.networking.NodeMenuNodeSummary;
import io.github.loongin.omniresonance.networking.NodeMenuState;
import io.github.loongin.omniresonance.networking.NodeNetworkSummary;
import io.github.loongin.omniresonance.networking.NodeTunnelSummary;
import io.github.loongin.omniresonance.networking.TunnelSummary;
import io.github.loongin.omniresonance.node.NodeForm;
import io.github.loongin.omniresonance.node.NodeMode;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

final class AutomaticNameCommitTest {
    @Test
    void matchingNodeAndChannelCreationStatesCommitOnce() {
        AutomaticNameCommit.Resolution node = AutomaticNameCommit.idle()
                .arm(AutomaticNameCommit.Target.NODE_LINK)
                .resolveNode(true, new NodeMenuState.BlankEdit(network(), 7));
        AutomaticNameCommit.Resolution channel = AutomaticNameCommit.idle()
                .arm(AutomaticNameCommit.Target.CHANNEL)
                .resolveNode(true, new NodeMenuState.DirectChannelEdit(nodeSummary(), nodeTunnel(), null, "Channel 2"));

        assertEquals(AutomaticNameCommit.Target.NODE_LINK, node.commit());
        assertEquals(AutomaticNameCommit.Target.CHANNEL, channel.commit());
        assertTrue(node.next().submitting());
        assertTrue(channel.next().submitting());
    }

    @Test
    void matchingTunnelCreationStateCommitsOnce() {
        AutomaticNameCommit.Resolution resolution = AutomaticNameCommit.idle()
                .arm(AutomaticNameCommit.Target.TUNNEL)
                .resolveTerminal(true, new NetworkTerminalState.TunnelEdit(networkSummary(), null, "Tunnel 3"));

        assertEquals(AutomaticNameCommit.Target.TUNNEL, resolution.commit());
        assertTrue(resolution.next().submitting());
    }

    @Test
    void renameStatesNeverTriggerAutomaticCreation() {
        AutomaticNameCommit.Resolution channelRename = AutomaticNameCommit.idle()
                .arm(AutomaticNameCommit.Target.CHANNEL)
                .resolveNode(
                        true,
                        new NodeMenuState.DirectChannelEdit(
                                nodeSummary(),
                                nodeTunnel(),
                                new io.github.loongin.omniresonance.networking.NodeChannelSummary(
                                        new UUID(5, 1), "Channel", 0, 1, 0, null),
                                null));
        AutomaticNameCommit.Resolution tunnelRename = AutomaticNameCommit.idle()
                .arm(AutomaticNameCommit.Target.TUNNEL)
                .resolveTerminal(
                        true,
                        new NetworkTerminalState.TunnelEdit(
                                networkSummary(), new TunnelSummary(new UUID(5, 2), "Tunnel", 0, true, 1, 0), null));

        assertNull(channelRename.commit());
        assertNull(tunnelRename.commit());
        assertFalse(channelRename.next().armed());
        assertFalse(tunnelRename.next().armed());
    }

    @Test
    void failureClearsPendingCreationWithoutRetrying() {
        AutomaticNameCommit.Resolution resolution = AutomaticNameCommit.idle()
                .arm(AutomaticNameCommit.Target.NODE_LINK)
                .resolveNode(false, new NodeMenuState.BlankEdit(network(), 7));

        assertNull(resolution.commit());
        assertFalse(resolution.next().armed());
    }

    @Test
    void submittedDefaultNameSuppressesTheEditorUntilItsResponse() {
        NodeMenuState.BlankEdit edit = new NodeMenuState.BlankEdit(network(), 7);
        AutomaticNameCommit.Resolution begin = AutomaticNameCommit.idle()
                .arm(AutomaticNameCommit.Target.NODE_LINK)
                .resolveNode(true, edit);

        assertTrue(begin.next().suppressEditor());

        AutomaticNameCommit.Resolution rejected = begin.next().resolveNode(false, edit);
        assertNull(rejected.commit());
        assertFalse(rejected.next().suppressEditor());
    }

    private static NodeNetworkSummary network() {
        return new NodeNetworkSummary(new UUID(1, 1), "Network", NodeNetworkSummary.Role.OWNER);
    }

    private static NetworkSummary networkSummary() {
        return new NetworkSummary(new UUID(2, 1), new UUID(2, 2), "Network");
    }

    private static NodeMenuNodeSummary nodeSummary() {
        return new NodeMenuNodeSummary(
                new UUID(3, 1),
                "Network",
                new UUID(3, 2),
                "Node",
                1,
                ResourceLocation.withDefaultNamespace("overworld"),
                new BlockPos(1, 2, 3),
                NodeForm.BLOCK,
                Direction.NORTH,
                true,
                false,
                NodeMode.DIRECT);
    }

    private static NodeTunnelSummary nodeTunnel() {
        return new NodeTunnelSummary(new UUID(4, 1), "Tunnel", 0, true, 1, 0, 0);
    }
}
