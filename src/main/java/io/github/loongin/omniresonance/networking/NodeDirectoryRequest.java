// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** Bounded node-view intent; network and permissions are derived from the existing terminal session. */
public record NodeDirectoryRequest(
        UUID view,
        UUID session,
        long generation,
        long sequence,
        Action action,
        String query,
        Status status,
        @Nullable ResourceLocation dimension,
        @Nullable ResourceLocation resource,
        long anchor,
        boolean before,
        @Nullable UUID node,
        int detailOffset,
        long revision,
        String name,
        boolean enabled,
        @Nullable UUID channel)
        implements CustomPacketPayload {
    public enum Action {
        QUERY,
        SELECT,
        RENAME,
        SET_ENABLED,
        EDIT_CONFIGURATION,
        BEGIN_RENAME,
        CANCEL_EDIT,
        CATALOG
    }

    public enum Status {
        ALL,
        ONLINE,
        OFFLINE,
        ERROR,
        DISABLED
    }

    public static final Type<NodeDirectoryRequest> TYPE =
            new Type<>(ResourceLocation.parse("omniresonance:node_directory_request"));
    public static final StreamCodec<FriendlyByteBuf, NodeDirectoryRequest> STREAM_CODEC = new StreamCodec<>() {
        public void encode(FriendlyByteBuf b, NodeDirectoryRequest r) {
            b.writeUUID(r.view)
                    .writeUUID(r.session)
                    .writeLong(r.generation)
                    .writeLong(r.sequence)
                    .writeByte(r.action.ordinal());
            b.writeUtf(r.query, 256).writeByte(r.status.ordinal());
            writeId(b, r.dimension);
            writeId(b, r.resource);
            b.writeLong(r.anchor).writeBoolean(r.before);
            writeUuid(b, r.node);
            b.writeInt(r.detailOffset)
                    .writeLong(r.revision)
                    .writeUtf(r.name, 128)
                    .writeBoolean(r.enabled);
            writeUuid(b, r.channel);
        }

        public NodeDirectoryRequest decode(FriendlyByteBuf b) {
            if (b.readableBytes() > 4096) throw new IllegalArgumentException("Node request too large");
            UUID view = b.readUUID(), session = b.readUUID();
            long generation = b.readLong(), sequence = b.readLong();
            int action = b.readUnsignedByte();
            if (action >= Action.values().length) throw new IllegalArgumentException("Invalid node action");
            String query = b.readUtf(256);
            int status = b.readUnsignedByte();
            if (status >= Status.values().length) throw new IllegalArgumentException("Invalid node filter");
            var r = new NodeDirectoryRequest(
                    view,
                    session,
                    generation,
                    sequence,
                    Action.values()[action],
                    query,
                    Status.values()[status],
                    readId(b),
                    readId(b),
                    b.readLong(),
                    b.readBoolean(),
                    readUuid(b),
                    b.readInt(),
                    b.readLong(),
                    b.readUtf(128),
                    b.readBoolean(),
                    readUuid(b));
            if (b.isReadable()) throw new IllegalArgumentException("Trailing node request");
            return r;
        }
    };

    static void writeId(FriendlyByteBuf b, @Nullable ResourceLocation id) {
        b.writeBoolean(id != null);
        if (id != null) b.writeUtf(id.toString(), 128);
    }

    static @Nullable ResourceLocation readId(FriendlyByteBuf b) {
        return b.readBoolean() ? ResourceLocation.parse(b.readUtf(128)) : null;
    }

    static void writeUuid(FriendlyByteBuf b, @Nullable UUID id) {
        b.writeBoolean(id != null);
        if (id != null) b.writeUUID(id);
    }

    static @Nullable UUID readUuid(FriendlyByteBuf b) {
        return b.readBoolean() ? b.readUUID() : null;
    }

    public NodeDirectoryRequest {
        java.util.Objects.requireNonNull(view);
        java.util.Objects.requireNonNull(session);
        java.util.Objects.requireNonNull(action);
        java.util.Objects.requireNonNull(status);
        if (generation < 1
                || sequence < 1
                || query == null
                || query.length() > 256
                || name == null
                || name.length() > 128
                || anchor < 0
                || detailOffset < 0
                || detailOffset > 262144
                || revision < 0
                || action != Action.QUERY && action != Action.CATALOG && node == null
                || dimension != null && dimension.toString().length() > 128
                || resource != null && resource.toString().length() > 128)
            throw new IllegalArgumentException("Invalid node request");
    }

    public Type<NodeDirectoryRequest> type() {
        return TYPE;
    }
}
