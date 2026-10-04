// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.compat.ae2;

import io.github.loongin.omniresonance.config.ServerSettings;
import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.network.NetworkDirectory;
import io.github.loongin.omniresonance.node.NetworkNodeDirectory;
import io.github.loongin.omniresonance.node.NodeAuthorityService;
import io.github.loongin.omniresonance.node.NodeForm;
import io.github.loongin.omniresonance.node.NodeLinkState;
import io.github.loongin.omniresonance.node.NodeManagementService;
import io.github.loongin.omniresonance.node.ResonanceNodeBlockEntity;
import io.github.loongin.omniresonance.persistence.SavedNetworkRepository;
import io.github.loongin.omniresonance.transfer.TransferWorkBudget;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/** One server's loaded AE interfaces and physical owner-only selection sessions; no asynchronous world access. */
public final class Ae2InterfaceRuntime implements AutoCloseable {
    private static final Map<MinecraftServer, Ae2InterfaceRuntime> SERVERS = new IdentityHashMap<>();
    final MinecraftServer server;
    final SavedNetworkRepository repository;
    final NetworkDirectory networks;
    final NetworkNodeDirectory nodes;
    final NodeAuthorityService authority;
    final NodeManagementService management;
    final Supplier<ServerSettings> settings;
    final Ae2DomainMounts mounts = new Ae2DomainMounts(262144);
    final Map<UUID, Ae2InterfaceHost> byMember = new HashMap<>();
    final Set<Ae2InterfaceHost> pending = new LinkedHashSet<>();
    private final Map<ResonanceNodeBlockEntity, Ae2InterfaceHost> hosts = new IdentityHashMap<>();
    private final ArrayDeque<Ae2InterfaceHost> rotation = new ArrayDeque<>();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private boolean closed;

    private static final class Session {
        final ServerPlayer player;
        final UUID id = UUID.randomUUID();
        final ResonanceNodeBlockEntity entity;
        UUID node;
        long sequence;

        Session(ServerPlayer player, ResonanceNodeBlockEntity entity) {
            this.player = player;
            this.entity = entity;
            node = entity.state().orElseThrow().nodeId();
        }
    }

    public Ae2InterfaceRuntime(
            MinecraftServer server,
            SavedNetworkRepository repository,
            NetworkDirectory networks,
            NetworkNodeDirectory nodes,
            NodeAuthorityService authority,
            NodeManagementService management,
            Supplier<ServerSettings> settings) {
        this.server = server;
        this.repository = repository;
        this.networks = networks;
        this.nodes = nodes;
        this.authority = authority;
        this.management = management;
        this.settings = settings;
        if (SERVERS.putIfAbsent(server, this) != null) throw new IllegalStateException("Duplicate interface runtime");
    }

    public static @Nullable Ae2InterfaceRuntime find(MinecraftServer server) {
        return SERVERS.get(server);
    }

    public static void open(ServerPlayer player, BlockPos pos) {
        var runtime = find(player.server);
        if (runtime != null) runtime.openLocal(player, pos);
    }

    private void openLocal(ServerPlayer player, BlockPos pos) {
        check();
        try {
            management.inspectPhysical(player, pos);
            var entity = (ResonanceNodeBlockEntity) player.serverLevel().getBlockEntity(pos);
            requireAccess(player, entity);
            var session = new Session(player, entity);
            sessions.put(player.getUUID(), session);
            send(session, null, true, "");
        } catch (RuntimeException denied) {
            player.displayClientMessage(Component.translatable("omniresonance.ae_interface.denied"), true);
        }
    }

    private void requireAccess(ServerPlayer player, ResonanceNodeBlockEntity entity) {
        if (player.server != server
                || entity == null
                || entity.isRemoved()
                || entity.getLevel() != player.serverLevel()
                || !(entity.getBlockState().getBlock() instanceof Ae2InterfaceBlock)
                || player.distanceToSqr(
                                entity.getBlockPos().getX() + 0.5,
                                entity.getBlockPos().getY() + 0.5,
                                entity.getBlockPos().getZ() + 0.5)
                        > 64) throw new SecurityException("Interface is not accessible");
        var state = entity.state().orElseThrow();
        var bound = binding(entity);
        if (state.linkState() == NodeLinkState.LINKED && bound == null)
            throw new IllegalStateException("Interface authority unavailable");
        if (bound != null
                && !networks.find(bound.networkId()).orElseThrow().ownerId().equals(player.getUUID()))
            throw new SecurityException("Only the interface network owner may configure it");
    }

    @Nullable
    NetworkNodeDirectory.Entry binding(ResonanceNodeBlockEntity entity) {
        check();
        if (entity.isRemoved() || !(entity.getLevel() instanceof ServerLevel level) || level.getServer() != server)
            return null;
        var chunk = level.getChunkSource()
                .getChunkNow(
                        entity.getBlockPos().getX() >> 4, entity.getBlockPos().getZ() >> 4);
        if (chunk == null || chunk.getBlockEntities().get(entity.getBlockPos()) != entity) return null;
        var state = entity.state().orElse(null);
        if (state == null || state.linkState() != NodeLinkState.LINKED) return null;
        var found = nodes.byId(state.nodeId()).entry().orElse(null);
        if (found == null
                || found.record().form() != NodeForm.AE_INTERFACE
                || !found.record().position().equals(GlobalPos.of(level.dimension(), entity.getBlockPos())))
            return null;
        var data = repository.findLoadedNetwork(found.networkId()).orElse(null);
        var metadata = networks.find(found.networkId()).orElse(null);
        return data != null
                        && metadata != null
                        && data.metadata().equals(metadata)
                        && metadata.ownerId().equals(entity.interfaceOwner())
                        && data.findNode(state.nodeId())
                                .filter(found.record()::equals)
                                .isPresent()
                ? found
                : null;
    }

    public void loaded(ResonanceNodeBlockEntity entity) {
        check();
        if (entity.isRemoved()
                || !(entity.getBlockState().getBlock() instanceof Ae2InterfaceBlock)
                || hosts.containsKey(entity)
                || hosts.size() >= 262144) return;
        var host = new Ae2InterfaceHost(this, entity);
        hosts.put(entity, host);
        rotation.addLast(host);
        try {
            host.start();
        } catch (RuntimeException failure) {
            host.failed = true;
            org.slf4j.LoggerFactory.getLogger(Ae2InterfaceRuntime.class)
                    .error("AE interface initialization failed", failure);
        }
    }

    public @Nullable Ae2InterfaceHost host(ResonanceNodeBlockEntity entity) {
        check();
        return hosts.get(entity);
    }

    void changed(Ae2DomainMounts.Change change) {
        for (UUID id : change.affected()) {
            var host = byMember.get(id);
            if (host != null) pending.add(host);
        }
    }

    public void step(TransferWorkBudget budget) {
        check();
        int count = Math.min(32, rotation.size());
        for (int i = 0; i < count && budget.canFit(0); i++) {
            var host = rotation.removeFirst();
            if (host.entity.isRemoved()) {
                hosts.remove(host.entity);
                host.stop();
                pending.remove(host);
                continue;
            }
            rotation.addLast(host);
            try {
                host.prepare();
            } catch (RuntimeException failure) {
                host.failed = true;
                pending.add(host);
            }
        }
        int refreshed = 0;
        while (refreshed < 32 && budget.canFit(0) && !pending.isEmpty()) {
            var host = pending.iterator().next();
            pending.remove(host);
            try {
                host.refreshMount();
            } catch (RuntimeException failure) {
                host.failed = true;
                org.slf4j.LoggerFactory.getLogger(Ae2InterfaceRuntime.class)
                        .error("AE interface mount refresh failed", failure);
            }
            refreshed++;
        }
    }

    public void unload(
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
            net.minecraft.world.level.ChunkPos chunk) {
        check();
        var iterator = hosts.entrySet().iterator();
        while (iterator.hasNext()) {
            var e = iterator.next();
            var entity = e.getKey();
            if (entity.getLevel() != null
                    && entity.getLevel().dimension().equals(dimension)
                    && new net.minecraft.world.level.ChunkPos(entity.getBlockPos()).equals(chunk)) {
                var host = e.getValue();
                iterator.remove();
                rotation.remove(host);
                host.stop();
                pending.remove(host);
            }
        }
    }

    public void request(ServerPlayer player, Ae2InterfacePayloads.Request request) {
        check();
        var session = sessions.get(player.getUUID());
        if (session == null || session.player != player || !session.id.equals(request.session())) {
            Ae2InterfacePayloads.reject(player, request);
            return;
        }
        if (request.sequence() != session.sequence + 1) {
            sessions.remove(player.getUUID());
            Ae2InterfacePayloads.reject(player, request);
            return;
        }
        session.sequence = request.sequence();
        if (request.action() == 3) {
            sessions.remove(player.getUUID());
            return;
        }
        try {
            requireAccess(player, session.entity);
            if (!session.entity.state().orElseThrow().nodeId().equals(session.node))
                throw new IllegalStateException("Interface identity changed");
            if (request.action() == 1) {
                bind(player, session.entity, request.network(), request.prefix());
                session.node = session.entity.state().orElseThrow().nodeId();
            }
            if (request.action() == 2) {
                var old = binding(session.entity);
                if (old != null) {
                    authority.unlinkInterface(session.entity, old.record().revision());
                    repository.auditNetwork(
                            old.networkId(),
                            io.github.loongin.omniresonance.persistence.AuditEntry.of(
                                    "ae_interface_unbind", player, old.record().nodeId(), ""));
                }
                session.node = session.entity.state().orElseThrow().nodeId();
            }
            var host = hosts.get(session.entity);
            if (host != null) host.refreshMembership();
            if (request.action() == 4) sendStatus(session);
            else send(session, request.action() == 0 ? request.network() : null, false, "");
        } catch (RuntimeException denied) {
            sessions.remove(player.getUUID());
            Ae2InterfacePayloads.reject(player, request);
        }
    }

    void bind(ServerPlayer player, ResonanceNodeBlockEntity entity, UUID target, String prefix) {
        requireAccess(player, entity);
        var metadata = networks.find(target).orElseThrow();
        if (!metadata.ownerId().equals(player.getUUID())) throw new SecurityException("Network owner required");
        var old = binding(entity);
        if (old != null && old.networkId().equals(target)) return;
        var name =
                new ManagedName(new ManagedName(prefix).value() + " " + management.suggestedNodeNumber(player, target));
        if (old == null) {
            var token = management.acquireBlank(player, entity.getBlockPos(), target);
            try {
                management.linkBlank(player, entity.getBlockPos(), target, name, token);
            } catch (RuntimeException failure) {
                try {
                    management.cancel(player, token);
                } catch (RuntimeException released) {
                    failure.addSuppressed(released);
                }
                throw failure;
            }
        } else {
            var edit = management.beginNetworkMove(
                    player, old.networkId(), old.record().nodeId(), target);
            try {
                management.moveNetwork(player, edit, name);
            } catch (RuntimeException failure) {
                try {
                    management.cancel(player, edit.token());
                } catch (RuntimeException released) {
                    failure.addSuppressed(released);
                }
                throw failure;
            }
        }
        var ownerTag = entity.interfaceData();
        ownerTag.putUUID("domain_owner", player.getUUID());
        entity.interfaceData(ownerTag);
        var linked = binding(entity);
        if (linked != null && linked.record().mode() != io.github.loongin.omniresonance.node.NodeMode.DOMAIN) {
            var edit = management.acquireLinked(player, target, linked.record().nodeId());
            management.setMode(
                    player,
                    target,
                    linked.record().revision(),
                    io.github.loongin.omniresonance.node.NodeMode.DOMAIN,
                    false,
                    edit.token());
        }
        var host = hosts.get(entity);
        if (host != null) host.node.setOwningPlayer(player);
        repository.auditNetwork(
                target,
                io.github.loongin.omniresonance.persistence.AuditEntry.of(
                        "ae_interface_bind",
                        player,
                        entity.state().orElseThrow().nodeId(),
                        ""));
    }

    private void sendStatus(Session s) {
        var bound = binding(s.entity);
        var host = hosts.get(s.entity);
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                s.player,
                new Ae2InterfacePayloads.Frame(
                        s.id,
                        s.sequence,
                        false,
                        bound == null ? null : bound.networkId(),
                        host == null ? "preparing" : host.status(),
                        "",
                        java.util.List.of(),
                        -1,
                        false));
    }

    private void send(Session s, @Nullable UUID anchor, boolean initial, String error) {
        var choices = networks.pageOwned(s.player.getUUID(), anchor, 128);
        var bound = binding(s.entity);
        var host = hosts.get(s.entity);
        var rows = choices.entries().stream()
                .map(n -> new Ae2InterfacePayloads.Choice(n.id(), n.name().value()))
                .toList();
        var frame = new Ae2InterfacePayloads.Frame(
                s.id,
                s.sequence,
                initial,
                bound == null ? null : bound.networkId(),
                host == null ? "preparing" : host.status(),
                error,
                rows,
                choices.totalCount(),
                choices.hasNext());
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(s.player, frame);
    }

    public void logout(ServerPlayer player) {
        check();
        sessions.remove(player.getUUID());
    }

    private void check() {
        if (closed || !server.isSameThread())
            throw new IllegalStateException("Interface runtime closed or off server thread");
    }

    @Override
    public void close() {
        check();
        for (var host : hosts.values()) host.stop();
        hosts.clear();
        byMember.clear();
        rotation.clear();
        pending.clear();
        sessions.clear();
        mounts.clear();
        SERVERS.remove(server);
        closed = true;
    }
}
