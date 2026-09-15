// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Bounded view-local subscription intent; server derives the network and actor from the existing terminal session. */
public record DomainInventoryRequest(UUID view, UUID session, long generation, boolean open)
        implements CustomPacketPayload {
    public static final Type<DomainInventoryRequest> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("omniresonance", "domain_inventory_request"));
    public static final StreamCodec<FriendlyByteBuf, DomainInventoryRequest> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public void encode(FriendlyByteBuf buffer, DomainInventoryRequest value) {
            buffer.writeUUID(value.view())
                    .writeUUID(value.session())
                    .writeLong(value.generation())
                    .writeBoolean(value.open());
        }

        @Override
        public DomainInventoryRequest decode(FriendlyByteBuf buffer) {
            if (buffer.readableBytes() != 41) throw new IllegalArgumentException("Invalid inventory intent length");
            return new DomainInventoryRequest(
                    buffer.readUUID(), buffer.readUUID(), buffer.readLong(), buffer.readBoolean());
        }
    };

    public DomainInventoryRequest {
        Objects.requireNonNull(view);
        Objects.requireNonNull(session);
        if (generation <= 0) throw new IllegalArgumentException("Invalid inventory generation");
    }

    @Override
    public Type<DomainInventoryRequest> type() {
        return TYPE;
    }
}
