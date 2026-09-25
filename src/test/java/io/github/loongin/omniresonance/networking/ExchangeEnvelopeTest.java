// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.netty.buffer.Unpooled;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class ExchangeEnvelopeTest {
    private static final UUID ID = new UUID(1, 2);

    @Test
    void rejectsUnexpectedBodiesOffsetsAndEmptyUploadFragments() {
        for (int kind : new int[] {
            ExchangeRequest.OPEN,
            ExchangeRequest.CLOSE,
            ExchangeRequest.PRESETS,
            ExchangeRequest.CODES,
            ExchangeRequest.COMMIT
        }) {
            assertThrows(
                    IllegalArgumentException.class, () -> new ExchangeRequest(ID, ID, ID, 1, kind, 0, new byte[] {1}));
            assertThrows(
                    IllegalArgumentException.class, () -> new ExchangeRequest(ID, ID, ID, 1, kind, 1, new byte[0]));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExchangeRequest(ID, ID, ID, 1, ExchangeRequest.UPLOAD, 0, new byte[0]));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExchangeRequest(ID, ID, ID, 1, ExchangeRequest.CHUNK, 0, new byte[0]));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExchangeRequest(ID, ID, ID, 1, ExchangeRequest.FILTER, 0, new byte[15]));
    }

    @Test
    void maximumFragmentRoundTripAndCredentialsAreNotPrinted() {
        byte[] bytes = new byte[ManagementTransferPool.MAXIMUM_FRAGMENT_BYTES];
        bytes[17] = 22;
        var request = new ExchangeRequest(ID, ID, ID, 3, ExchangeRequest.CHUNK, 123, bytes);
        var b = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ExchangeRequest.STREAM_CODEC.encode(b, request);
            var restored = ExchangeRequest.STREAM_CODEC.decode(b);
            assertArrayEquals(bytes, restored.body());
            assertEquals(123, restored.offset());
            bytes[17] = 0;
            assertEquals(22, request.body()[17]);
            assertFalse(request.toString().contains("[B@"));
        } finally {
            b.release();
        }
    }

    @Test
    void boundedFactoryReservesUpperBoundThenReleasesActualBytes() {
        var pool = new ManagementTransferPool();
        int size = pool.beginBoundedDownload(ID, ID, ID, 1024, 0, () -> {
            assertEquals(1024, pool.reservedBytes());
            return new byte[] {3, 4};
        });
        assertEquals(2, size);
        assertEquals(2, pool.reservedBytes());
        assertArrayEquals(new byte[] {3, 4}, pool.nextDownload(ID, ID, ID, 0));
        assertEquals(0, pool.reservedBytes());
        assertThrows(
                IllegalArgumentException.class, () -> pool.beginBoundedDownload(ID, ID, ID, 1, 0, () -> new byte[2]));
        assertEquals(0, pool.reservedBytes());
        assertThrows(
                IllegalStateException.class,
                () -> pool.beginBoundedDownload(ID, ID, ID, 1, 0, () -> {
                    throw new IllegalStateException();
                }));
        assertEquals(0, pool.reservedBytes());
    }
}
