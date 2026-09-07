// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.github.loongin.omniresonance.network.ManagedName;
import io.netty.handler.codec.DecoderException;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;

/** Immutable permission-filtered network identity for node selection; no owner/member identity is exposed. */
public record NodeNetworkSummary(UUID networkId, String name, Role role) {
    /** The actual sender's current management role in this one network. */
    public enum Role {
        OWNER,
        ADMIN
    }

    public NodeNetworkSummary {
        Objects.requireNonNull(networkId, "networkId");
        Objects.requireNonNull(role, "role");
        if (!new ManagedName(name).value().equals(name)) {
            throw new IllegalArgumentException("Node network summary name must be canonical");
        }
    }

    static NodeNetworkSummary read(FriendlyByteBuf buffer) {
        UUID networkId = buffer.readUUID();
        String name = NetworkSummary.readName(buffer);
        Role role =
                switch (buffer.readUnsignedByte()) {
                    case 0 -> Role.OWNER;
                    case 1 -> Role.ADMIN;
                    default -> throw new DecoderException("Unknown node network role");
                };
        return new NodeNetworkSummary(networkId, name, role);
    }

    void write(FriendlyByteBuf buffer) {
        buffer.writeUUID(networkId);
        NetworkSummary.writeName(buffer, name);
        buffer.writeByte(role == Role.OWNER ? 0 : 1);
    }
}
