// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.loongin.omniresonance.network.WorkingFaces;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

final class NodeWorkingFacesCodecTest {
    @Test
    void roundTripsBoundedSelectionAndDistinctPreviewStates() {
        for (WorkingFaces faces :
                List.of(WorkingFaces.explicit(0), WorkingFaces.explicit(63), WorkingFaces.attachedFace())) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                NodeWorkingFacesCodec.write(buffer, faces);
                assertEquals(faces, NodeWorkingFacesCodec.read(buffer));
            } finally {
                buffer.release();
            }
        }
        List<NodeFacePreview> previews = List.of(
                new NodeFacePreview(
                        Direction.NORTH, NodeFacePreview.Status.BLOCK, ResourceLocation.withDefaultNamespace("chest")),
                new NodeFacePreview(Direction.UP, NodeFacePreview.Status.AIR, null),
                new NodeFacePreview(Direction.DOWN, NodeFacePreview.Status.UNLOADED, null));
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            NodeFacePreview.writeList(buffer, previews);
            assertEquals(previews, NodeFacePreview.readList(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsOversizedBlockIdsAndMissingBlockIdentity() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new NodeFacePreview(Direction.UP, NodeFacePreview.Status.BLOCK, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NodeFacePreview(
                        Direction.UP,
                        NodeFacePreview.Status.BLOCK,
                        ResourceLocation.withDefaultNamespace("a".repeat(300))));
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeByte(1);
            buffer.writeByte(1);
            buffer.writeByte(2);
            buffer.writeUtf("minecraft:" + "a".repeat(300));
            assertThrows(DecoderException.class, () -> NodeFacePreview.readList(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsOversizedAndDuplicatePreviewsAndInvalidSelections() {
        NodeFacePreview preview = new NodeFacePreview(Direction.UP, NodeFacePreview.Status.AIR, null);
        assertThrows(IllegalArgumentException.class, () -> NodeFacePreview.validate(List.of(preview, preview)));
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeByte(7);
            assertThrows(DecoderException.class, () -> NodeFacePreview.readList(buffer));
            buffer.clear();
            buffer.writeByte(64);
            buffer.writeBoolean(false);
            assertThrows(IllegalArgumentException.class, () -> NodeWorkingFacesCodec.read(buffer));
            buffer.clear();
            buffer.writeByte(1);
            buffer.writeByte(6);
            buffer.writeByte(0);
            assertThrows(DecoderException.class, () -> NodeFacePreview.readList(buffer));
        } finally {
            buffer.release();
        }
    }
}
