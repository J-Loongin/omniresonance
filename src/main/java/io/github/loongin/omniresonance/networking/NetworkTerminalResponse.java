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
 * Bounded immutable server result without exception text or administrator sets. Safe to share across
 * threads; codecs only validate framing/value bounds and never access or modify authoritative state.
 */
public sealed interface NetworkTerminalResponse extends CustomPacketPayload {
    Type<NetworkTerminalResponse> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("omniresonance", "network_terminal_response"));
    StreamCodec<FriendlyByteBuf, NetworkTerminalResponse> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public NetworkTerminalResponse decode(FriendlyByteBuf buffer) {
            NetworkSummary.requirePayloadBound(buffer);
            try {
                return switch (buffer.readUnsignedByte()) {
                    case 0 ->
                        new Success(
                                buffer.readUUID(),
                                buffer.readUUID(),
                                buffer.readLong(),
                                NetworkTerminalPage.read(buffer),
                                buffer.readBoolean() ? NetworkSummary.read(buffer) : null);
                    case 1 ->
                        new Failure(
                                buffer.readUUID(),
                                buffer.readBoolean() ? buffer.readUUID() : null,
                                buffer.readLong(),
                                Reason.fromWire(buffer.readUnsignedByte()));
                    default -> throw new DecoderException("Unknown terminal response tag");
                };
            } catch (IllegalArgumentException failure) {
                throw new DecoderException("Invalid terminal response fields", failure);
            }
        }

        @Override
        public void encode(FriendlyByteBuf buffer, NetworkTerminalResponse response) {
            int start = buffer.writerIndex();
            switch (response) {
                case Success success -> {
                    buffer.writeByte(0);
                    buffer.writeUUID(success.viewId());
                    buffer.writeUUID(success.sessionId());
                    buffer.writeLong(success.sequence());
                    success.page().write(buffer);
                    buffer.writeBoolean(success.created() != null);
                    if (success.created() != null) {
                        success.created().write(buffer);
                    }
                }
                case Failure failure -> {
                    buffer.writeByte(1);
                    buffer.writeUUID(failure.viewId());
                    buffer.writeBoolean(failure.sessionId() != null);
                    if (failure.sessionId() != null) {
                        buffer.writeUUID(failure.sessionId());
                    }
                    buffer.writeLong(failure.sequence());
                    buffer.writeByte(failure.reason().wireCode);
                }
            }
            NetworkSummary.requireEncodedBound(buffer, start);
        }
    };

    UUID viewId();

    @Nullable
    UUID sessionId();

    long sequence();

    @Override
    default Type<NetworkTerminalResponse> type() {
        return TYPE;
    }

    record Success(
            UUID viewId,
            UUID sessionId,
            long sequence,
            NetworkTerminalPage page,
            @Nullable NetworkSummary created) implements NetworkTerminalResponse {
        public Success {
            requireEnvelope(viewId, sequence);
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(page, "page");
        }
    }

    record Failure(UUID viewId, @Nullable UUID sessionId, long sequence, Reason reason)
            implements NetworkTerminalResponse {
        public Failure {
            requireEnvelope(viewId, sequence);
            Objects.requireNonNull(reason, "reason");
        }
    }

    enum Reason {
        LOADING(0),
        DATA_UNAVAILABLE(1),
        INVALID_NAME(2),
        NAME_CONFLICT(3),
        QUOTA_REACHED(4),
        INVALID_REQUEST(5),
        SESSION_EXPIRED(6),
        STALE_REQUEST(7),
        INTERNAL_ERROR(8);
        private final int wireCode;

        Reason(int wireCode) {
            this.wireCode = wireCode;
        }
        /** Returns a stable translatable identifier without carrying exception text or changing state. */
        public String translationKey() {
            return "omniresonance.terminal.error." + name().toLowerCase(java.util.Locale.ROOT);
        }

        private static Reason fromWire(int code) {
            return switch (code) {
                case 0 -> LOADING;
                case 1 -> DATA_UNAVAILABLE;
                case 2 -> INVALID_NAME;
                case 3 -> NAME_CONFLICT;
                case 4 -> QUOTA_REACHED;
                case 5 -> INVALID_REQUEST;
                case 6 -> SESSION_EXPIRED;
                case 7 -> STALE_REQUEST;
                case 8 -> INTERNAL_ERROR;
                default -> throw new DecoderException("Unknown terminal failure reason");
            };
        }
    }

    private static void requireEnvelope(UUID viewId, long sequence) {
        Objects.requireNonNull(viewId, "viewId");
        if (sequence < 0) {
            throw new IllegalArgumentException("Negative terminal response sequence");
        }
    }
}
