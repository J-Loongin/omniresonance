// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Bounded exchange response fragment. Only explicit owner code results contain receiving credentials. */
public record ExchangeFrame(
        UUID view,
        UUID session,
        UUID generation,
        long sequence,
        int kind,
        boolean owner,
        int total,
        int offset,
        byte[] body)
        implements CustomPacketPayload {
    public static final int ERROR = 0,
            META = 1,
            RULES = 2,
            DETAIL = 3,
            CODE = 4,
            PRESETS = 5,
            FILTER = 6,
            RECEIVER = 7,
            DONE = 8,
            ACK = 9,
            CODES = 10,
            TUNNELS = 11,
            CHANNELS = 12;
    public static final Type<ExchangeFrame> TYPE = new Type<>(ResourceLocation.parse("omniresonance:exchange_frame"));

    public ExchangeFrame {
        Objects.requireNonNull(view);
        Objects.requireNonNull(session);
        Objects.requireNonNull(generation);
        Objects.requireNonNull(body);
        if (sequence < 1
                || kind < 0
                || kind > 12
                || total < 0
                || total > ManagementTransferPool.MAXIMUM_OBJECT_BYTES
                || offset < 0
                || offset > total
                || body.length > total - offset
                || body.length > ManagementTransferPool.MAXIMUM_FRAGMENT_BYTES)
            throw new IllegalArgumentException("Invalid exchange fragment");
        body = body.clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }

    @Override
    public String toString() {
        return "ExchangeFrame[kind=" + kind + ", bytes=" + body.length + "]";
    }

    public static final StreamCodec<FriendlyByteBuf, ExchangeFrame> STREAM_CODEC = new StreamCodec<>() {
        public ExchangeFrame decode(FriendlyByteBuf b) {
            FullFilterCodec.requireBodyBound(b, TYPE.id());
            UUID view = b.readUUID(), session = b.readUUID(), generation = b.readUUID();
            long sequence = b.readLong();
            int kind = b.readUnsignedByte();
            int flag = b.readUnsignedByte();
            if (flag > 1) throw new IllegalArgumentException("Invalid owner flag");
            ExchangeFrame f = new ExchangeFrame(
                    view,
                    session,
                    generation,
                    sequence,
                    kind,
                    flag == 1,
                    b.readVarInt(),
                    b.readVarInt(),
                    b.readByteArray(ManagementTransferPool.MAXIMUM_FRAGMENT_BYTES));
            if (b.isReadable()) throw new IllegalArgumentException("Trailing exchange fragment");
            return f;
        }

        public void encode(FriendlyByteBuf b, ExchangeFrame f) {
            int start = b.writerIndex();
            b.writeUUID(f.view)
                    .writeUUID(f.session)
                    .writeUUID(f.generation)
                    .writeLong(f.sequence)
                    .writeByte(f.kind)
                    .writeBoolean(f.owner)
                    .writeVarInt(f.total)
                    .writeVarInt(f.offset)
                    .writeByteArray(f.body);
            FullFilterCodec.requireEncodedBodyBound(b, start, TYPE.id());
        }
    };

    public Type<ExchangeFrame> type() {
        return TYPE;
    }
}
