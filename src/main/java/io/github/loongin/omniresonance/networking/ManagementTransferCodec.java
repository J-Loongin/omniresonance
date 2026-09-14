// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Strict single-payload codec with explicit enum IDs and fixed-width lengths. Allocation follows
 * frame/length/readability validation. No text fields exist, so malformed UTF cannot enter context or
 * purpose. The payload ID's UTF-8 prefix is included in the 256-KiB envelope calculation. This codec
 * requires a buffer containing exactly one payload body; the transport supplies the frame boundary.
 */
public final class ManagementTransferCodec {
    public static final int MAXIMUM_PACKET_BYTES = 262144;
    // Stable ASCII ID is 33 bytes plus its one-byte VarInt length prefix.
    private static final int PAYLOAD_ID_BYTES = 34;

    private ManagementTransferCodec() {}

    /** Writes immutable values without authority access; throws if the encoded envelope exceeds its bound. */
    public static void write(FriendlyByteBuf buffer, ManagementTransferMessage message) {
        int start = buffer.writerIndex();
        int kind =
                switch (message) {
                    case ManagementTransferMessage.Begin ignored -> 0;
                    case ManagementTransferMessage.Chunk ignored -> 1;
                    case ManagementTransferMessage.Finish ignored -> 2;
                    case ManagementTransferMessage.Abort ignored -> 3;
                };
        buffer.writeByte(kind);
        buffer.writeUUID(message.session());
        buffer.writeUUID(message.transfer());
        switch (message) {
            case ManagementTransferMessage.Begin begin -> {
                buffer.writeByte(
                        switch (begin.direction()) {
                            case UPLOAD -> 0;
                            case DOWNLOAD -> 1;
                        });
                buffer.writeByte(
                        switch (begin.context()) {
                            case TERMINAL -> 0;
                            case NODE -> 1;
                        });
                buffer.writeUUID(begin.contextId());
                buffer.writeByte(
                        switch (begin.purpose()) {
                            case RULE_SNAPSHOT -> 0;
                            case NODE_POLICY -> 1;
                            case BATCH_PASTE -> 2;
                        });
                buffer.writeInt(begin.totalLength());
            }
            case ManagementTransferMessage.Chunk chunk -> {
                byte[] data = chunk.data();
                buffer.writeInt(chunk.offset());
                buffer.writeInt(data.length);
                buffer.writeBytes(data);
            }
            case ManagementTransferMessage.Finish ignored -> {}
            case ManagementTransferMessage.Abort ignored -> {}
        }
        if (buffer.writerIndex() - start > MAXIMUM_PACKET_BYTES - PAYLOAD_ID_BYTES)
            throw new EncoderException("Management packet exceeds envelope bound");
    }

    /** Reads one untrusted bounded body; malformed/unknown/truncated/trailing input throws before dispatch. */
    public static ManagementTransferMessage read(FriendlyByteBuf buffer) {
        if (buffer.readableBytes() > MAXIMUM_PACKET_BYTES - PAYLOAD_ID_BYTES)
            throw new DecoderException("Management packet exceeds envelope bound");
        try {
            int kind = buffer.readUnsignedByte();
            if (kind > 3) throw new DecoderException("Unknown management message kind");
            UUID session = buffer.readUUID();
            UUID transfer = buffer.readUUID();
            ManagementTransferMessage message =
                    switch (kind) {
                        case 0 -> {
                            ManagementTransferMessage.Direction direction =
                                    switch (buffer.readUnsignedByte()) {
                                        case 0 -> ManagementTransferMessage.Direction.UPLOAD;
                                        case 1 -> ManagementTransferMessage.Direction.DOWNLOAD;
                                        default -> throw new DecoderException("Unknown management direction");
                                    };
                            ManagementTransferMessage.Context context =
                                    switch (buffer.readUnsignedByte()) {
                                        case 0 -> ManagementTransferMessage.Context.TERMINAL;
                                        case 1 -> ManagementTransferMessage.Context.NODE;
                                        default -> throw new DecoderException("Unknown management context");
                                    };
                            UUID contextId = buffer.readUUID();
                            ManagementTransferMessage.Purpose purpose =
                                    switch (buffer.readUnsignedByte()) {
                                        case 0 -> ManagementTransferMessage.Purpose.RULE_SNAPSHOT;
                                        case 1 -> ManagementTransferMessage.Purpose.NODE_POLICY;
                                        case 2 -> ManagementTransferMessage.Purpose.BATCH_PASTE;
                                        default -> throw new DecoderException("Unknown management purpose");
                                    };
                            yield new ManagementTransferMessage.Begin(
                                    session, transfer, direction, context, contextId, purpose, buffer.readInt());
                        }
                        case 1 -> {
                            int offset = buffer.readInt();
                            int length = buffer.readInt();
                            if (offset < 0
                                    || length <= 0
                                    || length > ManagementTransferPool.MAXIMUM_FRAGMENT_BYTES
                                    || offset > ManagementTransferPool.MAXIMUM_OBJECT_BYTES - length
                                    || length != buffer.readableBytes())
                                throw new DecoderException("Invalid management fragment length or offset");
                            byte[] data = new byte[length];
                            buffer.readBytes(data);
                            yield new ManagementTransferMessage.Chunk(session, transfer, offset, data);
                        }
                        case 2 -> new ManagementTransferMessage.Finish(session, transfer);
                        case 3 -> new ManagementTransferMessage.Abort(session, transfer);
                        default -> throw new DecoderException("Unknown management message kind");
                    };
            if (buffer.isReadable()) throw new DecoderException("Trailing management bytes");
            return message;
        } catch (IndexOutOfBoundsException | IllegalArgumentException failure) {
            throw new DecoderException("Malformed management message", failure);
        }
    }
}
