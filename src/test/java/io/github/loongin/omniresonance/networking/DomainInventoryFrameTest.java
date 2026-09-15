// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.storage.DomainLedger;
import io.github.loongin.omniresonance.transfer.ResourceVariantKey;
import io.netty.buffer.Unpooled;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class DomainInventoryFrameTest {
    @Test
    void largestLegalKeyFitsOneBoundedRecordAndSplitsWithoutLosingItsTail() {
        var key = new ResourceVariantKey(ResourceLocation.parse("example:opaque"), new byte[262144]);
        var record = new DomainLedger.Change(1, key, Long.MAX_VALUE, 5);
        byte[] encoded = DomainInventoryRecordCodec.encode(record);
        assertEquals(record, DomainInventoryRecordCodec.decode(encoded));
        assertTrue(encoded.length > DomainInventoryFrame.MAXIMUM_FRAGMENT_BYTES);
        var message = new DomainInventoryFrame.Data(
                new UUID(1, 2),
                1,
                1,
                true,
                encoded.length,
                0,
                java.util.Arrays.copyOf(encoded, DomainInventoryFrame.MAXIMUM_FRAGMENT_BYTES));
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            DomainInventoryFrame.STREAM_CODEC.encode(buffer, message);
            assertEquals(message.wireSize(), buffer.readableBytes() + DomainInventoryFrame.ENVELOPE_BYTES);
            var decoded = (DomainInventoryFrame.Data) DomainInventoryFrame.STREAM_CODEC.decode(buffer);
            assertEquals(message.totalLength(), decoded.totalLength());
            org.junit.jupiter.api.Assertions.assertArrayEquals(message.data(), decoded.data());
        } finally {
            buffer.release();
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> DomainInventoryRecordCodec.decode(java.util.Arrays.copyOf(encoded, encoded.length - 1)));
    }
}
