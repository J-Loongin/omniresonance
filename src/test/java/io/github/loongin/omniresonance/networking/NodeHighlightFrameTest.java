// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.netty.buffer.Unpooled;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class NodeHighlightFrameTest {
    @Test
    void boundedFrameAndClearRoundTrip() {
        for (var frame : java.util.List.of(
                new NodeHighlightFrame(null, null, "", 0),
                new NodeHighlightFrame(
                        new UUID(1, 1),
                        GlobalPos.of(Level.OVERWORLD, new BlockPos(-30000000, 80, 30000000)),
                        "Node",
                        200))) {
            var b = new FriendlyByteBuf(Unpooled.buffer());
            try {
                NodeHighlightFrame.STREAM_CODEC.encode(b, frame);
                assertEquals(frame, NodeHighlightFrame.STREAM_CODEC.decode(b));
                b.clear();
                NodeHighlightFrame.STREAM_CODEC.encode(b, frame);
                b.writeByte(0);
                assertThrows(IllegalArgumentException.class, () -> NodeHighlightFrame.STREAM_CODEC.decode(b));
            } finally {
                b.release();
            }
        }
        assertThrows(IllegalArgumentException.class, () -> new NodeHighlightFrame(new UUID(1, 1), null, "", 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NodeHighlightFrame(
                        new UUID(1, 1), GlobalPos.of(Level.OVERWORLD, BlockPos.ZERO), "Node", 72001));
    }
}
