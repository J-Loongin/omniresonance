// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Read-only subscription; the current network is derived from the authenticated terminal session. */
public record NetworkStatusRequest(UUID view, UUID session, long generation, boolean open)
        implements CustomPacketPayload {
    public static final Type<NetworkStatusRequest> TYPE =
            new Type<>(ResourceLocation.parse("omniresonance:network_status_request"));

    public NetworkStatusRequest {
        Objects.requireNonNull(view);
        Objects.requireNonNull(session);
        if (generation < 1) throw new IllegalArgumentException("Invalid status generation");
    }

    public static final StreamCodec<FriendlyByteBuf, NetworkStatusRequest> STREAM_CODEC = new StreamCodec<>() {
        public void encode(FriendlyByteBuf b, NetworkStatusRequest v) {
            b.writeUUID(v.view).writeUUID(v.session).writeLong(v.generation).writeBoolean(v.open);
        }

        public NetworkStatusRequest decode(FriendlyByteBuf b) {
            if (b.readableBytes() != 41) throw new IllegalArgumentException("Invalid status request length");
            return new NetworkStatusRequest(b.readUUID(), b.readUUID(), b.readLong(), b.readBoolean());
        }
    };

    public Type<NetworkStatusRequest> type() {
        return TYPE;
    }
}
