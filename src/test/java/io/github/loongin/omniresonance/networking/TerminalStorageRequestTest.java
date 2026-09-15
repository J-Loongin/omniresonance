// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.netty.buffer.Unpooled;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class TerminalStorageRequestTest {
    @Test
    void bulkRequiresAnInventoryShiftLeftClickAndSurvivesTheWire() {
        var id = new UUID(1, 1);
        var request = new TerminalStorageRequest(id, id, 1, 1, 0, 0, 9, 0, true, true);
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            TerminalStorageRequest.STREAM_CODEC.encode(buffer, request);
            assertEquals(request, TerminalStorageRequest.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> new TerminalStorageRequest(id, id, 1, 1, 1, 0, -1, 0, true, true));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TerminalStorageRequest(id, id, 1, 1, 0, 0, 9, 0, false, true));
    }

    @Test
    void outcomesPreserveLargeQuantitiesAndRejectUnknownStatusOrTrailingBytes() {
        var value = new TerminalStorageResponse(
                new UUID(1, 1), 2, 3, TerminalStorageResponse.Status.COMPLETE, Long.MAX_VALUE, true, 32767);
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            TerminalStorageResponse.STREAM_CODEC.encode(buffer, value);
            assertEquals(46, buffer.readableBytes());
            assertEquals(value, TerminalStorageResponse.STREAM_CODEC.decode(buffer));
            buffer.clear();
            TerminalStorageResponse.STREAM_CODEC.encode(buffer, value);
            buffer.setByte(32, 255);
            assertThrows(IllegalArgumentException.class, () -> TerminalStorageResponse.STREAM_CODEC.decode(buffer));
            buffer.clear();
            TerminalStorageResponse.STREAM_CODEC.encode(buffer, value);
            buffer.writeByte(0);
            assertThrows(IllegalArgumentException.class, () -> TerminalStorageResponse.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> new TerminalStorageResponse(
                        value.session(), 2, 0, TerminalStorageResponse.Status.COMPLETE, 0, true, 0));
    }

    @Test
    void fixedIntentRoundTripsWithoutClientQuantitiesOrStacks() {
        var request = new TerminalStorageRequest(new UUID(1, 1), new UUID(2, 2), 1, 2, 3, 4, -1, 1, true);
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            TerminalStorageRequest.STREAM_CODEC.encode(buffer, request);
            assertEquals(64, buffer.readableBytes());
            assertEquals(request, TerminalStorageRequest.STREAM_CODEC.decode(buffer));
            TerminalStorageRequest.STREAM_CODEC.encode(buffer, request);
            buffer.writeByte(0);
            assertThrows(IllegalArgumentException.class, () -> TerminalStorageRequest.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> new TerminalStorageRequest(request.view(), request.session(), 1, 1, 0, 0, 36, 0, false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TerminalStorageRequest(request.view(), request.session(), 1, 0, 0, 0, -1, 0, false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TerminalStorageRequest(request.view(), request.session(), 1, 1, 0, 0, -1, 2, false));
    }
}
