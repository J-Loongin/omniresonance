// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.netty.handler.codec.DecoderException;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Untrusted client intent with bounded wire fields and no owner claim. Values are immutable and
 * thread-safe; decoding performs no authorization, simulation or authoritative state mutation.
 * Invalid framing is rejected; valid UTF-8 names still require semantic server validation.
 */
public sealed interface NetworkTerminalRequest extends CustomPacketPayload {
    Type<NetworkTerminalRequest> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("omniresonance", "network_terminal_request"));
    StreamCodec<FriendlyByteBuf, NetworkTerminalRequest> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public NetworkTerminalRequest decode(FriendlyByteBuf buffer) {
            NetworkSummary.requirePayloadBound(buffer);
            try {
                return switch (buffer.readUnsignedByte()) {
                    case 0 -> new Open(buffer.readUUID());
                    case 1 ->
                        new Page(
                                buffer.readUUID(),
                                buffer.readUUID(),
                                buffer.readLong(),
                                buffer.readBoolean() ? buffer.readUUID() : null,
                                buffer.readBoolean());
                    case 2 ->
                        new Create(
                                buffer.readUUID(),
                                buffer.readUUID(),
                                buffer.readLong(),
                                NetworkSummary.readName(buffer));
                    case 3 -> new Close(buffer.readUUID(), buffer.readUUID());
                    default -> throw new DecoderException("Unknown terminal request tag");
                };
            } catch (IllegalArgumentException failure) {
                throw new DecoderException("Invalid terminal request fields", failure);
            }
        }

        @Override
        public void encode(FriendlyByteBuf buffer, NetworkTerminalRequest request) {
            int start = buffer.writerIndex();
            switch (request) {
                case Open open -> {
                    buffer.writeByte(0);
                    buffer.writeUUID(open.viewId());
                }
                case Page page -> {
                    buffer.writeByte(1);
                    buffer.writeUUID(page.viewId());
                    buffer.writeUUID(page.sessionId());
                    buffer.writeLong(page.sequence());
                    buffer.writeBoolean(page.anchor() != null);
                    if (page.anchor() != null) {
                        buffer.writeUUID(page.anchor());
                    }
                    buffer.writeBoolean(page.backwards());
                }
                case Create create -> {
                    buffer.writeByte(2);
                    buffer.writeUUID(create.viewId());
                    buffer.writeUUID(create.sessionId());
                    buffer.writeLong(create.sequence());
                    NetworkSummary.writeName(buffer, create.name());
                }
                case Close close -> {
                    buffer.writeByte(3);
                    buffer.writeUUID(close.viewId());
                    buffer.writeUUID(close.sessionId());
                }
            }
            NetworkSummary.requireEncodedBound(buffer, start);
        }
    };

    UUID viewId();

    default @Nullable UUID sessionId() {
        return null;
    }

    default long sequence() {
        return 0;
    }

    @Override
    default Type<NetworkTerminalRequest> type() {
        return TYPE;
    }

    record Open(UUID viewId) implements NetworkTerminalRequest {
        public Open {
            Objects.requireNonNull(viewId, "viewId");
        }
    }

    record Page(
            UUID viewId,
            UUID sessionId,
            long sequence,
            @Nullable UUID anchor,
            boolean backwards) implements NetworkTerminalRequest {
        public Page {
            requireSession(viewId, sessionId);
            requireSequence(sequence);
        }
    }

    record Create(UUID viewId, UUID sessionId, long sequence, String name) implements NetworkTerminalRequest {
        public Create {
            requireSession(viewId, sessionId);
            requireSequence(sequence);
            Objects.requireNonNull(name, "name");
        }
    }

    record Close(UUID viewId, UUID sessionId) implements NetworkTerminalRequest {
        public Close {
            requireSession(viewId, sessionId);
        }
    }

    private static void requireSession(UUID viewId, UUID sessionId) {
        Objects.requireNonNull(viewId, "viewId");
        Objects.requireNonNull(sessionId, "sessionId");
    }

    private static void requireSequence(long sequence) {
        if (sequence < 1) {
            throw new IllegalArgumentException("Terminal request sequence must be positive");
        }
    }
}
