// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.github.loongin.omniresonance.network.ManagedName;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;

/** Immutable bounded confirmation counts for deleting one exact network; it carries no edit token or authority. */
public record NetworkDeletionSummary(
        UUID networkId, String name, int administratorCount, int tunnelCount, int channelCount) {
    public NetworkDeletionSummary {
        Objects.requireNonNull(networkId, "networkId");
        if (!new ManagedName(name).value().equals(name)
                || administratorCount < 0
                || administratorCount > 262144
                || tunnelCount < 0
                || tunnelCount > 65535
                || channelCount < 0
                || channelCount > 262144) {
            throw new IllegalArgumentException("Invalid network deletion summary");
        }
    }

    static NetworkDeletionSummary read(FriendlyByteBuf buffer) {
        return new NetworkDeletionSummary(
                buffer.readUUID(),
                NetworkSummary.readName(buffer),
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readVarInt());
    }

    void write(FriendlyByteBuf buffer) {
        buffer.writeUUID(networkId);
        NetworkSummary.writeName(buffer, name);
        buffer.writeVarInt(administratorCount);
        buffer.writeVarInt(tunnelCount);
        buffer.writeVarInt(channelCount);
    }
}
