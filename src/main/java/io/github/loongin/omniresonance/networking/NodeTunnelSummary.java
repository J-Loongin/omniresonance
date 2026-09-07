// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.github.loongin.omniresonance.network.ManagedName;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;

/** Bounded node-facing tunnel summary including only aggregate and current-node participation counts. */
public record NodeTunnelSummary(
        UUID tunnelId,
        String name,
        long revision,
        boolean enabled,
        int channelCount,
        int bindingCount,
        int currentNodeBindingCount) {
    public NodeTunnelSummary {
        Objects.requireNonNull(tunnelId, "tunnelId");
        if (!new ManagedName(name).value().equals(name)
                || revision < 0
                || channelCount < 0
                || channelCount > 65535
                || bindingCount < 0
                || bindingCount > 262144
                || currentNodeBindingCount < 0
                || currentNodeBindingCount > 1024
                || currentNodeBindingCount > bindingCount) {
            throw new IllegalArgumentException("Invalid node tunnel summary");
        }
    }

    static NodeTunnelSummary read(FriendlyByteBuf buffer) {
        return new NodeTunnelSummary(
                buffer.readUUID(),
                NetworkSummary.readName(buffer),
                buffer.readLong(),
                buffer.readBoolean(),
                buffer.readVarInt(),
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
        buffer.writeVarInt(currentNodeBindingCount);
    }
}
