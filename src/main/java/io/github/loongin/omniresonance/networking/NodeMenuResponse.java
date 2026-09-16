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
            NodeMenuCodecSupport.requirePayloadBound(buffer, TYPE.id());
            try {
                int tag = buffer.readUnsignedByte();
                if (tag > 4) {
                    throw new DecoderException("Unknown node-menu response");
                }
                int containerId = buffer.readVarInt();
                UUID sessionId = buffer.readUUID();
                long sequence = buffer.readLong();
                return switch (tag) {
                    case 3 -> new UploadReady(containerId, sessionId, sequence, buffer.readUUID());
                    case 4 ->
                        new Download(
                                containerId,
                                sessionId,
                                sequence,
                                readPolicyMetadata(buffer),
                                buffer.readUUID(),
                                buffer.readInt());
                    case 2 -> new Catalog(containerId, sessionId, sequence, ResourceTypeCatalogPage.read(buffer));
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
            if (response instanceof UploadReady ready) {
                writeEnvelope(buffer, 3, ready);
                buffer.writeUUID(ready.transfer());
            } else if (response instanceof Download download) {
                writeEnvelope(buffer, 4, download);
                NodeMenuState.write(buffer, download.metadata());
                buffer.writeUUID(download.transfer());
                buffer.writeInt(download.length());
            } else if (response instanceof Catalog catalog) {
                writeEnvelope(buffer, 2, catalog);
                ResourceTypeCatalogPage.write(buffer, catalog.page());
            } else if (response instanceof State state) {
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
            NodeMenuCodecSupport.requireEncodedBound(buffer, start, TYPE.id());
        }
    };

    int containerId();

    UUID sessionId();

    long sequence();

    @Override
    default Type<NodeMenuResponse> type() {
        return TYPE;
    }

    record UploadReady(int containerId, UUID sessionId, long sequence, UUID transfer) implements NodeMenuResponse {
        public UploadReady {
            requireEnvelope(containerId, sessionId, sequence);
            Objects.requireNonNull(transfer);
        }
    }

    record Download(
            int containerId,
            UUID sessionId,
            long sequence,
            NodeMenuState.ResourceEdit metadata,
            UUID transfer,
            int length)
            implements NodeMenuResponse {
        public Download {
            requireEnvelope(containerId, sessionId, sequence);
            Objects.requireNonNull(metadata);
            Objects.requireNonNull(transfer);
            if (metadata.policy() != null || length <= 0 || length > ManagementTransferPool.MAXIMUM_OBJECT_BYTES)
                throw new IllegalArgumentException("Invalid download metadata");
        }
    }

    record Catalog(int containerId, UUID sessionId, long sequence, ResourceTypeCatalogPage page)
            implements NodeMenuResponse {
        public Catalog {
            requireEnvelope(containerId, sessionId, sequence);
            Objects.requireNonNull(page);
        }
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
        TUNNEL_SWITCH_REQUIRED(16),
        CHUNK_OWNER_LIMIT(17),
        CHUNK_SERVER_LIMIT(18),
        CHUNK_DISABLED(19);

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
                case 17 -> CHUNK_OWNER_LIMIT;
                case 18 -> CHUNK_SERVER_LIMIT;
                case 19 -> CHUNK_DISABLED;
                default -> throw new DecoderException("Unknown node-menu failure reason");
            };
        }
    }

    private static NodeMenuState.ResourceEdit readPolicyMetadata(FriendlyByteBuf buffer) {
        NodeMenuState state = NodeMenuState.read(buffer);
        if (!(state instanceof NodeMenuState.ResourceEdit edit))
            throw new DecoderException("Expected node policy metadata");
        return edit;
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
