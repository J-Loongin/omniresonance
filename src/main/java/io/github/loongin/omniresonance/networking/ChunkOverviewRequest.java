// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Bounded overview intent; the server derives the network from the authenticated terminal session. */
public record ChunkOverviewRequest(
        UUID view,
        UUID session,
        long generation,
        long sequence,
        Action action,
        long anchor,
        boolean before,
        @org.jetbrains.annotations.Nullable UUID node)
        implements CustomPacketPayload {
    public enum Action {
        OPEN,
        PAGE,
        CLOSE,
        HIGHLIGHT,
        TELEPORT,
        OPEN_NODES
    }

    public static final Type<ChunkOverviewRequest> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("omniresonance", "chunk_overview_request"));
    public static final StreamCodec<FriendlyByteBuf, ChunkOverviewRequest> STREAM_CODEC = new StreamCodec<>() {
        public void encode(FriendlyByteBuf b, ChunkOverviewRequest v) {
            b.writeUUID(v.view)
                    .writeUUID(v.session)
                    .writeLong(v.generation)
                    .writeLong(v.sequence)
                    .writeByte(v.action.ordinal() < 2 ? v.action.ordinal() : v.action.ordinal() + 1)
                    .writeLong(v.anchor)
                    .writeBoolean(v.before);
            if (v.node != null) b.writeUUID(v.node);
        }

        public ChunkOverviewRequest decode(FriendlyByteBuf b) {
            if (b.readableBytes() != 58 && b.readableBytes() != 74)
                throw new IllegalArgumentException("Invalid overview request length");
            UUID view = b.readUUID(), session = b.readUUID();
            long generation = b.readLong(), sequence = b.readLong();
            int action = b.readUnsignedByte();
            if (action != 0 && action != 1 && action != 3 && action != 4 && action != 5 && action != 6)
                throw new IllegalArgumentException("Unknown overview action");
            var result = new ChunkOverviewRequest(
                    view,
                    session,
                    generation,
                    sequence,
                    Action.values()[action < 2 ? action : action - 1],
                    b.readLong(),
                    b.readBoolean(),
                    action == 4 || action == 5 ? b.readUUID() : null);
            if (b.isReadable()) throw new IllegalArgumentException("Trailing overview request");
            return result;
        }
    };

    public ChunkOverviewRequest(
            UUID view, UUID session, long generation, long sequence, Action action, long anchor, boolean before) {
        this(view, session, generation, sequence, action, anchor, before, null);
    }

    public ChunkOverviewRequest {
        Objects.requireNonNull(view);
        Objects.requireNonNull(session);
        Objects.requireNonNull(action);
        if (generation <= 0
                || sequence <= 0
                || anchor < 0
                || ((action == Action.HIGHLIGHT || action == Action.TELEPORT) != (node != null)))
            throw new IllegalArgumentException("Invalid overview intent");
    }

    public Type<ChunkOverviewRequest> type() {
        return TYPE;
    }
}
