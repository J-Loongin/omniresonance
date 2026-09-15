// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.network.TransferDirection;
import io.github.loongin.omniresonance.node.NodeForm;
import io.github.loongin.omniresonance.node.NodeMode;
import io.netty.buffer.Unpooled;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

final class DomainEditorCodecTest {
    @Test
    void domainUsesARealResourceEditorWithoutSyntheticChannelMetadata() {
        var node = new NodeMenuNodeSummary(
                new UUID(1, 1),
                "Network",
                new UUID(1, 2),
                "Node",
                0,
                ResourceLocation.parse("minecraft:overworld"),
                BlockPos.ZERO,
                NodeForm.BLOCK,
                Direction.DOWN,
                true,
                false,
                NodeMode.DOMAIN);
        NodeMenuState.ResourceEdit edit = new NodeMenuState.DomainEdit(node, TransferDirection.INPUT);
        assertTrue(edit.policy() != null);
        var response = new NodeMenuResponse.State(1, new UUID(2, 1), 1, edit);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            NodeMenuResponse.STREAM_CODEC.encode(buffer, response);
            assertEquals(response, NodeMenuResponse.STREAM_CODEC.decode(buffer));
            assertEquals(0, buffer.readableBytes());
            var download =
                    new NodeMenuResponse.Download(1, new UUID(2, 1), 2, edit.withPolicy(null), new UUID(2, 2), 100);
            NodeMenuResponse.STREAM_CODEC.encode(buffer, download);
            assertEquals(download, NodeMenuResponse.STREAM_CODEC.decode(buffer));
            assertTrue(NodePolicyFrames.responseSize(response) < NodePolicyFrames.MAXIMUM_BYTES);
        } finally {
            buffer.release();
        }
    }
}
