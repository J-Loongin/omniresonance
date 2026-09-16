// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.github.loongin.omniresonance.chunkloading.ChunkLoadingAllocator;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** One authorized bounded node window and aggregate quotas; periodic updates retain the current request sequence. */
public record ChunkOverviewPage(
        UUID session,
        long generation,
        long sequence,
        boolean available,
        boolean operationRejected,
        boolean enabled,
        int ownerUsed,
        int ownerLimit,
        int serverUsed,
        int serverLimit,
        int total,
        boolean previous,
        boolean next,
        List<Entry> entries)
        implements CustomPacketPayload {
    public record Entry(
            UUID id,
            long number,
            long revision,
            String name,
            ResourceLocation dimension,
            BlockPos position,
            io.github.loongin.omniresonance.node.NodeMode mode,
            boolean enabled,
            boolean requested,
            ChunkLoadingAllocator.Status status) {
        public Entry {
            Objects.requireNonNull(id);
            Objects.requireNonNull(name);
            Objects.requireNonNull(dimension);
            Objects.requireNonNull(position);
            Objects.requireNonNull(status);
            Objects.requireNonNull(mode);
            if (number < 1
                    || revision < 0
                    || !new io.github.loongin.omniresonance.network.ManagedName(name)
                            .value()
                            .equals(name)
                    || dimension.toString().length() > 128)
                throw new IllegalArgumentException("Invalid overview entry");
        }
    }

    public static final Type<ChunkOverviewPage> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("omniresonance", "chunk_overview_page"));
    public static final StreamCodec<FriendlyByteBuf, ChunkOverviewPage> STREAM_CODEC = new StreamCodec<>() {
        public void encode(FriendlyByteBuf b, ChunkOverviewPage p) {
            b.writeUUID(p.session)
                    .writeLong(p.generation)
                    .writeLong(p.sequence)
                    .writeBoolean(p.available)
                    .writeBoolean(p.operationRejected)
                    .writeBoolean(p.enabled)
                    .writeInt(p.ownerUsed)
                    .writeInt(p.ownerLimit)
                    .writeInt(p.serverUsed)
                    .writeInt(p.serverLimit)
                    .writeInt(p.total)
                    .writeBoolean(p.previous)
                    .writeBoolean(p.next)
                    .writeInt(p.entries.size());
            for (var e : p.entries) {
                b.writeUUID(e.id).writeLong(e.number).writeLong(e.revision);
                NetworkSummary.writeName(b, e.name);
                b.writeUtf(e.dimension.toString(), 128)
                        .writeBlockPos(e.position)
                        .writeByte(e.mode.ordinal())
                        .writeBoolean(e.enabled)
                        .writeBoolean(e.requested)
                        .writeByte(e.status.ordinal());
            }
        }

        public ChunkOverviewPage decode(FriendlyByteBuf b) {
            if (b.readableBytes() > 65536) throw new IllegalArgumentException("Overview page too large");
            UUID session = b.readUUID();
            long generation = b.readLong(), sequence = b.readLong();
            boolean available = b.readBoolean(), rejected = b.readBoolean(), enabled = b.readBoolean();
            int ownerUsed = b.readInt(),
                    ownerLimit = b.readInt(),
                    serverUsed = b.readInt(),
                    serverLimit = b.readInt(),
                    total = b.readInt();
            boolean previous = b.readBoolean(), next = b.readBoolean();
            int size = b.readInt();
            if (size < 0 || size > 64) throw new IllegalArgumentException("Invalid overview window");
            var entries = new ArrayList<Entry>(size);
            for (int i = 0; i < size; i++) {
                UUID id = b.readUUID();
                long number = b.readLong(), revision = b.readLong();
                String name = NetworkSummary.readName(b);
                var dimension = ResourceLocation.parse(b.readUtf(128));
                var pos = b.readBlockPos();
                int mode = b.readUnsignedByte();
                if (mode >= io.github.loongin.omniresonance.node.NodeMode.values().length)
                    throw new IllegalArgumentException("Unknown node mode");
                boolean active = b.readBoolean(), requested = b.readBoolean();
                int state = b.readUnsignedByte();
                if (state >= ChunkLoadingAllocator.Status.values().length)
                    throw new IllegalArgumentException("Unknown chunk state");
                entries.add(new Entry(
                        id,
                        number,
                        revision,
                        name,
                        dimension,
                        pos,
                        io.github.loongin.omniresonance.node.NodeMode.values()[mode],
                        active,
                        requested,
                        ChunkLoadingAllocator.Status.values()[state]));
            }
            if (b.isReadable()) throw new IllegalArgumentException("Trailing overview bytes");
            return new ChunkOverviewPage(
                    session,
                    generation,
                    sequence,
                    available,
                    rejected,
                    enabled,
                    ownerUsed,
                    ownerLimit,
                    serverUsed,
                    serverLimit,
                    total,
                    previous,
                    next,
                    entries);
        }
    };

    public ChunkOverviewPage {
        Objects.requireNonNull(session);
        entries = List.copyOf(entries);
        if (generation <= 0
                || sequence <= 0
                || ownerUsed < 0
                || serverUsed < 0
                || ownerLimit < -1
                || serverLimit < -1
                || total < 0
                || entries.size() > 64) throw new IllegalArgumentException("Invalid overview page");
    }

    public Type<ChunkOverviewPage> type() {
        return TYPE;
    }
}
