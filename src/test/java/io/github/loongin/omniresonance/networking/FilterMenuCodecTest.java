// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class FilterMenuCodecTest {
    @Test
    void stableOperationIdsAndPinnedMaximumSourceRoundTrip() {
        var operations = new io.github.loongin.omniresonance.filter.PresetEditOperation[] {
            io.github.loongin.omniresonance.filter.PresetEditOperation.CREATE,
            io.github.loongin.omniresonance.filter.PresetEditOperation.RENAME,
            io.github.loongin.omniresonance.filter.PresetEditOperation.ADD_RULE,
            io.github.loongin.omniresonance.filter.PresetEditOperation.REMOVE_RULE,
            io.github.loongin.omniresonance.filter.PresetEditOperation.COPY,
            io.github.loongin.omniresonance.filter.PresetEditOperation.DELETE,
            io.github.loongin.omniresonance.filter.PresetEditOperation.EDIT_RULE
        };
        var wire = new FriendlyByteBuf(Unpooled.buffer());
        try {
            for (int id = 0; id < operations.length; id++) {
                FilterMenuCodec.writeOperation(wire, operations[id]);
                assertEquals(id, wire.readUnsignedByte());
                wire.writeByte(id);
                assertEquals(operations[id], FilterMenuCodec.readOperation(wire));
            }
            String source = "a:" + "b".repeat(65533);
            var request = new NetworkTerminalRequest.BeginPresetEdit(
                    new UUID(1, 2), new UUID(3, 4), 1, operations[6], new UUID(5, 6), source);
            NetworkTerminalRequest.STREAM_CODEC.encode(wire, request);
            assertEquals(request, NetworkTerminalRequest.STREAM_CODEC.decode(wire));
            var state = new NetworkTerminalState.PresetEdit(
                    new NetworkSummary(new UUID(1, 2), new UUID(3, 4), "Network"),
                    new FilterPresetSummary(new UUID(5, 6), "Preset", 1, 1, true),
                    operations[6],
                    new FilterImpactSummary(1, 1, 1, true),
                    source);
            NetworkTerminalState.write(wire, state);
            assertEquals(state, NetworkTerminalState.read(wire));
            assertEquals(0, wire.readableBytes());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new NetworkTerminalRequest.BeginPresetEdit(
                            request.viewId(), request.sessionId(), 1, operations[6], request.presetId(), source + "b"));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new NetworkTerminalRequest.BeginPresetEdit(
                            request.viewId(),
                            request.sessionId(),
                            1,
                            operations[6],
                            request.presetId(),
                            "界".repeat(21846)));
            wire.writeByte(40)
                    .writeUUID(request.viewId())
                    .writeUUID(request.sessionId())
                    .writeLong(1)
                    .writeByte(6)
                    .writeBoolean(true)
                    .writeUUID(request.presetId())
                    .writeVarInt(65536);
            assertThrows(DecoderException.class, () -> NetworkTerminalRequest.STREAM_CODEC.decode(wire));
        } finally {
            wire.release();
        }
    }

    @Test
    void filterRequestsPreserveMaximumRuleAndSessionEnvelope() {
        for (int tag = 37; tag <= 41; tag++) {
            FriendlyByteBuf wire = new FriendlyByteBuf(Unpooled.buffer());
            FriendlyByteBuf encoded = new FriendlyByteBuf(Unpooled.buffer());
            try {
                wire.writeByte(tag)
                        .writeUUID(new UUID(1, 2))
                        .writeUUID(new UUID(3, 4))
                        .writeLong(1);
                if (tag == 38) wire.writeInt(128);
                if (tag == 39) wire.writeUUID(new UUID(5, 6)).writeLong(7).writeInt(128);
                if (tag == 40)
                    wire.writeByte(2)
                            .writeBoolean(true)
                            .writeUUID(new UUID(5, 6))
                            .writeUtf("");
                if (tag == 41) wire.writeUtf("a:" + "b".repeat(65533), 65535);
                byte[] expected = new byte[wire.readableBytes()];
                wire.getBytes(0, expected);
                NetworkTerminalRequest decoded = NetworkTerminalRequest.STREAM_CODEC.decode(wire);
                assertEquals(0, wire.readableBytes());
                NetworkTerminalRequest.STREAM_CODEC.encode(encoded, decoded);
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
    void boundedRulePagePreservesThreeMaximumIdsThroughActualStateCodec() {
        FriendlyByteBuf wire = new FriendlyByteBuf(Unpooled.buffer());
        FriendlyByteBuf encoded = new FriendlyByteBuf(Unpooled.buffer());
        try {
            wire.writeByte(14);
            wire.writeUUID(new UUID(1, 2)).writeUUID(new UUID(3, 4)).writeUtf("Network");
            wire.writeUUID(new UUID(5, 6))
                    .writeUtf("Preset")
                    .writeLong(7)
                    .writeInt(4)
                    .writeBoolean(true);
            wire.writeInt(0).writeInt(4).writeInt(0).writeBoolean(false).writeVarInt(3);
            for (int i = 0; i < 3; i++) wire.writeUtf("a:" + (char) ('b' + i) + "d".repeat(65532), 65535);
            byte[] expected = new byte[wire.readableBytes()];
            wire.getBytes(0, expected);
            NetworkTerminalState decoded = NetworkTerminalState.read(wire);
            assertEquals(0, wire.readableBytes());
            NetworkTerminalState.write(encoded, decoded);
            byte[] actual = new byte[encoded.readableBytes()];
            encoded.readBytes(actual);
            org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual);
        } finally {
            wire.release();
            encoded.release();
        }
    }

    @Test
    void extraFilterIntentFieldsRejectInsteadOfBeingSilentlyIgnored() {
        FriendlyByteBuf wire = new FriendlyByteBuf(Unpooled.buffer());
        try {
            wire.writeByte(41)
                    .writeUUID(new UUID(1, 2))
                    .writeUUID(new UUID(3, 4))
                    .writeLong(1)
                    .writeUtf("minecraft:stone")
                    .writeByte(7);
            assertThrows(DecoderException.class, () -> NetworkTerminalRequest.STREAM_CODEC.decode(wire));
        } finally {
            wire.release();
        }
    }

    @Test
    void clientIntentRejectsExcessUtf8BytesAndMalformedSurrogatesBeforeNetworkEncoding() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new NetworkTerminalRequest.SavePresetEdit(new UUID(1, 2), new UUID(3, 4), 1, "界".repeat(21846)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NetworkTerminalRequest.SavePresetEdit(
                        new UUID(1, 2), new UUID(3, 4), 1, String.valueOf((char) 0xd800)));
    }

    @Test
    void oversizedIntentRejectedBeforeStringAllocation() {
        FriendlyByteBuf wire = new FriendlyByteBuf(Unpooled.buffer());
        try {
            wire.writeByte(41)
                    .writeUUID(new UUID(1, 2))
                    .writeUUID(new UUID(3, 4))
                    .writeLong(1)
                    .writeVarInt(65536);
            wire.writeZero(65536);
            assertThrows(DecoderException.class, () -> NetworkTerminalRequest.STREAM_CODEC.decode(wire));
        } finally {
            wire.release();
        }
    }
}
