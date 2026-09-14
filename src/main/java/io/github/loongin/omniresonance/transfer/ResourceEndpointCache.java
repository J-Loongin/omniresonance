// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import io.github.loongin.omniresonance.node.AbstractResonanceNodeBlock;
import io.github.loongin.omniresonance.node.NetworkNodeRecord;
import io.github.loongin.omniresonance.node.NodeForm;
import io.github.loongin.omniresonance.node.NodeGeometry;
import io.github.loongin.omniresonance.node.NodeLinkState;
import io.github.loongin.omniresonance.node.ResonanceNodeBlockEntity;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Server-session-owned, server-thread-only endpoint cache, explicitly bounded by maximumEntries.
 * Keys are node UUID, selected face and registered resource type. Only requested endpoints are retained;
 * misses are cached until native invalidation. Physical moves/forms/facings retire all entries of that node.
 * Node/chunk indices and the deduplicated invalidation queue are bounded by actual retained entries.
 * Removal/unload/close retire listeners via alive=false; NeoForge removes them on invalidation or GC.
 * Callers own permissions/configuration validation and lifecycle hooks; no authoritative state is mutated.
 */
public final class ResourceEndpointCache implements AutoCloseable {
    private final MinecraftServer server;
    private final ResourceAdapterDirectory directory;
    private final int maximumEntries;
    private final Map<EndpointKey, Entry> entries = new HashMap<>();
    private final Map<UUID, Set<EndpointKey>> byNode = new HashMap<>();
    private final Map<ChunkKey, Set<EndpointKey>> byChunk = new HashMap<>();
    private final Set<UUID> invalidatedNodes = new LinkedHashSet<>();
    private boolean closed;

    public ResourceEndpointCache(MinecraftServer server, ResourceAdapterDirectory directory, int maximumEntries) {
        this.server = Objects.requireNonNull(server);
        this.directory = Objects.requireNonNull(directory);
        directory.types();
        if (maximumEntries <= 0) throw new IllegalArgumentException("Invalid endpoint capacity");
        this.maximumEntries = maximumEntries;
        requireThread();
    }

    /**
     * Resolves only the authorized requested face/type, without force-loading. Physical rejection, unknown
     * type, capacity exhaustion or absent capability returns null. Discovery counts once in the budget,
     * including failures, which propagate without automatic retry. Cached reads perform zero native calls.
     * Caller admits discovery against its tick budget. Returned port and physical identity are borrowed.
     */
    public @Nullable ResourceTransferEngine.Handle resolve(
            NetworkNodeRecord node, Direction face, ResourceLocation typeId, TransferWorkBudget budget) {
        requireThread();
        Objects.requireNonNull(face);
        Objects.requireNonNull(typeId);
        Objects.requireNonNull(budget);
        if (closed) return null;
        if (!physical(node)) {
            remove(node.nodeId());
            return null;
        }
        Set<EndpointKey> existing = byNode.get(node.nodeId());
        if (existing != null && !samePhysical(entries.get(existing.iterator().next()).node, node))
            remove(node.nodeId());
        if (node.form() == NodeForm.PANEL && face != node.facing()) return null;
        var binding = directory.binding(typeId);
        if (binding == null) return null;
        var key = new EndpointKey(node.nodeId(), face, typeId);
        if (!targetLoaded(node, face)) {
            remove(key);
            return null;
        }
        Entry entry = entries.get(key);
        if (entry == null) {
            if (entries.size() >= maximumEntries) return null;
            ServerLevel level =
                    Objects.requireNonNull(server.getLevel(node.position().dimension()));
            entry = new Entry(node, face, level, binding);
            entries.put(key, entry);
            byNode.computeIfAbsent(node.nodeId(), ignored -> new HashSet<>()).add(key);
            byChunk.computeIfAbsent(entry.nodeChunk, ignored -> new HashSet<>()).add(key);
            byChunk.computeIfAbsent(entry.targetChunk, ignored -> new HashSet<>())
                    .add(key);
        }

        if (entry.resolved) return entry.handle;
        ResourcePort port;
        long epoch = entry.epoch;
        budget.beforeCall();
        try {
            port = entry.discover.get();
        } finally {
            budget.afterCall();
        }
        if (!entry.alive || entry.epoch != epoch || !physical(node) || !targetLoaded(node, face)) return null;
        if (port != null && !port.typeId().equals(typeId))
            throw new IllegalStateException("Adapter returned wrong resource type");
        entry.resolved = true;
        entry.handle = port == null ? null : new Handle(entry, port, epoch);
        return entry.handle;
    }

    /** Exact non-discovering node identity check; target chunk loading is deliberately checked separately. */
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

    /** Retires only actual entries for the node; never traverses the directory's registered types. */
    public void remove(UUID nodeId) {
        requireThread();
        Set<EndpointKey> keys = byNode.get(nodeId);
        if (keys != null) for (EndpointKey key : List.copyOf(keys)) remove(key);
        invalidatedNodes.remove(nodeId);
    }

    /** Retires entries touching the unloaded node or target chunk, proportional to affected entries. */
    public void unload(ResourceKey<Level> dimension, ChunkPos chunk) {
        requireThread();
        Set<EndpointKey> keys = byChunk.get(new ChunkKey(dimension, chunk));
        if (keys != null) for (EndpointKey key : List.copyOf(keys)) remove(key);
    }

    /** Drains immutable deduplicated IDs during caller tick processing, never inside a native callback. */
    public List<UUID> drainInvalidatedNodes() {
        requireThread();
        var result = List.copyOf(invalidatedNodes);
        invalidatedNodes.clear();
        return result;
    }

    /** Current retained entries including cached null capabilities; no native calls. */
    public int size() {
        requireThread();
        return entries.size();
    }

    /** Ends the session and drops all cache ownership, permanently invalidating every borrowed handle. */
    @Override
    public void close() {
        requireThread();
        closed = true;
        for (Entry entry : entries.values()) {
            entry.alive = false;
            entry.handle = null;
        }
        entries.clear();
        byNode.clear();
        byChunk.clear();
        invalidatedNodes.clear();
    }

    private void remove(EndpointKey key) {
        Entry entry = entries.remove(key);
        if (entry == null) return;
        entry.alive = false;
        entry.handle = null;
        unindex(byChunk, entry.nodeChunk, key);
        unindex(byChunk, entry.targetChunk, key);
        unindex(byNode, key.nodeId(), key);
        if (!byNode.containsKey(key.nodeId())) invalidatedNodes.remove(key.nodeId());
    }

    private static <K> void unindex(Map<K, Set<EndpointKey>> index, K indexKey, EndpointKey key) {
        Set<EndpointKey> keys = index.get(indexKey);
        if (keys != null) {
            keys.remove(key);
            if (keys.isEmpty()) index.remove(indexKey);
        }
    }

    private static boolean samePhysical(NetworkNodeRecord a, NetworkNodeRecord b) {
        return a.position().equals(b.position()) && a.form() == b.form() && a.facing() == b.facing();
    }

    private boolean targetLoaded(NetworkNodeRecord node, Direction face) {
        ServerLevel level = server.getLevel(node.position().dimension());
        return level != null
                && level.isLoaded(NodeGeometry.targetPosition(node.position().pos(), face));
    }

    private void requireThread() {
        if (!server.isSameThread())
            throw new IllegalStateException("Resource endpoint cache accessed off server thread");
    }

    private record EndpointKey(UUID nodeId, Direction face, ResourceLocation typeId) {}

    private record ChunkKey(ResourceKey<Level> dimension, ChunkPos chunk) {}

    private final class Entry {
        final NetworkNodeRecord node;
        final Direction face;
        final GlobalPos physicalIdentity;
        final ChunkKey nodeChunk, targetChunk;
        final Supplier<@Nullable ResourcePort> discover;
        boolean alive = true, resolved;
        long epoch;

        @Nullable
        Handle handle;

        Entry(NetworkNodeRecord node, Direction face, ServerLevel level, ResourceAdapterDirectory.Binding<?> binding) {
            this.node = node;
            this.face = face;
            BlockPos target = NodeGeometry.targetPosition(node.position().pos(), face);
            physicalIdentity = GlobalPos.of(node.position().dimension(), target);
            nodeChunk = new ChunkKey(
                    node.position().dimension(), new ChunkPos(node.position().pos()));
            targetChunk = new ChunkKey(node.position().dimension(), new ChunkPos(target));
            discover = binding.create(level, target, NodeGeometry.targetSide(face), () -> alive, () -> {
                epoch++;
                resolved = false;
                handle = null;
                invalidatedNodes.add(node.nodeId());
            });
        }
    }

    private final class Handle implements ResourceTransferEngine.Handle {
        private final Entry entry;
        private final ResourcePort port;
        private final long epoch;

        Handle(Entry entry, ResourcePort port, long epoch) {
            this.entry = entry;
            this.port = port;
            this.epoch = epoch;
        }

        public ResourcePort port() {
            requireThread();
            return port;
        }

        public Object physicalIdentity() {
            requireThread();
            return entry.physicalIdentity;
        }

        public boolean valid() {
            requireThread();
            return entry.alive && entry.epoch == epoch && physical(entry.node) && targetLoaded(entry.node, entry.face);
        }
    }
}
