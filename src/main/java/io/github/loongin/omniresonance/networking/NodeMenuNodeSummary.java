// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.node.NodeForm;
import io.github.loongin.omniresonance.node.NodeMode;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/** Immutable authorized node/network display snapshot without administrator lists or world references. */
public record NodeMenuNodeSummary(
        UUID networkId,
        String networkName,
        UUID nodeId,
        String nodeName,
        long revision,
        ResourceLocation dimension,
        BlockPos position,
        NodeForm form,
        Direction facing,
        boolean enabled,
        boolean chunkLoadingRequested,
        NodeMode mode) {
    public NodeMenuNodeSummary {
        Objects.requireNonNull(networkId, "networkId");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(form, "form");
        Objects.requireNonNull(facing, "facing");
        Objects.requireNonNull(mode, "mode");
        if (!new ManagedName(networkName).value().equals(networkName)
                || !new ManagedName(nodeName).value().equals(nodeName)) {
            throw new IllegalArgumentException("Node menu names must be canonical");
        }
        if (revision < 0) {
            throw new IllegalArgumentException("Node menu revision must be nonnegative");
        }
        NodeMenuCodecSupport.validateDimension(dimension);
    }

    static NodeMenuNodeSummary read(FriendlyByteBuf buffer) {
        return new NodeMenuNodeSummary(
                buffer.readUUID(),
                NetworkSummary.readName(buffer),
                buffer.readUUID(),
                NetworkSummary.readName(buffer),
                buffer.readLong(),
                NodeMenuCodecSupport.readDimension(buffer),
                buffer.readBlockPos(),
                NodeMenuCodecSupport.readForm(buffer),
                NodeMenuCodecSupport.readDirection(buffer),
                buffer.readBoolean(),
                buffer.readBoolean(),
                NodeMenuCodecSupport.readMode(buffer));
    }

    void write(FriendlyByteBuf buffer) {
        buffer.writeUUID(networkId);
        NetworkSummary.writeName(buffer, networkName);
        buffer.writeUUID(nodeId);
        NetworkSummary.writeName(buffer, nodeName);
        buffer.writeLong(revision);
        NodeMenuCodecSupport.writeDimension(buffer, dimension);
        buffer.writeBlockPos(position);
        NodeMenuCodecSupport.writeForm(buffer, form);
        NodeMenuCodecSupport.writeDirection(buffer, facing);
        buffer.writeBoolean(enabled);
        buffer.writeBoolean(chunkLoadingRequested);
        NodeMenuCodecSupport.writeMode(buffer, mode);
    }
}
