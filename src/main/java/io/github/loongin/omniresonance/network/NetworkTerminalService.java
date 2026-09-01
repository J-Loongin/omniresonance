// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.network;

import io.github.loongin.omniresonance.config.ServerConfig;
import io.github.loongin.omniresonance.config.ServerSettings;
import io.github.loongin.omniresonance.networking.NetworkSummary;
import io.github.loongin.omniresonance.networking.NetworkTerminalPage;
import io.github.loongin.omniresonance.networking.NetworkTerminalRequest;
import io.github.loongin.omniresonance.networking.NetworkTerminalResponse;
import io.github.loongin.omniresonance.persistence.SavedNetworkRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
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
        this.server = Objects.requireNonNull(server, "server");
        requireServerThread();
        this.directory = Objects.requireNonNull(directory, "directory");
        this.creation = Objects.requireNonNull(creation, "creation");
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
                sessions.remove(sender.getUUID());
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
            NetworkTerminalRequest.Create create = (NetworkTerminalRequest.Create) request;
            NetworkMetadata created = creation.create(sender.getUUID(), create.name(), settings.networksPerOwner());
            return new NetworkTerminalResponse.Success(
                    request.viewId(),
                    session.id,
                    request.sequence(),
                    page(sender.getUUID(), null, false),
                    summary(created));
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
        } catch (IllegalStateException unavailable) {
            logFailure(sender, request, "data unavailable; request ended", unavailable);
            return failure(request, NetworkTerminalResponse.Reason.DATA_UNAVAILABLE);
        } catch (IllegalArgumentException | ArithmeticException rejected) {
            logFailure(sender, request, "internal pre-commit rejection", rejected);
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
    }

    /** Releases only a matching actual player instance on the server thread, without network or file changes. */
    public void closePlayer(ServerPlayer player) {
        requireServerThread();
        Session session = sessions.get(Objects.requireNonNull(player, "player").getUUID());
        if (session != null && session.player == player) {
            sessions.remove(player.getUUID());
        }
    }

    /** Permanently closes this runtime on the server thread, releasing all session references without saving files. */
    public void close() {
        requireServerThread();
        sessions.clear();
        closed = true;
    }

    private NetworkTerminalResponse.Success open(ServerPlayer player, UUID viewId) {
        UUID playerId = player.getUUID();
        Session session = sessions.get(playerId);
        if (session == null || session.player != player || !session.viewId.equals(viewId)) {
            session = new Session(player, viewId, Objects.requireNonNull(sessionIds.get(), "session id"));
        }
        NetworkTerminalPage page = page(playerId, null, false);
        sessions.put(playerId, session);
        return new NetworkTerminalResponse.Success(viewId, session.id, 0, page, null);
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

        private Session(ServerPlayer player, UUID viewId, UUID id) {
            this.player = player;
            this.viewId = viewId;
            this.id = id;
        }
    }
}
