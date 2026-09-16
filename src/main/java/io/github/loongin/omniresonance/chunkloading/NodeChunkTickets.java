// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.chunkloading;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

/**
 * Server-session native ticket owner. Acquisition requires a desired physical quota grant and never waits for
 * a chunk or persists forced state. Identity validation remains the runtime caller's responsibility after load.
 * The map holds only this server's ticketed levels, bounded by allocator grants; release/close relinquish this
 * private type only. All methods require the server thread; no simulation or resource transfer occurs.
 */
public final class NodeChunkTickets implements AutoCloseable {
    private final TicketType<ChunkPos> type =
            TicketType.create("omniresonance:node_chunk_loading", Comparator.comparingLong(ChunkPos::toLong));
    private final MinecraftServer server;
    private final ChunkLoadingAllocator allocator;
    private final Map<ChunkLoadingAllocator.Chunk, ServerLevel> held = new HashMap<>();
    private boolean closed;

    public NodeChunkTickets(MinecraftServer server, ChunkLoadingAllocator allocator) {
        this.server = Objects.requireNonNull(server);
        this.allocator = Objects.requireNonNull(allocator);
        check();
    }
    /** Issues one idempotent, nonblocking ticking ticket. Absent dimensions return false; no grant rejects before native access. */
    public boolean acquire(ChunkLoadingAllocator.Chunk key) {
        check();
        if (closed) throw new IllegalStateException("Ticket owner is closed");
        if (!allocator.physicalChunks().contains(key))
            throw new IllegalStateException("Physical chunk has no quota grant");
        if (held.containsKey(key)) return true;
        var level = server.getLevel(ResourceKey.create(Registries.DIMENSION, key.dimension()));
        if (level == null) return false;
        held.put(key, level);
        try {
            level.getChunkSource()
                    .addRegionTicket(type, new ChunkPos(key.x(), key.z()), 2, new ChunkPos(key.x(), key.z()), true);
        } catch (RuntimeException failure) {
            try {
                release(key);
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        return true;
    }
    /** Idempotent removal; a throwing native cleanup remains tracked for explicit shutdown cleanup. */
    public void release(ChunkLoadingAllocator.Chunk key) {
        check();
        var level = held.get(key);
        if (level == null) return;
        var pos = new ChunkPos(key.x(), key.z());
        level.getChunkSource().removeRegionTicket(type, pos, 2, pos, true);
        held.remove(key);
    }

    public int size() {
        check();
        return held.size();
    }

    @Override
    public void close() {
        check();
        closed = true;
        RuntimeException failure = null;
        var iterator = held.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var pos = new ChunkPos(entry.getKey().x(), entry.getKey().z());
            try {
                entry.getValue().getChunkSource().removeRegionTicket(type, pos, 2, pos, true);
                iterator.remove();
            } catch (RuntimeException cleanup) {
                if (failure == null) failure = cleanup;
                else failure.addSuppressed(cleanup);
            }
        }
        if (failure != null) throw failure;
    }

    private void check() {
        if (!server.isSameThread()) throw new IllegalStateException("Chunk tickets accessed off server thread");
    }
}
