// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class NetworkTerminalCodecTest {
    private static final UUID VIEW = new UUID(1, 1);
    private static final UUID SESSION = new UUID(2, 2);

    @Test
    void allRequestVariantsRoundTripIncludingSemanticInvalidNames() {
        List<NetworkTerminalRequest> requests = List.of(
                new NetworkTerminalRequest.Open(VIEW),
                new NetworkTerminalRequest.Page(VIEW, SESSION, 1, null, false),
                new NetworkTerminalRequest.Page(VIEW, SESSION, Long.MAX_VALUE, VIEW, true),
                new NetworkTerminalRequest.Create(VIEW, SESSION, 2, "😀".repeat(64)),
                new NetworkTerminalRequest.Create(VIEW, SESSION, 3, "  §  "),
                new NetworkTerminalRequest.OpenNetwork(VIEW, SESSION, 4, VIEW),
                new NetworkTerminalRequest.OpenTunnels(VIEW, SESSION, 5),
                new NetworkTerminalRequest.PageTunnels(VIEW, SESSION, 6, null, false),
                new NetworkTerminalRequest.BeginCreateTunnel(VIEW, SESSION, 7, "Tunnel"),
                new NetworkTerminalRequest.CreateTunnel(VIEW, SESSION, 8, "Tunnel 1", "Channel 1"),
                new NetworkTerminalRequest.BeginRenameTunnel(VIEW, SESSION, 9, VIEW),
                new NetworkTerminalRequest.RenameTunnel(VIEW, SESSION, 10, "Renamed"),
                new NetworkTerminalRequest.SetTunnelEnabled(VIEW, SESSION, 10, VIEW, false),
                new NetworkTerminalRequest.OpenChannels(VIEW, SESSION, 11, VIEW),
                new NetworkTerminalRequest.PageChannels(VIEW, SESSION, 12, null, false),
                new NetworkTerminalRequest.RequestDeleteTunnel(VIEW, SESSION, 16, VIEW),
                new NetworkTerminalRequest.ConfirmDelete(VIEW, SESSION, 18),
                new NetworkTerminalRequest.Heartbeat(VIEW, SESSION, 19),
                new NetworkTerminalRequest.CancelEdit(VIEW, SESSION, 20),
                new NetworkTerminalRequest.Back(VIEW, SESSION, 21),
                new NetworkTerminalRequest.OpenTunnelSettings(VIEW, SESSION, 22),
                new NetworkTerminalRequest.OpenMembers(VIEW, SESSION, 23),
                new NetworkTerminalRequest.PageMembers(VIEW, SESSION, 24, VIEW, true),
                new NetworkTerminalRequest.OpenAdministratorCandidates(VIEW, SESSION, 25),
                new NetworkTerminalRequest.PageAdministratorCandidates(VIEW, SESSION, 26, VIEW, 128),
                new NetworkTerminalRequest.AddAdministrator(VIEW, SESSION, 27, VIEW),
                new NetworkTerminalRequest.RequestRemoveAdministrator(VIEW, SESSION, 28, VIEW),
                new NetworkTerminalRequest.ConfirmRemoveAdministrator(VIEW, SESSION, 29),
                new NetworkTerminalRequest.Close(VIEW, SESSION));
        for (NetworkTerminalRequest request : requests) {
            FriendlyByteBuf buffer = buffer();
            try {
                NetworkTerminalRequest.STREAM_CODEC.encode(buffer, request);
                assertEquals(request, NetworkTerminalRequest.STREAM_CODEC.decode(buffer));
                assertEquals(0, buffer.readableBytes());
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void maximumPageAndAllFixedFailuresRoundTripWellBelowPacketLimit() {
        List<NetworkSummary> entries = new ArrayList<>();
        for (int i = 0; i < 128; i++) {
            entries.add(new NetworkSummary(new UUID(0, i), SESSION, "😀".repeat(64)));
        }
        NetworkTerminalPage page = new NetworkTerminalPage(
                entries, entries.getFirst(), Integer.MAX_VALUE, Integer.MAX_VALUE, -1, true, true);
        List<NetworkTerminalResponse> responses = new ArrayList<>();
        responses.add(new NetworkTerminalResponse.Success(VIEW, SESSION, Long.MAX_VALUE, page, entries.getFirst()));
        responses.add(new NetworkTerminalResponse.AccessRevoked(VIEW, SESSION, 4, VIEW));
        NetworkSummary network = new NetworkSummary(VIEW, SESSION, "Network");
        TunnelSummary tunnel = new TunnelSummary(VIEW, "Tunnel", 2, true, 3, 4);
        ChannelSummary channel = new ChannelSummary(SESSION, "Channel", 3, 5, 6);
        TunnelPage tunnelPage = new TunnelPage(List.of(tunnel), 1, false, false);
        ChannelPage channelPage = new ChannelPage(List.of(channel), 1, false, false);
        NetworkMemberSummary owner = new NetworkMemberSummary(SESSION, "Owner", NetworkMemberSummary.Role.OWNER, true);
        NetworkMemberSummary admin =
                new NetworkMemberSummary(VIEW, "Admin", NetworkMemberSummary.Role.ADMINISTRATOR, false);
        NetworkMemberPage memberPage = new NetworkMemberPage(List.of(owner, admin), 2, false, false);
        List<NetworkTerminalState> states = List.of(
                new NetworkTerminalState.NetworkRoot(network),
                new NetworkTerminalState.NetworkRoot(network, true),
                new NetworkTerminalState.TunnelList(network, tunnelPage),
                new NetworkTerminalState.TunnelEdit(network, null, "Tunnel 2"),
                new NetworkTerminalState.TunnelEdit(network, tunnel, null),
                new NetworkTerminalState.ChannelList(network, tunnel, channelPage),
                new NetworkTerminalState.TunnelSettings(network, tunnel),
                new NetworkTerminalState.Members(network, memberPage, 128, SESSION),
                new NetworkTerminalState.AdministratorCandidates(
                        network, new OnlinePlayerPage(VIEW, List.of(), 0, 0, false)),
                new NetworkTerminalState.RemoveAdministrator(network, admin),
                new NetworkTerminalState.DeleteConfirmation(
                        network,
                        new TopologyDeletionSummary(TopologyDeletionSummary.Kind.TUNNEL, VIEW, "Tunnel", 3, 4)));
        for (NetworkTerminalState state : states) {
            responses.add(new NetworkTerminalResponse.ViewState(VIEW, SESSION, 5, state));
        }
        for (NetworkTerminalResponse.Reason reason : NetworkTerminalResponse.Reason.values()) {
            responses.add(new NetworkTerminalResponse.Failure(VIEW, null, 0, reason));
            responses.add(new NetworkTerminalResponse.Failure(VIEW, SESSION, 2, reason));
            responses.add(new NetworkTerminalResponse.Failure(VIEW, SESSION, 3, reason, states.getFirst()));
        }
        for (NetworkTerminalResponse response : responses) {
            FriendlyByteBuf buffer = buffer();
            try {
                NetworkTerminalResponse.STREAM_CODEC.encode(buffer, response);
                assertTrue(buffer.readableBytes() < 262144);
                assertEquals(response, NetworkTerminalResponse.STREAM_CODEC.decode(buffer));
                assertEquals(0, buffer.readableBytes());
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void malformedWireRejectsBeforeAllocatingUnboundedCollectionsOrStrings() {
        rejectRequest(buffer -> buffer.writeByte(99));
        for (int removedTag : List.of(13, 14, 15, 17)) {
            rejectRequest(buffer -> requestHeader(buffer, removedTag, 1));
        }
        rejectRequest(buffer -> {
            createHeader(buffer, 1);
            buffer.writeVarInt(257);
        });
        rejectRequest(buffer -> {
            createHeader(buffer, 1);
            buffer.writeVarInt(Integer.MAX_VALUE);
        });
        rejectRequest(buffer -> {
            createHeader(buffer, 1);
            buffer.writeVarInt(-1);
        });
        rejectRequest(buffer -> {
            createHeader(buffer, 0);
            buffer.writeVarInt(0);
        });
        rejectRequest(buffer -> {
            createHeader(buffer, -1);
            buffer.writeVarInt(0);
        });
        rejectRequest(buffer -> {
            createHeader(buffer, 1);
            buffer.writeVarInt(2);
            buffer.writeByte(0xc3);
            buffer.writeByte(0x28);
        });
        rejectRequest(buffer -> {
            buffer.writeByte(0);
            buffer.writeUUID(VIEW);
            buffer.writeZero(262144);
        });
        rejectResponse(buffer -> buffer.writeByte(99));
        rejectResponse(buffer -> {
            successHeader(buffer);
            buffer.writeVarInt(129);
        });
        rejectResponse(buffer -> {
            successHeader(buffer);
            buffer.writeVarInt(-1);
        });
        rejectResponse(buffer -> {
            buffer.writeByte(1);
            buffer.writeUUID(VIEW);
            buffer.writeBoolean(false);
            buffer.writeLong(0);
            buffer.writeByte(99);
        });
        rejectResponse(buffer -> writeEmptyPage(buffer, -1, 0, 1));
        rejectResponse(buffer -> writeEmptyPage(buffer, 0, -1, 1));
        rejectResponse(buffer -> writeEmptyPage(buffer, 0, 0, -2));
        rejectResponse(buffer -> writeEmptyPage(buffer, 0, 0, 1025));
        rejectResponse(buffer -> {
            successHeader(buffer);
            buffer.writeVarInt(1);
            buffer.writeUUID(VIEW);
            buffer.writeUUID(SESSION);
            buffer.writeUtf("  Name ");
        });
    }

    @Test
    void pageOwnsItsListAndValidatesMetadataAndBounds() {
        NetworkSummary summary = new NetworkSummary(VIEW, SESSION, "Name");
        List<NetworkSummary> entries = new ArrayList<>(List.of(summary));
        NetworkTerminalPage page = new NetworkTerminalPage(entries, summary, 1, 1, -1, false, false);
        entries.clear();
        assertEquals(List.of(summary), page.entries());
        assertThrows(UnsupportedOperationException.class, () -> page.entries().clear());
        assertThrows(IllegalArgumentException.class, () -> new NetworkSummary(VIEW, SESSION, "  Name "));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NetworkTerminalPage(
                        java.util.Collections.nCopies(129, summary), null, 0, 129, 1, false, false));
        assertThrows(
                IllegalArgumentException.class, () -> new NetworkTerminalPage(List.of(), null, -1, 0, 1, false, false));
        assertThrows(
                IllegalArgumentException.class, () -> new NetworkTerminalPage(List.of(), null, 0, 0, -2, false, false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NetworkTerminalPage(List.of(), null, 0, 0, 1025, false, false));
    }

    private static void createHeader(FriendlyByteBuf buffer, long sequence) {
        requestHeader(buffer, 2, sequence);
    }

    private static void requestHeader(FriendlyByteBuf buffer, int tag, long sequence) {
        buffer.writeByte(tag);
        buffer.writeUUID(VIEW);
        buffer.writeUUID(SESSION);
        buffer.writeLong(sequence);
    }

    private static void successHeader(FriendlyByteBuf buffer) {
        buffer.writeByte(0);
        buffer.writeUUID(VIEW);
        buffer.writeUUID(SESSION);
        buffer.writeLong(0);
    }

    @Test
    void encoderRejectsOverlongNamesAndUnpairedSurrogates() {
        for (String invalid : List.of("a".repeat(257), "😀".repeat(65), "\uD800")) {
            FriendlyByteBuf buffer = buffer();
            try {
                assertThrows(
                        EncoderException.class,
                        () -> NetworkTerminalRequest.STREAM_CODEC.encode(
                                buffer, new NetworkTerminalRequest.Create(VIEW, SESSION, 1, invalid)));
            } finally {
                buffer.release();
            }
        }
    }

    private static void writeEmptyPage(FriendlyByteBuf buffer, int owned, int total, int quota) {
        successHeader(buffer);
        buffer.writeVarInt(0);
        buffer.writeBoolean(false);
        buffer.writeVarInt(owned);
        buffer.writeVarInt(total);
        buffer.writeVarInt(quota);
        buffer.writeBoolean(false);
        buffer.writeBoolean(false);
        buffer.writeBoolean(false);
    }

    private static void rejectRequest(Consumer<FriendlyByteBuf> write) {
        FriendlyByteBuf buffer = buffer();
        try {
            write.accept(buffer);
            assertThrows(DecoderException.class, () -> NetworkTerminalRequest.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    private static void rejectResponse(Consumer<FriendlyByteBuf> write) {
        FriendlyByteBuf buffer = buffer();
        try {
            write.accept(buffer);
            assertThrows(DecoderException.class, () -> NetworkTerminalResponse.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    private static FriendlyByteBuf buffer() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }
}
