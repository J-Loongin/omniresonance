// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.netty.handler.codec.DecoderException;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** Bounded immutable authoritative node-Menu state or stable failure without exception/private membership data. */
public sealed interface NodeMenuResponse extends CustomPacketPayload {
    Type<NodeMenuResponse> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("omniresonance", "node_menu_response"));
    StreamCodec<FriendlyByteBuf, NodeMenuResponse> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public NodeMenuResponse decode(FriendlyByteBuf buffer) {
            NodeMenuCodecSupport.requirePayloadBound(buffer);
            try {
                int tag = buffer.readUnsignedByte();
                if (tag > 1) {
                    throw new DecoderException("Unknown node-menu response");
                }
                int containerId = buffer.readVarInt();
                UUID sessionId = buffer.readUUID();
                long sequence = buffer.readLong();
                return switch (tag) {
                    case 0 -> new State(containerId, sessionId, sequence, NodeMenuState.read(buffer));
                    case 1 ->
                        new Failure(
                                containerId,
                                sessionId,
                                sequence,
                                Reason.fromWire(buffer.readUnsignedByte()),
                                buffer.readBoolean() ? NodeMenuState.read(buffer) : null);
                    default -> throw new DecoderException("Unknown node-menu response");
                };
            } catch (DecoderException failure) {
                throw failure;
            } catch (IllegalArgumentException | IndexOutOfBoundsException failure) {
                throw new DecoderException("Invalid node-menu response", failure);
            }
        }

        @Override
        public void encode(FriendlyByteBuf buffer, NodeMenuResponse response) {
            int start = buffer.writerIndex();
            if (response instanceof State state) {
                writeEnvelope(buffer, 0, state);
                NodeMenuState.write(buffer, state.state());
            } else {
                Failure failure = (Failure) response;
                writeEnvelope(buffer, 1, failure);
                buffer.writeByte(failure.reason().wireCode);
                buffer.writeBoolean(failure.state() != null);
                if (failure.state() != null) {
                    NodeMenuState.write(buffer, failure.state());
                }
            }
            NodeMenuCodecSupport.requireEncodedBound(buffer, start);
        }
    };

    int containerId();

    UUID sessionId();

    long sequence();

    @Override
    default Type<NodeMenuResponse> type() {
        return TYPE;
    }

    record State(int containerId, UUID sessionId, long sequence, NodeMenuState state) implements NodeMenuResponse {
        public State {
            requireEnvelope(containerId, sessionId, sequence);
            Objects.requireNonNull(state, "state");
        }
    }

    record Failure(
            int containerId,
            UUID sessionId,
            long sequence,
            Reason reason,
            @Nullable NodeMenuState state) implements NodeMenuResponse {
        public Failure {
            requireEnvelope(containerId, sessionId, sequence);
            Objects.requireNonNull(reason, "reason");
        }
    }

    public enum Reason {
        NO_ACCESS(0),
        UNAVAILABLE(1),
        OUT_OF_RANGE(2),
        LOCKED(3),
        LOCK_EXPIRED(4),
        STALE_REVISION(5),
        INVALID_NAME(6),
        NAME_CONFLICT(7),
        NODE_DISABLED(8),
        RESET_REQUIRED(9),
        INVALID_REQUEST(10),
        STALE_REQUEST(11),
        INTERNAL_ERROR(12),
        QUOTA_REACHED(13),
        TUNNEL_DISABLED(14),
        LAST_CHANNEL(15),
        TUNNEL_SWITCH_REQUIRED(16);

        private final int wireCode;

        Reason(int wireCode) {
            this.wireCode = wireCode;
        }

        /** Returns the stable bilingual language key without carrying server exception text. */
        public String translationKey() {
            return "omniresonance.node_menu.error." + name().toLowerCase(Locale.ROOT);
        }

        private static Reason fromWire(int code) {
            return switch (code) {
                case 0 -> NO_ACCESS;
                case 1 -> UNAVAILABLE;
                case 2 -> OUT_OF_RANGE;
                case 3 -> LOCKED;
                case 4 -> LOCK_EXPIRED;
                case 5 -> STALE_REVISION;
                case 6 -> INVALID_NAME;
                case 7 -> NAME_CONFLICT;
                case 8 -> NODE_DISABLED;
                case 9 -> RESET_REQUIRED;
                case 10 -> INVALID_REQUEST;
                case 11 -> STALE_REQUEST;
                case 12 -> INTERNAL_ERROR;
                case 13 -> QUOTA_REACHED;
                case 14 -> TUNNEL_DISABLED;
                case 15 -> LAST_CHANNEL;
                case 16 -> TUNNEL_SWITCH_REQUIRED;
                default -> throw new DecoderException("Unknown node-menu failure reason");
            };
        }
    }

    private static void writeEnvelope(FriendlyByteBuf buffer, int tag, NodeMenuResponse response) {
        buffer.writeByte(tag);
        buffer.writeVarInt(response.containerId());
        buffer.writeUUID(response.sessionId());
        buffer.writeLong(response.sequence());
    }

    private static void requireEnvelope(int containerId, UUID sessionId, long sequence) {
        if (containerId < 0 || sequence < 0) {
            throw new IllegalArgumentException("Invalid node-menu response envelope");
        }
        Objects.requireNonNull(sessionId, "sessionId");
    }
}
