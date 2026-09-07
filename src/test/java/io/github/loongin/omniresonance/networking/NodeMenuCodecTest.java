// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.network.TransferDirection;
import io.github.loongin.omniresonance.node.NodeForm;
import io.github.loongin.omniresonance.node.NodeMode;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class NodeMenuCodecTest {
    private static final int CONTAINER = 17;
    private static final UUID SESSION = new UUID(1, 2);
    private static final UUID NETWORK = new UUID(3, 4);
    private static final UUID NODE = new UUID(5, 6);

    @Test
    void everyRequestVariantRoundTripsWithoutSemanticNameTrust() {
        List<NodeMenuRequest> requests = List.of(
                new NodeMenuRequest.Page(CONTAINER, SESSION, 1, null, false),
                new NodeMenuRequest.Page(CONTAINER, SESSION, 2, NETWORK, true),
                new NodeMenuRequest.BeginBlank(CONTAINER, SESSION, 3, NETWORK),
                new NodeMenuRequest.Link(CONTAINER, SESSION, 4, "  §  "),
                new NodeMenuRequest.BeginRename(CONTAINER, SESSION, 5),
                new NodeMenuRequest.Rename(CONTAINER, SESSION, 6, "😀".repeat(64)),
                new NodeMenuRequest.BeginMode(CONTAINER, SESSION, 7),
                new NodeMenuRequest.SetMode(CONTAINER, SESSION, 8, NodeMode.DIRECT, false),
                new NodeMenuRequest.SetEnabled(CONTAINER, SESSION, 9, false),
                new NodeMenuRequest.SetChunkLoadingRequested(CONTAINER, SESSION, 10, true),
                new NodeMenuRequest.Heartbeat(CONTAINER, SESSION, 11),
                new NodeMenuRequest.CancelEdit(CONTAINER, SESSION, 12),
                new NodeMenuRequest.Back(CONTAINER, SESSION, 13),
                new NodeMenuRequest.OpenModeRoot(CONTAINER, SESSION, 14),
                new NodeMenuRequest.OpenNetworkSelection(CONTAINER, SESSION, 15),
                new NodeMenuRequest.BeginNetworkMove(CONTAINER, SESSION, 16, NETWORK),
                new NodeMenuRequest.MoveNetwork(CONTAINER, SESSION, 17, "Moved"),
                new NodeMenuRequest.OpenDirect(CONTAINER, SESSION, 18),
                new NodeMenuRequest.PageTunnels(CONTAINER, SESSION, 20, NODE, true),
                new NodeMenuRequest.OpenTunnel(CONTAINER, SESSION, 21, NODE),
                new NodeMenuRequest.PageChannels(CONTAINER, SESSION, 22, NODE, false),
                new NodeMenuRequest.BeginBinding(CONTAINER, SESSION, 23, NODE),
                new NodeMenuRequest.SetBindingDirection(CONTAINER, SESSION, 24, TransferDirection.OUTPUT, true),
                new NodeMenuRequest.RemoveBinding(CONTAINER, SESSION, 25),
                new NodeMenuRequest.OpenDomain(CONTAINER, SESSION, 26),
                new NodeMenuRequest.BeginDomainEdit(CONTAINER, SESSION, 27),
                new NodeMenuRequest.SetDomainDirection(CONTAINER, SESSION, 28, TransferDirection.INPUT, false),
                new NodeMenuRequest.RemoveDomain(CONTAINER, SESSION, 29),
                new NodeMenuRequest.BeginCreateChannel(CONTAINER, SESSION, 30, "Channel"),
                new NodeMenuRequest.BeginRenameChannel(CONTAINER, SESSION, 31, NODE),
                new NodeMenuRequest.SaveChannel(CONTAINER, SESSION, 32, "Channel 2"),
                new NodeMenuRequest.RequestDeleteChannel(CONTAINER, SESSION, 33, NODE),
                new NodeMenuRequest.ConfirmDeleteChannel(CONTAINER, SESSION, 34),
                new NodeMenuRequest.OpenChannel(CONTAINER, SESSION, 35, NODE),
                new NodeMenuRequest.OpenChannelSettings(CONTAINER, SESSION, 36),
                new NodeMenuRequest.RequestTunnelSwitch(CONTAINER, SESSION, 37, NODE),
                new NodeMenuRequest.ConfirmTunnelSwitch(CONTAINER, SESSION, Long.MAX_VALUE));

        for (NodeMenuRequest request : requests) {
            assertRoundTrip(NodeMenuRequest.STREAM_CODEC, request);
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> new NodeMenuRequest.SetMode(CONTAINER, SESSION, 1, NodeMode.UNCONFIGURED, false));
    }

    @Test
    void everyStateAndFailureRoundTripsWithinPacketLimit() {
        NodeNetworkSummary owner = new NodeNetworkSummary(NETWORK, "😀".repeat(64), NodeNetworkSummary.Role.OWNER);
        NodeNetworkSummary admin = new NodeNetworkSummary(new UUID(3, 5), "Admin", NodeNetworkSummary.Role.ADMIN);
        NodeNetworkPage page = new NodeNetworkPage(List.of(owner, admin), 2, true, true);
        NodeMenuNodeSummary node = nodeSummary();
        NodeTunnelSummary tunnel = new NodeTunnelSummary(NODE, "Tunnel", 3, true, 4, 7, 2);
        NodeTunnelSummary disabledTunnel = new NodeTunnelSummary(new UUID(8, 8), "Off", 4, false, 1, 0, 0);
        NodeChannelSummary channel =
                new NodeChannelSummary(new UUID(8, 9), "Channel", 5, 2, 3, TransferDirection.INPUT);
        NodeTunnelPage tunnelPage = new NodeTunnelPage(List.of(tunnel), 1, false, false);
        NodeChannelPage channelPage = new NodeChannelPage(List.of(channel), 1, false, false);
        List<NodeMenuState> states = List.of(
                new NodeMenuState.Unavailable(),
                new NodeMenuState.NoAccess(),
                new NodeMenuState.NoNetworks(),
                new NodeMenuState.BlankList(page),
                new NodeMenuState.BlankEdit(owner, Long.MAX_VALUE),
                new NodeMenuState.LinkedRoot(node),
                new NodeMenuState.LinkedRename(node),
                new NodeMenuState.LinkedMode(node),
                new NodeMenuState.ModeRoot(node),
                new NodeMenuState.NetworkSelection(node, page),
                new NodeMenuState.NetworkMoveEdit(node, admin),
                new NodeMenuState.DirectTunnelList(node, tunnelPage, 17),
                new NodeMenuState.RestrictedTunnel(node, disabledTunnel),
                new NodeMenuState.DirectChannelList(node, tunnel, channelPage),
                new NodeMenuState.DirectBindingEdit(node, tunnel, channel),
                new NodeMenuState.DirectChannelRoot(node, tunnel, channel),
                new NodeMenuState.DirectChannelSettings(node, tunnel, channel),
                new NodeMenuState.DirectTunnelSwitch(node, new NodeTunnelSwitchSummary(NODE, "Target", 3)),
                new NodeMenuState.DomainRoot(node, null),
                new NodeMenuState.DomainRoot(node, TransferDirection.OUTPUT),
                new NodeMenuState.DomainEdit(node, TransferDirection.INPUT),
                new NodeMenuState.DirectChannelEdit(node, tunnel, null, "Channel 2"),
                new NodeMenuState.DirectChannelEdit(node, tunnel, channel, null),
                new NodeMenuState.DirectChannelDelete(
                        node,
                        tunnel,
                        new TopologyDeletionSummary(
                                TopologyDeletionSummary.Kind.CHANNEL, channel.channelId(), channel.name(), 0, 3)));

        for (NodeMenuState state : states) {
            assertRoundTrip(NodeMenuResponse.STREAM_CODEC, new NodeMenuResponse.State(CONTAINER, SESSION, 0, state));
        }
        for (NodeMenuResponse.Reason reason : NodeMenuResponse.Reason.values()) {
            assertRoundTrip(
                    NodeMenuResponse.STREAM_CODEC, new NodeMenuResponse.Failure(CONTAINER, SESSION, 7, reason, null));
            assertRoundTrip(
                    NodeMenuResponse.STREAM_CODEC,
                    new NodeMenuResponse.Failure(CONTAINER, SESSION, 8, reason, new NodeMenuState.BlankList(page)));
        }
    }

    @Test
    void maximumNetworkPageStaysWellBelowManagementPacketLimit() {
        List<NodeNetworkSummary> entries = new ArrayList<>();
        for (int index = 0; index < 128; index++) {
            entries.add(new NodeNetworkSummary(new UUID(7, index), "😀".repeat(64), NodeNetworkSummary.Role.ADMIN));
        }
        NodeMenuResponse response = new NodeMenuResponse.State(
                CONTAINER, SESSION, 0, new NodeMenuState.BlankList(new NodeNetworkPage(entries, 1000, true, true)));
        FriendlyByteBuf buffer = buffer();
        try {
            NodeMenuResponse.STREAM_CODEC.encode(buffer, response);
            assertTrue(buffer.readableBytes() < 262144);
            assertEquals(response, NodeMenuResponse.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void maximumNodeTopologyPagesStayWellBelowManagementPacketLimit() {
        List<NodeTunnelSummary> tunnels = new ArrayList<>();
        List<NodeChannelSummary> channels = new ArrayList<>();
        for (int index = 0; index < 128; index++) {
            tunnels.add(new NodeTunnelSummary(new UUID(11, index), "😀".repeat(64), index, true, 65535, 262144, 1024));
            channels.add(new NodeChannelSummary(
                    new UUID(12, index),
                    "😀".repeat(64),
                    index,
                    131072,
                    131072,
                    index % 2 == 0 ? TransferDirection.INPUT : null));
        }
        NodeMenuResponse tunnelResponse = new NodeMenuResponse.State(
                CONTAINER,
                SESSION,
                0,
                new NodeMenuState.DirectTunnelList(
                        nodeSummary(), new NodeTunnelPage(tunnels, 65535, true, true), Long.MAX_VALUE));
        NodeTunnelSummary tunnel = new NodeTunnelSummary(NODE, "Tunnel", 3, true, 128, 256, 16);
        NodeMenuResponse channelResponse = new NodeMenuResponse.State(
                CONTAINER,
                SESSION,
                0,
                new NodeMenuState.DirectChannelList(
                        nodeSummary(), tunnel, new NodeChannelPage(channels, 65535, true, true)));

        assertEncodedBelowLimit(tunnelResponse);
        assertEncodedBelowLimit(channelResponse);
    }

    @Test
    void constructorsRejectInvalidEnvelopesBeforeEncoding() {
        assertThrows(IllegalArgumentException.class, () -> new NodeMenuRequest.Heartbeat(-1, SESSION, 1));
        assertThrows(IllegalArgumentException.class, () -> new NodeMenuRequest.Heartbeat(CONTAINER, SESSION, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NodeMenuResponse.State(-1, SESSION, 0, new NodeMenuState.NoNetworks()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NodeMenuResponse.State(CONTAINER, SESSION, -1, new NodeMenuState.NoNetworks()));
    }

    @Test
    void malformedWireRejectsUnknownTagsBoundsAndNoncanonicalValues() {
        rejectRequest(buffer -> buffer.writeByte(99));
        rejectRequest(buffer -> requestHeader(buffer, 17, 1));
        rejectRequest(buffer -> buffer.writeByte(9));
        rejectRequest(buffer -> requestHeader(buffer, 10, 0));
        rejectRequest(buffer -> requestHeader(buffer, -1, 1));
        rejectRequest(buffer -> {
            requestHeader(buffer, 2, 1);
            buffer.writeVarInt(257);
        });
        rejectRequest(buffer -> {
            requestHeader(buffer, 6, 1);
            buffer.writeByte(99);
            buffer.writeBoolean(false);
        });
        rejectRequest(buffer -> {
            requestHeader(buffer, 0, 1);
            buffer.writeBoolean(false);
            buffer.writeZero(262144);
        });

        rejectResponse(buffer -> buffer.writeByte(99));
        rejectResponse(buffer -> buffer.writeByte(0));
        rejectResponse(buffer -> {
            responseHeader(buffer, 0, 0);
            buffer.writeByte(3);
            buffer.writeVarInt(129);
        });
        rejectResponse(buffer -> {
            responseHeader(buffer, 0, 0);
            buffer.writeByte(3);
            buffer.writeVarInt(-1);
        });
        rejectResponse(buffer -> {
            responseHeader(buffer, 0, 0);
            buffer.writeByte(3);
            buffer.writeVarInt(1);
            writeNetwork(buffer, 99);
        });
        rejectResponse(buffer -> {
            responseHeader(buffer, 0, 0);
            buffer.writeByte(5);
            writeNodePrefix(buffer, "Bad:Dimension");
        });
        rejectResponse(buffer -> {
            buffer.writeByte(1);
            buffer.writeVarInt(CONTAINER);
            buffer.writeUUID(SESSION);
            buffer.writeLong(0);
            buffer.writeByte(99);
            buffer.writeBoolean(false);
        });
    }

    @Test
    void encodersRejectOversizedOrMalformedUtf8() {
        for (String invalid : List.of("a".repeat(257), "😀".repeat(65), "\uD800")) {
            FriendlyByteBuf request = buffer();
            try {
                assertThrows(
                        EncoderException.class,
                        () -> NodeMenuRequest.STREAM_CODEC.encode(
                                request, new NodeMenuRequest.Link(CONTAINER, SESSION, 1, invalid)));
            } finally {
                request.release();
            }
        }
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
                true,
                NodeMode.DOMAIN);
    }

    private static void requestHeader(FriendlyByteBuf buffer, int tag, long sequence) {
        buffer.writeByte(tag);
        buffer.writeVarInt(CONTAINER);
        buffer.writeUUID(SESSION);
        buffer.writeLong(sequence);
    }

    private static void responseHeader(FriendlyByteBuf buffer, int tag, long sequence) {
        buffer.writeByte(tag);
        buffer.writeVarInt(CONTAINER);
        buffer.writeUUID(SESSION);
        buffer.writeLong(sequence);
    }

    private static void writeNetwork(FriendlyByteBuf buffer, int role) {
        buffer.writeUUID(NETWORK);
        NetworkSummary.writeName(buffer, "Main");
        buffer.writeByte(role);
    }

    private static void writeNodePrefix(FriendlyByteBuf buffer, String dimension) {
        buffer.writeUUID(NETWORK);
        NetworkSummary.writeName(buffer, "Main");
        buffer.writeUUID(NODE);
        NetworkSummary.writeName(buffer, "Input");
        buffer.writeLong(0);
        NetworkSummary.writeName(buffer, dimension);
    }

    private static <T> void assertRoundTrip(
            net.minecraft.network.codec.StreamCodec<FriendlyByteBuf, T> codec, T expected) {
        FriendlyByteBuf buffer = buffer();
        try {
            codec.encode(buffer, expected);
            assertEquals(expected, codec.decode(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    private static void assertEncodedBelowLimit(NodeMenuResponse response) {
        FriendlyByteBuf buffer = buffer();
        try {
            NodeMenuResponse.STREAM_CODEC.encode(buffer, response);
            assertTrue(buffer.readableBytes() < 262144);
            assertEquals(response, NodeMenuResponse.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    private static void rejectRequest(Consumer<FriendlyByteBuf> writer) {
        FriendlyByteBuf buffer = buffer();
        try {
            writer.accept(buffer);
            assertThrows(DecoderException.class, () -> NodeMenuRequest.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    private static void rejectResponse(Consumer<FriendlyByteBuf> writer) {
        FriendlyByteBuf buffer = buffer();
        try {
            writer.accept(buffer);
            assertThrows(DecoderException.class, () -> NodeMenuResponse.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    private static FriendlyByteBuf buffer() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }
}
