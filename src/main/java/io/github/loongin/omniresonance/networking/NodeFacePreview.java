// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** Immutable adjacent block identity only; no inventory, block entity or private configuration data. */
public record NodeFacePreview(
        Direction direction, Status status, @Nullable ResourceLocation blockId) {
    public enum Status {
        UNLOADED,
        AIR,
        BLOCK
    }

    public NodeFacePreview {
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(status, "status");
        if ((status == Status.BLOCK) != (blockId != null))
            throw new IllegalArgumentException("Invalid preview identity");
        if (blockId != null) NodeMenuCodecSupport.validateDimension(blockId);
    }

    static List<NodeFacePreview> validate(List<NodeFacePreview> previews) {
        if (previews.size() > 6) throw new IllegalArgumentException("Too many face previews");
        int seen = 0;
        for (NodeFacePreview preview : previews) {
            int bit = 1 << preview.direction().get3DDataValue();
            if ((seen & bit) != 0) throw new IllegalArgumentException("Duplicate face preview");
            seen |= bit;
        }
        return List.copyOf(previews);
    }

    static List<NodeFacePreview> readList(FriendlyByteBuf buffer) {
        int count = buffer.readUnsignedByte();
        if (count > 6) throw new DecoderException("Too many face previews");
        List<NodeFacePreview> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            Direction direction = NodeMenuCodecSupport.readDirection(buffer);
            Status status =
                    switch (buffer.readUnsignedByte()) {
                        case 0 -> Status.UNLOADED;
                        case 1 -> Status.AIR;
                        case 2 -> Status.BLOCK;
                        default -> throw new DecoderException("Invalid face preview status");
                    };
            result.add(new NodeFacePreview(
                    direction, status, status == Status.BLOCK ? NodeMenuCodecSupport.readDimension(buffer) : null));
        }
        return validate(result);
    }

    static void writeList(FriendlyByteBuf buffer, List<NodeFacePreview> previews) {
        validate(previews);
        buffer.writeByte(previews.size());
        for (NodeFacePreview preview : previews) {
            NodeMenuCodecSupport.writeDirection(buffer, preview.direction());
            buffer.writeByte(
                    switch (preview.status()) {
                        case UNLOADED -> 0;
                        case AIR -> 1;
                        case BLOCK -> 2;
                    });
            if (preview.blockId() != null) NodeMenuCodecSupport.writeDimension(buffer, preview.blockId());
        }
    }
}
