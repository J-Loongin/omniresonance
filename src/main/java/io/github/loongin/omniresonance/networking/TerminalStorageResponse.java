// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Ordered operation outcome, sent after native inventory synchronization. Sequence zero updates only access mode. */
public record TerminalStorageResponse(
        UUID session, long generation, long sequence, Status status, long moved, boolean writable, int menuState)
        implements CustomPacketPayload {
    public enum Status {
        MODE,
        COMPLETE,
        REFUSED,
        DENIED,
        STALE,
        BUSY,
        WAITING_BUDGET,
        FAILED,
        UNKNOWN,
        NO_SPACE,
        NO_BUCKET,
        NO_CARRIER,
        INSUFFICIENT_FLUID,
        RECOVERY_FULL,
        PROGRESS
    }

    public static final Type<TerminalStorageResponse> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("omniresonance", "terminal_storage_response"));
    public static final StreamCodec<FriendlyByteBuf, TerminalStorageResponse> STREAM_CODEC = new StreamCodec<>() {
        public void encode(FriendlyByteBuf buffer, TerminalStorageResponse value) {
            buffer.writeUUID(value.session())
                    .writeLong(value.generation())
                    .writeLong(value.sequence())
                    .writeByte(value.status().ordinal())
                    .writeLong(value.moved())
                    .writeBoolean(value.writable())
                    .writeInt(value.menuState());
        }

        public TerminalStorageResponse decode(FriendlyByteBuf buffer) {
            if (buffer.readableBytes() != 46) throw new IllegalArgumentException("Invalid storage result length");
            UUID session = buffer.readUUID();
            long generation = buffer.readLong(), sequence = buffer.readLong();
            int status = buffer.readUnsignedByte();
            if (status >= Status.values().length) throw new IllegalArgumentException("Unknown storage result");
            return new TerminalStorageResponse(
                    session,
                    generation,
                    sequence,
                    Status.values()[status],
                    buffer.readLong(),
                    buffer.readBoolean(),
                    buffer.readInt());
        }
    };

    public TerminalStorageResponse {
        Objects.requireNonNull(session);
        Objects.requireNonNull(status);
        if (generation <= 0
                || sequence < 0
                || moved < 0
                || menuState < 0
                || menuState > 32767
                || (sequence == 0) != (status == Status.MODE))
            throw new IllegalArgumentException("Invalid storage result");
    }

    public Type<TerminalStorageResponse> type() {
        return TYPE;
    }
}
