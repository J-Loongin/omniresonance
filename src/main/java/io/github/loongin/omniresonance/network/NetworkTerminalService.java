// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.network;

import io.github.loongin.omniresonance.config.ServerConfig;
import io.github.loongin.omniresonance.config.ServerSettings;
import io.github.loongin.omniresonance.networking.ChannelPage;
import io.github.loongin.omniresonance.networking.ChannelSummary;
import io.github.loongin.omniresonance.networking.NetworkDeletionSummary;
import io.github.loongin.omniresonance.networking.NetworkMemberPage;
import io.github.loongin.omniresonance.networking.NetworkSettingsSummary;
import io.github.loongin.omniresonance.networking.NetworkSummary;
import io.github.loongin.omniresonance.networking.NetworkTerminalPage;
import io.github.loongin.omniresonance.networking.NetworkTerminalRequest;
import io.github.loongin.omniresonance.networking.NetworkTerminalResponse;
import io.github.loongin.omniresonance.networking.NetworkTerminalState;
import io.github.loongin.omniresonance.networking.OnlinePlayerPage;
import io.github.loongin.omniresonance.networking.OnlinePlayerSummary;
import io.github.loongin.omniresonance.networking.TopologyDeletionSummary;
import io.github.loongin.omniresonance.networking.TunnelPage;
import io.github.loongin.omniresonance.networking.TunnelSummary;
import io.github.loongin.omniresonance.node.NodeMenuService;
import io.github.loongin.omniresonance.persistence.SavedNetworkRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One world's server-thread terminal sessions and authority boundary. The runtime owns at most one
 * session per connected player UUID, bound additionally to the actual player object, view and nonce.
 * Logout, clone, replacement and shutdown invalidate sessions; no cursor history or directory mirror is retained.
 * All authoritative work is synchronous on the server thread, with no simulation or synchronous saves.
 */
public final class NetworkTerminalService {
    private @Nullable io.github.loongin.omniresonance.exchange.ExchangeTerminalWire exchangeWire;

    public void installExchange(
            io.github.loongin.omniresonance.exchange.ExchangeTerminalController controller,
            SavedNetworkRepository repository) {
        requireServerThread();
        if (exchangeWire != null || !sessions.isEmpty())
            throw new IllegalStateException("Exchange installation is not available");
        exchangeWire = new io.github.loongin.omniresonance.exchange.ExchangeTerminalWire(
                controller, repository, directory, pool());
    }

    /** Exchange shares the existing actual-player session and strict request sequence; network is never supplied by the payload. */
    public io.github.loongin.omniresonance.networking.ExchangeFrame exchange(
            ServerPlayer sender, io.github.loongin.omniresonance.networking.ExchangeRequest request) {
        requireServerThread();
        var session = sessions.get(sender.getUUID());
        if (closed
                || exchangeWire == null
                || sender.server != server
                || session == null
                || session.player != sender
                || !session.viewId.equals(request.view())
                || !session.id.equals(request.session()))
            return io.github.loongin.omniresonance.exchange.ExchangeTerminalWire.error(request, "session_expired");
        if (session.lastSequence == Long.MAX_VALUE || request.sequence() != session.lastSequence + 1)
            return io.github.loongin.omniresonance.exchange.ExchangeTerminalWire.error(request, "stale_request");
        session.lastSequence = request.sequence();
        try {
            requireLayer(session, Layer.NETWORK);
            UUID network = requireSelectedNetwork(session);
            topology().inspectNetwork(sender, network);
            if (request.kind() == io.github.loongin.omniresonance.networking.ExchangeRequest.OPEN) {
                clearStatus(session);
                session.chunkActive = false;
                if (nodeDirectory != null) nodeDirectory.closePlayer(sender);
                if (inventorySync != null) inventorySync.cancel(sender.getUUID());
                if (storageAccess != null) storageAccess.close(sender.getUUID());
                session.inventoryActive = false;
                exchangeWire.close(sender, session.id);
                session.exchange =
                        new io.github.loongin.omniresonance.exchange.ExchangeTerminalWire.State(request.generation());
            } else if (session.exchange == null || !session.exchange.generation.equals(request.generation()))
                return io.github.loongin.omniresonance.exchange.ExchangeTerminalWire.error(request, "stale_request");
            if (request.kind() == io.github.loongin.omniresonance.networking.ExchangeRequest.CLOSE) {
                exchangeWire.close(sender, session.id);
                session.exchange = null;
                return io.github.loongin.omniresonance.exchange.ExchangeTerminalWire.empty(
                        request, io.github.loongin.omniresonance.networking.ExchangeFrame.DONE, false);
            }
            return exchangeWire.handle(sender, network, session.exchange, request, settings);
        } catch (io.github.loongin.omniresonance.exchange.ExchangeManagementService.CommittedAuditFailure failure) {
            closePlayer(sender);
            return io.github.loongin.omniresonance.exchange.ExchangeTerminalWire.error(request, "committed");
        } catch (SecurityException | NetworkTopologyService.Rejected denied) {
            exchangeWire.close(sender, session.id);
            return io.github.loongin.omniresonance.exchange.ExchangeTerminalWire.error(request, "no_access");
        } catch (RuntimeException failure) {
            exchangeWire.close(sender, session.id);
            if (session.exchange != null && session.exchange.committed()) {
                closePlayer(sender);
                return io.github.loongin.omniresonance.exchange.ExchangeTerminalWire.error(request, "committed");
            }
            String message = failure.getMessage();
            String reason = message != null && (message.contains("quota") || message.contains("capacity"))
                    ? "quota"
                    : message != null && message.contains("already paired")
                            ? "paired"
                            : message != null && message.contains("Duplicate channel name")
                                    ? "channel_name"
                                    : "unavailable";
            return io.github.loongin.omniresonance.exchange.ExchangeTerminalWire.error(request, reason);
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(NetworkTerminalService.class);
    private @Nullable NetworkDiagnosticsService diagnostics;
    /** Installs the server-owned read model before terminal sessions are exposed; performs no storage access. */
    public void installDiagnostics(NetworkDiagnosticsService service) {
        requireServerThread();
        if (diagnostics != null || closed) throw new IllegalStateException("Diagnostics already installed or closed");
        diagnostics = java.util.Objects.requireNonNull(service);
    }
    /** Owner-thread count of pending sync work only; does not open an inventory view or activate storage. */
    public int pendingSyncTasks(UUID network) {
        requireServerThread();
        return inventorySync == null ? 0 : inventorySync.pendingTasks(network);
    }

    /** Trusted server adapter lookup; commands and UI must enforce their own permission boundary. */
    public @Nullable NetworkDiagnosticsService diagnostics() {
        requireServerThread();
        return closed ? null : diagnostics;
    }

    private BiConsumer<ServerPlayer, io.github.loongin.omniresonance.networking.NetworkStatusFrame> statusSender =
            (player, frame) -> net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, frame);

    public void installStatusSender(
            BiConsumer<ServerPlayer, io.github.loongin.omniresonance.networking.NetworkStatusFrame> sender) {
        requireServerThread();
        if (!sessions.isEmpty()) throw new IllegalStateException("Status sessions already active");
        statusSender = Objects.requireNonNull(sender);
    }

    /** Authenticated read-only subscription; network identity and role always come from the terminal session. */
    public void status(ServerPlayer player, io.github.loongin.omniresonance.networking.NetworkStatusRequest request) {
        requireServerThread();
        var session = sessions.get(player.getUUID());
        if (closed
                || diagnostics == null
                || session == null
                || session.player != player
                || !session.viewId.equals(request.view())
                || !session.id.equals(request.session())) return;
        if (!request.open()) {
            if (request.generation() == session.statusGeneration) session.statusActive = false;
            return;
        }
        if (request.generation() <= session.statusGeneration) return;
        session.statusGeneration = request.generation();
        session.statusSequence = 0;
        session.statusActive = true;
        session.chunkActive = false;
        if (nodeDirectory != null) nodeDirectory.closePlayer(player);
        if (inventorySync != null) inventorySync.cancel(player.getUUID());
        if (storageAccess != null) storageAccess.close(player.getUUID());
        session.inventoryActive = false;
        if (gameTick() >= session.nextStatusTick) publishStatus(session);
    }

    private void publishStatus(Session session) {
        io.github.loongin.omniresonance.network.NetworkDiagnosticsSnapshot snapshot = null;
        try {
            requireLayer(session, Layer.NETWORK);
            UUID network = requireSelectedNetwork(session);
            topology().inspectNetwork(session.player, network);
            snapshot = diagnostics.inspect(network).orElse(null);
        } catch (RuntimeException unavailable) {
            session.statusActive = false;
        }
        if (snapshot == null) session.statusActive = false;
        String version = net.neoforged.fml.ModList.get()
                .getModContainerById("omniresonance")
                .orElseThrow()
                .getModInfo()
                .getVersion()
                .toString();
        statusSender.accept(
                session.player,
                new io.github.loongin.omniresonance.networking.NetworkStatusFrame(
                        session.id, session.statusGeneration, ++session.statusSequence, snapshot, version));
        session.nextStatusTick = gameTick() + 20;
    }

    private void clearStatus(Session session) {
        if (!session.statusActive) return;
        session.statusActive = false;
        statusSender.accept(
                session.player,
                new io.github.loongin.omniresonance.networking.NetworkStatusFrame(
                        session.id, session.statusGeneration, ++session.statusSequence, null, ""));
    }

    private final MinecraftServer server;
    private @Nullable io.github.loongin.omniresonance.filter.ItemFilterService filters;
    private final NetworkDirectory directory;
    private final NetworkCreationService creation;
    private final @Nullable NetworkTopologyService topology;
    private final @Nullable NetworkAdministrationService administration;
    private final @Nullable NetworkSettingsService networkSettings;
    private final @Nullable NodeMenuService nodeMenus;
    private final BiConsumer<ServerPlayer, NetworkTerminalResponse> directReplies;
    private BiConsumer<ServerPlayer, io.github.loongin.omniresonance.networking.ManagementTransferMessage>
            transferReplies =
                    (player, message) -> net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, message);

    /** Installs the synchronous shared-transport sender before any sessions open; server thread only. */
    public void installTransferSender(
            BiConsumer<ServerPlayer, io.github.loongin.omniresonance.networking.ManagementTransferMessage> sender) {
        requireServerThread();
        if (!sessions.isEmpty()) throw new IllegalStateException("Terminal transport already active");
        transferReplies = Objects.requireNonNull(sender);
    }

    private @Nullable io.github.loongin.omniresonance.node.NodeNavigationService navigation;

    public void installNavigation(io.github.loongin.omniresonance.node.NodeNavigationService value) {
        requireServerThread();
        if (navigation != null) throw new IllegalStateException("Navigation already installed");
        navigation = Objects.requireNonNull(value);
    }

    public void disconnectNavigation(ServerPlayer player) {
        requireServerThread();
        if (navigation != null) navigation.disconnect(player);
    }

    private @Nullable NodeDirectoryService nodeDirectory;

    public void installNodeDirectory(NodeDirectoryService value) {
        requireServerThread();
        if (nodeDirectory != null) throw new IllegalStateException("Node directory installed");
        nodeDirectory = Objects.requireNonNull(value);
    }

    public void nodeDirectory(
            ServerPlayer player, io.github.loongin.omniresonance.networking.NodeDirectoryRequest request) {
        requireServerThread();
        var session = sessions.get(player.getUUID());
        if (closed
                || nodeDirectory == null
                || session == null
                || session.player != player
                || !session.viewId.equals(request.view())
                || !session.id.equals(request.session())
                || !session.chunkActive
                || !session.nodeOverview
                || session.chunkGeneration != request.generation()
                || request.sequence() <= session.nodeDirectorySequence) return;
        session.nodeDirectorySequence = request.sequence();
        try {
            requireLayer(session, Layer.NETWORK);
            nodeDirectory.handle(player, requireSelectedNetwork(session), request);
        } catch (RuntimeException rejected) {
            nodeDirectory.unavailable(player, request);
            player.displayClientMessage(
                    net.minecraft.network.chat.Component.translatable("omniresonance.navigation.rejected"), true);
        }
    }

    private final Supplier<UUID> sessionIds;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final long configurationEpoch;
    private final boolean configurationLoaded;
    private ServerSettings settings;
    private long configurationRevision;
    private @Nullable DomainInventorySync inventorySync;
    private @Nullable TerminalStorageService storageAccess;
    private final TerminalInventoryWork inventoryWork = new TerminalInventoryWork(
            budget -> {
                if (storageAccess != null) storageAccess.tick(budget);
            },
            this::syncInventory);
    private @Nullable java.util.function.BiConsumer<
                    ServerPlayer, io.github.loongin.omniresonance.networking.DomainInventoryFrame>
            inventoryReplies;
    private @Nullable io.github.loongin.omniresonance.chunkloading.ChunkLoadingRuntime chunkRuntime;
    private @Nullable BiConsumer<ServerPlayer, io.github.loongin.omniresonance.networking.ChunkOverviewPage>
            chunkReplies;

    public void installChunkOverview(
            io.github.loongin.omniresonance.chunkloading.ChunkLoadingRuntime runtime,
            BiConsumer<ServerPlayer, io.github.loongin.omniresonance.networking.ChunkOverviewPage> sender) {
        requireServerThread();
        if (chunkRuntime != null) throw new IllegalStateException("Overview already installed");
        chunkRuntime = Objects.requireNonNull(runtime);
        chunkReplies = Objects.requireNonNull(sender);
    }

    public void chunkOverview(
            ServerPlayer player, io.github.loongin.omniresonance.networking.ChunkOverviewRequest request) {
        requireServerThread();
        var session = sessions.get(player.getUUID());
        if (closed
                || chunkRuntime == null
                || session == null
                || session.player != player
                || player.server != server
                || !session.viewId.equals(request.view())
                || !session.id.equals(request.session())) return;
        if (request.action() == io.github.loongin.omniresonance.networking.ChunkOverviewRequest.Action.OPEN
                || request.action()
                        == io.github.loongin.omniresonance.networking.ChunkOverviewRequest.Action.OPEN_NODES) {
            if (request.generation() <= session.chunkGeneration) return;
            session.chunkGeneration = request.generation();
            session.nodeDirectorySequence = 0;
            if (nodeDirectory != null) nodeDirectory.closePlayer(player);
            session.chunkActive = true;
            session.nodeOverview = request.action()
                    == io.github.loongin.omniresonance.networking.ChunkOverviewRequest.Action.OPEN_NODES;
            session.chunkPending = false;
            session.chunkSequence = 0;
            session.chunkAnchor = 0;
            session.chunkBefore = false;
            if (inventorySync != null) inventorySync.cancel(player.getUUID());
            if (storageAccess != null) storageAccess.close(player.getUUID());
            session.inventoryActive = false;
        } else if (!session.chunkActive || request.generation() != session.chunkGeneration) return;
        if (request.sequence() <= session.chunkSequence) return;
        if (request.action() == io.github.loongin.omniresonance.networking.ChunkOverviewRequest.Action.CLOSE) {
            session.chunkActive = false;
            return;
        }
        if (session.chunkPending) return;
        session.chunkSequence = request.sequence();
        session.chunkRejected = false;
        try {
            requireLayer(session, Layer.NETWORK);
            UUID network = requireSelectedNetwork(session);
            topology().inspectNetwork(player, network);
            if (request.action() == io.github.loongin.omniresonance.networking.ChunkOverviewRequest.Action.PAGE) {
                session.chunkAnchor = request.anchor();
                session.chunkBefore = request.before();
            } else if (request.action()
                    == io.github.loongin.omniresonance.networking.ChunkOverviewRequest.Action.HIGHLIGHT) {
                if (navigation == null || !session.nodeOverview)
                    throw new IllegalStateException("Node view required for navigation");
                navigation.highlight(player, network, request.node());
            } else if (request.action()
                    == io.github.loongin.omniresonance.networking.ChunkOverviewRequest.Action.TELEPORT) {
                if (navigation == null || !session.nodeOverview)
                    throw new IllegalStateException("Node view required for navigation");
                navigation.teleport(player, network, request.node());
            }
        } catch (RuntimeException rejected) {
            session.chunkRejected = true;
            player.displayClientMessage(
                    net.minecraft.network.chat.Component.translatable("omniresonance.navigation.rejected"), true);
        }
        session.chunkPending = true;
        session.chunkNextTick = 0;
    }
    /** Publishes bounded current windows after chunk-runtime reconciliation; no edit locks are held by subscriptions. */
    public void chunkOverviewTick() {
        requireServerThread();
        if (closed || chunkRuntime == null) return;
        long now = gameTick();
        for (var session : sessions.values())
            if (session.chunkActive && now >= session.chunkNextTick) {
                boolean available = true;
                UUID network = session.networkId;
                NetworkMetadata metadata = null;
                try {
                    requireLayer(session, Layer.NETWORK);
                    metadata = topology().inspectNetwork(session.player, requireSelectedNetwork(session));
                } catch (RuntimeException denied) {
                    available = false;
                    session.chunkActive = false;
                }
                var rows =
                        new java.util.ArrayList<io.github.loongin.omniresonance.networking.ChunkOverviewPage.Entry>();
                boolean previous = false, next = false;
                int total = 0, ownerUsed = 0;
                if (available) {
                    var page = chunkRuntime.page(network, session.chunkAnchor, session.chunkBefore);
                    previous = page.previous();
                    next = page.next();
                    total = page.total();
                    ownerUsed = chunkRuntime.ownerCount(metadata.ownerId());
                    for (var node : page.entries())
                        rows.add(new io.github.loongin.omniresonance.networking.ChunkOverviewPage.Entry(
                                node.nodeId(),
                                node.nodeNumber(),
                                node.revision(),
                                node.name().value(),
                                node.position().dimension().location(),
                                node.position().pos(),
                                node.mode(),
                                node.enabled(),
                                node.chunkLoadingRequested(),
                                chunkRuntime.status(node.nodeId())));
                }
                var cfg = settings.chunkLoading();
                try {
                    chunkReplies.accept(
                            session.player,
                            new io.github.loongin.omniresonance.networking.ChunkOverviewPage(
                                    session.id,
                                    session.chunkGeneration,
                                    session.chunkSequence,
                                    available,
                                    session.chunkRejected,
                                    available && cfg.enabled(),
                                    ownerUsed,
                                    available ? cfg.perOwner() : 0,
                                    available ? chunkRuntime.reservedCount() : 0,
                                    available ? cfg.server() : 0,
                                    total,
                                    previous,
                                    next,
                                    rows));
                } catch (RuntimeException failed) {
                    session.chunkActive = false;
                    LOGGER.error("Chunk overview send failed; subscription stopped", failed);
                }
                session.chunkPending = false;
                session.chunkRejected = false;
                session.chunkNextTick = now + 20;
            }
    }

    private boolean closed;

    /**
     * Retains caller-owned world collaborators and validated immutable settings on the server thread.
     * Invalid arguments or wrong-thread construction fail before session creation; no files or networks are created.
     */
    public NetworkTerminalService(
            MinecraftServer server,
            NetworkDirectory directory,
            NetworkCreationService creation,
            ServerConfig.State initialConfig,
            Supplier<UUID> sessionIds) {
        this(server, directory, creation, null, initialConfig, sessionIds);
    }

    /** Production constructor sharing the topology authority while retaining one terminal session per player. */
    public NetworkTerminalService(
            MinecraftServer server,
            NetworkDirectory directory,
            NetworkCreationService creation,
            @Nullable NetworkTopologyService topology,
            ServerConfig.State initialConfig,
            Supplier<UUID> sessionIds) {
        this(
                server,
                directory,
                creation,
                topology,
                null,
                null,
                initialConfig,
                sessionIds,
                PacketDistributor::sendToPlayer);
    }

    /** Composes member authority and scoped revocation into the same existing terminal session lifecycle. */
    public NetworkTerminalService(
            MinecraftServer server,
            NetworkDirectory directory,
            NetworkCreationService creation,
            @Nullable NetworkTopologyService topology,
            @Nullable NetworkAdministrationService administration,
            @Nullable NodeMenuService nodeMenus,
            ServerConfig.State initialConfig,
            Supplier<UUID> sessionIds,
            BiConsumer<ServerPlayer, NetworkTerminalResponse> directReplies) {
        this(
                server,
                directory,
                creation,
                topology,
                administration,
                null,
                nodeMenus,
                initialConfig,
                sessionIds,
                directReplies);
    }

    /** Composes settings authority with the existing hierarchy and scoped session-notification lifecycle. */
    public NetworkTerminalService(
            MinecraftServer server,
            NetworkDirectory directory,
            NetworkCreationService creation,
            @Nullable NetworkTopologyService topology,
            @Nullable NetworkAdministrationService administration,
            @Nullable NetworkSettingsService networkSettings,
            @Nullable NodeMenuService nodeMenus,
            ServerConfig.State initialConfig,
            Supplier<UUID> sessionIds,
            BiConsumer<ServerPlayer, NetworkTerminalResponse> directReplies) {
        this.server = Objects.requireNonNull(server, "server");
        requireServerThread();
        this.directory = Objects.requireNonNull(directory, "directory");
        this.creation = Objects.requireNonNull(creation, "creation");
        this.topology = topology;
        this.administration = administration;
        this.networkSettings = networkSettings;
        this.nodeMenus = nodeMenus;
        this.directReplies = Objects.requireNonNull(directReplies, "directReplies");
        this.sessionIds = Objects.requireNonNull(sessionIds, "sessionIds");
        Objects.requireNonNull(initialConfig, "initialConfig");
        if (initialConfig.epoch() < 0 || initialConfig.revision() < 0) {
            throw new IllegalArgumentException("Invalid configuration identity");
        }
        configurationEpoch = initialConfig.epoch();
        configurationLoaded = initialConfig.loaded();
        configurationRevision = initialConfig.revision();
        settings = initialConfig.loaded()
                ? Objects.requireNonNull(initialConfig.settings(), "settings")
                : ServerSettings.defaults();
        creation.configureAudit(settings.auditEntriesPerScope());
    }

    /** Installs the matching owner-library authority once before sessions exist; no player data is created. */
    public void installFilters(io.github.loongin.omniresonance.filter.ItemFilterService filters) {
        requireServerThread();
        if (this.filters != null || !sessions.isEmpty())
            throw new IllegalStateException("Filter authority already active");
        this.filters = Objects.requireNonNull(filters, "filters");
    }

    /** Current immutable settings on the server thread, for the composed filter service's quota checks. */
    public ServerSettings settingsSnapshot() {
        requireServerThread();
        return settings;
    }

    /**
     * Handles untrusted intent using only the actual server sender as owner. Close returns no reply.
     * Validation failures return fixed reasons without modifying network records; admitted Page/Create
     * requests consume exactly one sequence before execution, including rejected operations. Unexpected
     * internal failures invalidate only the affected session so the failed intent cannot replay.
     * Proven pre-commit failures retain the session; explicit reopening starts a new intent.
     */
    public @Nullable NetworkTerminalResponse handle(ServerPlayer sender, NetworkTerminalRequest request) {
        requireServerThread();
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(request, "request");
        if (request instanceof NetworkTerminalRequest.Close) {
            Session session = sessions.get(sender.getUUID());
            if (matches(session, sender, request)) {
                closePlayer(sender);
            }
            return null;
        }
        if (closed) {
            return failure(request, NetworkTerminalResponse.Reason.SESSION_EXPIRED);
        }
        if (sender.server != server) {
            return failure(request, NetworkTerminalResponse.Reason.INVALID_REQUEST);
        }
        try {
            if (request instanceof NetworkTerminalRequest.Open) {
                return open(sender, request.viewId());
            }
            Session session = sessions.get(sender.getUUID());
            if (!matches(session, sender, request)) {
                return failure(request, NetworkTerminalResponse.Reason.SESSION_EXPIRED);
            }
            if (session.lastSequence == Long.MAX_VALUE || request.sequence() != session.lastSequence + 1) {
                return failure(request, NetworkTerminalResponse.Reason.STALE_REQUEST);
            }
            session.lastSequence = request.sequence();
            if (!(request instanceof NetworkTerminalRequest.Heartbeat)) {
                if (session.exchange != null && exchangeWire != null) {
                    exchangeWire.close(sender, session.id);
                    session.exchange = null;
                }
                clearStatus(session);
                session.chunkActive = false;
                if (nodeDirectory != null) nodeDirectory.closePlayer(sender);
            }
            if (session.inventoryActive && !(request instanceof NetworkTerminalRequest.Heartbeat)) {
                if (inventorySync != null) inventorySync.cancel(sender.getUUID());
                if (storageAccess != null) storageAccess.close(sender.getUUID());
                session.inventoryActive = false;
            }
            if (request instanceof NetworkTerminalRequest.Page page) {
                NetworkTerminalPage result;
                try {
                    result = page(sender.getUUID(), page.anchor(), page.backwards());
                } catch (IllegalArgumentException invalidAnchor) {
                    return failure(request, NetworkTerminalResponse.Reason.INVALID_REQUEST);
                }
                return new NetworkTerminalResponse.Success(
                        request.viewId(), session.id, request.sequence(), result, null);
            }
            if (request instanceof NetworkTerminalRequest.Create create) {
                NetworkMetadata created = creation.create(sender.getUUID(), create.name(), settings.networksPerOwner());
                creation.recordCreation(sender, created);
                return new NetworkTerminalResponse.Success(
                        request.viewId(),
                        session.id,
                        request.sequence(),
                        page(sender.getUUID(), null, false),
                        summary(created));
            }
            return handleTopology(sender, session, request);
        } catch (io.github.loongin.omniresonance.filter.ItemFilterService.Rejected rejected) {
            return failure(
                    request,
                    switch (rejected.reason()) {
                        case NO_ACCESS -> NetworkTerminalResponse.Reason.NO_ACCESS;
                        case UNAVAILABLE -> NetworkTerminalResponse.Reason.DATA_UNAVAILABLE;
                        case LOCKED -> NetworkTerminalResponse.Reason.LOCKED;
                        case LOCK_EXPIRED -> NetworkTerminalResponse.Reason.LOCK_EXPIRED;
                        case STALE_REVISION -> NetworkTerminalResponse.Reason.STALE_REVISION;
                        case INVALID_REQUEST -> NetworkTerminalResponse.Reason.INVALID_REQUEST;
                        case NAME_CONFLICT -> NetworkTerminalResponse.Reason.NAME_CONFLICT;
                        case QUOTA_REACHED -> NetworkTerminalResponse.Reason.QUOTA_REACHED;
                    });
        } catch (NetworkAdministrationService.Rejected rejected) {
            return failure(request, membershipReason(rejected.reason()));
        } catch (NetworkSettingsService.Rejected rejected) {
            return failure(request, settingsReason(rejected.reason()));
        } catch (NetworkCreationService.Rejected rejected) {
            return failure(
                    request,
                    switch (rejected.reason()) {
                        case INVALID_NAME -> NetworkTerminalResponse.Reason.INVALID_NAME;
                        case NAME_CONFLICT -> NetworkTerminalResponse.Reason.NAME_CONFLICT;
                        case QUOTA_REACHED -> NetworkTerminalResponse.Reason.QUOTA_REACHED;
                    });
        } catch (SavedNetworkRepository.RegistrationFailure failure) {
            closePlayer(sender);
            logFailure(sender, request, "registration outcome unknown; session invalidated", failure);
            return failure(request, NetworkTerminalResponse.Reason.INTERNAL_ERROR);
        } catch (NetworkTopologyService.Rejected rejected) {
            return failure(request, topologyReason(rejected.reason()));
        } catch (IllegalStateException unavailable) {
            logFailure(sender, request, "data unavailable; request ended", unavailable);
            return failure(request, NetworkTerminalResponse.Reason.DATA_UNAVAILABLE);
        } catch (IllegalArgumentException rejected) {
            if (request instanceof NetworkTerminalRequest.CreateTunnel
                    || request instanceof NetworkTerminalRequest.RenameTunnel) {
                return failure(request, NetworkTerminalResponse.Reason.INVALID_NAME);
            }
            return failure(request, NetworkTerminalResponse.Reason.INVALID_REQUEST);
        } catch (ArithmeticException rejected) {
            logFailure(sender, request, "internal arithmetic rejection", rejected);
            return failure(request, NetworkTerminalResponse.Reason.INTERNAL_ERROR);
        } catch (RuntimeException failure) {
            closePlayer(sender);
            logFailure(sender, request, "unexpected failure; session invalidated", failure);
            return failure(request, NetworkTerminalResponse.Reason.INTERNAL_ERROR);
        }
    }

    /** Applies only newer validated snapshots from this loaded epoch on the server thread, never removing records. */
    public void applyConfiguration(ServerConfig.State state) {
        requireServerThread();
        Objects.requireNonNull(state, "state");
        if (!closed
                && configurationLoaded
                && state.loaded()
                && state.epoch() == configurationEpoch
                && state.revision() > configurationRevision
                && state.settings() != null) {
            settings = state.settings();
            configurationRevision = state.revision();
        }
        if (!closed) creation.configureAudit(settings.auditEntriesPerScope());
        if (!closed && administration != null) administration.applyConfiguration(state);
    }

    /** Installs the server-owned inventory publisher; source activation happens only for authorized admitted sessions. */
    public void installInventory(
            java.util.function.Function<UUID, io.github.loongin.omniresonance.storage.DomainLedger> source,
            java.util.function.BiConsumer<ServerPlayer, io.github.loongin.omniresonance.networking.DomainInventoryFrame>
                    sender) {
        requireServerThread();
        if (inventorySync != null) throw new IllegalStateException("Inventory runtime already installed");
        inventoryReplies = java.util.Objects.requireNonNull(sender);
        inventorySync = new DomainInventorySync(source, (playerId, frame) -> {
            Session session = sessions.get(playerId);
            if (session != null
                    && session.id.equals(frame.session())
                    && session.inventoryGeneration == frame.generation()) sender.accept(session.player, frame);
        });
    }

    /** Installs native storage work with the existing session/snapshot authority and shared scheduler budget. */
    public void installStorageAccess(
            java.util.function.Function<UUID, io.github.loongin.omniresonance.storage.DomainLedger> ledgers,
            java.util.function.Function<UUID, io.github.loongin.omniresonance.recovery.RecoveryBuffer> recovery,
            java.util.function.BiConsumer<
                            ServerPlayer, io.github.loongin.omniresonance.networking.TerminalStorageResponse>
                    sender) {
        requireServerThread();
        if (storageAccess != null || inventorySync == null)
            throw new IllegalStateException("Invalid storage installation");
        storageAccess = new TerminalStorageService(
                () -> settings,
                (player, sessionId, generation, network) -> {
                    Session session = sessions.get(player.getUUID());
                    if (closed
                            || session == null
                            || session.player != player
                            || !session.inventoryActive
                            || !session.id.equals(sessionId)
                            || session.inventoryGeneration != generation
                            || !network.equals(session.networkId)
                            || !inventorySync.ready(player.getUUID(), sessionId, generation)) return false;
                    try {
                        requireLayer(session, Layer.NETWORK);
                        topology().inspectNetwork(player, network);
                        return true;
                    } catch (RuntimeException denied) {
                        return false;
                    }
                },
                ledgers,
                recovery,
                sender,
                creation::recordNetwork);
    }

    /** Main-thread protocol entry; the storage coordinator owns replay and native inventory validation. */
    public void storage(
            ServerPlayer player, io.github.loongin.omniresonance.networking.TerminalStorageRequest request) {
        requireServerThread();
        if (!closed && storageAccess != null && player.server == server) storageAccess.request(player, request);
    }

    /** Validates a real current terminal session and root-page role before creating or cancelling inventory work. */
    public void inventory(
            ServerPlayer player, io.github.loongin.omniresonance.networking.DomainInventoryRequest request) {
        requireServerThread();
        Session session = sessions.get(player.getUUID());
        if (closed
                || inventorySync == null
                || player.server != server
                || session == null
                || session.player != player
                || !session.id.equals(request.session())
                || !session.viewId.equals(request.view())) return;
        if (!request.open()) {
            if (session.inventoryGeneration == request.generation()) {
                inventorySync.cancel(player.getUUID());
                if (storageAccess != null) storageAccess.close(player.getUUID());
                session.inventoryActive = false;
            }
            return;
        }
        if (request.generation() <= session.inventoryGeneration) return;
        session.chunkActive = false;
        inventorySync.cancel(player.getUUID());
        if (storageAccess != null) storageAccess.close(player.getUUID());
        session.inventoryActive = false;
        session.inventoryGeneration = request.generation();
        try {
            requireLayer(session, Layer.NETWORK);
            NetworkMetadata network = topology().inspectNetwork(player, requireSelectedNetwork(session));
            inventorySync.request(player.getUUID(), network.id(), session.id, request.generation());
            session.inventoryActive = true;
            if (storageAccess != null)
                storageAccess.open(player, session.viewId, session.id, request.generation(), network.id());
        } catch (RuntimeException denied) {
            inventoryReplies.accept(
                    player,
                    new io.github.loongin.omniresonance.networking.DomainInventoryFrame.Failed(
                            session.id,
                            request.generation(),
                            0,
                            io.github.loongin.omniresonance.networking.DomainInventoryFrame.Reason.UNAVAILABLE));
        }
    }

    /** Uses the shared tick CPU budget without charging internal serialization as native capability calls. */
    public void inventoryStep(io.github.loongin.omniresonance.transfer.TransferWorkBudget budget) {
        requireServerThread();
        if (closed || inventorySync == null) return;
        for (Session session : sessions.values())
            if (session.inventoryActive) {
                try {
                    requireLayer(session, Layer.NETWORK);
                    topology().inspectNetwork(session.player, requireSelectedNetwork(session));
                } catch (RuntimeException denied) {
                    session.inventoryActive = false;
                    if (storageAccess != null) storageAccess.close(session.player.getUUID());
                    inventorySync.fail(
                            session.player.getUUID(),
                            io.github.loongin.omniresonance.networking.DomainInventoryFrame.Reason.UNAVAILABLE);
                }
            }
        inventoryWork.accept(budget);
    }

    private void syncInventory(io.github.loongin.omniresonance.transfer.TransferWorkBudget budget) {
        var cfg = settings.terminalSync();
        inventorySync.tick(
                new DomainInventorySync.Limits(
                        cfg.bytesPerPlayer(), cfg.bytesServer(), cfg.concurrentFull(), cfg.pendingEntries()),
                1024,
                () -> budget.canFit(0));
    }

    /** Advances owned member/settings clocks; no per-tick roster enumeration or synchronization occurs. */
    public void tick() {
        requireServerThread();
        if (!closed) creation.configureAudit(settings.auditEntriesPerScope());
        if (!closed && navigation != null) navigation.tick(settings.navigation());
        if (!closed && nodeDirectory != null) nodeDirectory.tick();
        if (!closed && administration != null) administration.tick();
        if (!closed && filters != null) filters.tick();
        if (!closed && networkSettings != null) networkSettings.tick();
        if (!closed)
            for (Session session : sessions.values()) {
                if (session.statusActive && gameTick() >= session.nextStatusTick) publishStatus(session);
                tickFilterTransfer(session);
                if (session.sampleSequence > 0 && gameTick() - session.sampleStartedTick >= 200) {
                    long sequence = session.sampleSequence;
                    UUID token = session.sampleToken;
                    session.sampleSequence = 0;
                    session.sampleToken = null;
                    if (token != null && session.presetEdit != null)
                        filters().cancelSample(session.player, session.presetEdit, token);
                    directReplies.accept(
                            session.player,
                            new NetworkTerminalResponse.Failure(
                                    session.viewId, session.id, sequence, NetworkTerminalResponse.Reason.LOCK_EXPIRED));
                }
            }
    }

    /** Releases only a matching actual player instance on the server thread, without network or file changes. */
    public void closePlayer(ServerPlayer player) {
        requireServerThread();
        if (nodeDirectory != null) nodeDirectory.closePlayer(player);
        Session session = sessions.get(Objects.requireNonNull(player, "player").getUUID());
        if (session != null && session.player == player) {
            if (exchangeWire != null) exchangeWire.close(player, session.id);
            cancelSessionEdit(player, session);
            sessions.remove(player.getUUID());
        }
    }

    /** Permanently closes this runtime on the server thread, releasing all session references without saving files. */
    public void close() {
        requireServerThread();
        for (Session session : sessions.values()) {
            if (exchangeWire != null) exchangeWire.close(session.player, session.id);
            cancelSessionEdit(session.player, session);
        }
        sessions.clear();
        if (navigation != null) navigation.close();
        if (nodeDirectory != null) nodeDirectory.close();
        if (inventorySync != null) inventorySync.close();
        if (storageAccess != null) storageAccess.close();
        if (administration != null) administration.close();
        if (networkSettings != null) networkSettings.close();
        closed = true;
    }

    private NetworkTerminalResponse.Success open(ServerPlayer player, UUID viewId) {
        UUID playerId = player.getUUID();
        Session session = sessions.get(playerId);
        if (session == null || session.player != player || !session.viewId.equals(viewId)) {
            if (session != null) cancelSessionEdit(session.player, session);
            session = new Session(player, viewId, Objects.requireNonNull(sessionIds.get(), "session id"));
        }
        NetworkTerminalPage page = page(playerId, null, false);
        sessions.put(playerId, session);
        return new NetworkTerminalResponse.Success(viewId, session.id, 0, page, null);
    }

    private NetworkTerminalState tunnelList(
            ServerPlayer player, UUID networkId, @Nullable UUID anchor, boolean backwards) {
        NetworkTopologyService authority = topology();
        NetworkTopologyIndex.Page<NetworkTopologyService.TunnelView> page =
                authority.pageTunnels(player, networkId, anchor, backwards);
        List<TunnelSummary> entries = new ArrayList<>(page.entries().size());
        for (NetworkTopologyService.TunnelView tunnel : page.entries()) {
            entries.add(tunnelSummary(tunnel));
        }
        return new NetworkTerminalState.TunnelList(
                summary(authority.inspectNetwork(player, networkId)),
                new TunnelPage(entries, page.totalCount(), page.hasPrevious(), page.hasNext()));
    }

    private NetworkTerminalState channelList(
            ServerPlayer player,
            UUID networkId,
            NetworkTopologyService.TunnelView tunnel,
            @Nullable UUID anchor,
            boolean backwards) {
        List<ChannelSummary> entries = new ArrayList<>();
        int totalCount = tunnel.channelCount();
        boolean hasPrevious = false;
        boolean hasNext = false;
        if (tunnel.tunnel().enabled()) {
            NetworkTopologyIndex.Page<NetworkTopologyService.ChannelView> page =
                    topology().pageChannels(player, networkId, tunnel.tunnel().tunnelId(), anchor, backwards);
            for (NetworkTopologyService.ChannelView channel : page.entries()) {
                entries.add(channelSummary(channel));
            }
            totalCount = page.totalCount();
            hasPrevious = page.hasPrevious();
            hasNext = page.hasNext();
        }
        return new NetworkTerminalState.ChannelList(
                summary(topology().inspectNetwork(player, networkId)),
                tunnelSummary(tunnel),
                new ChannelPage(entries, totalCount, hasPrevious, hasNext));
    }

    private NetworkTerminalState tunnelSettings(ServerPlayer player, UUID networkId, UUID tunnelId) {
        return new NetworkTerminalState.TunnelSettings(
                summary(topology().inspectNetwork(player, networkId)),
                tunnelSummary(topology().inspectTunnel(player, networkId, tunnelId)));
    }

    private NetworkTerminalState deleteState(
            ServerPlayer player,
            UUID networkId,
            NetworkTopologyService.TunnelView tunnel,
            NetworkTopologyService.DeletionEdit deletion) {
        TopologyDeletionImpact impact = deletion.impact();
        return new NetworkTerminalState.DeleteConfirmation(
                summary(topology().inspectNetwork(player, networkId)),
                new TopologyDeletionSummary(
                        TopologyDeletionSummary.Kind.TUNNEL,
                        tunnel.tunnel().tunnelId(),
                        tunnel.tunnel().name().value(),
                        impact.channelCount(),
                        impact.bindingCount()));
    }

    private NetworkTerminalResponse backFromEdit(
            NetworkTerminalRequest request, ServerPlayer player, Session session, UUID networkId) {
        if (session.tunnelId != null) {
            NetworkTopologyService.TunnelView tunnel = topology().inspectTunnel(player, networkId, session.tunnelId);
            session.layer = Layer.CHANNELS;
            return view(request, session, channelList(player, networkId, tunnel, null, false));
        }
        session.layer = Layer.TUNNELS;
        return view(request, session, tunnelList(player, networkId, null, false));
    }

    private NetworkTerminalState.NetworkRoot networkRoot(ServerPlayer actor, NetworkMetadata network) {
        return new NetworkTerminalState.NetworkRoot(
                summary(network), topology().domainStorageUnavailable(actor, network.id()));
    }

    private NetworkTerminalResponse navigateBack(
            NetworkTerminalRequest request, ServerPlayer player, Session session, UUID networkId) {
        return switch (session.layer) {
            case FILTERS -> {
                session.layer = Layer.NETWORK;
                yield view(request, session, networkRoot(player, topology().inspectNetwork(player, networkId)));
            }
            case PRESET -> {
                session.layer = Layer.FILTERS;
                yield view(request, session, filterList(player, networkId, 0));
            }
            case PRESET_EDIT -> {
                if (session.presetId == null) {
                    session.layer = Layer.FILTERS;
                    yield view(request, session, filterList(player, networkId, 0));
                }
                session.layer = Layer.PRESET;
                yield view(request, session, presetState(player, networkId, session.presetId, 0));
            }
            case MEMBERS -> {
                session.layer = Layer.NETWORK;
                yield view(
                        request, session, networkRoot(player, administration().inspectNetwork(player, networkId)));
            }
            case NETWORK_SETTINGS -> {
                session.layer = Layer.NETWORK;
                yield view(
                        request,
                        session,
                        networkRoot(
                                player,
                                networkSettings().inspect(player, networkId).metadata()));
            }
            case NETWORK_RENAME, NETWORK_DELETE -> {
                session.layer = Layer.NETWORK_SETTINGS;
                yield view(request, session, networkSettingsState(player, networkId));
            }
            case ADMIN_CANDIDATES, ADMIN_REMOVE -> {
                clearCandidates(session);
                session.layer = Layer.MEMBERS;
                yield view(request, session, members(player, networkId, null, false, null));
            }
            case CHANNELS -> {
                session.tunnelId = null;
                session.layer = Layer.TUNNELS;
                yield view(request, session, tunnelList(player, networkId, null, false));
            }
            case TUNNELS -> {
                session.layer = Layer.NETWORK;
                yield view(request, session, networkRoot(player, topology().inspectNetwork(player, networkId)));
            }
            case TUNNEL_SETTINGS -> {
                UUID tunnelId = requireSelectedTunnel(session);
                NetworkTopologyService.TunnelView tunnel = topology().inspectTunnel(player, networkId, tunnelId);
                session.layer = Layer.CHANNELS;
                yield view(request, session, channelList(player, networkId, tunnel, null, false));
            }
            case NETWORK -> {
                session.networkId = null;
                session.layer = Layer.DIRECTORY;
                yield new NetworkTerminalResponse.Success(
                        request.viewId(), session.id, request.sequence(), page(player.getUUID(), null, false), null);
            }
            case TUNNEL_EDIT, DELETE -> backFromEdit(request, player, session, networkId);
            case DIRECTORY ->
                new NetworkTerminalResponse.Success(
                        request.viewId(), session.id, request.sequence(), page(player.getUUID(), null, false), null);
        };
    }

    private void clearEdit(ServerPlayer player, Session session) {
        cancelFilterTransfer(session);
        session.sampleSequence = 0;
        session.sampleToken = null;
        if (session.presetEdit != null && filters != null) filters.cancel(player, session.presetEdit);
        session.presetEdit = null;
        if (session.edit != null) {
            safeCancel(player, session.edit);
        }
        if (session.deletion != null) {
            safeCancel(player, session.deletion.edit());
        }
        session.edit = null;
        session.deletion = null;
        if (session.networkRename != null) {
            safeCancel(player, session.networkRename);
        }
        if (session.networkDeletion != null) {
            safeCancel(player, session.networkDeletion);
        }
        session.networkRename = null;
        session.networkDeletion = null;
        if (session.removal != null) administration().cancel(player, session.removal);
        session.removal = null;
    }

    private void cancelSessionEdit(ServerPlayer player, Session session) {
        clearStatus(session);
        session.chunkActive = false;
        if (inventorySync != null) inventorySync.cancel(player.getUUID());
        if (storageAccess != null) storageAccess.close(player.getUUID());
        session.inventoryActive = false;
        clearEdit(player, session);
        clearCandidates(session);
    }

    private void safeCancel(ServerPlayer player, NetworkTopologyService.Edit edit) {
        try {
            topology().cancel(player, edit);
        } catch (RuntimeException ignored) {
            // Session cleanup is idempotent even when the lease already expired or authority disappeared.
        }
    }

    private void safeCancel(ServerPlayer player, NetworkSettingsService.RenameEdit edit) {
        try {
            networkSettings().cancel(player, edit);
        } catch (RuntimeException ignored) {
            // Session cleanup is idempotent after expiry, revocation, deletion or an already-completed rename.
        }
    }

    private void safeCancel(ServerPlayer player, NetworkSettingsService.DeletionEdit edit) {
        try {
            networkSettings().cancel(player, edit);
        } catch (RuntimeException ignored) {
            // Session cleanup is idempotent after expiry, revocation, deletion or an already-completed deletion.
        }
    }

    private static TunnelSummary tunnelSummary(NetworkTopologyService.TunnelView view) {
        NetworkTunnelRecord tunnel = view.tunnel();
        return new TunnelSummary(
                tunnel.tunnelId(),
                tunnel.name().value(),
                tunnel.revision(),
                tunnel.enabled(),
                view.channelCount(),
                view.bindingCount());
    }

    private static ChannelSummary channelSummary(NetworkTopologyService.ChannelView view) {
        NetworkChannelRecord channel = view.channel();
        return new ChannelSummary(
                channel.channelId(), channel.name().value(), channel.revision(), view.inputCount(), view.outputCount());
    }

    private static NetworkTerminalResponse.ViewState view(
            NetworkTerminalRequest request, Session session, NetworkTerminalState state) {
        return new NetworkTerminalResponse.ViewState(request.viewId(), session.id, request.sequence(), state);
    }

    private static UUID requireSelectedNetwork(Session session) {
        if (session.networkId == null) {
            throw new IllegalArgumentException("No network selected in terminal session");
        }
        return session.networkId;
    }

    private static UUID requireSelectedTunnel(Session session) {
        if (session.tunnelId == null) {
            throw new IllegalArgumentException("No tunnel selected in terminal session");
        }
        return session.tunnelId;
    }

    private static NetworkTopologyService.Edit requireEdit(Session session) {
        return Objects.requireNonNull(session.edit, "terminal edit");
    }

    private static void requireLayer(Session session, Layer expected) {
        if (session.layer != expected) {
            throw new IllegalArgumentException("Terminal operation is invalid for current layer");
        }
    }

    private @Nullable NetworkTerminalResponse handleTopology(
            ServerPlayer sender, Session session, NetworkTerminalRequest request) {
        NetworkTopologyService authority = topology();
        if (request instanceof NetworkTerminalRequest.OpenNetwork open) {
            NetworkMetadata network = authority.inspectNetwork(sender, open.networkId());
            clearEdit(sender, session);
            clearCandidates(session);
            session.networkId = network.id();
            session.tunnelId = null;
            session.layer = Layer.NETWORK;
            return view(request, session, networkRoot(sender, network));
        }
        UUID networkId = requireSelectedNetwork(session);
        if (request instanceof NetworkTerminalRequest.OpenFilters
                || request instanceof NetworkTerminalRequest.PagePresets
                || request instanceof NetworkTerminalRequest.OpenPreset
                || request instanceof NetworkTerminalRequest.BeginPresetEdit
                || request instanceof NetworkTerminalRequest.SavePresetEdit
                || request instanceof NetworkTerminalRequest.QueryFilterLibrary
                || request instanceof NetworkTerminalRequest.ReadResourceRule
                || request instanceof NetworkTerminalRequest.BeginResourceRule
                || request instanceof NetworkTerminalRequest.SaveResourceRule
                || request instanceof NetworkTerminalRequest.SampleResourceRule
                || request instanceof NetworkTerminalRequest.PrepareResourceRuleUpload) {
            return handleFilters(sender, session, request, networkId);
        }
        if (request instanceof NetworkTerminalRequest.OpenNetworkSettings) {
            requireLayer(session, Layer.NETWORK);
            NetworkTerminalState.NetworkSettings state = networkSettingsState(sender, networkId);
            clearEdit(sender, session);
            session.layer = Layer.NETWORK_SETTINGS;
            return view(request, session, state);
        }
        if (request instanceof NetworkTerminalRequest.BeginRenameNetwork) {
            requireLayer(session, Layer.NETWORK_SETTINGS);
            session.networkRename = networkSettings().beginRename(sender, networkId);
            session.layer = Layer.NETWORK_RENAME;
            return view(request, session, new NetworkTerminalState.NetworkRename(settingsSummary(sender, networkId)));
        }
        if (request instanceof NetworkTerminalRequest.RenameNetwork rename) {
            requireLayer(session, Layer.NETWORK_RENAME);
            NetworkSettingsService.RenameEdit edit =
                    Objects.requireNonNull(session.networkRename, "network rename edit");
            try {
                networkSettings().rename(sender, edit, rename.name());
            } catch (NetworkSettingsService.Rejected rejected) {
                if (rejected.reason() != NetworkSettingsService.Reason.INVALID_NAME
                        && rejected.reason() != NetworkSettingsService.Reason.NAME_CONFLICT) {
                    session.networkRename = null;
                    session.layer = Layer.NETWORK_SETTINGS;
                }
                throw rejected;
            }
            session.networkRename = null;
            session.layer = Layer.NETWORK_SETTINGS;
            return view(request, session, networkSettingsState(sender, networkId));
        }
        if (request instanceof NetworkTerminalRequest.SetDefaultNetwork) {
            requireLayer(session, Layer.NETWORK_SETTINGS);
            networkSettings().setDefault(sender, networkId);
            return view(request, session, networkSettingsState(sender, networkId));
        }
        if (request instanceof NetworkTerminalRequest.RequestDeleteNetwork) {
            requireLayer(session, Layer.NETWORK_SETTINGS);
            session.networkDeletion = networkSettings().beginDeletion(sender, networkId);
            session.layer = Layer.NETWORK_DELETE;
            return view(request, session, networkDeleteState(sender, networkId, session.networkDeletion));
        }
        if (request instanceof NetworkTerminalRequest.ConfirmDeleteNetwork) {
            requireLayer(session, Layer.NETWORK_DELETE);
            NetworkSettingsService.DeletionEdit deletion =
                    Objects.requireNonNull(session.networkDeletion, "network deletion edit");
            try {
                networkSettings().delete(sender, deletion);
            } catch (NetworkSettingsService.Rejected rejected) {
                session.networkDeletion = null;
                session.layer = Layer.NETWORK_SETTINGS;
                if (rejected.reason() == NetworkSettingsService.Reason.STALE_REVISION
                        || rejected.reason() == NetworkSettingsService.Reason.LOCK_EXPIRED
                        || rejected.reason() == NetworkSettingsService.Reason.HAS_EXCHANGES
                        || rejected.reason() == NetworkSettingsService.Reason.HAS_NODES
                        || rejected.reason() == NetworkSettingsService.Reason.STORAGE_UNVERIFIED) {
                    return new NetworkTerminalResponse.Failure(
                            request.viewId(),
                            session.id,
                            request.sequence(),
                            settingsReason(rejected.reason()),
                            networkSettingsState(sender, networkId));
                }
                throw rejected;
            }
            session.networkDeletion = null;
            session.networkId = null;
            session.layer = Layer.DIRECTORY;
            closeDeletedNetworkSessions(networkId, session);
            return new NetworkTerminalResponse.Success(
                    request.viewId(), session.id, request.sequence(), page(sender.getUUID(), null, false), null);
        }
        if (request instanceof NetworkTerminalRequest.OpenMembers) {
            requireLayer(session, Layer.NETWORK);
            var state = members(sender, networkId, null, false, null);
            clearEdit(sender, session);
            session.layer = Layer.MEMBERS;
            return view(request, session, state);
        }
        if (request instanceof NetworkTerminalRequest.PageMembers page) {
            requireLayer(session, Layer.MEMBERS);
            return view(request, session, members(sender, networkId, page.anchor(), page.backwards(), null));
        }
        if (request instanceof NetworkTerminalRequest.OpenAdministratorCandidates) {
            if (session.layer != Layer.MEMBERS && session.layer != Layer.ADMIN_CANDIDATES)
                throw new IllegalArgumentException("Invalid candidate selection layer");
            return view(request, session, startCandidates(sender, session, networkId));
        }
        if (request instanceof NetworkTerminalRequest.PageAdministratorCandidates page) {
            requireLayer(session, Layer.ADMIN_CANDIDATES);
            requireMemberOwner(sender, networkId);
            if (!page.snapshotId().equals(session.candidateSnapshotId)
                    || page.offset() != session.nextCandidateOffset
                    || page.offset() >= session.candidates.size())
                throw new IllegalArgumentException("Invalid candidate continuation");
            return view(request, session, candidatePage(sender, session, networkId, page.offset()));
        }
        if (request instanceof NetworkTerminalRequest.AddAdministrator add) {
            requireLayer(session, Layer.ADMIN_CANDIDATES);
            requireMemberOwner(sender, networkId);
            if (!containsCandidate(session.candidates, add.target()))
                throw new IllegalArgumentException("Target was not a candidate");
            try {
                administration().add(sender, networkId, add.target());
            } catch (NetworkAdministrationService.Rejected rejected) {
                if (rejected.reason() == NetworkAdministrationService.Reason.PLAYER_OFFLINE) {
                    return new NetworkTerminalResponse.Failure(
                            request.viewId(),
                            session.id,
                            request.sequence(),
                            NetworkTerminalResponse.Reason.PLAYER_OFFLINE,
                            startCandidates(sender, session, networkId));
                }
                throw rejected;
            }
            clearCandidates(session);
            session.layer = Layer.MEMBERS;
            return view(request, session, members(sender, networkId, null, false, add.target()));
        }
        if (request instanceof NetworkTerminalRequest.RequestRemoveAdministrator remove) {
            requireLayer(session, Layer.MEMBERS);
            session.removal = administration().beginRemoval(sender, networkId, remove.target());
            session.layer = Layer.ADMIN_REMOVE;
            return view(
                    request,
                    session,
                    new NetworkTerminalState.RemoveAdministrator(
                            summary(administration().inspectNetwork(sender, networkId)),
                            administration().member(sender, networkId, remove.target())));
        }
        if (request instanceof NetworkTerminalRequest.ConfirmRemoveAdministrator) {
            requireLayer(session, Layer.ADMIN_REMOVE);
            var edit = Objects.requireNonNull(session.removal, "removal");
            try {
                administration().remove(sender, edit);
            } catch (NetworkAdministrationService.Rejected rejected) {
                session.removal = null;
                session.layer = Layer.MEMBERS;
                return new NetworkTerminalResponse.Failure(
                        request.viewId(),
                        session.id,
                        request.sequence(),
                        membershipReason(rejected.reason()),
                        members(sender, networkId, null, false, null));
            }
            session.removal = null;
            session.layer = Layer.MEMBERS;
            revokeAccess(edit.target(), networkId);
            return view(request, session, members(sender, networkId, null, false, null));
        }
        if (request instanceof NetworkTerminalRequest.OpenTunnels) {
            clearEdit(sender, session);
            session.tunnelId = null;
            session.layer = Layer.TUNNELS;
            return view(request, session, tunnelList(sender, networkId, null, false));
        }
        if (request instanceof NetworkTerminalRequest.PageTunnels page) {
            requireLayer(session, Layer.TUNNELS);
            return view(request, session, tunnelList(sender, networkId, page.anchor(), page.backwards()));
        }
        if (request instanceof NetworkTerminalRequest.BeginCreateTunnel begin) {
            requireLayer(session, Layer.TUNNELS);
            session.edit = authority.acquireTunnelCollection(sender, networkId);
            session.layer = Layer.TUNNEL_EDIT;
            return view(
                    request,
                    session,
                    new NetworkTerminalState.TunnelEdit(
                            summary(authority.inspectNetwork(sender, networkId)),
                            null,
                            authority
                                    .suggestedTunnelName(
                                            sender, networkId, new ManagedNamePrefix(begin.suggestionPrefix()))
                                    .value()));
        }
        if (request instanceof NetworkTerminalRequest.BeginRenameTunnel begin) {
            requireLayer(session, Layer.TUNNEL_SETTINGS);
            if (!requireSelectedTunnel(session).equals(begin.tunnelId())) {
                throw new IllegalArgumentException("Tunnel settings identity mismatch");
            }
            session.edit = authority.acquireTunnel(sender, networkId, begin.tunnelId());
            NetworkTopologyService.TunnelView tunnel = authority.inspectTunnel(sender, networkId, begin.tunnelId());
            session.layer = Layer.TUNNEL_EDIT;
            return view(
                    request,
                    session,
                    new NetworkTerminalState.TunnelEdit(
                            summary(authority.inspectNetwork(sender, networkId)), tunnelSummary(tunnel), null));
        }
        if (request instanceof NetworkTerminalRequest.CreateTunnel create) {
            requireLayer(session, Layer.TUNNEL_EDIT);
            NetworkTopologyService.Edit edit = requireEdit(session);
            if (edit.kind() != NetworkTopologyService.Kind.TUNNEL_COLLECTION) {
                throw new IllegalArgumentException("Wrong tunnel creation edit kind");
            }
            authority.createTunnel(
                    sender, edit, new ManagedName(create.tunnelName()), new ManagedName(create.initialChannelName()));
            session.edit = null;
            session.layer = Layer.TUNNELS;
            return view(request, session, tunnelList(sender, networkId, null, false));
        }
        if (request instanceof NetworkTerminalRequest.RenameTunnel rename) {
            requireLayer(session, Layer.TUNNEL_EDIT);
            NetworkTopologyService.Edit edit = requireEdit(session);
            if (edit.kind() != NetworkTopologyService.Kind.TUNNEL) {
                throw new IllegalArgumentException("Wrong tunnel rename edit kind");
            }
            authority.renameTunnel(sender, edit, new ManagedName(rename.name()));
            session.edit = null;
            UUID tunnelId = requireSelectedTunnel(session);
            NetworkTopologyService.TunnelView tunnel = authority.inspectTunnel(sender, networkId, tunnelId);
            session.layer = Layer.CHANNELS;
            return view(request, session, channelList(sender, networkId, tunnel, null, false));
        }
        if (request instanceof NetworkTerminalRequest.SetTunnelEnabled enabled) {
            requireLayer(session, Layer.TUNNEL_SETTINGS);
            if (!requireSelectedTunnel(session).equals(enabled.tunnelId())) {
                throw new IllegalArgumentException("Tunnel settings identity mismatch");
            }
            NetworkTopologyService.Edit edit = authority.acquireTunnel(sender, networkId, enabled.tunnelId());
            try {
                authority.setTunnelEnabled(sender, edit, enabled.enabled());
            } catch (RuntimeException failure) {
                safeCancel(sender, edit);
                throw failure;
            }
            NetworkTopologyService.TunnelView tunnel = authority.inspectTunnel(sender, networkId, enabled.tunnelId());
            session.layer = Layer.CHANNELS;
            return view(request, session, channelList(sender, networkId, tunnel, null, false));
        }
        if (request instanceof NetworkTerminalRequest.OpenChannels open) {
            requireLayer(session, Layer.TUNNELS);
            NetworkTopologyService.TunnelView tunnel = authority.inspectTunnel(sender, networkId, open.tunnelId());
            session.tunnelId = open.tunnelId();
            session.layer = Layer.CHANNELS;
            return view(request, session, channelList(sender, networkId, tunnel, null, false));
        }
        if (request instanceof NetworkTerminalRequest.PageChannels page) {
            requireLayer(session, Layer.CHANNELS);
            UUID tunnelId = requireSelectedTunnel(session);
            NetworkTopologyService.TunnelView tunnel = authority.inspectTunnel(sender, networkId, tunnelId);
            return view(request, session, channelList(sender, networkId, tunnel, page.anchor(), page.backwards()));
        }
        if (request instanceof NetworkTerminalRequest.OpenTunnelSettings) {
            requireLayer(session, Layer.CHANNELS);
            UUID tunnelId = requireSelectedTunnel(session);
            session.layer = Layer.TUNNEL_SETTINGS;
            return view(request, session, tunnelSettings(sender, networkId, tunnelId));
        }
        if (request instanceof NetworkTerminalRequest.RequestDeleteTunnel delete) {
            requireLayer(session, Layer.TUNNEL_SETTINGS);
            if (!requireSelectedTunnel(session).equals(delete.tunnelId())) {
                throw new IllegalArgumentException("Tunnel settings identity mismatch");
            }
            session.deletion = authority.beginTunnelDeletion(sender, networkId, delete.tunnelId());
            session.layer = Layer.DELETE;
            NetworkTopologyService.TunnelView tunnel = authority.inspectTunnel(sender, networkId, delete.tunnelId());
            return view(request, session, deleteState(sender, networkId, tunnel, session.deletion));
        }
        if (request instanceof NetworkTerminalRequest.ConfirmDelete) {
            requireLayer(session, Layer.DELETE);
            NetworkTopologyService.DeletionEdit deletion = Objects.requireNonNull(session.deletion, "deletion");
            if (deletion.edit().kind() != NetworkTopologyService.Kind.TUNNEL) {
                throw new IllegalArgumentException("Terminal deletion is not a tunnel");
            }
            authority.confirmTunnelDeletion(sender, deletion);
            session.deletion = null;
            session.tunnelId = null;
            session.layer = Layer.TUNNELS;
            return view(request, session, tunnelList(sender, networkId, null, false));
        }
        if (request instanceof NetworkTerminalRequest.Heartbeat) {
            if (session.presetEdit != null) {
                filters().heartbeat(sender, session.presetEdit);
                return null;
            }
            if (session.networkRename != null) {
                networkSettings().heartbeat(sender, session.networkRename);
                return null;
            }
            if (session.networkDeletion != null) {
                networkSettings().heartbeat(sender, session.networkDeletion);
                return null;
            }
            if (session.removal != null) {
                administration().heartbeat(sender, session.removal);
                return null;
            }
            NetworkTopologyService.Edit edit =
                    session.edit != null ? session.edit : session.deletion == null ? null : session.deletion.edit();
            if (edit == null) {
                throw new IllegalArgumentException("No terminal edit to renew");
            }
            authority.heartbeat(sender, edit);
            return null;
        }
        if (request instanceof NetworkTerminalRequest.CancelEdit) {
            Layer previous = session.layer;
            clearEdit(sender, session);
            if (previous == Layer.PRESET_EDIT
                    || previous == Layer.ADMIN_REMOVE
                    || previous == Layer.NETWORK_RENAME
                    || previous == Layer.NETWORK_DELETE) {
                return navigateBack(request, sender, session, networkId);
            }
            return backFromEdit(request, sender, session, networkId);
        }
        if (request instanceof NetworkTerminalRequest.Back) {
            clearEdit(sender, session);
            return navigateBack(request, sender, session, networkId);
        }
        throw new IllegalArgumentException("Terminal operation is invalid for topology session");
    }

    private NetworkTerminalPage page(UUID player, @Nullable UUID anchor, boolean backwards) {
        NetworkDirectory.AccessPage accessible = directory.pageAccessible(player, anchor, backwards, 128);
        List<NetworkSummary> entries = new ArrayList<>(accessible.entries().size());
        for (NetworkMetadata metadata : accessible.entries()) {
            entries.add(summary(metadata));
        }
        UUID preferredId = creation.preferredNetwork(player).orElse(null);
        NetworkSummary preferred = preferredId == null
                ? null
                : directory
                        .find(preferredId)
                        .map(NetworkTerminalService::summary)
                        .orElse(null);
        return new NetworkTerminalPage(
                entries,
                preferred,
                directory.ownedCount(player),
                accessible.totalCount(),
                settings.networksPerOwner(),
                accessible.hasPrevious(),
                accessible.hasNext());
    }

    private NetworkTerminalState.Members members(
            ServerPlayer actor, UUID networkId, @Nullable UUID anchor, boolean backwards, @Nullable UUID selected) {
        NetworkAdministrationService authority = administration();
        NetworkMemberPage page = selected == null
                ? authority.members(actor, networkId, anchor, backwards)
                : authority.membersIncluding(actor, networkId, selected);
        return new NetworkTerminalState.Members(
                summary(authority.inspectNetwork(actor, networkId)),
                page,
                settings.administratorsPerNetwork(),
                selected == null ? page.entries().getFirst().playerId() : selected);
    }

    private NetworkTerminalState.NetworkSettings networkSettingsState(ServerPlayer actor, UUID networkId) {
        return new NetworkTerminalState.NetworkSettings(settingsSummary(actor, networkId));
    }

    private NetworkSettingsSummary settingsSummary(ServerPlayer actor, UUID networkId) {
        NetworkSettingsService.SettingsView view = networkSettings().inspect(actor, networkId);
        return new NetworkSettingsSummary(
                summary(view.metadata()),
                view.ownerName(),
                view.managementRevision(),
                view.topologyRevision(),
                view.ownerActions(),
                view.defaultNetwork());
    }

    private NetworkTerminalState.NetworkDelete networkDeleteState(
            ServerPlayer actor, UUID networkId, NetworkSettingsService.DeletionEdit deletion) {
        NetworkSettingsSummary settings = settingsSummary(actor, networkId);
        return new NetworkTerminalState.NetworkDelete(
                settings,
                new NetworkDeletionSummary(
                        networkId,
                        settings.network().name(),
                        deletion.administratorCount(),
                        deletion.tunnelCount(),
                        deletion.channelCount()));
    }

    private NetworkTerminalState.AdministratorCandidates startCandidates(
            ServerPlayer actor, Session session, UUID networkId) {
        List<NetworkAdministrationService.PlayerIdentity> candidates =
                administration().onlineCandidates(actor, networkId);
        UUID snapshotId = Objects.requireNonNull(sessionIds.get(), "candidate snapshot id");
        session.candidates = candidates;
        session.candidateSnapshotId = snapshotId;
        session.layer = Layer.ADMIN_CANDIDATES;
        return candidatePage(actor, session, networkId, 0);
    }

    private NetworkTerminalState.AdministratorCandidates candidatePage(
            ServerPlayer actor, Session session, UUID networkId, int offset) {
        int end = Math.min(session.candidates.size(), offset + 128);
        List<OnlinePlayerSummary> page = new ArrayList<>(end - offset);
        for (int index = offset; index < end; index++) {
            var candidate = session.candidates.get(index);
            page.add(new OnlinePlayerSummary(candidate.id(), candidate.name()));
        }
        session.nextCandidateOffset = end;
        return new NetworkTerminalState.AdministratorCandidates(
                summary(administration().inspectNetwork(actor, networkId)),
                new OnlinePlayerPage(
                        Objects.requireNonNull(session.candidateSnapshotId, "candidate snapshot"),
                        page,
                        offset,
                        session.candidates.size(),
                        end < session.candidates.size()));
    }

    private static boolean containsCandidate(List<NetworkAdministrationService.PlayerIdentity> candidates, UUID id) {
        int low = 0;
        int high = candidates.size() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int order = candidates.get(middle).id().compareTo(id);
            if (order == 0) return true;
            if (order < 0) low = middle + 1;
            else high = middle - 1;
        }
        return false;
    }

    private static void clearCandidates(Session session) {
        session.candidates = List.of();
        session.candidateSnapshotId = null;
        session.nextCandidateOffset = 0;
    }

    private void requireMemberOwner(ServerPlayer actor, UUID networkId) {
        administration().inspectOwner(actor, networkId);
    }

    private void revokeAccess(UUID target, UUID networkId) {
        Session revoked = sessions.get(target);
        ServerPlayer player = server.getPlayerList().getPlayer(target);
        if (player == null && revoked != null) player = revoked.player;
        if (revoked != null && networkId.equals(revoked.networkId)) {
            cancelSessionEdit(revoked.player, revoked);
            sessions.remove(target);
            directReplies.accept(
                    revoked.player,
                    new NetworkTerminalResponse.AccessRevoked(
                            revoked.viewId, revoked.id, revoked.lastSequence, networkId));
        }
        if (player != null && nodeMenus != null) nodeMenus.revokeNetworkAccess(player, networkId);
    }

    private void closeDeletedNetworkSessions(UUID networkId, Session initiator) {
        var iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Session> entry = iterator.next();
            Session affected = entry.getValue();
            if (affected != initiator && networkId.equals(affected.networkId)) {
                cancelSessionEdit(affected.player, affected);
                iterator.remove();
                if (nodeMenus != null) {
                    nodeMenus.revokeNetworkAccess(affected.player, networkId);
                }
                directReplies.accept(
                        affected.player,
                        new NetworkTerminalResponse.NetworkDeleted(
                                affected.viewId, affected.id, affected.lastSequence, networkId));
            }
        }
        if (nodeMenus != null) {
            nodeMenus.revokeDeletedNetwork(networkId);
        }
    }

    private io.github.loongin.omniresonance.filter.ItemFilterService filters() {
        if (filters == null) throw new IllegalStateException("Filter authority is unavailable");
        return filters;
    }

    private NetworkTerminalState.Filters filterList(ServerPlayer actor, UUID networkId, int offset) {
        return new NetworkTerminalState.Filters(
                summary(topology().inspectNetwork(actor, networkId)), filters().page(actor, networkId, offset));
    }

    private NetworkTerminalState.Preset presetState(ServerPlayer actor, UUID networkId, UUID presetId, int offset) {
        var preset = filters().summary(actor, networkId, presetId);
        if (preset == null) throw new IllegalStateException("Preset is unavailable");
        return new NetworkTerminalState.Preset(
                summary(topology().inspectNetwork(actor, networkId)),
                preset,
                filters().rules(actor, networkId, presetId, preset.revision(), offset));
    }

    private @Nullable NetworkTerminalResponse handleFilters(
            ServerPlayer actor, Session session, NetworkTerminalRequest request, UUID networkId) {
        if (request instanceof NetworkTerminalRequest.QueryFilterLibrary query) {
            if (session.layer != Layer.FILTERS && session.layer != Layer.PRESET && session.layer != Layer.PRESET_EDIT)
                throw new IllegalArgumentException("Invalid filter search layer");
            return new NetworkTerminalResponse.FilterLibrary(
                    session.viewId,
                    session.id,
                    query.sequence(),
                    query.query(),
                    filters().page(actor, networkId, query.offset(), query.query(), query.revision()));
        }
        if (request instanceof NetworkTerminalRequest.ReadResourceRule read) {
            requireLayer(session, Layer.PRESET);
            if (!Objects.equals(session.presetId, read.presetId())) throw new IllegalArgumentException("Wrong preset");
            var rule = filters().rule(actor, networkId, read.presetId(), read.revision(), read.ruleId());
            var summary = Objects.requireNonNull(filters().summary(actor, networkId, read.presetId()));
            var snapshot = new io.github.loongin.omniresonance.filter.ResourceFilterPreset(
                    read.presetId(), new ManagedName(summary.name()), summary.revision(), List.of(rule));
            return fullRuleReply(session, request.sequence(), snapshot, null, 0, 0);
        }
        if (request instanceof NetworkTerminalRequest.BeginResourceRule begin) {
            requireLayer(session, Layer.PRESET);
            if (!Objects.equals(session.presetId, begin.presetId())) throw new IllegalArgumentException("Wrong preset");
            var edit = filters().beginRule(actor, networkId, begin.presetId(), begin.ruleId(), begin.remove());
            session.presetEdit = edit;
            session.layer = Layer.PRESET_EDIT;
            return view(
                    request,
                    session,
                    new NetworkTerminalState.PresetEdit(
                            summary(topology().inspectNetwork(actor, networkId)),
                            filters().summary(actor, networkId, begin.presetId()),
                            edit.operation(),
                            edit.impact(),
                            edit.originalRule()));
        }
        if (request instanceof NetworkTerminalRequest.SaveResourceRule save) {
            requireLayer(session, Layer.PRESET_EDIT);
            return saveResourceRule(actor, session, request.sequence(), save.intent());
        }
        if (request instanceof NetworkTerminalRequest.SampleResourceRule sample) {
            requireLayer(session, Layer.PRESET_EDIT);
            var edit = Objects.requireNonNull(session.presetEdit);
            long startedTick = gameTick();
            session.sampleSequence = request.sequence();
            session.sampleStartedTick = startedTick;
            session.sampleToken = filters()
                    .requestSample(
                            actor,
                            edit,
                            sample.typeId(),
                            sample.slot(),
                            sample.tank(),
                            () -> sessions.get(actor.getUUID()) == session
                                    && session.layer == Layer.PRESET_EDIT
                                    && session.presetEdit == edit,
                            () -> session.sampleSequence == request.sequence() && gameTick() - startedTick < 200,
                            result -> {
                                if (sessions.get(actor.getUUID()) != session
                                        || session.layer != Layer.PRESET_EDIT
                                        || session.presetEdit != edit
                                        || session.sampleSequence != request.sequence()
                                        || !Objects.equals(session.sampleToken, result.token())
                                        || gameTick() - startedTick >= 200) return;
                                NetworkTerminalResponse response;
                                try {
                                    if (!result.failure().isEmpty())
                                        response = new NetworkTerminalResponse.FullRule(
                                                session.viewId,
                                                session.id,
                                                request.sequence(),
                                                result.token(),
                                                Math.max(0, result.tankCount()),
                                                result.tank(),
                                                result.failure(),
                                                new byte[0]);
                                    else {
                                        var preset = Objects.requireNonNull(filters()
                                                .summary(actor, networkId, Objects.requireNonNull(session.presetId)));
                                        UUID ruleId = edit.originalRule().isEmpty()
                                                ? result.token()
                                                : UUID.fromString(edit.originalRule());
                                        var match = new io.github.loongin.omniresonance.filter.ResourceFilterRule.Match(
                                                ruleId,
                                                result.typeId(),
                                                io.github.loongin.omniresonance.filter.ResourceFilterRule.Selector
                                                        .exact(Objects.requireNonNull(result.resourceId())),
                                                Objects.requireNonNull(result.components()));
                                        var snapshot = new io.github.loongin.omniresonance.filter.ResourceFilterPreset(
                                                preset.id(),
                                                new ManagedName(preset.name()),
                                                preset.revision(),
                                                List.of(match));
                                        response = fullRuleReply(
                                                session,
                                                request.sequence(),
                                                snapshot,
                                                result.token(),
                                                result.tankCount(),
                                                result.tank());
                                    }
                                } catch (IllegalStateException | IllegalArgumentException unavailable) {
                                    // Admission is request-local: preserve the edit and unrelated pool reservations.
                                    filters().cancelSample(actor, edit, result.token());
                                    logFailure(
                                            actor, request, "sample response unavailable; request ended", unavailable);
                                    response = new NetworkTerminalResponse.Failure(
                                            session.viewId,
                                            session.id,
                                            request.sequence(),
                                            NetworkTerminalResponse.Reason.DATA_UNAVAILABLE);
                                }
                                session.sampleSequence = 0;
                                session.sampleToken = null;
                                directReplies.accept(actor, response);
                            });
            return null;
        }
        if (request instanceof NetworkTerminalRequest.PrepareResourceRuleUpload upload) {
            requireLayer(session, Layer.PRESET_EDIT);
            var edit = Objects.requireNonNull(session.presetEdit);
            filters().validateEdit(actor, edit);
            if (session.filterTransfer != null) throw new IllegalStateException("Transfer already active");
            pool().beginUpload(actor.getUUID(), session.id, upload.transferId(), upload.length(), gameTick());
            session.filterTransfer = new FilterTransfer(
                    upload.transferId(),
                    request.sequence(),
                    upload.length(),
                    true,
                    Objects.requireNonNull(session.presetId),
                    edit.revision(),
                    edit);
            session.filterTransfer.expiresTick = gameTick() + 200;
            return new NetworkTerminalResponse.RuleTransferReady(
                    session.viewId,
                    session.id,
                    request.sequence(),
                    upload.transferId(),
                    upload.length(),
                    true,
                    null,
                    0,
                    0);
        }
        if (request instanceof NetworkTerminalRequest.OpenFilters) {
            requireLayer(session, Layer.NETWORK);
            var state = filterList(actor, networkId, 0);
            clearEdit(actor, session);
            session.presetId = null;
            session.layer = Layer.FILTERS;
            return view(request, session, state);
        }
        if (request instanceof NetworkTerminalRequest.PagePresets page) {
            requireLayer(session, Layer.FILTERS);
            return view(request, session, filterList(actor, networkId, page.offset()));
        }
        if (request instanceof NetworkTerminalRequest.OpenPreset open) {
            if (session.layer != Layer.FILTERS && session.layer != Layer.PRESET)
                throw new IllegalArgumentException("Invalid preset navigation");
            var rules = filters().rules(actor, networkId, open.presetId(), open.revision(), open.offset());
            var preset = Objects.requireNonNull(filters().summary(actor, networkId, open.presetId()));
            session.presetId = open.presetId();
            session.layer = Layer.PRESET;
            return view(
                    request,
                    session,
                    new NetworkTerminalState.Preset(
                            summary(topology().inspectNetwork(actor, networkId)), preset, rules));
        }
        if (request instanceof NetworkTerminalRequest.BeginPresetEdit begin) {
            if (begin.operation() == io.github.loongin.omniresonance.filter.PresetEditOperation.CREATE)
                requireLayer(session, Layer.FILTERS);
            else {
                requireLayer(session, Layer.PRESET);
                if (!Objects.equals(session.presetId, begin.presetId()))
                    throw new IllegalArgumentException("Preset identity mismatch");
            }
            var edit = filters().begin(actor, networkId, begin.operation(), begin.presetId(), begin.originalRule());
            session.presetEdit = edit;
            session.presetId = begin.presetId();
            session.layer = Layer.PRESET_EDIT;
            return view(
                    request,
                    session,
                    new NetworkTerminalState.PresetEdit(
                            summary(topology().inspectNetwork(actor, networkId)),
                            begin.presetId() == null ? null : filters().summary(actor, networkId, begin.presetId()),
                            begin.operation(),
                            edit.impact(),
                            edit.originalRule()));
        }
        if (request instanceof NetworkTerminalRequest.SavePresetEdit save) {
            requireLayer(session, Layer.PRESET_EDIT);
            UUID saved = filters().save(actor, Objects.requireNonNull(session.presetEdit), save.value());
            session.presetEdit = null;
            session.presetId = saved;
            if (saved == null) {
                session.layer = Layer.FILTERS;
                return view(request, session, filterList(actor, networkId, 0));
            }
            session.layer = Layer.PRESET;
            return view(request, session, presetState(actor, networkId, saved, 0));
        }
        throw new IllegalArgumentException("Invalid filter operation");
    }

    private io.github.loongin.omniresonance.networking.ManagementTransferPool pool() {
        return Objects.requireNonNull(nodeMenus, "Shared management coordinator unavailable")
                .transfers();
    }

    private long gameTick() {
        return server.overworld().getGameTime();
    }

    private NetworkTerminalResponse saveResourceRule(
            ServerPlayer actor,
            Session session,
            long sequence,
            @Nullable io.github.loongin.omniresonance.filter.ResourceRuleIntent intent) {
        UUID saved = filters().saveRule(actor, Objects.requireNonNull(session.presetEdit), intent);
        session.presetEdit = null;
        session.layer = Layer.PRESET;
        return new NetworkTerminalResponse.ViewState(
                session.viewId,
                session.id,
                sequence,
                presetState(actor, Objects.requireNonNull(session.networkId), saved, 0));
    }

    private NetworkTerminalResponse fullRuleReply(
            Session session,
            long sequence,
            io.github.loongin.omniresonance.filter.ResourceFilterPreset snapshot,
            @Nullable UUID sampleToken,
            int tanks,
            int tank) {
        int size = io.github.loongin.omniresonance.networking.FullFilterCodec.snapshotSize(snapshot);
        if (io.github.loongin.omniresonance.networking.FullFilterCodec.snapshotFitsPacket(size, sampleToken != null))
            return new NetworkTerminalResponse.FullRule(
                    session.viewId,
                    session.id,
                    sequence,
                    sampleToken,
                    tanks,
                    tank,
                    "",
                    io.github.loongin.omniresonance.networking.FullFilterCodec.snapshot(snapshot));
        if (session.filterTransfer != null) throw new IllegalStateException("Transfer already active");
        UUID transfer = Objects.requireNonNull(sessionIds.get());
        pool().beginDownload(
                        session.player.getUUID(),
                        session.id,
                        transfer,
                        size,
                        gameTick(),
                        () -> io.github.loongin.omniresonance.networking.FullFilterCodec.snapshot(snapshot));
        session.filterTransfer = new FilterTransfer(
                transfer, sequence, size, false, snapshot.id(), snapshot.revision(), session.presetEdit);
        session.filterTransfer.expiresTick = gameTick() + 200;
        return new NetworkTerminalResponse.RuleTransferReady(
                session.viewId, session.id, sequence, transfer, size, false, sampleToken, tanks, tank);
    }

    private static final class FilterTransfer {
        final UUID id, preset;
        final long sequence, revision;
        final int length;
        final boolean upload;
        final @Nullable io.github.loongin.omniresonance.filter.ItemFilterService.Edit edit;
        int offset;
        long expiresTick;

        FilterTransfer(
                UUID id,
                long sequence,
                int length,
                boolean upload,
                UUID preset,
                long revision,
                @Nullable io.github.loongin.omniresonance.filter.ItemFilterService.Edit edit) {
            this.id = id;
            this.sequence = sequence;
            this.length = length;
            this.upload = upload;
            this.preset = preset;
            this.revision = revision;
            this.edit = edit;
        }
    }

    private boolean transferAuthorized(Session session, FilterTransfer transfer) {
        if (gameTick() >= transfer.expiresTick
                || sessions.get(session.player.getUUID()) != session
                || session.filterTransfer != transfer
                || !Objects.equals(session.presetId, transfer.preset)
                || session.presetEdit != transfer.edit
                || session.layer != (transfer.edit == null ? Layer.PRESET : Layer.PRESET_EDIT)) return false;
        var current = filters().summary(session.player, Objects.requireNonNull(session.networkId), transfer.preset);
        if (current == null || current.revision() != transfer.revision) return false;
        if (transfer.edit != null) filters().validateEdit(session.player, transfer.edit);
        return true;
    }

    /** Handles only the actual terminal connection's previously admitted purpose-specific transfer. */
    public @Nullable NetworkTerminalResponse handleTransfer(
            ServerPlayer actor, io.github.loongin.omniresonance.networking.ManagementTransferMessage message) {
        requireServerThread();
        Session session = sessions.get(actor.getUUID());
        if (session == null || session.player != actor || !session.id.equals(message.session())) return null;
        FilterTransfer transfer = session.filterTransfer;
        if (transfer == null || !transfer.id.equals(message.transfer())) return null;
        try {
            if (!transferAuthorized(session, transfer)) throw new IllegalStateException("Stale transfer authority");
            if (message instanceof io.github.loongin.omniresonance.networking.ManagementTransferMessage.Abort) {
                cancelFilterTransfer(session);
                return null;
            }
            if (!transfer.upload) throw new IllegalArgumentException("Wrong transfer direction");
            if (message instanceof io.github.loongin.omniresonance.networking.ManagementTransferMessage.Chunk chunk) {
                pool().upload(actor.getUUID(), session.id, transfer.id, chunk.offset(), chunk.data(), gameTick());
                return null;
            }
            if (!(message instanceof io.github.loongin.omniresonance.networking.ManagementTransferMessage.Finish))
                throw new IllegalArgumentException("Unprepared filter transfer");
            NetworkTerminalResponse[] result = new NetworkTerminalResponse[1];
            io.github.loongin.omniresonance.filter.ResourceRuleIntent[] parsed =
                    new io.github.loongin.omniresonance.filter.ResourceRuleIntent[1];
            pool().finishUpload(
                            actor.getUUID(),
                            session.id,
                            transfer.id,
                            gameTick(),
                            object -> parsed[0] =
                                    io.github.loongin.omniresonance.networking.FullFilterCodec.readIntent(object),
                            () -> transferAuthorized(session, transfer),
                            object -> result[0] = saveResourceRule(actor, session, transfer.sequence, parsed[0]));
            session.filterTransfer = null;
            return result[0];
        } catch (RuntimeException failure) {
            cancelFilterTransfer(session);
            return new NetworkTerminalResponse.Failure(
                    session.viewId, session.id, transfer.sequence, NetworkTerminalResponse.Reason.INVALID_REQUEST);
        }
    }

    private void tickFilterTransfer(Session session) {
        FilterTransfer transfer = session.filterTransfer;
        if (transfer == null) return;
        try {
            if (!transferAuthorized(session, transfer)) throw new IllegalStateException("Expired filter transfer");
            if (transfer.upload) return;
            byte[] data = pool().nextDownload(session.player.getUUID(), session.id, transfer.id, gameTick());
            transferReplies.accept(
                    session.player,
                    new io.github.loongin.omniresonance.networking.ManagementTransferMessage.Chunk(
                            session.id, transfer.id, transfer.offset, data));
            transfer.offset += data.length;
            if (transfer.offset == transfer.length) session.filterTransfer = null;
        } catch (RuntimeException failure) {
            cancelFilterTransfer(session);
            directReplies.accept(
                    session.player,
                    new NetworkTerminalResponse.Failure(
                            session.viewId,
                            session.id,
                            transfer.sequence,
                            NetworkTerminalResponse.Reason.INVALID_REQUEST));
        }
    }

    private void cancelFilterTransfer(Session session) {
        FilterTransfer transfer = session.filterTransfer;
        session.filterTransfer = null;
        if (transfer != null) pool().abort(session.player.getUUID(), session.id, transfer.id);
    }

    private NetworkAdministrationService administration() {
        if (administration == null) throw new IllegalStateException("Member management is unavailable");
        return administration;
    }

    private NetworkSettingsService networkSettings() {
        if (networkSettings == null) throw new IllegalStateException("Network settings are unavailable");
        return networkSettings;
    }

    private static NetworkTerminalResponse.Reason membershipReason(NetworkAdministrationService.Reason reason) {
        return switch (reason) {
            case NO_ACCESS -> NetworkTerminalResponse.Reason.NO_ACCESS;
            case UNAVAILABLE -> NetworkTerminalResponse.Reason.DATA_UNAVAILABLE;
            case LOCKED -> NetworkTerminalResponse.Reason.LOCKED;
            case LOCK_EXPIRED -> NetworkTerminalResponse.Reason.LOCK_EXPIRED;
            case STALE_REVISION -> NetworkTerminalResponse.Reason.STALE_REVISION;
            case QUOTA_REACHED -> NetworkTerminalResponse.Reason.QUOTA_REACHED;
            case PLAYER_OFFLINE -> NetworkTerminalResponse.Reason.PLAYER_OFFLINE;
            case ALREADY_ADMINISTRATOR -> NetworkTerminalResponse.Reason.ALREADY_ADMINISTRATOR;
            case NOT_ADMINISTRATOR -> NetworkTerminalResponse.Reason.NOT_ADMINISTRATOR;
            case OWNER_TARGET -> NetworkTerminalResponse.Reason.INVALID_REQUEST;
        };
    }

    private static NetworkTerminalResponse.Reason settingsReason(NetworkSettingsService.Reason reason) {
        return switch (reason) {
            case NO_ACCESS -> NetworkTerminalResponse.Reason.NO_ACCESS;
            case UNAVAILABLE -> NetworkTerminalResponse.Reason.DATA_UNAVAILABLE;
            case LOCKED -> NetworkTerminalResponse.Reason.LOCKED;
            case LOCK_EXPIRED -> NetworkTerminalResponse.Reason.LOCK_EXPIRED;
            case STALE_REVISION -> NetworkTerminalResponse.Reason.STALE_REVISION;
            case INVALID_NAME -> NetworkTerminalResponse.Reason.INVALID_NAME;
            case NAME_CONFLICT -> NetworkTerminalResponse.Reason.NAME_CONFLICT;
            case HAS_NODES -> NetworkTerminalResponse.Reason.HAS_NODES;
            case HAS_EXCHANGES -> NetworkTerminalResponse.Reason.HAS_EXCHANGES;
            case STORAGE_UNVERIFIED -> NetworkTerminalResponse.Reason.STORAGE_UNVERIFIED;
        };
    }

    private static NetworkSummary summary(NetworkMetadata metadata) {
        return new NetworkSummary(
                metadata.id(), metadata.ownerId(), metadata.name().value());
    }

    private static boolean matches(@Nullable Session session, ServerPlayer player, NetworkTerminalRequest request) {
        return session != null
                && session.player == player
                && session.viewId.equals(request.viewId())
                && session.id.equals(request.sessionId());
    }

    private static NetworkTerminalResponse.Failure failure(
            NetworkTerminalRequest request, NetworkTerminalResponse.Reason reason) {
        return new NetworkTerminalResponse.Failure(request.viewId(), request.sessionId(), request.sequence(), reason);
    }

    private static NetworkTerminalResponse.Reason topologyReason(NetworkTopologyService.Reason reason) {
        return switch (reason) {
            case NO_ACCESS -> NetworkTerminalResponse.Reason.NO_ACCESS;
            case UNAVAILABLE -> NetworkTerminalResponse.Reason.DATA_UNAVAILABLE;
            case LOCKED -> NetworkTerminalResponse.Reason.LOCKED;
            case LOCK_EXPIRED -> NetworkTerminalResponse.Reason.LOCK_EXPIRED;
            case STALE_REVISION -> NetworkTerminalResponse.Reason.STALE_REVISION;
            case NAME_CONFLICT -> NetworkTerminalResponse.Reason.NAME_CONFLICT;
            case QUOTA_REACHED -> NetworkTerminalResponse.Reason.QUOTA_REACHED;
            case NODE_DISABLED -> NetworkTerminalResponse.Reason.INVALID_REQUEST;
            case TUNNEL_DISABLED -> NetworkTerminalResponse.Reason.TUNNEL_DISABLED;
            case LAST_CHANNEL -> NetworkTerminalResponse.Reason.INVALID_REQUEST;
            case RESET_REQUIRED -> NetworkTerminalResponse.Reason.RESET_REQUIRED;
            case TUNNEL_SWITCH_REQUIRED -> NetworkTerminalResponse.Reason.INVALID_REQUEST;
        };
    }

    private NetworkTopologyService topology() {
        if (topology == null) {
            throw new IllegalStateException("Topology management is unavailable");
        }
        return topology;
    }

    private static void logFailure(
            ServerPlayer player, NetworkTerminalRequest request, String outcome, RuntimeException failure) {
        LOGGER.error(
                "Terminal request failed ({}): operation={} player={} view={} session={} sequence={}",
                outcome,
                request.getClass().getSimpleName(),
                player.getUUID(),
                request.viewId(),
                request.sessionId(),
                request.sequence(),
                failure);
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Terminal runtime accessed outside the server thread");
        }
    }

    private static final class Session {
        private @Nullable io.github.loongin.omniresonance.exchange.ExchangeTerminalWire.State exchange;
        private boolean statusActive;
        private long statusGeneration, statusSequence, nextStatusTick;
        private final ServerPlayer player;
        private final UUID viewId;
        private final UUID id;
        private long lastSequence;
        private long inventoryGeneration;
        private boolean inventoryActive;
        private boolean nodeOverview;
        private long nodeDirectorySequence;
        private boolean chunkActive, chunkPending, chunkRejected, chunkBefore;
        private long chunkGeneration, chunkSequence, chunkAnchor, chunkNextTick;
        private Layer layer = Layer.DIRECTORY;
        private @Nullable UUID networkId;
        private @Nullable UUID tunnelId;
        private @Nullable UUID presetId;
        private @Nullable FilterTransfer filterTransfer;
        private long sampleSequence, sampleStartedTick;
        private @Nullable UUID sampleToken;
        private @Nullable io.github.loongin.omniresonance.filter.ItemFilterService.Edit presetEdit;
        private @Nullable NetworkTopologyService.Edit edit;
        private @Nullable NetworkTopologyService.DeletionEdit deletion;
        private @Nullable NetworkAdministrationService.RemovalEdit removal;
        private @Nullable NetworkSettingsService.RenameEdit networkRename;
        private @Nullable NetworkSettingsService.DeletionEdit networkDeletion;
        private List<NetworkAdministrationService.PlayerIdentity> candidates = List.of();
        private @Nullable UUID candidateSnapshotId;
        private int nextCandidateOffset;

        private Session(ServerPlayer player, UUID viewId, UUID id) {
            this.player = player;
            this.viewId = viewId;
            this.id = id;
        }
    }

    private enum Layer {
        DIRECTORY,
        NETWORK,
        NETWORK_SETTINGS,
        NETWORK_RENAME,
        NETWORK_DELETE,
        TUNNELS,
        CHANNELS,
        TUNNEL_SETTINGS,
        TUNNEL_EDIT,
        DELETE,
        MEMBERS,
        ADMIN_CANDIDATES,
        ADMIN_REMOVE,
        FILTERS,
        PRESET,
        PRESET_EDIT
    }
}
