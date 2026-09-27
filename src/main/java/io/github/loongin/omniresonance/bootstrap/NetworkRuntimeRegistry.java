// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.bootstrap;

import io.github.loongin.omniresonance.config.ServerConfig;
import io.github.loongin.omniresonance.config.ServerSettings;
import io.github.loongin.omniresonance.exchange.ExchangeRuntime;
import io.github.loongin.omniresonance.network.NetworkAdministrationService;
import io.github.loongin.omniresonance.network.NetworkCreationService;
import io.github.loongin.omniresonance.network.NetworkDirectory;
import io.github.loongin.omniresonance.network.NetworkSettingsService;
import io.github.loongin.omniresonance.network.NetworkTerminalService;
import io.github.loongin.omniresonance.network.NetworkTopologyService;
import io.github.loongin.omniresonance.network.ServerPlayerDirectory;
import io.github.loongin.omniresonance.networking.NetworkTerminalRequest;
import io.github.loongin.omniresonance.networking.NetworkTerminalResponse;
import io.github.loongin.omniresonance.node.NetworkNodeDirectory;
import io.github.loongin.omniresonance.node.NetworkNodeRecord;
import io.github.loongin.omniresonance.node.NodeAuthorityService;
import io.github.loongin.omniresonance.node.NodeLifecycleEvent;
import io.github.loongin.omniresonance.node.NodeManagementService;
import io.github.loongin.omniresonance.node.NodeMenuOpenEvent;
import io.github.loongin.omniresonance.node.NodeMenuService;
import io.github.loongin.omniresonance.node.NodeReconciliationQueue;
import io.github.loongin.omniresonance.node.NodeTransferWakeEvent;
import io.github.loongin.omniresonance.persistence.SavedNetworkRepository;
import io.github.loongin.omniresonance.security.EditLockTable;
import io.github.loongin.omniresonance.transfer.ResourceDirectRuntime;
import io.github.loongin.omniresonance.transfer.ResourceDirectScheduler;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mod-instance-owned terminal/node lifecycle bridge with no static world references.
 *
 * <p>The server thread publishes one fully composed runtime per server session, applies configuration and bounded
 * node work at Pre ticks, and releases every player/server/world collaborator on stop. Pre-publication node/chunk
 * observations are reduced to a bounded queue of immutable keys; failed initialization never drains them or
 * interprets missing authority. No operation performs simulation, synchronous save or asynchronous world access.
 */
public final class NetworkRuntimeRegistry {
    @FunctionalInterface
    interface RuntimeComponentsFactory {
        RuntimeComponents create(MinecraftServer server, ServerConfig.State state);
    }

    record RuntimeComponents(
            NetworkTerminalService terminal,
            @Nullable NodeAuthorityService nodes,
            @Nullable NodeManagementService nodeManagement,
            @Nullable NodeMenuService nodeMenus,
            @Nullable NetworkTopologyService topology,
            @Nullable ResourceDirectRuntime directTransfers,
            @Nullable io.github.loongin.omniresonance.chunkloading.ChunkLoadingRuntime chunkLoading,
            @Nullable ExchangeRuntime exchanges) {
        RuntimeComponents {
            Objects.requireNonNull(terminal, "terminal");
            if (exchanges != null && directTransfers == null)
                throw new IllegalArgumentException("Exchange requires the shared transfer budget");
            if (nodeManagement != null && nodes == null) {
                throw new IllegalArgumentException("Node management requires node authority");
            }
            if (nodeMenus != null && nodeManagement == null) {
                throw new IllegalArgumentException("Node menus require node management");
            }
            if (topology != null && nodeManagement == null) {
                throw new IllegalArgumentException("Topology management requires the shared node-management locks");
            }
        }

        RuntimeComponents(
                NetworkTerminalService terminal,
                @Nullable NodeAuthorityService nodes,
                @Nullable NodeManagementService nodeManagement,
                @Nullable NodeMenuService nodeMenus,
                @Nullable NetworkTopologyService topology,
                @Nullable ResourceDirectRuntime directTransfers,
                @Nullable io.github.loongin.omniresonance.chunkloading.ChunkLoadingRuntime chunkLoading) {
            this(terminal, nodes, nodeManagement, nodeMenus, topology, directTransfers, chunkLoading, null);
        }

        RuntimeComponents(
                NetworkTerminalService terminal,
                NodeAuthorityService nodes,
                NodeManagementService nodeManagement,
                NodeMenuService nodeMenus,
                NetworkTopologyService topology,
                ResourceDirectRuntime directTransfers) {
            this(terminal, nodes, nodeManagement, nodeMenus, topology, directTransfers, null);
        }

        RuntimeComponents(
                NetworkTerminalService terminal,
                @Nullable NodeAuthorityService nodes,
                @Nullable NodeManagementService nodeManagement,
                @Nullable NodeMenuService nodeMenus,
                @Nullable NetworkTopologyService topology) {
            this(terminal, nodes, nodeManagement, nodeMenus, topology, null);
        }

        RuntimeComponents(NetworkTerminalService terminal, @Nullable NodeAuthorityService nodes) {
            this(terminal, nodes, null, null, null);
        }

        RuntimeComponents(
                NetworkTerminalService terminal,
                @Nullable NodeAuthorityService nodes,
                @Nullable NodeManagementService nodeManagement) {
            this(terminal, nodes, nodeManagement, null, null);
        }

        RuntimeComponents(
                NetworkTerminalService terminal,
                @Nullable NodeAuthorityService nodes,
                @Nullable NodeManagementService nodeManagement,
                @Nullable NodeMenuService nodeMenus) {
            this(terminal, nodes, nodeManagement, nodeMenus, null);
        }
    }

    public io.github.loongin.omniresonance.networking.ExchangeFrame handleExchange(
            ServerPlayer player, io.github.loongin.omniresonance.networking.ExchangeRequest request) {
        requireServerThread(player.server);
        return server == player.server && runtime != null
                ? runtime.terminal().exchange(player, request)
                : io.github.loongin.omniresonance.exchange.ExchangeTerminalWire.error(request, "session_expired");
    }

    /** Main-thread status dispatch for the currently published server only; stale runtimes are never reused. */
    public void handleStatus(
            ServerPlayer player, io.github.loongin.omniresonance.networking.NetworkStatusRequest request) {
        var owner = player.serverLevel().getServer();
        requireServerThread(owner);
        if (server == owner && runtime != null) runtime.terminal().status(player, request);
    }

    /** Registers lazy read-only command adapters; old dispatchers cannot access another server session. */
    public void onRegisterCommands(net.neoforged.neoforge.event.RegisterCommandsEvent event) {
        io.github.loongin.omniresonance.network.NetworkDiagnosticCommands.register(
                event.getDispatcher(),
                owner -> runtime != null && server == owner ? runtime.terminal().diagnostics() : null);
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(NetworkRuntimeRegistry.class);
    private final ServerConfig config;
    private final RuntimeComponentsFactory factory;
    private @Nullable MinecraftServer server;
    private @Nullable RuntimeComponents runtime;
    private @Nullable MinecraftServer pendingServer;
    private @Nullable NodeReconciliationQueue pendingNodeWork;
    private boolean pendingOverflowLogged;
    private boolean unavailable;
    private long lastWorldEpoch = -1;
    private final java.util.concurrent.atomic.AtomicLong tagGeneration = new java.util.concurrent.atomic.AtomicLong();
    private long appliedTagGeneration;

    /** SERVER_DATA_LOAD may fire on an integrated client thread: signal only, retain no event/world references. */
    public void onTagsUpdated(net.neoforged.neoforge.event.TagsUpdatedEvent event) {
        if (event.getUpdateCause() == net.neoforged.neoforge.event.TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD)
            tagGeneration.incrementAndGet();
    }

    /** Retains native configuration without live world access; production composition stays lazy until start. */
    public NetworkRuntimeRegistry(ServerConfig config) {
        this(config, NetworkRuntimeRegistry::createRuntime, true);
    }

    /**
     * Retains the existing terminal-only test factory contract without publishing a production no-op authority.
     * Standalone registries built through this seam do not route node events unless the package test factory below
     * supplies real components.
     */
    public NetworkRuntimeRegistry(
            ServerConfig config, BiFunction<MinecraftServer, ServerConfig.State, NetworkTerminalService> factory) {
        this(
                config,
                (server, state) -> new RuntimeComponents(
                        Objects.requireNonNull(factory, "factory").apply(server, state), null, null, null, null),
                true);
    }

    private NetworkRuntimeRegistry(ServerConfig config, RuntimeComponentsFactory factory, boolean internal) {
        this.config = Objects.requireNonNull(config, "config");
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    static NetworkRuntimeRegistry forTesting(ServerConfig config, RuntimeComponentsFactory factory) {
        return new NetworkRuntimeRegistry(config, factory, true);
    }

    /** Routes one synchronous physical lifecycle event or queues only its immutable position before publication. */
    void onNodeLifecycle(NodeLifecycleEvent event) {
        Objects.requireNonNull(event, "event");
        ServerLevel level = event instanceof NodeLifecycleEvent.Loaded loaded
                ? loaded.level()
                : ((NodeLifecycleEvent.Removed) event).level();
        MinecraftServer eventServer = level.getServer();
        requireServerThread(eventServer);
        NodeAuthorityService nodes = activeNodes(eventServer);
        if (nodes != null) {
            if (event instanceof NodeLifecycleEvent.Loaded loaded) {
                nodes.reconcileLoaded(loaded.entity());
                if (runtime != null && runtime.directTransfers() != null)
                    loaded.entity()
                            .state()
                            .ifPresent(state -> runtime.directTransfers().nodeChanged(state.nodeId()));
            } else {
                NodeLifecycleEvent.Removed removed = (NodeLifecycleEvent.Removed) event;
                nodes.removePhysical(removed.nodeId(), GlobalPos.of(level.dimension(), removed.position()));
                if (runtime != null && runtime.directTransfers() != null)
                    runtime.directTransfers().nodeChanged(removed.nodeId());
            }
        } else if (server == null && event instanceof NodeLifecycleEvent.Loaded loaded) {
            offerPendingPosition(
                    eventServer, GlobalPos.of(level.dimension(), loaded.entity().getBlockPos()));
        }
    }

    /** Routes redstone/physical wakeups without discovering capabilities during block callbacks. */
    public void onTransferWake(NodeTransferWakeEvent event) {
        requireServerThread(event.level().getServer());
        if (server == event.level().getServer() && runtime != null && runtime.directTransfers() != null)
            runtime.directTransfers().nodeChanged(event.nodeId());
    }

    /** Releases handles by indexed node and target chunk before any later transfer can use them. */
    public void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            requireServerThread(level.getServer());
            if (server == level.getServer() && runtime != null && runtime.chunkLoading() != null)
                runtime.chunkLoading()
                        .chunkChanged(
                                new io.github.loongin.omniresonance.chunkloading.ChunkLoadingAllocator.Chunk(
                                        level.dimension().location(),
                                        event.getChunk().getPos().x,
                                        event.getChunk().getPos().z),
                                false);
            if (server == level.getServer() && runtime != null && runtime.directTransfers() != null)
                runtime.directTransfers()
                        .chunkUnloaded(level.dimension(), event.getChunk().getPos());
        }
    }

    /** Read-only server-thread status for the node configuration view. */
    public ResourceDirectScheduler.Status directStatus(MinecraftServer owner, UUID nodeId, UUID channelId) {
        requireServerThread(owner);
        return server == owner && runtime != null && runtime.directTransfers() != null
                ? runtime.directTransfers().status(nodeId, channelId)
                : ResourceDirectScheduler.Status.IDLE;
    }

    /** Queues a chunk observation without accessing its contents during the early NeoForge load callback. */
    public void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) {
            onChunkLoaded(level, event.getChunk().getPos().x, event.getChunk().getPos().z);
            if (server == level.getServer() && runtime != null && runtime.chunkLoading() != null)
                runtime.chunkLoading()
                        .chunkChanged(new io.github.loongin.omniresonance.chunkloading.ChunkLoadingAllocator.Chunk(
                                level.dimension().location(),
                                event.getChunk().getPos().x,
                                event.getChunk().getPos().z));
        }
    }

    /** Opens a physical node Menu only through the matching published runtime and actual interaction sender. */
    public void onNodeMenuOpen(NodeMenuOpenEvent event) {
        Objects.requireNonNull(event, "event");
        ServerPlayer player = event.player();
        MinecraftServer eventServer = player.server;
        requireServerThread(eventServer);
        if (server == eventServer && runtime != null && runtime.nodeMenus() != null) {
            runtime.nodeMenus().open(player, event.position());
        }
    }

    void onChunkLoaded(ServerLevel level, int chunkX, int chunkZ) {
        Objects.requireNonNull(level, "level");
        MinecraftServer eventServer = level.getServer();
        requireServerThread(eventServer);
        NodeAuthorityService nodes = activeNodes(eventServer);
        if (nodes != null) {
            nodes.enqueueChunk(level.dimension(), chunkX, chunkZ);
        } else if (server == null) {
            offerPendingChunk(eventServer, level, chunkX, chunkZ);
        }
    }

    /** Initializes once after native SERVER loading without creating a player network or synchronously saving. */
    public void onServerStarted(ServerStartedEvent event) {
        MinecraftServer started = event.getServer();
        requireServerThread(started);
        if (server == started) {
            return;
        }
        if (server != null) {
            throw new IllegalStateException("Overlapping network server lifecycles");
        }
        server = started;
        unavailable = false;
        ServerConfig.State candidate = config.latest();
        ServerConfig.State initial = candidate.loaded() && candidate.epoch() > lastWorldEpoch
                ? candidate
                : new ServerConfig.State(candidate.epoch(), 0, false, ServerSettings.defaults());
        lastWorldEpoch = Math.max(lastWorldEpoch, candidate.epoch());
        try {
            runtime = Objects.requireNonNull(factory.create(started, initial), "runtime");
            if (runtime.nodes() != null) {
                runtime.nodes().beginStartupSweep();
                drainPending(started, runtime.nodes());
            } else {
                clearPending();
            }
        } catch (RuntimeException failure) {
            runtime = null;
            unavailable = true;
            clearPending();
            LOGGER.error(
                    "Network runtime initialization failed; terminal data is unavailable (configuration epoch={})",
                    initial.epoch(),
                    failure);
        }
    }

    /** Releases only the matching server's terminal/node runtime and all pending/session references. */
    public void onServerStopped(ServerStoppedEvent event) {
        requireServerThread(event.getServer());
        if (server != event.getServer()) {
            return;
        }
        if (runtime != null) {
            if (runtime.exchanges() != null) runtime.exchanges().close();
            if (runtime.chunkLoading() != null) runtime.chunkLoading().close();
            if (runtime.directTransfers() != null) runtime.directTransfers().close();
            if (runtime.nodeMenus() != null) {
                runtime.nodeMenus().close();
            }
            runtime.terminal().close();
            if (runtime.topology() != null) {
                runtime.topology().close();
            }
            if (runtime.nodeManagement() != null) {
                runtime.nodeManagement().close();
            }
            if (runtime.nodes() != null) {
                runtime.nodes().close();
            }
        }
        runtime = null;
        clearPending();
        server = null;
        unavailable = false;
    }

    /** Releases the disconnected instance's terminal session and every node-edit lease owned by its player UUID. */
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            closePlayer(player);
        }
    }

    /** Closes the original terminal instance and releases its UUID's node-edit leases before player replacement. */
    public void onPlayerClone(PlayerEvent.Clone event) {
        if (event.getOriginal() instanceof ServerPlayer original) {
            closePlayer(original);
        }
    }

    /** Applies one immutable config candidate and advances one bounded node batch on the matching Pre tick. */
    public void onServerTick(ServerTickEvent.Pre event) {
        requireServerThread(event.getServer());
        if (server == event.getServer() && runtime != null) {
            runtime.terminal().applyConfiguration(config.latest());
            runtime.terminal().tick();
            if (runtime.chunkLoading() != null) runtime.chunkLoading().tick(config.latest());
            runtime.terminal().chunkOverviewTick();
            if (runtime.nodeMenus() != null) runtime.nodeMenus().tick();
            if (runtime.topology() != null) {
                runtime.topology().applyConfiguration(config.latest());
            }
            if (runtime.nodeManagement() != null) {
                runtime.nodeManagement().tick();
            }
            if (runtime.topology() != null) {
                runtime.topology().tick();
            }
            if (runtime.nodes() != null) {
                runtime.nodes().tick();
            }
            long currentTagGeneration = tagGeneration.get();
            if (currentTagGeneration != appliedTagGeneration) {
                appliedTagGeneration = currentTagGeneration;
                if (runtime.directTransfers() != null) runtime.directTransfers().tagsChanged();
                if (runtime.exchanges() != null) runtime.exchanges().tagsChanged();
            }
            if (runtime.directTransfers() != null)
                runtime.directTransfers().tick(event.getServer().overworld().getGameTime(), config.latest());
        }
    }

    /**
     * Routes actual sender intent on its server thread; absent/unavailable runtimes return fixed failures.
     * Close remains fire-and-forget and this boundary never initializes data implicitly.
     */
    public void handleNodeDirectory(
            ServerPlayer player, io.github.loongin.omniresonance.networking.NodeDirectoryRequest request) {
        requireServerThread(player.server);
        if (server == player.server && runtime != null) runtime.terminal().nodeDirectory(player, request);
    }

    public void handleChunkOverview(
            ServerPlayer player, io.github.loongin.omniresonance.networking.ChunkOverviewRequest request) {
        requireServerThread(player.server);
        if (server == player.server && runtime != null) runtime.terminal().chunkOverview(player, request);
    }

    public void handleStorage(
            ServerPlayer player, io.github.loongin.omniresonance.networking.TerminalStorageRequest request) {
        requireServerThread(player.server);
        if (server == player.server && runtime != null) runtime.terminal().storage(player, request);
    }

    public void handleInventory(
            ServerPlayer player, io.github.loongin.omniresonance.networking.DomainInventoryRequest request) {
        requireServerThread(player.server);
        if (server == player.server && runtime != null) runtime.terminal().inventory(player, request);
    }

    public @Nullable NetworkTerminalResponse handle(ServerPlayer player, NetworkTerminalRequest request) {
        MinecraftServer senderServer = player.server;
        requireServerThread(senderServer);
        if (server == senderServer && runtime != null) {
            return runtime.terminal().handle(player, request);
        }
        if (request instanceof NetworkTerminalRequest.Close) {
            return null;
        }
        NetworkTerminalResponse.Reason reason = server == senderServer && unavailable
                ? NetworkTerminalResponse.Reason.DATA_UNAVAILABLE
                : NetworkTerminalResponse.Reason.LOADING;
        return new NetworkTerminalResponse.Failure(request.viewId(), request.sessionId(), request.sequence(), reason);
    }

    /** Dispatches the shared transport to an already existing actual terminal session. */
    public @Nullable NetworkTerminalResponse handleTerminalTransfer(
            ServerPlayer player, io.github.loongin.omniresonance.networking.ManagementTransferMessage message) {
        requireServerThread(player.server);
        return server == player.server && runtime != null ? runtime.terminal().handleTransfer(player, message) : null;
    }

    private void closePlayer(ServerPlayer player) {
        MinecraftServer senderServer = player.server;
        requireServerThread(senderServer);
        if (server == senderServer && runtime != null) {
            runtime.terminal().disconnectNavigation(player);
            runtime.terminal().closePlayer(player);
            if (runtime.nodeMenus() != null) runtime.nodeMenus().disconnect(player);
            if (runtime.nodeManagement() != null) {
                runtime.nodeManagement().releasePlayer(player.getUUID());
            }
        }
    }

    private @Nullable NodeAuthorityService activeNodes(MinecraftServer eventServer) {
        return server == eventServer && runtime != null ? runtime.nodes() : null;
    }

    private void offerPendingPosition(MinecraftServer eventServer, GlobalPos position) {
        NodeReconciliationQueue queue = pendingQueue(eventServer);
        handlePendingOffer(queue.offerPosition(position));
    }

    private void offerPendingChunk(MinecraftServer eventServer, ServerLevel level, int chunkX, int chunkZ) {
        NodeReconciliationQueue queue = pendingQueue(eventServer);
        handlePendingOffer(queue.offerChunk(level.dimension(), chunkX, chunkZ));
    }

    private NodeReconciliationQueue pendingQueue(MinecraftServer eventServer) {
        if (pendingServer != null && pendingServer != eventServer) {
            throw new IllegalStateException("Overlapping pending node lifecycles");
        }
        pendingServer = eventServer;
        if (pendingNodeWork == null) {
            pendingNodeWork = NodeReconciliationQueue.production();
        }
        return pendingNodeWork;
    }

    private void handlePendingOffer(NodeReconciliationQueue.OfferResult result) {
        if (result == NodeReconciliationQueue.OfferResult.OVERFLOW && !pendingOverflowLogged) {
            pendingOverflowLogged = true;
            LOGGER.error(
                    "Pre-start node observations reached hard limit {}; later lifecycle events must retry them",
                    NodeReconciliationQueue.PRODUCTION_MAXIMUM);
        }
    }

    private void drainPending(MinecraftServer started, NodeAuthorityService nodes) {
        if (pendingServer != null && pendingServer != started) {
            clearPending();
            return;
        }
        if (pendingNodeWork != null) {
            NodeReconciliationQueue.Work work;
            while ((work = pendingNodeWork.poll().orElse(null)) != null) {
                if (work instanceof NodeReconciliationQueue.Position position) {
                    nodes.enqueuePosition(position.position());
                } else if (work instanceof NodeReconciliationQueue.Chunk chunk) {
                    nodes.enqueueChunk(chunk.dimension(), chunk.x(), chunk.z());
                }
            }
        }
        clearPending();
    }

    private void clearPending() {
        if (pendingNodeWork != null) {
            pendingNodeWork.clear();
        }
        pendingNodeWork = null;
        pendingServer = null;
        pendingOverflowLogged = false;
    }

    private static RuntimeComponents createRuntime(MinecraftServer server, ServerConfig.State initial) {
        var adapters = io.github.loongin.omniresonance.transfer.ResourceAdapterDirectory.nativeDefaults();
        SavedNetworkRepository repository = new SavedNetworkRepository(
                server.overworld().getDataStorage(),
                server.getWorldPath(LevelResource.ROOT).resolve("data"),
                adapters);
        List<SavedNetworkRepository.LoadedNetwork> loaded = repository.loadNetworkData();
        NetworkDirectory networks = new NetworkDirectory(loaded.stream()
                .map(SavedNetworkRepository.LoadedNetwork::metadata)
                .toList());
        List<NetworkNodeDirectory.Entry> entries = new ArrayList<>();
        for (SavedNetworkRepository.LoadedNetwork network : loaded) {
            if (networks.find(network.metadata().id()).isPresent()) {
                for (NetworkNodeRecord node : network.nodes()) {
                    entries.add(
                            new NetworkNodeDirectory.Entry(network.metadata().id(), node));
                }
            }
        }
        NetworkNodeDirectory nodes = new NetworkNodeDirectory(entries);
        NetworkCreationService creation = new NetworkCreationService(repository, networks, UUID::randomUUID);
        NodeAuthorityService authority = new NodeAuthorityService(server, repository, nodes, UUID::randomUUID);
        EditLockTable locks = new EditLockTable();
        NodeManagementService nodeManagement =
                new NodeManagementService(server, networks, repository, nodes, authority, locks);
        NetworkTopologyService topology =
                new NetworkTopologyService(server, networks, repository, nodes, locks, initial, UUID::randomUUID);
        ResourceDirectRuntime directTransfers = new ResourceDirectRuntime(server, repository, nodes, initial);
        io.github.loongin.omniresonance.filter.ItemFilterService filters =
                new io.github.loongin.omniresonance.filter.ItemFilterService(
                        server,
                        repository,
                        networks,
                        locks,
                        topology::settingsSnapshot,
                        directTransfers::ownerLibraryChanged,
                        UUID::randomUUID);
        NodeMenuService nodeMenus = new NodeMenuService(
                server, nodeManagement, topology, networks, UUID::randomUUID, filters, directTransfers::status);
        nodeMenus.installDomainStatus(directTransfers::domainStatus);
        ServerPlayerDirectory players = new ServerPlayerDirectory(server);
        NetworkAdministrationService administration =
                new NetworkAdministrationService(server, repository, networks, locks, players, initial);
        NetworkSettingsService networkSettings =
                new NetworkSettingsService(server, repository, networks, locks, players);
        NetworkTerminalService terminal = new NetworkTerminalService(
                server,
                networks,
                creation,
                topology,
                administration,
                networkSettings,
                nodeMenus,
                initial,
                UUID::randomUUID,
                (player, response) -> net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, response));
        terminal.installFilters(filters);
        terminal.installInventory(
                id -> repository.domainStorage(id).activate().orElse(null),
                (player, frame) -> net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, frame));
        terminal.installStorageAccess(
                id -> repository.domainStorage(id).activate().orElse(null),
                directTransfers::recovery,
                (player, response) -> net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, response));
        ExchangeRuntime exchanges = new ExchangeRuntime(
                server, repository, networks, topology::settingsSnapshot, directTransfers::openFilterTag);
        terminal.installExchange(exchanges.terminalController(), repository);
        if (net.neoforged.fml.ModList.get().isLoaded("ae2")) {
            var interfaces = new io.github.loongin.omniresonance.compat.ae2.Ae2InterfaceRuntime(
                    server, repository, networks, nodes, authority, nodeManagement, topology::settingsSnapshot);
            directTransfers.installSampleWork(new TerminalAuxiliaryWork(
                    filters::sampleStep, terminal::inventoryStep, exchanges::step, interfaces::step));
        } else
            directTransfers.installSampleWork(
                    new TerminalAuxiliaryWork(filters::sampleStep, terminal::inventoryStep, exchanges::step));
        var chunkLoading = new io.github.loongin.omniresonance.chunkloading.ChunkLoadingRuntime(
                server, repository, networks, nodes, authority, initial);
        var diagnostics = new io.github.loongin.omniresonance.network.NetworkDiagnosticsService(
                server, repository, networks, chunkLoading, topology::settingsSnapshot);
        diagnostics.installRuntime(network -> {
            var counts = directTransfers.queueCounts(network);
            return new io.github.loongin.omniresonance.network.NetworkDiagnosticsSnapshot.RuntimeStats(
                    directTransfers.telemetrySnapshot(network),
                    counts.due(),
                    counts.backoff(),
                    terminal.pendingSyncTasks(network),
                    exchanges.diagnostics(network, server.overworld().getGameTime()));
        });
        terminal.installDiagnostics(diagnostics);
        nodeManagement.installChunkAdmission(chunkLoading::admission);
        nodeMenus.installChunkStatus(chunkLoading::status);
        terminal.installNodeDirectory(new io.github.loongin.omniresonance.network.NodeDirectoryService(
                server,
                repository,
                chunkLoading,
                nodeManagement,
                topology,
                nodeMenus,
                filters,
                (player, page) -> net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, page)));
        terminal.installNavigation(new io.github.loongin.omniresonance.node.NodeNavigationService(
                server,
                nodeManagement,
                authority,
                initial.settings().navigation(),
                (player, frame) -> net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, frame)));
        terminal.installChunkOverview(
                chunkLoading,
                (player, page) -> net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, page));
        return new RuntimeComponents(
                terminal, authority, nodeManagement, nodeMenus, topology, directTransfers, chunkLoading, exchanges);
    }

    private static void requireServerThread(MinecraftServer server) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Network runtime lifecycle accessed outside the server thread");
        }
    }
}
