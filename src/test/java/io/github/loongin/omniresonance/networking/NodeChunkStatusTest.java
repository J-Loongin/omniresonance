// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.loongin.omniresonance.chunkloading.ChunkLoadingAllocator;
import io.netty.buffer.Unpooled;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class NodeChunkStatusTest {
    @Test
    void fixedStatusRoundTripsAndRejectsUnknownStatesOrTrailingBytes() {
        var value = new NodeChunkStatus(1, new UUID(1, 1), new UUID(2, 2), 3, ChunkLoadingAllocator.Status.OWNER_LIMIT);
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            NodeChunkStatus.STREAM_CODEC.encode(buffer, value);
            assertEquals(45, buffer.readableBytes());
            assertEquals(value, NodeChunkStatus.STREAM_CODEC.decode(buffer));
            buffer.clear();
            NodeChunkStatus.STREAM_CODEC.encode(buffer, value);
            buffer.setByte(44, 255);
            assertThrows(IllegalArgumentException.class, () -> NodeChunkStatus.STREAM_CODEC.decode(buffer));
            buffer.clear();
            NodeChunkStatus.STREAM_CODEC.encode(buffer, value);
            buffer.writeByte(0);
            assertThrows(IllegalArgumentException.class, () -> NodeChunkStatus.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }
}
