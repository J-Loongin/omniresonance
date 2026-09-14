// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class ManagementTransferCodecTest {
    private static final UUID SESSION = new UUID(1, 2);
    private static final UUID TRANSFER = new UUID(3, 4);

    @Test
    void everyMessageRoundTripsWithItsActualPayloadIdEnvelope() {
        for (ManagementTransferMessage message : new ManagementTransferMessage[] {
            new ManagementTransferMessage.Begin(
                    SESSION,
                    TRANSFER,
                    ManagementTransferMessage.Direction.UPLOAD,
                    ManagementTransferMessage.Context.TERMINAL,
                    SESSION,
                    ManagementTransferMessage.Purpose.RULE_SNAPSHOT,
                    16777216),
            new ManagementTransferMessage.Begin(
                    SESSION,
                    TRANSFER,
                    ManagementTransferMessage.Direction.DOWNLOAD,
                    ManagementTransferMessage.Context.NODE,
                    SESSION,
                    ManagementTransferMessage.Purpose.NODE_POLICY,
                    1),
            new ManagementTransferMessage.Begin(
                    SESSION,
                    TRANSFER,
                    ManagementTransferMessage.Direction.UPLOAD,
                    ManagementTransferMessage.Context.NODE,
                    SESSION,
                    ManagementTransferMessage.Purpose.BATCH_PASTE,
                    2),
            new ManagementTransferMessage.Chunk(SESSION, TRANSFER, 0, new byte[261120]),
            new ManagementTransferMessage.Finish(SESSION, TRANSFER),
            new ManagementTransferMessage.Abort(SESSION, TRANSFER)
        }) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                buffer.writeResourceLocation(message.type().id());
                ManagementTransferMessage.STREAM_CODEC.encode(buffer, message);
                assertTrue(buffer.writerIndex() <= 262144);
                if (message instanceof ManagementTransferMessage.Chunk) assertEquals(261195, buffer.writerIndex());
                assertEquals(message.type().id(), buffer.readResourceLocation());
                ManagementTransferMessage decoded = ManagementTransferMessage.STREAM_CODEC.decode(buffer);
                assertEquals(message.getClass(), decoded.getClass());
                assertEquals(message.session(), decoded.session());
                assertEquals(message.transfer(), decoded.transfer());
                if (message instanceof ManagementTransferMessage.Chunk chunk)
                    assertArrayEquals(chunk.data(), ((ManagementTransferMessage.Chunk) decoded).data());
                else assertEquals(message, decoded);
                assertEquals(0, buffer.readableBytes());
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void malformedAndTruncatedFramesAreRejectedBeforeDataAllocation() {
        for (int scenario = 0; scenario < 9; scenario++) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                switch (scenario) {
                    case 0 -> buffer.writeByte(255);
                    case 1 -> {
                        header(buffer, 1);
                        buffer.writeInt(0);
                        buffer.writeInt(-1);
                    }
                    case 2 -> {
                        header(buffer, 1);
                        buffer.writeInt(0);
                        buffer.writeInt(261121);
                    }
                    case 3 -> {
                        header(buffer, 1);
                        buffer.writeInt(0);
                        buffer.writeInt(2);
                        buffer.writeByte(1);
                    }
                    case 4 -> {
                        header(buffer, 1);
                        buffer.writeInt(0);
                        buffer.writeInt(0);
                    }
                    case 5 -> {
                        header(buffer, 0);
                        buffer.writeByte(255);
                        buffer.writeByte(0);
                        buffer.writeUUID(SESSION);
                        buffer.writeByte(0);
                        buffer.writeInt(1);
                    }
                    case 6 -> {
                        header(buffer, 0);
                        buffer.writeByte(0);
                        buffer.writeByte(255);
                        buffer.writeUUID(SESSION);
                        buffer.writeByte(0);
                        buffer.writeInt(1);
                    }
                    case 7 -> {
                        header(buffer, 0);
                        buffer.writeByte(0);
                        buffer.writeByte(0);
                        buffer.writeUUID(SESSION);
                        buffer.writeByte(255);
                        buffer.writeInt(1);
                    }
                    default -> {
                        header(buffer, 2);
                        buffer.writeByte(0);
                    }
                }
                assertThrows(DecoderException.class, () -> ManagementTransferCodec.read(buffer));
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void completeBeginRejectsObjectBoundAndPacketRejectsTotalBytes() {
        for (int length : new int[] {0, -1, 16777217, Integer.MAX_VALUE}) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                header(buffer, 0);
                buffer.writeByte(0);
                buffer.writeByte(0);
                buffer.writeUUID(SESSION);
                buffer.writeByte(0);
                buffer.writeInt(length);
                assertThrows(DecoderException.class, () -> ManagementTransferCodec.read(buffer));
            } finally {
                buffer.release();
            }
        }
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeZero(262145);
            assertThrows(DecoderException.class, () -> ManagementTransferCodec.read(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void fragmentOwnershipAndConstructorBoundsAreEnforced() {
        byte[] source = {3};
        ManagementTransferMessage.Chunk chunk = new ManagementTransferMessage.Chunk(SESSION, TRANSFER, 0, source);
        source[0] = 4;
        assertEquals(3, chunk.data()[0]);
        byte[] output = chunk.data();
        output[0] = 5;
        assertEquals(3, chunk.data()[0]);
        assertThrows(
                IllegalArgumentException.class,
                () -> new ManagementTransferMessage.Chunk(SESSION, TRANSFER, 0, new byte[261121]));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ManagementTransferMessage.Chunk(SESSION, TRANSFER, -1, new byte[1]));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ManagementTransferMessage.Chunk(SESSION, TRANSFER, 16777216, new byte[1]));
    }

    @Test
    void everyTruncationOfValidBeginAndChunkFailsWithoutPartialAcceptance() {
        for (ManagementTransferMessage message : new ManagementTransferMessage[] {
            new ManagementTransferMessage.Begin(
                    SESSION,
                    TRANSFER,
                    ManagementTransferMessage.Direction.UPLOAD,
                    ManagementTransferMessage.Context.NODE,
                    SESSION,
                    ManagementTransferMessage.Purpose.NODE_POLICY,
                    16),
            new ManagementTransferMessage.Chunk(SESSION, TRANSFER, 0, new byte[16])
        }) {
            FriendlyByteBuf complete = new FriendlyByteBuf(Unpooled.buffer());
            try {
                ManagementTransferCodec.write(complete, message);
                for (int length = 0; length < complete.writerIndex(); length++) {
                    FriendlyByteBuf truncated = new FriendlyByteBuf(complete.copy(0, length));
                    try {
                        assertThrows(DecoderException.class, () -> ManagementTransferCodec.read(truncated));
                    } finally {
                        truncated.release();
                    }
                }
            } finally {
                complete.release();
            }
        }
    }

    private static void header(FriendlyByteBuf buffer, int kind) {
        buffer.writeByte(kind);
        buffer.writeUUID(SESSION);
        buffer.writeUUID(TRANSFER);
    }
}
