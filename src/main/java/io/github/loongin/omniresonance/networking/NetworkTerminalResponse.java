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
 * Bounded immutable server result; member pages are limited to authorized views and never include exception text. Safe to share across
 * threads; codecs only validate framing/value bounds and never access or modify authoritative state.
 */
public sealed interface NetworkTerminalResponse extends CustomPacketPayload {
    Type<NetworkTerminalResponse> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("omniresonance", "network_terminal_response"));
    StreamCodec<FriendlyByteBuf, NetworkTerminalResponse> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public NetworkTerminalResponse decode(FriendlyByteBuf buffer) {
            FullFilterCodec.requireBodyBound(buffer, TYPE.id());
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
                                Reason.fromWire(buffer.readUnsignedByte()),
                                buffer.readBoolean() ? NetworkTerminalState.read(buffer) : null);
                    case 2 ->
                        new ViewState(
                                buffer.readUUID(),
                                buffer.readUUID(),
                                buffer.readLong(),
                                NetworkTerminalState.read(buffer));
                    case 3 ->
                        new AccessRevoked(buffer.readUUID(), buffer.readUUID(), buffer.readLong(), buffer.readUUID());
                    case 4 ->
                        new NetworkDeleted(buffer.readUUID(), buffer.readUUID(), buffer.readLong(), buffer.readUUID());
                    case 7 ->
                        new FilterLibrary(
                                buffer.readUUID(),
                                buffer.readUUID(),
                                buffer.readLong(),
                                buffer.readUtf(256),
                                FilterPresetPage.read(buffer));
                    case 5 ->
                        new FullRule(
                                buffer.readUUID(),
                                buffer.readUUID(),
                                buffer.readLong(),
                                buffer.readBoolean() ? buffer.readUUID() : null,
                                buffer.readInt(),
                                buffer.readInt(),
                                buffer.readUtf(64),
                                buffer.readByteArray(262144));
                    case 6 ->
                        new RuleTransferReady(
                                buffer.readUUID(),
                                buffer.readUUID(),
                                buffer.readLong(),
                                buffer.readUUID(),
                                buffer.readInt(),
                                buffer.readBoolean(),
                                buffer.readBoolean() ? buffer.readUUID() : null,
                                buffer.readInt(),
                                buffer.readInt());
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
                case FilterLibrary library -> {
                    buffer.writeByte(7);
                    buffer.writeUUID(library.viewId());
                    buffer.writeUUID(library.sessionId());
                    buffer.writeLong(library.sequence());
                    buffer.writeUtf(library.query(), 256);
                    library.page().write(buffer);
                }
                case FullRule rule -> {
                    buffer.writeByte(5);
                    buffer.writeUUID(rule.viewId());
                    buffer.writeUUID(rule.sessionId());
                    buffer.writeLong(rule.sequence());
                    buffer.writeBoolean(rule.sampleToken() != null);
                    if (rule.sampleToken() != null) buffer.writeUUID(rule.sampleToken());
                    buffer.writeInt(rule.tanks());
                    buffer.writeInt(rule.tank());
                    buffer.writeUtf(rule.failure(), 64);
                    buffer.writeByteArray(rule.snapshot());
                }
                case RuleTransferReady ready -> {
                    buffer.writeByte(6);
                    buffer.writeUUID(ready.viewId());
                    buffer.writeUUID(ready.sessionId());
                    buffer.writeLong(ready.sequence());
                    buffer.writeUUID(ready.transferId());
                    buffer.writeInt(ready.length());
                    buffer.writeBoolean(ready.upload());
                    buffer.writeBoolean(ready.sampleToken() != null);
                    if (ready.sampleToken() != null) buffer.writeUUID(ready.sampleToken());
                    buffer.writeInt(ready.tanks());
                    buffer.writeInt(ready.tank());
                }
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
                    buffer.writeBoolean(failure.state() != null);
                    if (failure.state() != null) {
                        NetworkTerminalState.write(buffer, failure.state());
                    }
                }
                case ViewState state -> {
                    buffer.writeByte(2);
                    buffer.writeUUID(state.viewId());
                    buffer.writeUUID(state.sessionId());
                    buffer.writeLong(state.sequence());
                    NetworkTerminalState.write(buffer, state.state());
                }
                case AccessRevoked revoked -> {
                    buffer.writeByte(3);
                    buffer.writeUUID(revoked.viewId());
                    buffer.writeUUID(revoked.sessionId());
                    buffer.writeLong(revoked.sequence());
                    buffer.writeUUID(revoked.networkId());
                }
                case NetworkDeleted deleted -> {
                    buffer.writeByte(4);
                    buffer.writeUUID(deleted.viewId());
                    buffer.writeUUID(deleted.sessionId());
                    buffer.writeLong(deleted.sequence());
                    buffer.writeUUID(deleted.networkId());
                }
            }
            FullFilterCodec.requireEncodedBodyBound(buffer, start, TYPE.id());
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

    record ViewState(UUID viewId, UUID sessionId, long sequence, NetworkTerminalState state)
            implements NetworkTerminalResponse {
        public ViewState {
            requireEnvelope(viewId, sequence);
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(state, "state");
        }
    }

    record Failure(
            UUID viewId,
            @Nullable UUID sessionId,
            long sequence,
            Reason reason,
            @Nullable NetworkTerminalState state) implements NetworkTerminalResponse {
        public Failure {
            requireEnvelope(viewId, sequence);
            Objects.requireNonNull(reason, "reason");
        }

        public Failure(UUID viewId, @Nullable UUID sessionId, long sequence, Reason reason) {
            this(viewId, sessionId, sequence, reason, null);
        }
    }

    /** Unsolicited closure for one exact active view/session, independent of a pending request sequence. */
    record AccessRevoked(UUID viewId, UUID sessionId, long sequence, UUID networkId)
            implements NetworkTerminalResponse {
        public AccessRevoked {
            requireEnvelope(viewId, sequence);
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(networkId, "networkId");
        }
    }

    /** Unsolicited closure for one exact view/session whose selected network was deleted. */
    record NetworkDeleted(UUID viewId, UUID sessionId, long sequence, UUID networkId)
            implements NetworkTerminalResponse {
        public NetworkDeleted {
            requireEnvelope(viewId, sequence);
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(networkId, "networkId");
        }
    }

    record FilterLibrary(UUID viewId, UUID sessionId, long sequence, String query, FilterPresetPage page)
            implements NetworkTerminalResponse {
        public FilterLibrary {
            requireEnvelope(viewId, sequence);
            Objects.requireNonNull(sessionId);
            Objects.requireNonNull(query);
            Objects.requireNonNull(page);
        }
    }

    record FullRule(
            UUID viewId,
            UUID sessionId,
            long sequence,
            @Nullable UUID sampleToken,
            int tanks,
            int tank,
            String failure,
            byte[] snapshot)
            implements NetworkTerminalResponse {
        public FullRule {
            requireEnvelope(viewId, sequence);
            Objects.requireNonNull(sessionId);
            Objects.requireNonNull(failure);
            if (snapshot.length > ManagementTransferPool.MAXIMUM_OBJECT_BYTES
                    || failure.length() > 64
                    || tanks < 0
                    || tank < 0) throw new IllegalArgumentException("Invalid full rule result");
            snapshot = snapshot.clone();
        }

        @Override
        public byte[] snapshot() {
            return snapshot.clone();
        }
    }

    record RuleTransferReady(
            UUID viewId,
            UUID sessionId,
            long sequence,
            UUID transferId,
            int length,
            boolean upload,
            @Nullable UUID sampleToken,
            int tanks,
            int tank)
            implements NetworkTerminalResponse {
        public RuleTransferReady {
            requireEnvelope(viewId, sequence);
            Objects.requireNonNull(sessionId);
            Objects.requireNonNull(transferId);
            if (length <= 0 || length > ManagementTransferPool.MAXIMUM_OBJECT_BYTES || tanks < 0 || tank < 0)
                throw new IllegalArgumentException("Invalid full rule transfer");
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
        INTERNAL_ERROR(8),
        NO_ACCESS(9),
        LOCKED(10),
        LOCK_EXPIRED(11),
        STALE_REVISION(12),
        TUNNEL_DISABLED(13),
        RESET_REQUIRED(14),
        PLAYER_OFFLINE(15),
        ALREADY_ADMINISTRATOR(16),
        NOT_ADMINISTRATOR(17),
        HAS_NODES(18),
        STORAGE_UNVERIFIED(19),
        HAS_EXCHANGES(20);
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
                case 9 -> NO_ACCESS;
                case 10 -> LOCKED;
                case 11 -> LOCK_EXPIRED;
                case 12 -> STALE_REVISION;
                case 13 -> TUNNEL_DISABLED;
                case 14 -> RESET_REQUIRED;
                case 15 -> PLAYER_OFFLINE;
                case 16 -> ALREADY_ADMINISTRATOR;
                case 17 -> NOT_ADMINISTRATOR;
                case 18 -> HAS_NODES;
                case 19 -> STORAGE_UNVERIFIED;
                case 20 -> HAS_EXCHANGES;
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
