// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import io.github.loongin.omniresonance.node.AbstractResonanceNodeBlock;
import io.github.loongin.omniresonance.node.NetworkNodeRecord;
import io.github.loongin.omniresonance.node.NodeForm;
import io.github.loongin.omniresonance.node.NodeGeometry;
import io.github.loongin.omniresonance.node.NodeLinkState;
import io.github.loongin.omniresonance.node.ResonanceNodeBlockEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

/**
 * Server-session-owned item capability cache. Key: node UUID plus its exact physical target/face; at most six
 * entries per configured loaded block node, one per panel. Node/target chunk indices clear entries on unload. Configuration changes,
 * removal and stop retire handles. Native invalidation only changes an epoch and enqueues the immutable node ID.
 */
public final class ItemEndpointCache implements AutoCloseable {
    private final MinecraftServer server;
    private final Consumer<UUID> wakeup;
    private final Map<EndpointKey, Entry> entries = new HashMap<>();
    private final Map<ChunkKey, Set<EndpointKey>> byChunk = new HashMap<>();
    private boolean closed;

    public ItemEndpointCache(MinecraftServer server, Consumer<UUID> wakeup) {
        this.server = server;
        this.wakeup = wakeup;
    }

    public @Nullable ItemTransferEngine.Handle resolve(NetworkNodeRecord node, TransferWorkBudget budget) {
        return resolve(node, node.facing(), budget);
    }

    /** Resolves only a selected adjacent face; caller owns selection validation and this method validates physical form. */
    public @Nullable ItemTransferEngine.Handle resolve(
            NetworkNodeRecord node, Direction face, TransferWorkBudget budget) {
        requireThread();
        if (node.form() == NodeForm.PANEL && face != node.facing()) return null;
        if (closed || !physical(node)) {
            remove(node.nodeId());
            return null;
        }
        EndpointKey key = new EndpointKey(node.nodeId(), face);
        if (!targetLoaded(node, face)) {
            remove(key);
            return null;
        }
        Entry entry = entries.get(key);
        if (entry != null
                && (!entry.node.position().equals(node.position())
                        || entry.node.facing() != node.facing()
                        || entry.node.form() != node.form())) {
            remove(node.nodeId());
            entry = null;
        }
        if (entry == null) {
            ServerLevel level = server.getLevel(node.position().dimension());
            entry = new Entry(node, face, level);
            entries.put(key, entry);
            index(key, entry.nodeChunk);
            index(key, entry.targetChunk);
        }
        if (entry.resolved) return entry.handle;
        budget.beforeCall();
        IItemHandler handler;
        try {
            handler = entry.cache.getCapability();
        } finally {
            budget.afterCall();
        }
        entry.resolved = true;
        entry.handle = handler == null ? null : new Handle(entry, handler, entry.epoch);
        return entry.handle;
    }
    /** Exact physical validation without capability lookup or chunk loading. */
    public boolean physical(NetworkNodeRecord node) {
        requireThread();
        ServerLevel level = server.getLevel(node.position().dimension());
        if (closed || level == null || !level.isLoaded(node.position().pos())) return false;
        if (!(level.getBlockEntity(node.position().pos()) instanceof ResonanceNodeBlockEntity entity)
                || entity.isRemoved()) return false;
        var local = entity.state().orElse(null);
        var state = entity.getBlockState();
        return local != null
                && local.linkState() == NodeLinkState.LINKED
                && local.nodeId().equals(node.nodeId())
                && state.getBlock() instanceof AbstractResonanceNodeBlock block
                && block.form() == node.form()
                && state.getValue(AbstractResonanceNodeBlock.FACING) == node.facing();
    }

    public void remove(UUID nodeId) {
        requireThread();
        for (Direction face : Direction.values()) remove(new EndpointKey(nodeId, face));
    }

    private void remove(EndpointKey key) {
        Entry e = entries.remove(key);
        if (e == null) return;
        e.alive = false;
        e.handle = null;
        unindex(key, e.nodeChunk);
        unindex(key, e.targetChunk);
    }

    public void unload(ResourceKey<Level> dimension, ChunkPos chunk) {
        requireThread();
        Set<EndpointKey> ids = byChunk.get(new ChunkKey(dimension, chunk));
        if (ids == null) return;
        for (EndpointKey id : new ArrayList<>(ids)) {
            remove(id);
            wakeup.accept(id.nodeId());
        }
    }

    public int size() {
        requireThread();
        return entries.size();
    }

    public void close() {
        requireThread();
        closed = true;
        for (Entry e : entries.values()) {
            e.alive = false;
            e.handle = null;
        }
        entries.clear();
        byChunk.clear();
    }

    private void index(EndpointKey id, ChunkKey key) {
        byChunk.computeIfAbsent(key, ignored -> new HashSet<>()).add(id);
    }

    private void unindex(EndpointKey id, ChunkKey key) {
        Set<EndpointKey> ids = byChunk.get(key);
        if (ids != null) {
            ids.remove(id);
            if (ids.isEmpty()) byChunk.remove(key);
        }
    }

    private void requireThread() {
        if (!server.isSameThread()) throw new IllegalStateException("Item endpoint cache accessed off server thread");
    }

    private boolean targetLoaded(NetworkNodeRecord node, Direction face) {
        ServerLevel level = server.getLevel(node.position().dimension());
        return level != null
                && level.isLoaded(NodeGeometry.targetPosition(node.position().pos(), face));
    }

    private record EndpointKey(UUID nodeId, Direction face) {}

    private record ChunkKey(ResourceKey<Level> dimension, ChunkPos chunk) {}

    private final class Entry {
        final NetworkNodeRecord node;
        final Direction face;
        final net.minecraft.core.GlobalPos physicalIdentity;
        final BlockCapabilityCache<IItemHandler, Direction> cache;
        final ChunkKey nodeChunk, targetChunk;
        boolean alive = true, resolved;
        long epoch;

        @Nullable
        Handle handle;

        Entry(NetworkNodeRecord node, Direction face, ServerLevel level) {
            this.node = node;
            this.face = face;
            BlockPos target = NodeGeometry.targetPosition(node.position().pos(), face);
            physicalIdentity = net.minecraft.core.GlobalPos.of(node.position().dimension(), target);
            nodeChunk = new ChunkKey(
                    node.position().dimension(), new ChunkPos(node.position().pos()));
            targetChunk = new ChunkKey(node.position().dimension(), new ChunkPos(target));
            cache = BlockCapabilityCache.create(
                    Capabilities.ItemHandler.BLOCK, level, target, NodeGeometry.targetSide(face), () -> alive, () -> {
                        epoch++;
                        resolved = false;
                        handle = null;
                        wakeup.accept(node.nodeId());
                    });
        }
    }

    private final class Handle implements ItemTransferEngine.Handle {
        final Entry entry;
        final IItemHandler handler;
        final long epoch;

        Handle(Entry entry, IItemHandler handler, long epoch) {
            this.entry = entry;
            this.handler = handler;
            this.epoch = epoch;
        }

        public IItemHandler handler() {
            return handler;
        }

        public Object physicalIdentity() {
            return entry.physicalIdentity;
        }

        public boolean valid() {
            return entry.alive && entry.epoch == epoch && physical(entry.node) && targetLoaded(entry.node, entry.face);
        }
    }
}
