// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.github.loongin.omniresonance.network.ManagedName;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Immutable identity-only summary, safe to share across threads without administrator or world references.
 * Construction validates canonical names, retaining immutable inputs without mutation or simulation.
 */
public record NetworkSummary(UUID id, UUID ownerId, String name) {
    static final int MAXIMUM_PAYLOAD_BYTES = 262144;
    private static final int MAXIMUM_NAME_BYTES = 256;

    public NetworkSummary {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerId, "ownerId");
        if (!new ManagedName(name).value().equals(name)) {
            throw new IllegalArgumentException("Summary name must be canonical");
        }
    }

    static NetworkSummary read(FriendlyByteBuf buffer) {
        return new NetworkSummary(buffer.readUUID(), buffer.readUUID(), readName(buffer));
    }

    void write(FriendlyByteBuf buffer) {
        buffer.writeUUID(id);
        buffer.writeUUID(ownerId);
        writeName(buffer, name);
    }

    static String readName(FriendlyByteBuf buffer) {
        int length = buffer.readVarInt();
        if (length < 0 || length > MAXIMUM_NAME_BYTES || length > buffer.readableBytes()) {
            throw new DecoderException("Invalid terminal name byte length");
        }
        byte[] bytes = new byte[length];
        buffer.readBytes(bytes);
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException failure) {
            throw new DecoderException("Invalid terminal UTF-8", failure);
        }
    }

    static void writeName(FriendlyByteBuf buffer, String name) {
        if (name.length() > MAXIMUM_NAME_BYTES) {
            throw new EncoderException("Terminal name exceeds byte limit");
        }
        ByteBuffer encoded;
        try {
            encoded = StandardCharsets.UTF_8
                    .newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(name));
        } catch (CharacterCodingException failure) {
            throw new EncoderException("Invalid terminal name characters", failure);
        }
        if (encoded.remaining() > MAXIMUM_NAME_BYTES) {
            throw new EncoderException("Terminal name exceeds byte limit");
        }
        buffer.writeVarInt(encoded.remaining());
        buffer.writeBytes(encoded);
    }

    static void requirePayloadBound(FriendlyByteBuf buffer) {
        if (buffer.readableBytes() > MAXIMUM_PAYLOAD_BYTES) {
            throw new DecoderException("Terminal payload exceeds byte limit");
        }
    }

    static void requireEncodedBound(FriendlyByteBuf buffer, int start) {
        if (buffer.writerIndex() - start > MAXIMUM_PAYLOAD_BYTES) {
            throw new EncoderException("Terminal payload exceeds byte limit");
        }
    }
}
