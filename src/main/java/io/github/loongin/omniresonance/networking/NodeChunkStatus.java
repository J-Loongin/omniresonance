// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.github.loongin.omniresonance.chunkloading.ChunkLoadingAllocator;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Small read-only status, scoped to an authorized physical menu and exact node revision; never changes edit state. */
public record NodeChunkStatus(
        int containerId, UUID sessionId, UUID nodeId, long revision, ChunkLoadingAllocator.Status status)
        implements CustomPacketPayload {
    public static final Type<NodeChunkStatus> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("omniresonance", "node_chunk_status"));
    public static final StreamCodec<FriendlyByteBuf, NodeChunkStatus> STREAM_CODEC = new StreamCodec<>() {
        public void encode(FriendlyByteBuf b, NodeChunkStatus value) {
            b.writeInt(value.containerId())
                    .writeUUID(value.sessionId())
                    .writeUUID(value.nodeId())
                    .writeLong(value.revision())
                    .writeByte(value.status().ordinal());
        }

        public NodeChunkStatus decode(FriendlyByteBuf b) {
            if (b.readableBytes() != 45) throw new IllegalArgumentException("Invalid chunk status length");
            int container = b.readInt();
            UUID session = b.readUUID(), node = b.readUUID();
            long revision = b.readLong();
            int status = b.readUnsignedByte();
            if (status >= ChunkLoadingAllocator.Status.values().length)
                throw new IllegalArgumentException("Unknown chunk status");
            return new NodeChunkStatus(
                    container, session, node, revision, ChunkLoadingAllocator.Status.values()[status]);
        }
    };

    public NodeChunkStatus {
        Objects.requireNonNull(sessionId);
        Objects.requireNonNull(nodeId);
        Objects.requireNonNull(status);
        if (containerId < 0 || revision < 0) throw new IllegalArgumentException("Invalid chunk status identity");
    }

    public Type<NodeChunkStatus> type() {
        return TYPE;
    }
}
