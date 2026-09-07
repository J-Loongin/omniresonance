// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.github.loongin.omniresonance.network.ManagedName;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;

/** Bounded terminal tunnel summary without child records or membership identities. */
public record TunnelSummary(
        UUID tunnelId, String name, long revision, boolean enabled, int channelCount, int bindingCount) {
    public TunnelSummary {
        Objects.requireNonNull(tunnelId, "tunnelId");
        if (!new ManagedName(name).value().equals(name) || revision < 0 || channelCount < 0 || bindingCount < 0) {
            throw new IllegalArgumentException("Invalid tunnel summary");
        }
    }

    static TunnelSummary read(FriendlyByteBuf buffer) {
        return new TunnelSummary(
                buffer.readUUID(),
                NetworkSummary.readName(buffer),
                buffer.readLong(),
                buffer.readBoolean(),
                buffer.readVarInt(),
                buffer.readVarInt());
    }

    void write(FriendlyByteBuf buffer) {
        buffer.writeUUID(tunnelId);
        NetworkSummary.writeName(buffer, name);
        buffer.writeLong(revision);
        buffer.writeBoolean(enabled);
        buffer.writeVarInt(channelCount);
        buffer.writeVarInt(bindingCount);
    }
}
