// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Fixed-size click intent. Runtime resource IDs and menu revisions are hints, never quantities or authority. */
public record TerminalStorageRequest(
        UUID view,
        UUID session,
        long generation,
        long sequence,
        long resourceId,
        int menuState,
        int inventorySlot,
        int button,
        boolean shift,
        boolean bulk)
        implements CustomPacketPayload {
    public static final Type<TerminalStorageRequest> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("omniresonance", "terminal_storage_request"));
    public static final StreamCodec<FriendlyByteBuf, TerminalStorageRequest> STREAM_CODEC = new StreamCodec<>() {
        public void encode(FriendlyByteBuf buffer, TerminalStorageRequest value) {
            buffer.writeUUID(value.view())
                    .writeUUID(value.session())
                    .writeLong(value.generation())
                    .writeLong(value.sequence())
                    .writeLong(value.resourceId())
                    .writeInt(value.menuState())
                    .writeByte(value.inventorySlot())
                    .writeByte(value.button())
                    .writeBoolean(value.shift())
                    .writeBoolean(value.bulk());
        }

        public TerminalStorageRequest decode(FriendlyByteBuf buffer) {
            if (buffer.readableBytes() != 64) throw new IllegalArgumentException("Invalid storage click length");
            return new TerminalStorageRequest(
                    buffer.readUUID(),
                    buffer.readUUID(),
                    buffer.readLong(),
                    buffer.readLong(),
                    buffer.readLong(),
                    buffer.readInt(),
                    buffer.readByte(),
                    buffer.readUnsignedByte(),
                    buffer.readBoolean(),
                    buffer.readBoolean());
        }
    };

    public TerminalStorageRequest(
            UUID view,
            UUID session,
            long generation,
            long sequence,
            long resourceId,
            int menuState,
            int inventorySlot,
            int button,
            boolean shift) {
        this(view, session, generation, sequence, resourceId, menuState, inventorySlot, button, shift, false);
    }

    public TerminalStorageRequest {
        Objects.requireNonNull(view);
        Objects.requireNonNull(session);
        if (generation <= 0
                || sequence <= 0
                || resourceId < 0
                || menuState < 0
                || menuState > 32767
                || inventorySlot < -1
                || inventorySlot > 35
                || button < 0
                || button > 1
                || inventorySlot >= 0 && resourceId != 0
                || bulk && (!shift || button != 0 || inventorySlot < 0))
            throw new IllegalArgumentException("Invalid storage click");
    }

    public Type<TerminalStorageRequest> type() {
        return TYPE;
    }
}
