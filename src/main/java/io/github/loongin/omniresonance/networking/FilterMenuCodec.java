// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.github.loongin.omniresonance.filter.PresetEditOperation;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import net.minecraft.network.FriendlyByteBuf;

/** Bounded management text: preflights byte counts before allocating and never truncates persisted IDs. */
public final class FilterMenuCodec {
    public static final int MAXIMUM_TEXT_BYTES = 65535;

    private FilterMenuCodec() {}

    public static String readText(FriendlyByteBuf buffer) {
        int length = buffer.readVarInt();
        if (length < 0 || length > MAXIMUM_TEXT_BYTES || length > buffer.readableBytes())
            throw new DecoderException("Invalid filter intent length");
        byte[] bytes = new byte[length];
        buffer.readBytes(bytes);
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (java.nio.charset.CharacterCodingException failure) {
            throw new DecoderException("Invalid filter UTF-8", failure);
        }
    }

    public static void writeText(FriendlyByteBuf buffer, String text) {
        if (text.length() > MAXIMUM_TEXT_BYTES) throw new EncoderException("Filter intent too long");
        try {
            ByteBuffer bytes = StandardCharsets.UTF_8
                    .newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(text));
            if (bytes.remaining() > MAXIMUM_TEXT_BYTES) throw new EncoderException("Filter intent too long");
            buffer.writeVarInt(bytes.remaining());
            buffer.writeBytes(bytes);
        } catch (java.nio.charset.CharacterCodingException failure) {
            throw new EncoderException("Invalid filter UTF-8", failure);
        }
    }

    static void validateText(String value) {
        int bytes = 0;
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character <= 0x7f) bytes++;
            else if (character <= 0x7ff) bytes += 2;
            else if (Character.isHighSurrogate(character)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1)))
                    throw new IllegalArgumentException("Invalid filter text surrogate");
                i++;
                bytes += 4;
            } else if (Character.isLowSurrogate(character))
                throw new IllegalArgumentException("Invalid filter text surrogate");
            else bytes += 3;
            if (bytes > MAXIMUM_TEXT_BYTES) throw new IllegalArgumentException("Filter intent exceeds byte bound");
        }
    }

    static PresetEditOperation readOperation(FriendlyByteBuf buffer) {
        return switch (buffer.readUnsignedByte()) {
            case 0 -> PresetEditOperation.CREATE;
            case 1 -> PresetEditOperation.RENAME;
            case 2 -> PresetEditOperation.ADD_RULE;
            case 3 -> PresetEditOperation.REMOVE_RULE;
            case 4 -> PresetEditOperation.COPY;
            case 5 -> PresetEditOperation.DELETE;
            case 6 -> PresetEditOperation.EDIT_RULE;
            default -> throw new DecoderException("Unknown preset operation");
        };
    }

    static void writeOperation(FriendlyByteBuf buffer, PresetEditOperation operation) {
        buffer.writeByte(
                switch (operation) {
                    case CREATE -> 0;
                    case RENAME -> 1;
                    case ADD_RULE -> 2;
                    case REMOVE_RULE -> 3;
                    case COPY -> 4;
                    case DELETE -> 5;
                    case EDIT_RULE -> 6;
                });
    }
}
