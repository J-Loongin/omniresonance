// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.netty.buffer.Unpooled;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class ItemPolicyMenuCodecTest {
    @Test
    void fullPolicyIntentRoundTripsBothDirectionExclusiveLimits() {
        for (boolean input : new boolean[] {true, false}) {
            FriendlyByteBuf wire = policy(input, 17, Integer.MAX_VALUE, input ? Long.MAX_VALUE : Integer.MIN_VALUE);
            FriendlyByteBuf encoded = new FriendlyByteBuf(Unpooled.buffer());
            try {
                byte[] expected = new byte[wire.readableBytes()];
                wire.getBytes(0, expected);
                io.github.loongin.omniresonance.transfer.ItemTransferPolicy request = ItemPolicyMenuCodec.read(wire);
                assertEquals(0, wire.readableBytes());
                ItemPolicyMenuCodec.write(encoded, request);
                byte[] actual = new byte[encoded.readableBytes()];
                encoded.readBytes(actual);
                org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual);
            } finally {
                wire.release();
                encoded.release();
            }
        }
    }

    @Test
    void invalidNumbersAndMixedDirectionTrailingFieldsReject() {
        for (int[] values : new int[][] {{0, 1}, {1, 0}, {-1, 1}}) {
            FriendlyByteBuf wire = policy(true, values[0], values[1], 0);
            try {
                assertThrows(IllegalArgumentException.class, () -> ItemPolicyMenuCodec.read(wire));
            } finally {
                wire.release();
            }
        }
        FriendlyByteBuf wire = policy(true, 1, 1, -1);
        try {
            assertThrows(IllegalArgumentException.class, () -> ItemPolicyMenuCodec.read(wire));
        } finally {
            wire.release();
        }
    }

    private static FriendlyByteBuf policy(boolean input, int interval, int rate, long exclusive) {
        FriendlyByteBuf wire = new FriendlyByteBuf(Unpooled.buffer());
        wire.writeByte(input ? 0 : 1).writeInt(interval).writeInt(rate).writeByte(2);
        wire.writeBoolean(true).writeUUID(new UUID(3, 4)).writeByte(1);
        if (input) wire.writeLong(exclusive);
        else wire.writeInt((int) exclusive);
        return wire;
    }
}
