// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import io.github.loongin.omniresonance.config.ServerSettings;
import io.github.loongin.omniresonance.filter.ResourceFilterCache;
import io.github.loongin.omniresonance.filter.ResourceFilterCompiler;
import io.github.loongin.omniresonance.filter.ResourceFilterPreset;
import io.github.loongin.omniresonance.network.DirectNodeBinding;
import io.github.loongin.omniresonance.node.NetworkNodeDirectory;
import io.github.loongin.omniresonance.node.NetworkNodeRecord;
import io.github.loongin.omniresonance.node.NodeMode;
import io.github.loongin.omniresonance.persistence.NetworkSavedData;
import io.github.loongin.omniresonance.persistence.SavedNetworkRepository;
import io.github.loongin.omniresonance.recovery.RecoveryBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Server-owned resource runtime publishing the authoritative effective multi-resource policy. Healthy network IDs and node IDs bound all deduplicated event sets. Routing refresh
 * happens only after authority edits, outside matching. Owner snapshots are shared by active selected roots and released
 * with their last reference; a missing owner is retried only on activation or an explicit owner-library event, never per candidate.
 */
public final class ResourceDirectRuntime implements AutoCloseable, ResourceDirectScheduler.Environment {
    private final MinecraftServer server;
    private final SavedNetworkRepository repository;
    private final NetworkNodeDirectory nodes;
    private final ResourceEndpointCache endpoints;
    private final ResourceDirectScheduler scheduler;
    private final java.util.function.LongSupplier clock;
    private final Set<UUID> changedNetworks = new LinkedHashSet<>(), changedNodes = new LinkedHashSet<>();
    private final Map<UUID, Set<UUID>> networkNodes = new HashMap<>(), ownerNetworks = new HashMap<>();
    private final Map<UUID, UUID> networkOwners = new HashMap<>();
    private final ResourceAdapterDirectory adapters;
    private final ResourceFilterCache filters;
    private final Map<ResourceDirectScheduler.Key, Publication> publications = new HashMap<>();
    private final Map<UUID, List<Publication>> networkPublications = new HashMap<>();
    private java.util.function.Consumer<TransferWorkBudget> sampleWork = ignored -> {};
    private boolean samplesFirst;

    /** Installs owning-thread management work that shares this runtime's sole tick call/time budget. */
    public void installSampleWork(java.util.function.Consumer<TransferWorkBudget> work) {
        requireThread();
        sampleWork = java.util.Objects.requireNonNull(work);
    }

    private final Object unfilteredToken = new Object();

    private record Publication(
            ResourceDirectScheduler.Configuration configuration,
            DirectNodeBinding binding,
            @Nullable ResourceFilterCache.Key filter) {}

    private final Map<UUID, UUID> trackedNodes = new HashMap<>();
    private long currentTick;
    private io.github.loongin.omniresonance.config.ServerConfig.State configuration;
    private boolean closed;

    public ResourceDirectRuntime(
            MinecraftServer server,
            SavedNetworkRepository repository,
            NetworkNodeDirectory nodes,
            ServerSettings settings) {
        this(server, repository, nodes, settings, System::nanoTime);
    }
    /** Uses a supplied monotonic clock; deterministic fixtures need no wall-time timing assertions. */
    public ResourceDirectRuntime(
            MinecraftServer server,
            SavedNetworkRepository repository,
            NetworkNodeDirectory nodes,
            ServerSettings settings,
            java.util.function.LongSupplier clock) {
        this.clock = java.util.Objects.requireNonNull(clock);
        configuration = new io.github.loongin.omniresonance.config.ServerConfig.State(0, 0, true, settings);
        this.server = server;
        this.repository = repository;
        this.nodes = nodes;
        requireThread();
        adapters = repository.resourceAdapters();
        // Sparse occupancy is bounded by tracked nodes × selected faces × registered types; no lower global
        // admission ceiling is inferred from per-network authority limits, and nothing is preallocated.
        endpoints = new ResourceEndpointCache(server, adapters, Integer.MAX_VALUE);
        filters = new ResourceFilterCache(this::readOwner, this::openTag);
        scheduler = new ResourceDirectScheduler(this);
        repository.onRuntimeChanged(new SavedNetworkRepository.RuntimeListener() {
            public void networkChanged(UUID id) {
                ResourceDirectRuntime.this.networkChanged(id);
            }

            public void ownerCreated(UUID id) {
                ownerLibraryChanged(id);
            }
        });
        changedNetworks.addAll(repository.loadedNetworkIds());
    }

    public ResourceDirectRuntime(
            MinecraftServer server,
            SavedNetworkRepository repository,
            NetworkNodeDirectory nodes,
            io.github.loongin.omniresonance.config.ServerConfig.State initial) {
        this(server, repository, nodes, initial.settings());
        configuration = initial;
    }

    public void tick(long currentTick, io.github.loongin.omniresonance.config.ServerConfig.State candidate) {
        requireThread();
        if (configuration.loaded()
                && candidate.loaded()
                && candidate.epoch() == configuration.epoch()
                && candidate.revision() > configuration.revision()) configuration = candidate;
        tick(currentTick, configuration.settings());
    }

    public void tick(long currentTick, ServerSettings settings) {
        requireThread();
        if (closed) return;
        this.currentTick = currentTick;
        while (!changedNetworks.isEmpty()) {
            UUID id = changedNetworks.iterator().next();
            changedNetworks.remove(id);
            refresh(id);
        }
        for (UUID id : endpoints.drainInvalidatedNodes()) enqueueNode(id);
        while (!changedNodes.isEmpty()) {
            UUID id = changedNodes.iterator().next();
            changedNodes.remove(id);
            scheduler.wakeNode(id, currentTick);
        }
        ServerSettings.Scheduler cfg = settings.scheduler();
        TransferWorkBudget budget = new TransferWorkBudget(
                cfg.capabilityCallsPerTick(),
                (long) (cfg.cpuBudgetMillisPerTick() * 1_000_000),
                (long) (cfg.slowCallThresholdMillis() * 1_000_000),
                clock);
        samplesFirst = !samplesFirst;
        if (samplesFirst) sampleWork.accept(budget);
        scheduler.tick(currentTick, settings, budget);
        if (!samplesFirst) sampleWork.accept(budget);
    }
    /** Enqueues only; authoritative multi-object commits must finish before routing is read on the next tick. */
    public void networkChanged(UUID networkId) {
        requireThread();
        if (!closed && (repository.findLoadedNetwork(networkId).isPresent() || networkNodes.containsKey(networkId)))
            changedNetworks.add(networkId);
    }
    /** Reads owner authority once after an explicit library edit, then wakes only its referencing networks. */
    public void ownerLibraryChanged(UUID ownerId) {
        requireThread();
        if (closed) return;
        filters.ownerChanged(ownerId);
        Set<UUID> refs = ownerNetworks.get(ownerId);
        if (refs == null) return;
        for (UUID networkId : refs) {
            Set<UUID> ids = networkNodes.get(networkId);
            if (ids != null) for (UUID id : ids) enqueueNode(id);
        }
    }
    /** Physical/configuration event, called outside native capability invalidation callbacks. */
    public void nodeChanged(UUID nodeId) {
        requireThread();
        if (closed) return;
        endpoints.remove(nodeId);
        enqueueNode(nodeId);
    }

    private void enqueueNode(UUID nodeId) {
        if (!closed && trackedNodes.containsKey(nodeId)) changedNodes.add(nodeId);
    }

    public void chunkUnloaded(ResourceKey<Level> dimension, ChunkPos chunk) {
        requireThread();
        if (!closed) endpoints.unload(dimension, chunk);
    }

    public ResourceDirectScheduler.Status status(UUID nodeId, UUID channelId) {
        requireThread();
        return scheduler.status(nodeId, channelId);
    }

    public int cachedEndpoints() {
        return endpoints.size();
    }

    public void close() {
        requireThread();
        if (closed) return;
        closed = true;
        repository.onRuntimeChanged(null);
        endpoints.close();
        scheduler.close();
        changedNetworks.clear();
        changedNodes.clear();
        networkNodes.clear();
        ownerNetworks.clear();
        networkOwners.clear();
        filters.close();
        publications.clear();
        networkPublications.clear();
        trackedNodes.clear();
    }

    @Override
    public boolean active(ResourceDirectScheduler.Configuration c) {
        NetworkSavedData data = repository.findLoadedNetwork(c.networkId()).orElse(null);
        if (data == null) return false;
        NetworkNodeRecord node = data.findNode(c.nodeId()).orElse(null);
        if (node == null || node.revision() != c.revision() || !node.enabled() || node.mode() != NodeMode.DIRECT)
            return false;
        var directory = nodes.byId(c.nodeId()).entry().orElse(null);
        if (directory == null
                || !directory.networkId().equals(c.networkId())
                || !directory.record().equals(node)) return false;
        DirectNodeBinding binding =
                data.findDirectBinding(c.nodeId(), c.channelId()).orElse(null);
        Publication publication = publications.get(c.key());
        if (publication == null || publication.configuration() != c || binding != publication.binding()) return false;
        var channel = data.findChannel(c.channelId()).orElse(null);
        if (channel == null) return false;
        var tunnel = data.findTunnel(channel.tunnelId()).orElse(null);
        if (tunnel == null || !tunnel.enabled() || !endpoints.physical(node)) return false;
        ServerLevel level = server.getLevel(node.position().dimension());
        return c.policy().redstoneCondition() == RedstoneCondition.IGNORE
                || level.hasNeighborSignal(node.position().pos())
                        == (c.policy().redstoneCondition() == RedstoneCondition.SIGNAL);
    }

    @Override
    public List<net.minecraft.resources.ResourceLocation> registeredTypes() {
        return adapters.types();
    }

    @Override
    public int faceMask(ResourceDirectScheduler.Configuration c) {
        var data = repository.findLoadedNetwork(c.networkId()).orElse(null);
        var node = data == null ? null : data.findNode(c.nodeId()).orElse(null);
        return node == null ? 0 : c.workingFaces().effectiveMask(node.facing());
    }

    @Override
    public @Nullable ResourceTransferEngine.Handle resolve(
            ResourceDirectScheduler.Configuration c,
            net.minecraft.resources.ResourceLocation type,
            Direction face,
            TransferWorkBudget budget) {
        if (!active(c) || (faceMask(c) & (1 << face.get3DDataValue())) == 0) return null;
        return endpoints.resolve(
                repository
                        .findLoadedNetwork(c.networkId())
                        .orElseThrow()
                        .findNode(c.nodeId())
                        .orElseThrow(),
                face,
                type,
                budget);
    }

    @Override
    public RecoveryBuffer recovery(UUID networkId) {
        return repository.findLoadedNetwork(networkId).orElseThrow().recovery();
    }

    private void refresh(UUID networkId) {
        Set<UUID> previous = networkNodes.remove(networkId);
        if (previous != null)
            for (UUID id : previous) {
                if (trackedNodes.remove(id, networkId)) {
                    endpoints.remove(id);
                    changedNodes.remove(id);
                }
            }
        UUID oldOwner = networkOwners.remove(networkId);
        if (oldOwner != null) {
            Set<UUID> refs = ownerNetworks.get(oldOwner);
            refs.remove(networkId);
            if (refs.isEmpty()) ownerNetworks.remove(oldOwner);
        }
        List<Publication> previousPublications = networkPublications.remove(networkId);
        NetworkSavedData data = repository.findLoadedNetwork(networkId).orElse(null);
        if (data == null) {
            retire(previousPublications);
            scheduler.replaceNetwork(networkId, List.of(), currentTick);
            return;
        }
        List<ResourceDirectScheduler.Configuration> configs = new ArrayList<>();
        List<Publication> nextPublications = new ArrayList<>();
        Set<UUID> ids = new HashSet<>();
        for (NetworkNodeRecord node : data.nodes()) {
            if (node.mode() != NodeMode.DIRECT) continue;
            for (DirectNodeBinding binding : data.directBindings(node.nodeId())) {
                ResourceDirectScheduler.Configuration config = publishPolicy(networkId, node, binding);
                configs.add(config);
                UUID preset = config.policy().filterPresetId();
                var key =
                        preset == null ? null : filters.acquire(data.metadata().ownerId(), preset);
                nextPublications.add(new Publication(config, binding, key));
            }
            ids.add(node.nodeId());
        }
        if (!ids.isEmpty()) {
            networkNodes.put(networkId, ids);
            for (UUID id : ids) trackedNodes.put(id, networkId);
            UUID owner = data.metadata().ownerId();
            networkOwners.put(networkId, owner);
            ownerNetworks.computeIfAbsent(owner, ignored -> new HashSet<>()).add(networkId);
        }
        retire(previousPublications);
        for (Publication publication : nextPublications)
            publications.put(publication.configuration().key(), publication);
        if (!nextPublications.isEmpty()) networkPublications.put(networkId, nextPublications);
        scheduler.replaceNetwork(networkId, configs, currentTick);
    }

    private void retire(@Nullable List<Publication> previous) {
        if (previous == null) return;
        for (Publication publication : previous) {
            publications.remove(publication.configuration().key(), publication);
            if (publication.filter() != null) filters.release(publication.filter());
        }
    }

    /** First 5c replacement point: read-only M2 policy publication; never projects resources back to items. */
    private static ResourceDirectScheduler.Configuration publishPolicy(
            UUID networkId, NetworkNodeRecord node, DirectNodeBinding binding) {
        return new ResourceDirectScheduler.Configuration(
                networkId,
                node.nodeId(),
                binding.channelId(),
                node.revision(),
                binding.policy(),
                binding.workingFaces());
    }

    /** Second 5c replacement point: one owner snapshot on first selected reference or explicit owner edit. */
    private @Nullable ResourceFilterCompiler.OwnerSnapshot readOwner(UUID ownerId) {
        var owner = repository.findOwner(ownerId).orElse(null);
        if (owner == null) return null;
        Map<UUID, ResourceFilterPreset> presets = new HashMap<>();
        for (var preset : owner.presets()) presets.put(preset.id(), preset);
        return new ResourceFilterCompiler.OwnerSnapshot(ownerId, presets);
    }

    @Override
    public ResourceDirectScheduler.FilterView filter(ResourceDirectScheduler.Configuration config) {
        var publication = publications.get(config.key());
        return publication == null || publication.filter() == null
                ? new ResourceDirectScheduler.FilterView(unfilteredToken, null)
                : filters.view(publication.filter());
    }

    @Override
    public Object filterToken(ResourceDirectScheduler.Configuration config) {
        var publication = publications.get(config.key());
        return publication == null || publication.filter() == null
                ? unfilteredToken
                : filters.token(publication.filter());
    }

    @Override
    public int advanceFilter(ResourceDirectScheduler.Configuration config, int workUnits) {
        var publication = publications.get(config.key());
        return publication == null || publication.filter() == null
                ? 0
                : filters.advance(publication.filter(), workUnits);
    }

    /** Called only on the server tick after the native event has signalled a generation. */
    public void tagsChanged() {
        requireThread();
        if (closed) return;
        filters.tagsChanged();
        for (UUID id : trackedNodes.keySet()) enqueueNode(id);
    }

    public int cachedFilterRoots() {
        return filters.rootCount();
    }

    public int cachedFilterOwners() {
        return filters.ownerCount();
    }

    public int cachedFilterTags() {
        return filters.tagCount();
    }

    private @Nullable java.util.Iterator<net.minecraft.resources.ResourceLocation> openTag(
            ResourceFilterCompiler.TagKey key) {
        requireThread();
        if (key.typeId().equals(ResourceTypes.ITEM))
            return tagMembers(
                    server.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ITEM),
                    key.tagId());
        if (key.typeId().equals(ResourceTypes.FLUID))
            return tagMembers(
                    server.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.FLUID),
                    key.tagId());
        return null;
    }

    private static <T> @Nullable java.util.Iterator<net.minecraft.resources.ResourceLocation> tagMembers(
            net.minecraft.core.Registry<T> registry, net.minecraft.resources.ResourceLocation id) {
        var tag = registry.getTag(net.minecraft.tags.TagKey.create(registry.key(), id))
                .orElse(null);
        if (tag == null) return null;
        var members = tag.iterator();
        return new java.util.Iterator<>() {
            public boolean hasNext() {
                return members.hasNext();
            }

            public net.minecraft.resources.ResourceLocation next() {
                return members.next().unwrapKey().orElseThrow().location();
            }
        };
    }

    private void requireThread() {
        if (!server.isSameThread()) throw new IllegalStateException("Resource runtime accessed off server thread");
    }
}
