// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.github.loongin.omniresonance.network.ManagedName;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;

/** Bounded terminal channel summary; channel intentionally has no enabled or resource-child state. */
public record ChannelSummary(UUID channelId, String name, long revision, int inputCount, int outputCount) {
    public ChannelSummary {
        Objects.requireNonNull(channelId, "channelId");
        if (!new ManagedName(name).value().equals(name) || revision < 0 || inputCount < 0 || outputCount < 0) {
            throw new IllegalArgumentException("Invalid channel summary");
        }
    }

    static ChannelSummary read(FriendlyByteBuf buffer) {
        return new ChannelSummary(
                buffer.readUUID(),
                NetworkSummary.readName(buffer),
                buffer.readLong(),
                buffer.readVarInt(),
                buffer.readVarInt());
    }

    void write(FriendlyByteBuf buffer) {
        buffer.writeUUID(channelId);
        NetworkSummary.writeName(buffer, name);
        buffer.writeLong(revision);
        buffer.writeVarInt(inputCount);
        buffer.writeVarInt(outputCount);
    }
}
