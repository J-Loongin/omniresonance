// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.github.loongin.omniresonance.chunkloading.ChunkLoadingAllocator;
import net.minecraft.network.FriendlyByteBuf;

/** Authorized selected-node loading state and quota snapshot, never a mutation intent. */
public record NodeChunkLoadingInfo(
        ChunkLoadingAllocator.Status status, int ownerUsed, int ownerLimit, int serverUsed, int serverLimit) {
    public NodeChunkLoadingInfo {
        if (status == null || ownerUsed < 0 || serverUsed < 0 || ownerLimit < -1 || serverLimit < -1)
            throw new IllegalArgumentException("Invalid loading info");
    }

    void write(FriendlyByteBuf buffer) {
        buffer.writeByte(status.ordinal())
                .writeInt(ownerUsed)
                .writeInt(ownerLimit)
                .writeInt(serverUsed)
                .writeInt(serverLimit);
    }

    static NodeChunkLoadingInfo read(FriendlyByteBuf buffer) {
        int status = buffer.readUnsignedByte();
        if (status >= ChunkLoadingAllocator.Status.values().length)
            throw new IllegalArgumentException("Invalid loading state");
        return new NodeChunkLoadingInfo(
                ChunkLoadingAllocator.Status.values()[status],
                buffer.readInt(),
                buffer.readInt(),
                buffer.readInt(),
                buffer.readInt());
    }
}
