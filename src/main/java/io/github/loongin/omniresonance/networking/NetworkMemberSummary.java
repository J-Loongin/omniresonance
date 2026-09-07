// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.github.loongin.omniresonance.network.ManagedName;
import io.netty.handler.codec.DecoderException;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;

/** Immutable bounded member display value; role and online flags confer no client-side authority. */
public record NetworkMemberSummary(UUID playerId, String name, Role role, boolean online) {
    public enum Role {
        OWNER,
        ADMINISTRATOR
    }

    public NetworkMemberSummary {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(role, "role");
        if (!new ManagedName(name).value().equals(name)) throw new IllegalArgumentException("Invalid member name");
    }

    static NetworkMemberSummary read(FriendlyByteBuf buffer) {
        UUID id = buffer.readUUID();
        String name = NetworkSummary.readName(buffer);
        Role role =
                switch (buffer.readUnsignedByte()) {
                    case 0 -> Role.OWNER;
                    case 1 -> Role.ADMINISTRATOR;
                    default -> throw new DecoderException("Unknown member role");
                };
        return new NetworkMemberSummary(id, name, role, buffer.readBoolean());
    }

    void write(FriendlyByteBuf buffer) {
        buffer.writeUUID(playerId);
        NetworkSummary.writeName(buffer, name);
        buffer.writeByte(role == Role.OWNER ? 0 : 1);
        buffer.writeBoolean(online);
    }
}
