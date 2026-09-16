// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.loongin.omniresonance.chunkloading.ChunkLoadingAllocator;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class ChunkOverviewCodecTest {
    @Test
    void boundedRequestsRoundTripAndRejectTrailingOrUnknownActions() {
        for (var action : ChunkOverviewRequest.Action.values()) {
            var request = new ChunkOverviewRequest(
                    new UUID(1, 1),
                    new UUID(2, 2),
                    1,
                    2,
                    action,
                    12,
                    false,
                    action == ChunkOverviewRequest.Action.HIGHLIGHT || action == ChunkOverviewRequest.Action.TELEPORT
                            ? new UUID(3, 3)
                            : null);
            var b = new FriendlyByteBuf(Unpooled.buffer());
            try {
                ChunkOverviewRequest.STREAM_CODEC.encode(b, request);
                assertEquals(request, ChunkOverviewRequest.STREAM_CODEC.decode(b));
                b.clear();
                ChunkOverviewRequest.STREAM_CODEC.encode(b, request);
                b.writeByte(0);
                assertThrows(IllegalArgumentException.class, () -> ChunkOverviewRequest.STREAM_CODEC.decode(b));
                b.clear();
                ChunkOverviewRequest.STREAM_CODEC.encode(b, request);
                b.setByte(48, 255);
                assertThrows(IllegalArgumentException.class, () -> ChunkOverviewRequest.STREAM_CODEC.decode(b));
            } finally {
                b.release();
            }
        }
    }

    @Test
    void obsoleteRemoteTogglePayloadIsRejected() {
        var b = new FriendlyByteBuf(Unpooled.buffer());
        try {
            b.writeUUID(new UUID(1, 1))
                    .writeUUID(new UUID(2, 2))
                    .writeLong(1)
                    .writeLong(1)
                    .writeByte(2)
                    .writeLong(0)
                    .writeBoolean(false)
                    .writeBoolean(true)
                    .writeUUID(new UUID(3, 3))
                    .writeLong(0)
                    .writeBoolean(true);
            assertThrows(IllegalArgumentException.class, () -> ChunkOverviewRequest.STREAM_CODEC.decode(b));
        } finally {
            b.release();
        }
    }

    @Test
    void pagesRoundTripAndRejectOversizedWindows() {
        var entry = new ChunkOverviewPage.Entry(
                new UUID(3, 3),
                1,
                0,
                "Node",
                ResourceLocation.parse("minecraft:overworld"),
                BlockPos.ZERO,
                io.github.loongin.omniresonance.node.NodeMode.DIRECT,
                true,
                false,
                ChunkLoadingAllocator.Status.OFF);
        var page = new ChunkOverviewPage(
                new UUID(2, 2), 1, 2, true, false, true, 0, 25, 0, 500, 1, false, false, List.of(entry));
        var b = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ChunkOverviewPage.STREAM_CODEC.encode(b, page);
            assertEquals(page, ChunkOverviewPage.STREAM_CODEC.decode(b));
            b.clear();
            ChunkOverviewPage.STREAM_CODEC.encode(b, page);
            b.writeByte(0);
            assertThrows(IllegalArgumentException.class, () -> ChunkOverviewPage.STREAM_CODEC.decode(b));
            b.clear();
            ChunkOverviewPage.STREAM_CODEC.encode(b, page);
            b.setInt(57, 65);
            assertThrows(IllegalArgumentException.class, () -> ChunkOverviewPage.STREAM_CODEC.decode(b));
        } finally {
            b.release();
        }
    }
}
