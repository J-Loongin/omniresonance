// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.network.TransferDirection;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.Nullable;

/** Bounded node-facing channel summary; current direction is null when this node has not joined the channel. */
public record NodeChannelSummary(
        UUID channelId,
        String name,
        long revision,
        int inputCount,
        int outputCount,
        @Nullable TransferDirection currentDirection) {
    public NodeChannelSummary {
        Objects.requireNonNull(channelId, "channelId");
        if (!new ManagedName(name).value().equals(name)
                || revision < 0
                || inputCount < 0
                || inputCount > 262144
                || outputCount < 0
                || outputCount > 262144) {
            throw new IllegalArgumentException("Invalid node channel summary");
        }
    }

    static NodeChannelSummary read(FriendlyByteBuf buffer) {
        return new NodeChannelSummary(
                buffer.readUUID(),
                NetworkSummary.readName(buffer),
                buffer.readLong(),
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readBoolean() ? NodeMenuCodecSupport.readTransferDirection(buffer) : null);
    }

    void write(FriendlyByteBuf buffer) {
        buffer.writeUUID(channelId);
        NetworkSummary.writeName(buffer, name);
        buffer.writeLong(revision);
        buffer.writeVarInt(inputCount);
        buffer.writeVarInt(outputCount);
        buffer.writeBoolean(currentDirection != null);
        if (currentDirection != null) {
            NodeMenuCodecSupport.writeTransferDirection(buffer, currentDirection);
        }
    }
}
