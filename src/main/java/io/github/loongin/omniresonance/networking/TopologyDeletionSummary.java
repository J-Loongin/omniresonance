// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.github.loongin.omniresonance.network.ManagedName;
import io.netty.handler.codec.DecoderException;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;

/** Player-visible bounded deletion impact; the server session retains the actual revision/token authority. */
public record TopologyDeletionSummary(Kind kind, UUID objectId, String name, int channelCount, int bindingCount) {
    public enum Kind {
        TUNNEL,
        CHANNEL
    }

    public TopologyDeletionSummary {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(objectId, "objectId");
        if (!new ManagedName(name).value().equals(name) || channelCount < 0 || bindingCount < 0) {
            throw new IllegalArgumentException("Invalid topology deletion summary");
        }
    }

    static TopologyDeletionSummary read(FriendlyByteBuf buffer) {
        Kind kind =
                switch (buffer.readUnsignedByte()) {
                    case 0 -> Kind.TUNNEL;
                    case 1 -> Kind.CHANNEL;
                    default -> throw new DecoderException("Unknown topology deletion kind");
                };
        return new TopologyDeletionSummary(
                kind, buffer.readUUID(), NetworkSummary.readName(buffer), buffer.readVarInt(), buffer.readVarInt());
    }

    void write(FriendlyByteBuf buffer) {
        buffer.writeByte(kind == Kind.TUNNEL ? 0 : 1);
        buffer.writeUUID(objectId);
        NetworkSummary.writeName(buffer, name);
        buffer.writeVarInt(channelCount);
        buffer.writeVarInt(bindingCount);
    }
}
