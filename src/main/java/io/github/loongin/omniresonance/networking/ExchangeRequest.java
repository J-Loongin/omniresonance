// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Exchange child-page envelope; authentication and ordering belong to the existing terminal session. */
public record ExchangeRequest(
        UUID view, UUID session, UUID generation, long sequence, int kind, int offset, byte[] body)
        implements CustomPacketPayload {
    public static final int OPEN = 0,
            ACTION = 1,
            MORE = 2,
            CLOSE = 3,
            PRESETS = 4,
            FILTER = 5,
            RESOLVE = 6,
            UPLOAD = 7,
            CHUNK = 8,
            COMMIT = 9,
            CODES = 10;
    public static final Type<ExchangeRequest> TYPE =
            new Type<>(ResourceLocation.parse("omniresonance:exchange_request"));

    public ExchangeRequest {
        Objects.requireNonNull(view);
        Objects.requireNonNull(session);
        Objects.requireNonNull(generation);
        Objects.requireNonNull(body);
        if (sequence < 1
                || kind < 0
                || kind > 10
                || offset < 0
                || offset > ManagementTransferPool.MAXIMUM_OBJECT_BYTES
                || body.length > ManagementTransferPool.MAXIMUM_FRAGMENT_BYTES)
            throw new IllegalArgumentException("Invalid exchange envelope");
        boolean valid =
                switch (kind) {
                    case OPEN, CLOSE, PRESETS, COMMIT, CODES -> offset == 0 && body.length == 0;
                    case MORE -> offset > 0 && body.length == 0;
                    case UPLOAD -> offset > 0 && body.length == 0;
                    case CHUNK ->
                        body.length > 0 && (long) offset + body.length <= ManagementTransferPool.MAXIMUM_OBJECT_BYTES;
                    case FILTER -> offset == 0 && body.length == 16;
                    case ACTION, RESOLVE -> offset == 0 && body.length > 0;
                    default -> false;
                };
        if (!valid) throw new IllegalArgumentException("Invalid exchange operation shape");
        body = body.clone();
    }

    @Override
    public String toString() {
        return "ExchangeRequest[kind=" + kind + ", body=redacted]";
    }

    @Override
    public byte[] body() {
        return body.clone();
    }

    public static final StreamCodec<FriendlyByteBuf, ExchangeRequest> STREAM_CODEC = new StreamCodec<>() {
        public ExchangeRequest decode(FriendlyByteBuf b) {
            FullFilterCodec.requireBodyBound(b, TYPE.id());
            ExchangeRequest r = new ExchangeRequest(
                    b.readUUID(),
                    b.readUUID(),
                    b.readUUID(),
                    b.readLong(),
                    b.readUnsignedByte(),
                    b.readVarInt(),
                    b.readByteArray(ManagementTransferPool.MAXIMUM_FRAGMENT_BYTES));
            if (b.isReadable()) throw new IllegalArgumentException("Trailing exchange envelope");
            return r;
        }

        public void encode(FriendlyByteBuf b, ExchangeRequest r) {
            int start = b.writerIndex();
            b.writeUUID(r.view)
                    .writeUUID(r.session)
                    .writeUUID(r.generation)
                    .writeLong(r.sequence)
                    .writeByte(r.kind)
                    .writeVarInt(r.offset)
                    .writeByteArray(r.body);
            FullFilterCodec.requireEncodedBodyBound(b, start, TYPE.id());
        }
    };

    public Type<ExchangeRequest> type() {
        return TYPE;
    }
}
