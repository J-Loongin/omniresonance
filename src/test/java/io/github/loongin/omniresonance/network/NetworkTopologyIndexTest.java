// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.node.NetworkNodeRecord;
import io.github.loongin.omniresonance.node.NodeForm;
import io.github.loongin.omniresonance.node.NodeMode;
import io.github.loongin.omniresonance.persistence.NetworkSavedData;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

/** Pure topology paging/index behavior and authoritative SavedData mutation contracts. */
final class NetworkTopologyIndexTest {
    private static final UUID NETWORK = new UUID(310, 1);
    private static final UUID OWNER = new UUID(310, 2);
    private static final UUID TUNNEL = new UUID(311, 1);
    private static final UUID TUNNEL_SECOND = new UUID(311, 2);
    private static final UUID CHANNEL_A = new UUID(312, 1);
    private static final UUID CHANNEL_B = new UUID(312, 2);
    private static final UUID CHANNEL_C = new UUID(312, 3);
    private static final UUID CHANNEL_D = new UUID(312, 4);
    private static final UUID CHANNEL_E = new UUID(312, 5);
    private static final UUID NODE_A = new UUID(313, 1);
    private static final UUID NODE_B = new UUID(313, 2);

    @Test
    void tenThousandTunnelsPageInBothDirectionsWithoutSharingLists() {
        List<NetworkTunnelRecord> tunnels = new ArrayList<>();
        List<NetworkChannelRecord> channels = new ArrayList<>();
        for (int index = 0; index < 10_000; index++) {
            UUID tunnelId = new UUID(320, index + 1);
            tunnels.add(NetworkTunnelRecord.freshWithInitialChannel(
                    tunnelId, index + 1, new ManagedName("Tunnel " + index)));
            channels.add(
                    NetworkChannelRecord.fresh(new UUID(322, index + 1), tunnelId, 1, new ManagedName("Channel 1")));
        }
        NetworkTopologyIndex index = new NetworkTopologyIndex(tunnels, channels, List.of(), List.of());

        NetworkTopologyIndex.Page<NetworkTunnelRecord> first = index.pageTunnels(null, false, 128);
        UUID anchor = first.entries().getLast().tunnelId();
        NetworkTopologyIndex.Page<NetworkTunnelRecord> second = index.pageTunnels(anchor, false, 128);
        NetworkTopologyIndex.Page<NetworkTunnelRecord> backwards =
                index.pageTunnels(second.entries().getFirst().tunnelId(), true, 128);

        assertEquals(10_000, first.totalCount());
        assertEquals(128, first.entries().size());
        assertFalse(first.hasPrevious());
        assertTrue(first.hasNext());
        assertEquals(129, second.entries().getFirst().tunnelNumber());
        assertEquals(first.entries(), backwards.entries());
        assertThrows(UnsupportedOperationException.class, () -> first.entries().clear());
        assertThrows(IllegalArgumentException.class, () -> index.pageTunnels(new UUID(999, 1), false, 128));
        assertThrows(IllegalArgumentException.class, () -> index.pageTunnels(null, true, 128));
    }

    @Test
    void constructorIndependentlyRejectsDuplicateAuthorityKeys() {
        NetworkTunnelRecord first = NetworkTunnelRecord.fresh(TUNNEL, 1, new ManagedName("First"));
        NetworkTunnelRecord duplicateNumber = NetworkTunnelRecord.fresh(new UUID(311, 2), 1, new ManagedName("Second"));
        NetworkChannelRecord channel = NetworkChannelRecord.fresh(CHANNEL_A, TUNNEL, 1, new ManagedName("Channel"));
        DirectNodeBinding input = new DirectNodeBinding(NODE_A, CHANNEL_A, TransferDirection.INPUT);
        DirectNodeBinding output = new DirectNodeBinding(NODE_A, CHANNEL_A, TransferDirection.OUTPUT);
        DomainNodeConfiguration domainInput = new DomainNodeConfiguration(NODE_A, TransferDirection.INPUT);
        DomainNodeConfiguration domainOutput = new DomainNodeConfiguration(NODE_A, TransferDirection.OUTPUT);

        assertThrows(
                IllegalArgumentException.class,
                () -> new NetworkTopologyIndex(List.of(first, duplicateNumber), List.of(), List.of(), List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NetworkTopologyIndex(List.of(first), List.of(channel), List.of(input, output), List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NetworkTopologyIndex(
                        List.of(first), List.of(channel), List.of(), List.of(domainInput, domainOutput)));
    }

    @Test
    void oneNodeCannotIndexBindingsFromDifferentTunnels() {
        NetworkTopologyIndex index = twoTunnelIndex();
        index.putBinding(new DirectNodeBinding(NODE_A, CHANNEL_A, TransferDirection.INPUT));

        assertThrows(
                IllegalArgumentException.class,
                () -> index.putBinding(new DirectNodeBinding(NODE_A, CHANNEL_C, TransferDirection.OUTPUT)));

        assertEquals(Optional.of(TUNNEL), index.directTunnelId(NODE_A));
        assertTrue(index.findBinding(NODE_A, CHANNEL_A).isPresent());
        assertTrue(index.findBinding(NODE_A, CHANNEL_C).isEmpty());
    }

    @Test
    void removingTheLastBindingClearsTheDerivedTunnel() {
        NetworkTopologyIndex index = twoTunnelIndex();
        index.putBinding(new DirectNodeBinding(NODE_A, CHANNEL_A, TransferDirection.INPUT));

        index.removeBinding(NODE_A, CHANNEL_A);

        assertTrue(index.directTunnelId(NODE_A).isEmpty());
    }

    @Test
    void tunnelAndChannelMutationsPreserveNumbersNamesRevisionsAndDirtyState() {
        NetworkSavedData data = data();
        NetworkSavedData.TunnelCreation creation =
                data.createTunnel(TUNNEL, new ManagedName("Main"), CHANNEL_A, new ManagedName("Channel 1"), 1);
        NetworkTunnelRecord tunnel = creation.tunnel();
        assertEquals(1, tunnel.tunnelNumber());
        assertEquals(1, tunnel.lastChannelNumber());
        assertEquals(0, tunnel.revision());
        assertEquals(creation.initialChannel(), data.channels(TUNNEL).getFirst());
        assertEquals(1, data.lastTunnelNumber());
        assertEquals(1, data.topologyRevision());
        data.setDirty(false);

        assertThrows(
                IllegalArgumentException.class,
                () -> data.createTunnel(
                        new UUID(311, 2), new ManagedName("main"), new UUID(312, 3), new ManagedName("Channel 1"), 2));
        assertThrows(
                IllegalArgumentException.class,
                () -> data.createTunnel(
                        new UUID(311, 2),
                        new ManagedName("Second"),
                        new UUID(312, 3),
                        new ManagedName("Channel 1"),
                        1));
        assertEquals(1, data.lastTunnelNumber());
        assertFalse(data.isDirty());

        assertSame(tunnel, data.renameTunnel(TUNNEL, 0, tunnel.name()).orElseThrow());
        assertFalse(data.isDirty());
        NetworkTunnelRecord renamed =
                data.renameTunnel(TUNNEL, 0, new ManagedName("Renamed")).orElseThrow();
        assertEquals(1, renamed.revision());
        assertEquals(2, data.topologyRevision());
        NetworkTunnelRecord disabled = data.setTunnelEnabled(TUNNEL, 1, false).orElseThrow();
        assertFalse(disabled.enabled());
        assertEquals(2, disabled.revision());

        NetworkChannelRecord channel =
                data.renameChannel(CHANNEL_A, 0, new ManagedName("Items")).orElseThrow();
        assertEquals(1, channel.channelNumber());
        NetworkTunnelRecord allocated = data.findTunnel(TUNNEL).orElseThrow();
        assertEquals(1, allocated.lastChannelNumber());
        assertEquals(2, allocated.revision());
        assertThrows(
                IllegalArgumentException.class,
                () -> data.createChannel(TUNNEL, 2, CHANNEL_B, new ManagedName("Fluids"), 1));
        assertEquals(1, data.channels(TUNNEL).size());
        NetworkChannelRecord renamedChannel = data.renameChannel(CHANNEL_A, 1, new ManagedName("Primary items"))
                .orElseThrow();
        assertEquals(2, renamedChannel.revision());
    }

    @Test
    void displayNameSuggestionsFillHolesWithoutReusingInternalNumbers() {
        NetworkTunnelRecord tunnel = new NetworkTunnelRecord(TUNNEL, 7, new ManagedName("Tunnel 1"), 0, true, 3);
        NetworkChannelRecord first = new NetworkChannelRecord(CHANNEL_A, TUNNEL, 1, new ManagedName("Channel 1"), 0);
        NetworkChannelRecord third = new NetworkChannelRecord(CHANNEL_B, TUNNEL, 3, new ManagedName("Channel 3"), 0);
        NetworkTopologyIndex index =
                new NetworkTopologyIndex(List.of(tunnel), List.of(first, third), List.of(), List.of());

        assertEquals(
                "Tunnel 2",
                index.suggestedTunnelName(new ManagedNamePrefix("Tunnel")).value());
        assertEquals(
                "Channel 2",
                index.suggestedChannelName(TUNNEL, new ManagedNamePrefix("Channel"))
                        .value());
        assertEquals(3, tunnel.lastChannelNumber());
    }

    @Test
    void directAndDomainConfigurationsAreModeScopedRevisionedAndQuotaBounded() {
        NetworkSavedData data = data();
        NetworkNodeRecord node = data.createNode(
                NODE_A,
                new ManagedName("Node A"),
                GlobalPos.of(Level.OVERWORLD, new BlockPos(1, 64, 1)),
                NodeForm.BLOCK,
                Direction.DOWN);
        node = data.setNodeMode(NODE_A, node.revision(), NodeMode.DIRECT, false).orElseThrow();
        NetworkSavedData.TunnelCreation creation =
                data.createTunnel(TUNNEL, new ManagedName("Main"), CHANNEL_A, new ManagedName("A"), -1);
        NetworkChannelRecord first = creation.initialChannel();
        data.createChannel(TUNNEL, creation.tunnel().revision(), CHANNEL_B, new ManagedName("B"), -1);

        long beforeBinding = data.topologyRevision();
        NetworkNodeRecord bound =
                data.setDirectBinding(NODE_A, node.revision(), first.channelId(), TransferDirection.INPUT, false, 1);
        assertEquals(node.revision() + 1, bound.revision());
        assertEquals(beforeBinding + 1, data.topologyRevision());
        assertEquals(
                List.of(new DirectNodeBinding(NODE_A, CHANNEL_A, TransferDirection.INPUT)),
                data.directBindings(NODE_A));
        assertThrows(
                IllegalStateException.class,
                () -> data.setDirectBinding(NODE_A, bound.revision(), CHANNEL_A, TransferDirection.OUTPUT, false, 1));
        NetworkNodeRecord switched =
                data.setDirectBinding(NODE_A, bound.revision(), CHANNEL_A, TransferDirection.OUTPUT, true, 1);
        assertThrows(
                IllegalArgumentException.class,
                () -> data.setDirectBinding(NODE_A, switched.revision(), CHANNEL_B, TransferDirection.INPUT, false, 1));

        NetworkNodeRecord domain = data.setNodeMode(NODE_A, switched.revision(), NodeMode.DOMAIN, true)
                .orElseThrow();
        assertTrue(data.directBindings(NODE_A).isEmpty());
        NetworkNodeRecord configured =
                data.setDomainConfiguration(NODE_A, domain.revision(), TransferDirection.INPUT, false);
        assertEquals(
                TransferDirection.INPUT,
                data.domainConfiguration(NODE_A).orElseThrow().direction());
        assertThrows(
                IllegalStateException.class,
                () -> data.setDomainConfiguration(NODE_A, configured.revision(), TransferDirection.OUTPUT, false));
        NetworkNodeRecord removed =
                data.removeDomainConfiguration(NODE_A, configured.revision()).orElseThrow();
        assertEquals(configured.revision() + 1, removed.revision());
        assertTrue(data.domainConfiguration(NODE_A).isEmpty());
    }

    @Test
    void directTunnelSwitchRemovesEveryOldBindingWithOneRevision() {
        DirectSwitchFixture fixture = directSwitchFixture();
        fixture.data().setDirty(false);
        long topologyRevision = fixture.data().topologyRevision();
        long nodeRevision = fixture.node().revision();

        NetworkSavedData.TunnelSwitchResult result =
                fixture.data().switchDirectTunnel(NODE_A, nodeRevision, TUNNEL_SECOND, topologyRevision);

        assertEquals(TUNNEL_SECOND, result.targetTunnelId());
        assertEquals(3, result.removedBindingCount());
        assertEquals(nodeRevision + 1, result.node().revision());
        assertEquals(topologyRevision + 1, fixture.data().topologyRevision());
        assertTrue(fixture.data().directBindings(NODE_A).isEmpty());
        assertTrue(fixture.data().directTunnelId(NODE_A).isEmpty());
        assertTrue(fixture.data().isDirty());
    }

    @Test
    void rejectedDirectTunnelSwitchPreservesBindingsRevisionsAndDirtyState() {
        DirectSwitchFixture fixture = directSwitchFixture();
        fixture.data().setDirty(false);
        long topologyRevision = fixture.data().topologyRevision();
        long nodeRevision = fixture.node().revision();
        List<DirectNodeBinding> before = fixture.data().directBindings(NODE_A);

        assertThrows(
                IllegalStateException.class,
                () -> fixture.data().switchDirectTunnel(NODE_A, nodeRevision - 1, TUNNEL_SECOND, topologyRevision));
        assertThrows(
                IllegalStateException.class,
                () -> fixture.data().switchDirectTunnel(NODE_A, nodeRevision, TUNNEL_SECOND, topologyRevision - 1));

        assertEquals(before, fixture.data().directBindings(NODE_A));
        assertEquals(nodeRevision, fixture.data().findNode(NODE_A).orElseThrow().revision());
        assertEquals(topologyRevision, fixture.data().topologyRevision());
        assertFalse(fixture.data().isDirty());

        NetworkTunnelRecord target = fixture.data().findTunnel(TUNNEL_SECOND).orElseThrow();
        fixture.data().setTunnelEnabled(TUNNEL_SECOND, target.revision(), false).orElseThrow();
        long disabledTopologyRevision = fixture.data().topologyRevision();
        fixture.data().setDirty(false);

        assertThrows(
                IllegalStateException.class,
                () -> fixture.data().switchDirectTunnel(NODE_A, nodeRevision, TUNNEL_SECOND, disabledTopologyRevision));
        assertEquals(before, fixture.data().directBindings(NODE_A));
        assertEquals(nodeRevision, fixture.data().findNode(NODE_A).orElseThrow().revision());
        assertEquals(disabledTopologyRevision, fixture.data().topologyRevision());
        assertFalse(fixture.data().isDirty());
    }

    @Test
    void deletionSummaryIsBoundedStableAndStaleConfirmationCannotCascade() {
        NetworkSavedData data = data();
        NetworkNodeRecord first = directNode(data, NODE_A, "A", 1);
        NetworkNodeRecord second = directNode(data, NODE_B, "B", 2);
        NetworkChannelRecord channel = data.createTunnel(
                        TUNNEL, new ManagedName("Main"), CHANNEL_A, new ManagedName("Items"), -1)
                .initialChannel();
        first = data.setDirectBinding(NODE_A, first.revision(), CHANNEL_A, TransferDirection.INPUT, false, -1);
        second = data.setDirectBinding(NODE_B, second.revision(), CHANNEL_A, TransferDirection.OUTPUT, false, -1);

        data.createChannel(
                TUNNEL, data.findTunnel(TUNNEL).orElseThrow().revision(), CHANNEL_B, new ManagedName("Reserve"), -1);
        TopologyDeletionImpact summary = data.summarizeChannelDeletion(CHANNEL_A);
        assertEquals(CHANNEL_A, summary.objectId());
        assertEquals(0, summary.channelCount());
        assertEquals(2, summary.bindingCount());
        assertEquals(List.of(NODE_A, NODE_B), summary.affectedNodeIds());
        assertThrows(
                UnsupportedOperationException.class,
                () -> summary.affectedNodeIds().clear());

        NetworkChannelRecord renamed = data.renameChannel(CHANNEL_A, channel.revision(), new ManagedName("Renamed"))
                .orElseThrow();
        assertThrows(
                IllegalStateException.class,
                () -> data.deleteChannel(CHANNEL_A, renamed.revision(), summary.topologyRevision()));
        assertTrue(data.findChannel(CHANNEL_A).isPresent());
        assertEquals(2, data.summarizeChannelDeletion(CHANNEL_A).bindingCount());

        TopologyDeletionImpact fresh = data.summarizeChannelDeletion(CHANNEL_A);
        List<NetworkNodeRecord> changed = data.deleteChannel(CHANNEL_A, renamed.revision(), fresh.topologyRevision());
        assertEquals(
                List.of(NODE_A, NODE_B),
                changed.stream().map(NetworkNodeRecord::nodeId).toList());
        assertTrue(data.findChannel(CHANNEL_A).isEmpty());
        assertTrue(data.directBindings(NODE_A).isEmpty());
        assertEquals(first.revision() + 1, data.findNode(NODE_A).orElseThrow().revision());
        assertEquals(second.revision() + 1, data.findNode(NODE_B).orElseThrow().revision());
    }

    private static NetworkNodeRecord directNode(NetworkSavedData data, UUID id, String name, int x) {
        NetworkNodeRecord node = data.createNode(
                id,
                new ManagedName(name),
                GlobalPos.of(Level.OVERWORLD, new BlockPos(x, 64, 0)),
                NodeForm.BLOCK,
                Direction.DOWN);
        return data.setNodeMode(id, node.revision(), NodeMode.DIRECT, false).orElseThrow();
    }

    private static NetworkTopologyIndex twoTunnelIndex() {
        NetworkTunnelRecord first = NetworkTunnelRecord.freshWithInitialChannel(TUNNEL, 1, new ManagedName("First"));
        NetworkTunnelRecord second =
                NetworkTunnelRecord.freshWithInitialChannel(TUNNEL_SECOND, 2, new ManagedName("Second"));
        NetworkChannelRecord firstChannel =
                NetworkChannelRecord.fresh(CHANNEL_A, TUNNEL, 1, new ManagedName("First channel"));
        NetworkChannelRecord secondChannel =
                NetworkChannelRecord.fresh(CHANNEL_C, TUNNEL_SECOND, 1, new ManagedName("Second channel"));
        return new NetworkTopologyIndex(
                List.of(first, second), List.of(firstChannel, secondChannel), List.of(), List.of());
    }

    private static DirectSwitchFixture directSwitchFixture() {
        NetworkSavedData data = data();
        NetworkNodeRecord node = directNode(data, NODE_A, "Switching", 1);
        NetworkSavedData.TunnelCreation source =
                data.createTunnel(TUNNEL, new ManagedName("Source"), CHANNEL_A, new ManagedName("A"), -1);
        data.createChannel(TUNNEL, source.tunnel().revision(), CHANNEL_B, new ManagedName("B"), -1);
        data.createChannel(
                TUNNEL, data.findTunnel(TUNNEL).orElseThrow().revision(), CHANNEL_D, new ManagedName("C"), -1);
        data.createTunnel(TUNNEL_SECOND, new ManagedName("Target"), CHANNEL_E, new ManagedName("Target channel"), -1);
        for (UUID channelId : List.of(CHANNEL_A, CHANNEL_B, CHANNEL_D)) {
            node = data.setDirectBinding(NODE_A, node.revision(), channelId, TransferDirection.INPUT, false, -1);
        }
        return new DirectSwitchFixture(data, node);
    }

    private record DirectSwitchFixture(NetworkSavedData data, NetworkNodeRecord node) {}

    private static NetworkSavedData data() {
        return NetworkSavedData.create(new NetworkMetadata(NETWORK, OWNER, new ManagedName("Network"), 0, Set.of()));
    }
}
