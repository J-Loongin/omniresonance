// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.github.loongin.omniresonance.network.TransferDirection;
import io.github.loongin.omniresonance.node.NodeForm;
import io.github.loongin.omniresonance.node.NodeMode;
import io.netty.handler.codec.DecoderException;
import java.nio.charset.StandardCharsets;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/** Package-owned stable mappings and hard bounds shared by node-menu codecs; no authority is accessed. */
final class NodeMenuCodecSupport {
    static final int MAXIMUM_PAGE_ENTRIES = 128;
    private static final int MAXIMUM_DIMENSION_BYTES = 256;

    private NodeMenuCodecSupport() {}

    static void requirePayloadBound(FriendlyByteBuf buffer) {
        NetworkSummary.requirePayloadBound(buffer);
    }

    static void requireEncodedBound(FriendlyByteBuf buffer, int start) {
        NetworkSummary.requireEncodedBound(buffer, start);
    }

    static void requirePayloadBound(FriendlyByteBuf buffer, ResourceLocation id) {
        int length = id.toString().getBytes(StandardCharsets.UTF_8).length;
        if (buffer.readableBytes() + net.minecraft.network.VarInt.getByteSize(length) + length > 262144)
            throw new DecoderException("Oversized registered node frame");
    }

    static void requireEncodedBound(FriendlyByteBuf buffer, int start, ResourceLocation id) {
        int length = id.toString().getBytes(StandardCharsets.UTF_8).length;
        if (buffer.writerIndex() - start + net.minecraft.network.VarInt.getByteSize(length) + length > 262144)
            throw new io.netty.handler.codec.EncoderException("Oversized registered node frame");
    }

    static ResourceLocation readDimension(FriendlyByteBuf buffer) {
        String encoded = NetworkSummary.readName(buffer);
        ResourceLocation dimension = ResourceLocation.tryParse(encoded);
        if (dimension == null || !dimension.toString().equals(encoded)) {
            throw new DecoderException("Invalid node-menu dimension");
        }
        return dimension;
    }

    static void writeDimension(FriendlyByteBuf buffer, ResourceLocation dimension) {
        validateDimension(dimension);
        NetworkSummary.writeName(buffer, dimension.toString());
    }

    static void validateDimension(ResourceLocation dimension) {
        if (dimension.toString().getBytes(StandardCharsets.UTF_8).length > MAXIMUM_DIMENSION_BYTES) {
            throw new IllegalArgumentException("Node-menu dimension exceeds byte limit");
        }
    }

    static NodeForm readForm(FriendlyByteBuf buffer) {
        return switch (buffer.readUnsignedByte()) {
            case 0 -> NodeForm.BLOCK;
            case 1 -> NodeForm.PANEL;
            default -> throw new DecoderException("Unknown node-menu form");
        };
    }

    static void writeForm(FriendlyByteBuf buffer, NodeForm form) {
        buffer.writeByte(
                switch (form) {
                    case BLOCK -> 0;
                    case PANEL -> 1;
                });
    }

    static NodeMode readMode(FriendlyByteBuf buffer) {
        return switch (buffer.readUnsignedByte()) {
            case 0 -> NodeMode.UNCONFIGURED;
            case 1 -> NodeMode.DIRECT;
            case 2 -> NodeMode.DOMAIN;
            default -> throw new DecoderException("Unknown node-menu mode");
        };
    }

    static void writeMode(FriendlyByteBuf buffer, NodeMode mode) {
        buffer.writeByte(
                switch (mode) {
                    case UNCONFIGURED -> 0;
                    case DIRECT -> 1;
                    case DOMAIN -> 2;
                });
    }

    static TransferDirection readTransferDirection(FriendlyByteBuf buffer) {
        return switch (buffer.readUnsignedByte()) {
            case 0 -> TransferDirection.INPUT;
            case 1 -> TransferDirection.OUTPUT;
            default -> throw new DecoderException("Unknown node-menu transfer direction");
        };
    }

    static void writeTransferDirection(FriendlyByteBuf buffer, TransferDirection direction) {
        buffer.writeByte(
                switch (direction) {
                    case INPUT -> 0;
                    case OUTPUT -> 1;
                });
    }

    static Direction readDirection(FriendlyByteBuf buffer) {
        return switch (buffer.readUnsignedByte()) {
            case 0 -> Direction.DOWN;
            case 1 -> Direction.UP;
            case 2 -> Direction.NORTH;
            case 3 -> Direction.SOUTH;
            case 4 -> Direction.WEST;
            case 5 -> Direction.EAST;
            default -> throw new DecoderException("Unknown node-menu direction");
        };
    }

    static void writeDirection(FriendlyByteBuf buffer, Direction direction) {
        buffer.writeByte(
                switch (direction) {
                    case DOWN -> 0;
                    case UP -> 1;
                    case NORTH -> 2;
                    case SOUTH -> 3;
                    case WEST -> 4;
                    case EAST -> 5;
                });
    }
}
