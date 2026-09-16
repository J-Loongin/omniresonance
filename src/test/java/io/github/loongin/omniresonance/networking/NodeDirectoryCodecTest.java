// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.netty.buffer.Unpooled;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class NodeDirectoryCodecTest {
    @Test
    void emptyCatalogRoundTripsAndBoundsAreEnforced() {
        var p = new NodeDirectoryPage(
                new UUID(1, 1),
                1,
                1,
                true,
                false,
                false,
                java.util.List.of(),
                false,
                false,
                null,
                java.util.List.of(),
                0,
                0,
                java.util.List.of(),
                java.util.List.of(),
                null,
                new NodeDirectoryPage.Catalog(1, 0, 0));
        var b = new FriendlyByteBuf(Unpooled.buffer());
        try {
            NodeDirectoryPage.STREAM_CODEC.encode(b, p);
            assertEquals(p, NodeDirectoryPage.STREAM_CODEC.decode(b));
        } finally {
            b.release();
        }
        assertThrows(IllegalArgumentException.class, () -> new NodeDirectoryPage.Catalog(1, 0, 262145));
        assertThrows(IllegalArgumentException.class, () -> new NodeDirectoryPage.Catalog(0, 0, 0));
    }

    @Test
    void selectedNodeCarriesAuthoritativeLoadingStateAndQuotas() {
        var node = new NodeMenuNodeSummary(
                new UUID(1, 1),
                "Network",
                new UUID(2, 2),
                "Node",
                0,
                net.minecraft.resources.ResourceLocation.parse("minecraft:overworld"),
                net.minecraft.core.BlockPos.ZERO,
                io.github.loongin.omniresonance.node.NodeForm.BLOCK,
                net.minecraft.core.Direction.NORTH,
                true,
                true,
                io.github.loongin.omniresonance.node.NodeMode.DIRECT);
        var row = new NodeDirectoryPage.Row(node, 1, NodeDirectoryRequest.Status.ONLINE, 4, 1);
        var info = new NodeChunkLoadingInfo(
                io.github.loongin.omniresonance.chunkloading.ChunkLoadingAllocator.Status.OWNER_LIMIT, 25, 25, 40, 500);
        var page = new NodeDirectoryPage(
                new UUID(3, 3),
                1,
                1,
                true,
                false,
                false,
                java.util.List.of(row),
                false,
                false,
                row,
                java.util.List.of(),
                0,
                0,
                java.util.List.of(),
                java.util.List.of(),
                info);
        var b = new FriendlyByteBuf(Unpooled.buffer());
        try {
            NodeDirectoryPage.STREAM_CODEC.encode(b, page);
            assertEquals(page, NodeDirectoryPage.STREAM_CODEC.decode(b));
            b.clear();
            b.writeByte(255).writeInt(0).writeInt(25).writeInt(0).writeInt(500);
            assertThrows(IllegalArgumentException.class, () -> NodeChunkLoadingInfo.read(b));
        } finally {
            b.release();
        }
    }

    @Test
    void intentsRoundTripAndRejectTrailingOrUnknownActions() {
        for (var action : NodeDirectoryRequest.Action.values()) {
            var request = new NodeDirectoryRequest(
                    new UUID(1, 1),
                    new UUID(2, 2),
                    1,
                    2,
                    action,
                    "iron",
                    NodeDirectoryRequest.Status.OFFLINE,
                    null,
                    null,
                    12,
                    false,
                    new UUID(3, 3),
                    0,
                    0,
                    "Node",
                    true,
                    null);
            var b = new FriendlyByteBuf(Unpooled.buffer());
            try {
                NodeDirectoryRequest.STREAM_CODEC.encode(b, request);
                assertEquals(request, NodeDirectoryRequest.STREAM_CODEC.decode(b));
                b.clear();
                NodeDirectoryRequest.STREAM_CODEC.encode(b, request);
                b.writeByte(0);
                assertThrows(IllegalArgumentException.class, () -> NodeDirectoryRequest.STREAM_CODEC.decode(b));
                b.clear();
                NodeDirectoryRequest.STREAM_CODEC.encode(b, request);
                b.setByte(48, 255);
                assertThrows(IllegalArgumentException.class, () -> NodeDirectoryRequest.STREAM_CODEC.decode(b));
            } finally {
                b.release();
            }
        }
    }

    @Test
    void directoryWindowRejectsOverflowAndRoundTripsEditState() {
        var page = new NodeDirectoryPage(
                new UUID(2, 2),
                1,
                2,
                true,
                false,
                true,
                java.util.List.of(),
                false,
                false,
                null,
                java.util.List.of(),
                0,
                0,
                java.util.List.of(),
                java.util.List.of());
        var b = new FriendlyByteBuf(Unpooled.buffer());
        try {
            NodeDirectoryPage.STREAM_CODEC.encode(b, page);
            assertEquals(page, NodeDirectoryPage.STREAM_CODEC.decode(b));
            b.clear();
            NodeDirectoryPage.STREAM_CODEC.encode(b, page);
            b.setInt(35, 65);
            assertThrows(IllegalArgumentException.class, () -> NodeDirectoryPage.STREAM_CODEC.decode(b));
        } finally {
            b.release();
        }
    }
}
