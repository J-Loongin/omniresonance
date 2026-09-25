// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.github.loongin.omniresonance.network.ManagedName;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;

/** Pure bounded placement/name projection; caller must authorize the associated channel before writing. */
public record ExchangeChannelView(UUID tunnel, String name) {
    public ExchangeChannelView {
        Objects.requireNonNull(tunnel);
        if (!new ManagedName(name).value().equals(name))
            throw new IllegalArgumentException("Noncanonical channel name");
    }

    public void write(FriendlyByteBuf b) {
        b.writeUUID(tunnel);
        NetworkSummary.writeName(b, name);
    }

    public static ExchangeChannelView read(FriendlyByteBuf b) {
        return new ExchangeChannelView(b.readUUID(), NetworkSummary.readName(b));
    }
}
