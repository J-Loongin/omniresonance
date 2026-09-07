// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class NetworkSettingsCodecTest {
    private static final UUID VIEW = new UUID(860, 1);
    private static final UUID SESSION = new UUID(860, 2);
    private static final UUID NETWORK = new UUID(860, 3);
    private static final UUID OWNER = new UUID(860, 4);

    @Test
    void everySettingsIntentStateAndDeletionNoticeRoundTrips() {
        NetworkSummary network = new NetworkSummary(NETWORK, OWNER, "Network");
        NetworkSettingsSummary owner = new NetworkSettingsSummary(network, "Owner", 7, 8, true, false);
        NetworkDeletionSummary deletion = new NetworkDeletionSummary(NETWORK, "Network", 3, 4, 9);
        List<NetworkTerminalRequest> requests = List.of(
                new NetworkTerminalRequest.OpenNetworkSettings(VIEW, SESSION, 1),
                new NetworkTerminalRequest.BeginRenameNetwork(VIEW, SESSION, 2),
                new NetworkTerminalRequest.RenameNetwork(VIEW, SESSION, 3, "Renamed"),
                new NetworkTerminalRequest.SetDefaultNetwork(VIEW, SESSION, 4),
                new NetworkTerminalRequest.RequestDeleteNetwork(VIEW, SESSION, 5),
                new NetworkTerminalRequest.ConfirmDeleteNetwork(VIEW, SESSION, 6));
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

        List<NetworkTerminalState> states = List.of(
                new NetworkTerminalState.NetworkSettings(owner),
                new NetworkTerminalState.NetworkRename(owner),
                new NetworkTerminalState.NetworkDelete(owner, deletion));
        for (NetworkTerminalState state : states) {
            roundTrip(new NetworkTerminalResponse.ViewState(VIEW, SESSION, 7, state));
        }
        roundTrip(new NetworkTerminalResponse.NetworkDeleted(VIEW, SESSION, 8, NETWORK));
    }

    @Test
    void settingsWireValuesAreBoundedAndOwnerOnlyStateCannotLeakToAdministrators() {
        NetworkSummary network = new NetworkSummary(NETWORK, OWNER, "Network");
        assertThrows(
                IllegalArgumentException.class, () -> new NetworkSettingsSummary(network, "Owner", -1, 0, true, false));
        assertThrows(
                IllegalArgumentException.class, () -> new NetworkSettingsSummary(network, "Owner", 0, -1, true, false));
        assertThrows(
                IllegalArgumentException.class, () -> new NetworkSettingsSummary(network, "Owner", 0, 0, false, true));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NetworkSettingsSummary(network, "Bad§Name", 0, 0, true, false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NetworkTerminalState.NetworkDelete(
                        new NetworkSettingsSummary(network, "Owner", 0, 0, true, false),
                        new NetworkDeletionSummary(NETWORK, "Other", 0, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> new NetworkDeletionSummary(NETWORK, "Network", -1, 0, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NetworkTerminalState.NetworkDelete(
                        new NetworkSettingsSummary(network, "Owner", 0, 0, true, false),
                        new NetworkDeletionSummary(new UUID(860, 9), "Network", 0, 0, 0)));

        FriendlyByteBuf buffer = buffer();
        try {
            NetworkTerminalResponse.STREAM_CODEC.encode(
                    buffer,
                    new NetworkTerminalResponse.ViewState(
                            VIEW,
                            SESSION,
                            1,
                            new NetworkTerminalState.NetworkSettings(
                                    new NetworkSettingsSummary(network, "Owner", 0, 0, false, false))));
            assertTrue(buffer.readableBytes() < 262144);
        } finally {
            buffer.release();
        }
    }

    private static void roundTrip(NetworkTerminalResponse response) {
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

    private static FriendlyByteBuf buffer() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }
}
