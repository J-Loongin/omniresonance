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
    private static final Logger LOGGER = LoggerFactory.getLogger(NetworkTerminalService.class);
    private final MinecraftServer server;
    private final NetworkDirectory directory;
    private final NetworkCreationService creation;
    private final @Nullable NetworkTopologyService topology;
    private final @Nullable NetworkAdministrationService administration;
    private final @Nullable NetworkSettingsService networkSettings;
    private final @Nullable NodeMenuService nodeMenus;
    private final BiConsumer<ServerPlayer, NetworkTerminalResponse> directReplies;
    private final Supplier<UUID> sessionIds;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final long configurationEpoch;
    private final boolean configurationLoaded;
    private ServerSettings settings;
    private long configurationRevision;
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
                return new NetworkTerminalResponse.Success(
                        request.viewId(),
                        session.id,
                        request.sequence(),
                        page(sender.getUUID(), null, false),
                        summary(created));
            }
            return handleTopology(sender, session, request);
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
        if (!closed && administration != null) administration.applyConfiguration(state);
    }

    /** Advances owned member/settings clocks; no per-tick roster enumeration or synchronization occurs. */
    public void tick() {
        requireServerThread();
        if (!closed && administration != null) administration.tick();
        if (!closed && networkSettings != null) networkSettings.tick();
    }

    /** Releases only a matching actual player instance on the server thread, without network or file changes. */
    public void closePlayer(ServerPlayer player) {
        requireServerThread();
        Session session = sessions.get(Objects.requireNonNull(player, "player").getUUID());
        if (session != null && session.player == player) {
            cancelSessionEdit(player, session);
            sessions.remove(player.getUUID());
        }
    }

    /** Permanently closes this runtime on the server thread, releasing all session references without saving files. */
    public void close() {
        requireServerThread();
        for (Session session : sessions.values()) {
            cancelSessionEdit(session.player, session);
        }
        sessions.clear();
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

    private NetworkTerminalResponse navigateBack(
            NetworkTerminalRequest request, ServerPlayer player, Session session, UUID networkId) {
        return switch (session.layer) {
            case MEMBERS -> {
                session.layer = Layer.NETWORK;
                yield view(
                        request,
                        session,
                        new NetworkTerminalState.NetworkRoot(
                                summary(administration().inspectNetwork(player, networkId))));
            }
            case NETWORK_SETTINGS -> {
                session.layer = Layer.NETWORK;
                yield view(
                        request,
                        session,
                        new NetworkTerminalState.NetworkRoot(summary(
                                networkSettings().inspect(player, networkId).metadata())));
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
                yield view(
                        request,
                        session,
                        new NetworkTerminalState.NetworkRoot(summary(topology().inspectNetwork(player, networkId))));
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
            return view(request, session, new NetworkTerminalState.NetworkRoot(summary(network)));
        }
        UUID networkId = requireSelectedNetwork(session);
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
            if (previous == Layer.ADMIN_REMOVE
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
        private final ServerPlayer player;
        private final UUID viewId;
        private final UUID id;
        private long lastSequence;
        private Layer layer = Layer.DIRECTORY;
        private @Nullable UUID networkId;
        private @Nullable UUID tunnelId;
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
        ADMIN_REMOVE
    }
}
