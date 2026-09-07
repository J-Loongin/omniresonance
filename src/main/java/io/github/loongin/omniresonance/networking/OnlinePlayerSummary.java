// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.github.loongin.omniresonance.network.ManagedName;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;

/** Immutable bounded selection identity, never a guarantee that its player is still online at commit time. */
public record OnlinePlayerSummary(UUID playerId, String name) {
    public OnlinePlayerSummary {
        Objects.requireNonNull(playerId, "playerId");
        if (!new ManagedName(name).value().equals(name)) throw new IllegalArgumentException("Invalid player name");
    }

    static OnlinePlayerSummary read(FriendlyByteBuf buffer) {
        return new OnlinePlayerSummary(buffer.readUUID(), NetworkSummary.readName(buffer));
    }

    void write(FriendlyByteBuf buffer) {
        buffer.writeUUID(playerId);
        NetworkSummary.writeName(buffer, name);
    }
}
