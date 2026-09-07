// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.github.loongin.omniresonance.network.ManagedName;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;

/** Bounded display-only target and impact for one server-issued direct-tunnel switch confirmation. */
public record NodeTunnelSwitchSummary(UUID targetTunnelId, String targetTunnelName, int removedBindingCount) {
    public NodeTunnelSwitchSummary {
        Objects.requireNonNull(targetTunnelId, "targetTunnelId");
        if (!new ManagedName(targetTunnelName).value().equals(targetTunnelName) || removedBindingCount < 0) {
            throw new IllegalArgumentException("Invalid direct tunnel switch summary");
        }
    }

    static NodeTunnelSwitchSummary read(FriendlyByteBuf buffer) {
        return new NodeTunnelSwitchSummary(buffer.readUUID(), NetworkSummary.readName(buffer), buffer.readVarInt());
    }

    void write(FriendlyByteBuf buffer) {
        buffer.writeUUID(targetTunnelId);
        NetworkSummary.writeName(buffer, targetTunnelName);
        buffer.writeVarInt(removedBindingCount);
    }
}
