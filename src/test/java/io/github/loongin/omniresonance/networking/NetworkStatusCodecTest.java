// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.netty.buffer.Unpooled;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class NetworkStatusCodecTest {
    @Test
    void incidentMetadataRoundTripsWithBoundedNamesAndNoExceptionText() {
        var position = net.minecraft.core.GlobalPos.of(
                net.minecraft.world.level.Level.OVERWORLD, new net.minecraft.core.BlockPos(1, 70, -3));
        var incident = new io.github.loongin.omniresonance.transfer.TransferIncident(
                new io.github.loongin.omniresonance.transfer.TransferIncident.Endpoint(
                        new UUID(1, 2), "Node name", position),
                null,
                new UUID(2, 3),
                "Channel name",
                io.github.loongin.omniresonance.transfer.ResourceTypes.ITEM,
                io.github.loongin.omniresonance.transfer.TransferIncident.Reason.SLOW_CALL,
                io.github.loongin.omniresonance.transfer.ResourceTransferEngine.Stage.NONE);
        var metrics = new io.github.loongin.omniresonance.transfer.TransferTelemetry.Snapshot(
                10, 0, 0, java.util.List.of(), 0, "direct_transfer", 9, incident);
        var snapshot = new io.github.loongin.omniresonance.network.NetworkDiagnosticsSnapshot(
                        new UUID(1, 1), new UUID(2, 2), 11, 1, 2, 3, 1, "not_loaded", -1, 0, 0, 0, 0, 0, -1, 500)
                .withRuntime(new io.github.loongin.omniresonance.network.NetworkDiagnosticsSnapshot.RuntimeStats(
                        metrics,
                        0,
                        0,
                        0,
                        new io.github.loongin.omniresonance.exchange.ExchangeTelemetry.Snapshot(
                                true,
                                true,
                                2,
                                4,
                                false,
                                new io.github.loongin.omniresonance.exchange.ExchangeTelemetry.Incident(
                                        new UUID(10, 1),
                                        new UUID(11, 1),
                                        new UUID(11, 2),
                                        io.github.loongin.omniresonance.transfer.ResourceTypes.FLUID,
                                        9,
                                        io.github.loongin.omniresonance.exchange.ExchangeTelemetry.Stage.TRANSFER,
                                        io.github.loongin.omniresonance.exchange.ExchangeTelemetry.Reason
                                                .UNCERTAIN_TRANSFER,
                                        "Water",
                                        "Source",
                                        "Target"))));
        var frame = new NetworkStatusFrame(new UUID(3, 3), 1, 1, snapshot, "test");
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            NetworkStatusFrame.STREAM_CODEC.encode(buffer, frame);
            assertEquals(frame, NetworkStatusFrame.STREAM_CODEC.decode(buffer));
            var exported = com.google.gson.JsonParser.parseString(snapshot.export("test"))
                    .getAsJsonObject()
                    .getAsJsonObject("incident");
            assertEquals("slow_call", exported.get("reason").getAsString());
            assertEquals(
                    "Node name", exported.getAsJsonObject("node").get("name").getAsString());
        } finally {
            buffer.release();
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> new io.github.loongin.omniresonance.transfer.TransferIncident.Endpoint(
                        new UUID(1, 2), "x".repeat(257), position));
    }

    @Test
    void runtimeFramesRoundTripAndMalformedObservationsAreRejected() {
        var metrics = new io.github.loongin.omniresonance.transfer.TransferTelemetry.Snapshot(
                10,
                4,
                100,
                java.util.List.of(new io.github.loongin.omniresonance.transfer.TransferTelemetry.Movement(
                        io.github.loongin.omniresonance.transfer.ResourceTypes.FLUID, 1000, false)),
                0,
                "domain_input",
                9);
        var snapshot = new io.github.loongin.omniresonance.network.NetworkDiagnosticsSnapshot(
                        new UUID(1, 1), new UUID(2, 2), 11, 1, 2, 3, 1, "not_loaded", -1, 0, 0, 0, 0, 0, -1, 500)
                .withRuntime(new io.github.loongin.omniresonance.network.NetworkDiagnosticsSnapshot.RuntimeStats(
                        metrics, 1, 2, 3));
        var frame = new NetworkStatusFrame(new UUID(3, 3), 1, 1, snapshot, "test");
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            NetworkStatusFrame.STREAM_CODEC.encode(buffer, frame);
            assertEquals(frame, NetworkStatusFrame.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> new io.github.loongin.omniresonance.transfer.TransferTelemetry.Snapshot(
                        1, -1, 0, java.util.List.of(), 0, "", -1));
    }

    @Test
    void unavailableFramesRoundTripAndRejectTrailingBytes() {
        var frame = new NetworkStatusFrame(new UUID(1, 1), 1, 1, null, "test");
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            NetworkStatusFrame.STREAM_CODEC.encode(buffer, frame);
            assertEquals(frame, NetworkStatusFrame.STREAM_CODEC.decode(buffer));
            buffer.clear();
            NetworkStatusFrame.STREAM_CODEC.encode(buffer, frame);
            buffer.writeByte(0);
            assertThrows(IllegalArgumentException.class, () -> NetworkStatusFrame.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }
}
