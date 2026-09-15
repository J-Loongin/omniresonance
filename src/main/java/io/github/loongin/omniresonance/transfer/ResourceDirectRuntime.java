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
    private final DomainInputScheduler domainInputs;
    private final DomainOutputScheduler domainOutputs;

    private record OutputPublication(
            DomainOutputScheduler.Configuration configuration,
            io.github.loongin.omniresonance.network.DomainNodeConfiguration binding,
            @Nullable ResourceFilterCache.Key filter) {}

    private final Map<UUID, OutputPublication> outputPublications = new HashMap<>();

    private record DomainPublication(
            DomainInputScheduler.Configuration configuration,
            io.github.loongin.omniresonance.network.DomainNodeConfiguration binding,
            @Nullable ResourceFilterCache.Key filter) {}

    private record SourceKey(net.minecraft.core.GlobalPos position, net.minecraft.resources.ResourceLocation type) {}

    private record SourceRegistration(SourceKey source, ResourceDirectScheduler.Key configuration) {}

    private final Map<UUID, DomainPublication> domainPublications = new HashMap<>();
    private final Map<UUID, List<UUID>> domainNetworks = new HashMap<>();
    private final Map<SourceKey, Set<ResourceDirectScheduler.Key>> directSources = new HashMap<>();
    private final Map<UUID, List<SourceRegistration>> networkSources = new HashMap<>();
    private final java.util.function.LongSupplier clock;
    private final Set<UUID> changedNetworks = new LinkedHashSet<>(), changedNodes = new LinkedHashSet<>();
    private final Set<UUID> changedRecovery = new LinkedHashSet<>();
    private final Map<UUID, RecoveryBuffer> recoveryDestinations = new HashMap<>();
    private ServerSettings currentSettings;
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
        currentSettings = settings;
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
        domainInputs = new DomainInputScheduler(new DomainInputScheduler.Environment() {
            public List<net.minecraft.resources.ResourceLocation> types() {
                return adapters.types();
            }

            public boolean active(DomainInputScheduler.Configuration c) {
                return domainActive(c);
            }

            public int faces(DomainInputScheduler.Configuration c) {
                var data = repository.findLoadedNetwork(c.networkId()).orElse(null);
                var node = data == null ? null : data.findNode(c.nodeId()).orElse(null);
                return node == null ? 0 : c.faces().effectiveMask(node.facing());
            }

            public ResourceTransferEngine.Handle resolve(
                    DomainInputScheduler.Configuration c,
                    net.minecraft.resources.ResourceLocation type,
                    Direction face,
                    TransferWorkBudget budget) {
                if (!domainActive(c)) return null;
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

            public io.github.loongin.omniresonance.storage.DomainLedger ledger(UUID networkId) {
                return repository.domainStorage(networkId).activate().orElse(null);
            }

            public RecoveryBuffer recovery(UUID networkId) {
                return ResourceDirectRuntime.this.recovery(networkId);
            }

            public long directFirst(
                    DomainInputScheduler.Configuration c,
                    net.minecraft.resources.ResourceLocation type,
                    Direction face,
                    long tick,
                    TransferWorkBudget budget) {
                return directSourceTurn(c, type, face, tick, budget);
            }

            public ResourceDirectScheduler.FilterView filter(DomainInputScheduler.Configuration c) {
                var publication = domainPublications.get(c.nodeId());
                return publication == null || publication.filter() == null
                        ? new ResourceDirectScheduler.FilterView(unfilteredToken, null)
                        : filters.view(publication.filter());
            }

            public Object filterToken(DomainInputScheduler.Configuration c) {
                var publication = domainPublications.get(c.nodeId());
                return publication == null || publication.filter() == null
                        ? unfilteredToken
                        : filters.token(publication.filter());
            }

            public int advanceFilter(DomainInputScheduler.Configuration c, int units) {
                var publication = domainPublications.get(c.nodeId());
                return publication == null || publication.filter() == null
                        ? 0
                        : filters.advance(publication.filter(), units);
            }
        });
        domainOutputs = new DomainOutputScheduler(new DomainOutputScheduler.Environment() {
            public List<net.minecraft.resources.ResourceLocation> types() {
                return adapters.types();
            }

            public boolean active(DomainOutputScheduler.Configuration c) {
                return outputActive(c);
            }

            public int faces(DomainOutputScheduler.Configuration c) {
                var data = repository.findLoadedNetwork(c.networkId()).orElse(null);
                var node = data == null ? null : data.findNode(c.nodeId()).orElse(null);
                return node == null ? 0 : c.faces().effectiveMask(node.facing());
            }

            public ResourceTransferEngine.Handle resolve(
                    DomainOutputScheduler.Configuration c,
                    net.minecraft.resources.ResourceLocation type,
                    Direction face,
                    TransferWorkBudget budget) {
                if (!outputActive(c)) return null;
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

            public io.github.loongin.omniresonance.storage.DomainLedger ledger(UUID networkId) {
                return repository.domainStorage(networkId).activate().orElse(null);
            }

            public ResourceVariant decode(ResourceVariantKey key) {
                return adapters.decode(key, server.registryAccess()).orElse(null);
            }

            public RecoveryBuffer recovery(UUID networkId) {
                return ResourceDirectRuntime.this.recovery(networkId);
            }

            public ResourceDirectScheduler.FilterView filter(DomainOutputScheduler.Configuration c) {
                var p = outputPublications.get(c.nodeId());
                return p == null || p.filter() == null
                        ? new ResourceDirectScheduler.FilterView(unfilteredToken, null)
                        : filters.view(p.filter());
            }

            public Object filterToken(DomainOutputScheduler.Configuration c) {
                var publication = outputPublications.get(c.nodeId());
                return publication == null || publication.filter() == null
                        ? unfilteredToken
                        : filters.token(publication.filter());
            }

            public int advanceFilter(DomainOutputScheduler.Configuration c, int units) {
                var p = outputPublications.get(c.nodeId());
                return p == null || p.filter() == null ? 0 : filters.advance(p.filter(), units);
            }
        });
        scheduler = new ResourceDirectScheduler(this);
        repository.onRuntimeChanged(new SavedNetworkRepository.RuntimeListener() {
            public void networkChanged(UUID id) {
                ResourceDirectRuntime.this.networkChanged(id);
            }

            public void ownerCreated(UUID id) {
                ownerLibraryChanged(id);
            }

            public void recoveryChanged(UUID id) {
                if (!closed) changedRecovery.add(id);
            }
        });
        changedNetworks.addAll(repository.loadedNetworkIds());
        changedRecovery.addAll(repository.loadedNetworkIds());
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
        currentSettings = settings;
        while (!changedNetworks.isEmpty()) {
            UUID id = changedNetworks.iterator().next();
            changedNetworks.remove(id);
            refresh(id);
        }
        for (UUID id : endpoints.drainInvalidatedNodes()) enqueueNode(id);
        while (!changedRecovery.isEmpty()) {
            UUID id = changedRecovery.iterator().next();
            changedRecovery.remove(id);
            scheduler.wakeRecovery(id, currentTick);
        }
        while (!changedNodes.isEmpty()) {
            UUID id = changedNodes.iterator().next();
            changedNodes.remove(id);
            scheduler.wakeNode(id, currentTick);
            var domain = domainInputs.configuration(id);
            if (domain != null) scheduler.scheduleDomain(id, domain.networkId(), domainInputs.wake(id, currentTick));
            else {
                var output = domainOutputs.configuration(id);
                if (output != null)
                    scheduler.scheduleDomain(id, output.networkId(), domainOutputs.wake(id, currentTick));
            }
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
        if (!closed
                && (repository.findLoadedNetwork(networkId).isPresent()
                        || networkNodes.containsKey(networkId)
                        || recoveryDestinations.containsKey(networkId))) changedNetworks.add(networkId);
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

    /** Read-only player summary; does not activate buckets, discover capabilities or advance work. */
    public io.github.loongin.omniresonance.networking.NodeDomainStatus domainStatus(UUID networkId, UUID nodeId) {
        requireThread();
        var data = repository.findLoadedNetwork(networkId).orElse(null);
        var node = data == null ? null : data.findNode(nodeId).orElse(null);
        var binding = data == null ? null : data.domainConfiguration(nodeId).orElse(null);
        if (binding == null) return io.github.loongin.omniresonance.networking.NodeDomainStatus.UNCONFIGURED;
        if (!binding.configured()) return io.github.loongin.omniresonance.networking.NodeDomainStatus.PENDING;
        if (node == null || !node.enabled()) return io.github.loongin.omniresonance.networking.NodeDomainStatus.BLOCKED;
        if (binding.workingFaces().effectiveMask(node.facing()) == 0)
            return io.github.loongin.omniresonance.networking.NodeDomainStatus.NO_FACES;
        if (repository.domainStorage(networkId).state()
                == io.github.loongin.omniresonance.persistence.DomainStorage.State.UNAVAILABLE)
            return io.github.loongin.omniresonance.networking.NodeDomainStatus.STORAGE_UNAVAILABLE;
        if (binding.policy() instanceof ResourceTransferPolicy.Output
                && binding.policy().filterPresetId() == null)
            return io.github.loongin.omniresonance.networking.NodeDomainStatus.NO_PRESET;
        var input = domainPublications.get(nodeId);
        var output = outputPublications.get(nodeId);
        ResourceFilterCache.Key filter = input != null ? input.filter() : output != null ? output.filter() : null;
        if (filter != null) {
            var compiled = filters.view(filter).compiled();
            if (compiled == null || !compiled.valid())
                return io.github.loongin.omniresonance.networking.NodeDomainStatus.FILTER_BLOCKED;
            if (binding.policy() instanceof ResourceTransferPolicy.Output
                    && binding.policy().filterMode() == io.github.loongin.omniresonance.filter.FilterMode.BLACKLIST
                    && compiled.ruleCount() == 0)
                return io.github.loongin.omniresonance.networking.NodeDomainStatus.EMPTY_BLACKLIST;
        }
        return binding.policy() instanceof ResourceTransferPolicy.Input
                ? domainInputs.status(nodeId, currentTick)
                : domainOutputs.status(nodeId, currentTick);
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
        domainInputs.close();
        domainOutputs.close();
        outputPublications.clear();
        domainPublications.clear();
        domainNetworks.clear();
        directSources.clear();
        networkSources.clear();
        changedNetworks.clear();
        changedRecovery.clear();
        for (RecoveryBuffer buffer : recoveryDestinations.values()) buffer.onDomainReturn(null);
        recoveryDestinations.clear();
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
    public @Nullable UUID domainNetwork(UUID nodeId) {
        var config = domainInputs.configuration(nodeId);
        if (config != null) return config.networkId();
        var output = domainOutputs.configuration(nodeId);
        return output == null ? null : output.networkId();
    }

    @Override
    public long advanceDomain(UUID nodeId, long tick, ServerSettings settings, TransferWorkBudget budget) {
        return domainInputs.contains(nodeId)
                ? domainInputs.step(nodeId, tick, settings, budget)
                : domainOutputs.step(nodeId, tick, settings, budget);
    }

    private boolean outputActive(DomainOutputScheduler.Configuration c) {
        var data = repository.findLoadedNetwork(c.networkId()).orElse(null);
        var node = data == null ? null : data.findNode(c.nodeId()).orElse(null);
        var publication = outputPublications.get(c.nodeId());
        if (node == null
                || !node.enabled()
                || node.mode() != NodeMode.DOMAIN
                || node.revision() != c.revision()
                || publication == null
                || publication.configuration() != c
                || data.domainConfiguration(c.nodeId()).orElse(null) != publication.binding()
                || !publication.binding().configured()) return false;
        var directory = nodes.byId(c.nodeId()).entry().orElse(null);
        if (directory == null
                || !directory.networkId().equals(c.networkId())
                || !directory.record().equals(node)
                || !endpoints.physical(node)
                || repository.domainStorage(c.networkId()).state()
                        == io.github.loongin.omniresonance.persistence.DomainStorage.State.UNAVAILABLE) return false;
        var level = server.getLevel(node.position().dimension());
        return c.policy().redstoneCondition() == RedstoneCondition.IGNORE
                || level.hasNeighborSignal(node.position().pos())
                        == (c.policy().redstoneCondition() == RedstoneCondition.SIGNAL);
    }

    private boolean domainActive(DomainInputScheduler.Configuration c) {
        var data = repository.findLoadedNetwork(c.networkId()).orElse(null);
        var node = data == null ? null : data.findNode(c.nodeId()).orElse(null);
        var publication = domainPublications.get(c.nodeId());
        if (node == null
                || !node.enabled()
                || node.mode() != NodeMode.DOMAIN
                || node.revision() != c.revision()
                || publication == null
                || publication.configuration() != c
                || data.domainConfiguration(c.nodeId()).orElse(null) != publication.binding()
                || !publication.binding().configured()) return false;
        var directory = nodes.byId(c.nodeId()).entry().orElse(null);
        if (directory == null
                || !directory.networkId().equals(c.networkId())
                || !directory.record().equals(node)
                || !endpoints.physical(node)
                || repository.domainStorage(c.networkId()).state()
                        == io.github.loongin.omniresonance.persistence.DomainStorage.State.UNAVAILABLE) return false;
        var level = server.getLevel(node.position().dimension());
        return c.policy().redstoneCondition() == RedstoneCondition.IGNORE
                || level.hasNeighborSignal(node.position().pos())
                        == (c.policy().redstoneCondition() == RedstoneCondition.SIGNAL);
    }

    private long directSourceTurn(
            DomainInputScheduler.Configuration c,
            net.minecraft.resources.ResourceLocation type,
            Direction face,
            long tick,
            TransferWorkBudget budget) {
        var data = repository.findLoadedNetwork(c.networkId()).orElse(null);
        var node = data == null ? null : data.findNode(c.nodeId()).orElse(null);
        if (node == null) return Math.addExact(tick, 1);
        var key = new SourceKey(
                net.minecraft.core.GlobalPos.of(
                        node.position().dimension(), node.position().pos().relative(face)),
                type);
        Set<ResourceDirectScheduler.Key> contenders = directSources.get(key);
        if (contenders == null) return -1;
        long next = -1;
        for (var contender : contenders) {
            if (!budget.canStart()) return Math.addExact(tick, 1);
            next = Math.max(next, scheduler.directInputTurn(contender, type, tick));
        }
        return next;
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

    @Override
    public boolean hasRecoveryWork(UUID networkId) {
        NetworkSavedData data = repository.findLoadedNetwork(networkId).orElse(null);
        return data != null
                && !data.recovery().isEmpty()
                && repository.domainStorage(networkId).state()
                        != io.github.loongin.omniresonance.persistence.DomainStorage.State.UNAVAILABLE;
    }

    @Override
    public void advanceRecovery(UUID networkId, ServerSettings settings) {
        var domain = repository.domainStorage(networkId);
        var ledger = domain.activate().orElse(null);
        if (ledger == null) return;
        try {
            recovery(networkId).drainOne(ledger, settings.storageVariantLimitPerNetwork());
        } catch (RuntimeException failure) {
            if (domain.state() != io.github.loongin.omniresonance.persistence.DomainStorage.State.UNAVAILABLE)
                throw failure;
        }
    }

    private void refresh(UUID networkId) {
        changedRecovery.add(networkId);
        List<SourceRegistration> oldSources = networkSources.remove(networkId);
        if (oldSources != null)
            for (var entry : oldSources) {
                var group = directSources.get(entry.source());
                if (group != null) {
                    group.remove(entry.configuration());
                    if (group.isEmpty()) directSources.remove(entry.source());
                }
            }
        List<UUID> oldDomains = domainNetworks.remove(networkId);
        List<DomainPublication> retiredDomains = new ArrayList<>();
        List<OutputPublication> retiredOutputs = new ArrayList<>();
        if (oldDomains != null)
            for (UUID id : oldDomains) {
                var old = domainPublications.remove(id);
                if (old != null) retiredDomains.add(old);
                var oldOutput = outputPublications.remove(id);
                if (oldOutput != null) retiredOutputs.add(oldOutput);
            }
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
            domainOutputs.replaceNetwork(networkId, List.of(), currentTick);
            for (var old : retiredOutputs) if (old.filter() != null) filters.release(old.filter());
            if (oldDomains != null)
                for (UUID id : oldDomains) {
                    domainInputs.remove(id);
                    scheduler.scheduleDomain(id, null, Long.MAX_VALUE);
                }
            for (var old : retiredDomains) if (old.filter() != null) filters.release(old.filter());
            RecoveryBuffer previousBuffer = recoveryDestinations.remove(networkId);
            if (previousBuffer != null) previousBuffer.onDomainReturn(null);
            retire(previousPublications);
            scheduler.replaceNetwork(networkId, List.of(), currentTick);
            return;
        }
        if (!recoveryDestinations.containsKey(networkId)) {
            RecoveryBuffer buffer = data.recovery();
            buffer.onDomainReturn((key, amount) -> returnToDomain(networkId, key, amount));
            recoveryDestinations.put(networkId, buffer);
        }
        List<ResourceDirectScheduler.Configuration> configs = new ArrayList<>();
        List<Publication> nextPublications = new ArrayList<>();
        List<UUID> nextDomains = new ArrayList<>();
        List<DomainOutputScheduler.Configuration> outputConfigs = new ArrayList<>();
        Set<UUID> nextDomainIds = new HashSet<>();
        List<SourceRegistration> sourceRegistrations = new ArrayList<>();
        Set<UUID> ids = new HashSet<>();
        for (NetworkNodeRecord node : data.nodes()) {
            if (node.mode() == NodeMode.DOMAIN) {
                var binding = data.domainConfiguration(node.nodeId()).orElse(null);
                if (binding == null || !binding.configured()) continue;
                if (binding.policy() instanceof ResourceTransferPolicy.Output output) {
                    var config = new DomainOutputScheduler.Configuration(
                            networkId, node.nodeId(), node.revision(), output, binding.workingFaces());
                    var filter = output.filterPresetId() == null
                            ? null
                            : filters.acquire(data.metadata().ownerId(), output.filterPresetId());
                    outputPublications.put(node.nodeId(), new OutputPublication(config, binding, filter));
                    outputConfigs.add(config);
                    domainInputs.remove(node.nodeId());
                    nextDomains.add(node.nodeId());
                    nextDomainIds.add(node.nodeId());
                    ids.add(node.nodeId());
                    continue;
                }
                ResourceTransferPolicy.Input input = (ResourceTransferPolicy.Input) binding.policy();
                var config = new DomainInputScheduler.Configuration(
                        networkId, node.nodeId(), node.revision(), input, binding.workingFaces());
                var filter = input.filterPresetId() == null
                        ? null
                        : filters.acquire(data.metadata().ownerId(), input.filterPresetId());
                domainPublications.put(node.nodeId(), new DomainPublication(config, binding, filter));
                domainInputs.replace(config, currentTick);
                scheduler.scheduleDomain(node.nodeId(), networkId, domainInputs.wake(node.nodeId(), currentTick));
                nextDomains.add(node.nodeId());
                nextDomainIds.add(node.nodeId());
                ids.add(node.nodeId());
                continue;
            }
            if (node.mode() != NodeMode.DIRECT) continue;
            for (DirectNodeBinding binding : data.directBindings(node.nodeId())) {
                ResourceDirectScheduler.Configuration config = publishPolicy(networkId, node, binding);
                configs.add(config);
                if (config.policy() instanceof ResourceTransferPolicy.Input) {
                    int mask = binding.workingFaces().effectiveMask(node.facing());
                    for (Direction face : Direction.values())
                        if ((mask & (1 << face.get3DDataValue())) != 0)
                            for (var type : adapters.types())
                                if (config.policy().scope().includes(type)) {
                                    var key = new SourceKey(
                                            net.minecraft.core.GlobalPos.of(
                                                    node.position().dimension(),
                                                    node.position().pos().relative(face)),
                                            type);
                                    directSources
                                            .computeIfAbsent(key, ignored -> new HashSet<>())
                                            .add(config.key());
                                    sourceRegistrations.add(new SourceRegistration(key, config.key()));
                                }
                }
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
        domainOutputs.replaceNetwork(networkId, outputConfigs, currentTick);
        for (var config : outputConfigs)
            scheduler.scheduleDomain(config.nodeId(), networkId, domainOutputs.wake(config.nodeId(), currentTick));
        for (var old : retiredOutputs) if (old.filter() != null) filters.release(old.filter());
        if (oldDomains != null)
            for (UUID id : oldDomains)
                if (!nextDomainIds.contains(id)) {
                    domainInputs.remove(id);
                    scheduler.scheduleDomain(id, null, Long.MAX_VALUE);
                }
        for (var old : retiredDomains) if (old.filter() != null) filters.release(old.filter());
        if (!nextDomains.isEmpty()) domainNetworks.put(networkId, nextDomains);
        if (!sourceRegistrations.isEmpty()) networkSources.put(networkId, sourceRegistrations);
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

    private long returnToDomain(UUID networkId, ResourceVariantKey key, long amount) {
        var domain = repository.domainStorage(networkId);
        var ledger = domain.activate().orElse(null);
        if (ledger == null) return 0;
        long accepted = Math.min(amount, ledger.insertCapacity(key, currentSettings.storageVariantLimitPerNetwork()));
        if (accepted == 0) return 0;
        io.github.loongin.omniresonance.storage.DomainLedger.Deposit deposit;
        try {
            deposit = ledger.reserveDeposit(key, accepted, currentSettings.storageVariantLimitPerNetwork())
                    .orElse(null);
        } catch (RuntimeException failure) {
            // Deposit reservation never credits inventory, including failed native bucket registration.
            return 0;
        }
        if (deposit == null) return 0;
        try (deposit) {
            deposit.commit(accepted);
        }
        return accepted;
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
