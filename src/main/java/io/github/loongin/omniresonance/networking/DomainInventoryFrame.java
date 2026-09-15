// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Immutable server-to-client inventory stream, one bounded record fragment per packet. No implicit retries. */
public sealed interface DomainInventoryFrame extends CustomPacketPayload {
    Type<DomainInventoryFrame> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("omniresonance", "domain_inventory"));
    int MAXIMUM_FRAGMENT_BYTES = 32768;
    int ENVELOPE_BYTES = 1 + TYPE.id().toString().length();
    int HEADER_BYTES = ENVELOPE_BYTES + 33;
    int DATA_HEADER_BYTES = HEADER_BYTES + 13;
    StreamCodec<FriendlyByteBuf, DomainInventoryFrame> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public void encode(FriendlyByteBuf buffer, DomainInventoryFrame frame) {
            int tag = frame instanceof Begin ? 0 : frame instanceof Data ? 1 : frame instanceof End ? 2 : 3;
            buffer.writeByte(tag)
                    .writeUUID(frame.session())
                    .writeLong(frame.generation())
                    .writeLong(frame.sequence());
            switch (frame) {
                case Begin begin -> buffer.writeLong(begin.ceiling()).writeInt(begin.initialCount());
                case Data data -> {
                    buffer.writeBoolean(data.base())
                            .writeInt(data.totalLength())
                            .writeInt(data.offset());
                    buffer.writeInt(data.data.length).writeBytes(data.data);
                }
                case End end -> buffer.writeInt(end.count()).writeLong(end.revision());
                case Failed failed -> buffer.writeByte(failed.reason().ordinal());
            }
        }

        @Override
        public DomainInventoryFrame decode(FriendlyByteBuf buffer) {
            if (buffer.readableBytes() > DATA_HEADER_BYTES + MAXIMUM_FRAGMENT_BYTES - ENVELOPE_BYTES)
                throw new IllegalArgumentException("Inventory packet exceeds bound");
            int tag = buffer.readUnsignedByte();
            UUID session = buffer.readUUID();
            long generation = buffer.readLong(), sequence = buffer.readLong();
            DomainInventoryFrame frame =
                    switch (tag) {
                        case 0 -> new Begin(session, generation, sequence, buffer.readLong(), buffer.readInt());
                        case 1 -> {
                            boolean base = buffer.readBoolean();
                            int total = buffer.readInt(), offset = buffer.readInt(), length = buffer.readInt();
                            if (length < 1 || length > MAXIMUM_FRAGMENT_BYTES || length > buffer.readableBytes())
                                throw new IllegalArgumentException("Invalid inventory fragment length");
                            byte[] bytes = new byte[length];
                            buffer.readBytes(bytes);
                            yield new Data(session, generation, sequence, base, total, offset, bytes);
                        }
                        case 2 -> new End(session, generation, sequence, buffer.readInt(), buffer.readLong());
                        case 3 -> {
                            int reason = buffer.readUnsignedByte();
                            if (reason >= Reason.values().length)
                                throw new IllegalArgumentException("Unknown inventory failure");
                            yield new Failed(session, generation, sequence, Reason.values()[reason]);
                        }
                        default -> throw new IllegalArgumentException("Unknown inventory frame");
                    };
            if (buffer.isReadable()) throw new IllegalArgumentException("Trailing inventory bytes");
            return frame;
        }
    };

    UUID session();

    long generation();

    long sequence();

    @Override
    default Type<DomainInventoryFrame> type() {
        return TYPE;
    }

    default int wireSize() {
        return HEADER_BYTES + (this instanceof Data data ? 13 + data.data.length : this instanceof Failed ? 1 : 12);
    }

    enum Reason {
        UNAVAILABLE,
        CHANGING_TOO_FAST
    }

    private static void identity(UUID session, long generation, long sequence) {
        Objects.requireNonNull(session);
        if (generation <= 0 || sequence < 0) throw new IllegalArgumentException("Invalid inventory stream");
    }

    record Begin(UUID session, long generation, long sequence, long ceiling, int initialCount)
            implements DomainInventoryFrame {
        public Begin {
            identity(session, generation, sequence);
            if (sequence != 0 || ceiling < 0 || initialCount < 0)
                throw new IllegalArgumentException("Invalid inventory begin");
        }
    }

    record Data(UUID session, long generation, long sequence, boolean base, int totalLength, int offset, byte[] data)
            implements DomainInventoryFrame {
        public Data {
            identity(session, generation, sequence);
            Objects.requireNonNull(data);
            if (sequence == 0
                    || totalLength < 33
                    || totalLength > DomainInventoryRecordCodec.MAXIMUM_BYTES
                    || offset < 0
                    || data.length < 1
                    || data.length > MAXIMUM_FRAGMENT_BYTES
                    || offset > totalLength - data.length)
                throw new IllegalArgumentException("Invalid inventory data range");
            data = data.clone();
        }

        @Override
        public byte[] data() {
            return data.clone();
        }
    }

    record End(UUID session, long generation, long sequence, int count, long revision) implements DomainInventoryFrame {
        public End {
            identity(session, generation, sequence);
            if (sequence == 0 || count < 0 || revision < 0) throw new IllegalArgumentException("Invalid inventory end");
        }
    }

    record Failed(UUID session, long generation, long sequence, Reason reason) implements DomainInventoryFrame {
        public Failed {
            identity(session, generation, sequence);
            Objects.requireNonNull(reason);
        }
    }
}
