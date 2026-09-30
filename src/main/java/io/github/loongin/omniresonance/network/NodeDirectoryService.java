// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.network;

import io.github.loongin.omniresonance.chunkloading.ChunkLoadingRuntime;
import io.github.loongin.omniresonance.filter.ItemFilterService;
import io.github.loongin.omniresonance.networking.NodeDirectoryPage;
import io.github.loongin.omniresonance.networking.NodeDirectoryRequest;
import io.github.loongin.omniresonance.networking.NodeMenuNodeSummary;
import io.github.loongin.omniresonance.node.NetworkNodeRecord;
import io.github.loongin.omniresonance.node.NodeManagementService;
import io.github.loongin.omniresonance.node.NodeMenuService;
import io.github.loongin.omniresonance.node.NodeMode;
import io.github.loongin.omniresonance.node.ResonanceNodeBlockEntity;
import io.github.loongin.omniresonance.persistence.NetworkSavedData;
import io.github.loongin.omniresonance.persistence.SavedNetworkRepository;
import io.github.loongin.omniresonance.transfer.ResourceScope;
import io.github.loongin.omniresonance.transfer.ResourceTransferPolicy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * Server-thread node browser. Pending scans share 128 node checks/tick round-robin across viewers; one node has
 * at most 1024 configured bindings. Metadata binding snapshots use a 1024-entry LRU keyed by network/node/revision,
 * detached immutable policies only, replaced on revision and cleared at shutdown. No lookup loads chunks.
 */
public final class NodeDirectoryService implements AutoCloseable {
    private final MinecraftServer server;
    private final SavedNetworkRepository repository;
    private final ChunkLoadingRuntime runtime;
    private final NodeManagementService management;
    private final NetworkTopologyService topology;
    private final NodeMenuService menus;
    private final ItemFilterService filters;
    private final BiConsumer<ServerPlayer, NodeDirectoryPage> sender;
    private final Map<UUID, Browser> browsers = new LinkedHashMap<>();
    private final ArrayDeque<UUID> ready = new ArrayDeque<>();
    private final Set<UUID> queued = new HashSet<>();
    private final LinkedHashMap<UUID, Cached> cache = new LinkedHashMap<>(16, 0.75f, true);

    private record Cached(UUID network, long revision, List<DirectNodeBinding> bindings) {}

    private long catalogRevision;

    private static final class Browser {
        final ServerPlayer player;
        final UUID network;
        NodeDirectoryRequest request;
        List<NetworkNodeRecord> snapshot = List.of();
        final ArrayList<NodeDirectoryPage.Row> found = new ArrayList<>(65);
        int index, step;
        boolean pending, rejected, fallback;
        long nextTick, catalogRevision;
        List<NetworkNodeRecord> catalogSnapshot = List.of();
        String query;

        @Nullable
        io.github.loongin.omniresonance.security.EditLockTable.Token rename;

        Browser(ServerPlayer player, UUID network, NodeDirectoryRequest request) {
            this.player = player;
            this.network = network;
            this.request = request;
        }
    }

    public NodeDirectoryService(
            MinecraftServer server,
            SavedNetworkRepository repository,
            ChunkLoadingRuntime runtime,
            NodeManagementService management,
            NetworkTopologyService topology,
            NodeMenuService menus,
            ItemFilterService filters,
            BiConsumer<ServerPlayer, NodeDirectoryPage> sender) {
        this.server = server;
        this.repository = repository;
        this.runtime = runtime;
        this.management = management;
        this.topology = topology;
        this.menus = menus;
        this.filters = filters;
        this.sender = sender;
    }

    private void check() {
        if (!server.isSameThread()) throw new IllegalStateException("Node browser off server thread");
    }

    public void handle(ServerPlayer player, UUID network, NodeDirectoryRequest request) {
        check();
        topology.inspectNetwork(player, network);
        var b = browsers.get(player.getUUID());
        if (b == null
                || b.player != player
                || !b.network.equals(network)
                || !b.request.session().equals(request.session())
                || b.request.generation() != request.generation()) {
            if (b != null) cancelRename(b);
            b = new Browser(player, network, request);
            browsers.put(player.getUUID(), b);
        } else if (request.sequence() <= b.request.sequence()) return;
        b.request = request;
        b.rejected = false;
        if (request.action() == NodeDirectoryRequest.Action.CATALOG) {
            catalog(b);
            return;
        }
        try {
            if (request.action() == NodeDirectoryRequest.Action.BEGIN_RENAME) {
                if (b.rename != null) throw new IllegalStateException("Node edit already active");
                var edit = management.acquireLinked(player, network, request.node());
                if (!edit.node().enabled()) {
                    management.cancel(player, edit.token());
                    throw new IllegalStateException("Node disabled");
                }
                b.rename = edit.token();
            } else if (request.action() == NodeDirectoryRequest.Action.CANCEL_EDIT) {
                cancelRename(b);
            } else if (request.action() == NodeDirectoryRequest.Action.RENAME) {
                if (b.rename == null || !b.rename.objectId().equals(request.node()))
                    throw new IllegalStateException("Rename lease missing");
                management.rename(player, network, request.revision(), new ManagedName(request.name()), b.rename);
                b.rename = null;
            } else if (request.action() == NodeDirectoryRequest.Action.SET_ENABLED) {
                var edit = management.acquireLinked(player, network, request.node());
                boolean committed = false;
                try {
                    if (edit.node().revision() != request.revision())
                        throw new IllegalStateException("Stale node edit");
                    management.setEnabled(player, network, request.revision(), request.enabled(), edit.token());
                    committed = true;
                } finally {
                    if (!committed) management.cancel(player, edit.token());
                }
            } else if (request.action() == NodeDirectoryRequest.Action.EDIT_CONFIGURATION) {
                management.inspectLinked(player, network, request.node());
                menus.openExistingConfiguration(player, network, request.node(), request.channel());
                closePlayer(player);
                return;
            }
        } catch (RuntimeException rejected) {
            b.rejected = true;
            player.displayClientMessage(
                    net.minecraft.network.chat.Component.translatable("omniresonance.nodes.rejected"), true);
        }
        if (!b.rejected
                && (request.action() == NodeDirectoryRequest.Action.RENAME
                        || request.action() == NodeDirectoryRequest.Action.SET_ENABLED)) {
            var data = repository.findLoadedNetwork(network).orElseThrow();
            var changed = data.findNode(request.node()).orElse(null);
            if (changed != null) {
                // Only the bounded visible window is patched; no full-catalog rebuild or optimistic value.
                for (int i = 0; i < b.found.size(); i++) {
                    if (b.found.get(i).node().nodeId().equals(changed.nodeId())) {
                        b.found.set(i, row(player, network, data, changed));
                        break;
                    }
                }
            }
        }
        if (request.action() != NodeDirectoryRequest.Action.QUERY && !b.pending && !b.snapshot.isEmpty()) {
            publish(b, repository.findLoadedNetwork(network).orElseThrow(), true);
            b.nextTick = server.overworld().getGameTime() + 20;
        } else begin(b);
    }

    private void catalog(Browser b) {
        cancelRename(b);
        b.pending = false;
        ready.remove(b.player.getUUID());
        queued.remove(b.player.getUUID());
        var snapshot = runtime.nodeSnapshot(b.network);
        int offset = b.request.detailOffset();
        if (offset == 0) {
            b.catalogSnapshot = snapshot;
            b.catalogRevision = catalogRevision = Math.incrementExact(catalogRevision);
        } else if (b.request.revision() != b.catalogRevision || snapshot != b.catalogSnapshot) {
            unavailable(b.player, b.request);
            return;
        }
        if (offset > snapshot.size()) {
            unavailable(b.player, b.request);
            return;
        }
        var data = repository.findLoadedNetwork(b.network).orElseThrow();
        var rows = new ArrayList<NodeDirectoryPage.Row>();
        for (int i = offset; i < Math.min(snapshot.size(), offset + 64); i++) {
            var expected = snapshot.get(i);
            var current = data.findNode(expected.nodeId()).orElse(null);
            if (!expected.equals(current)) {
                unavailable(b.player, b.request);
                return;
            }
            rows.add(row(b.player, b.network, data, current));
        }
        sender.accept(
                b.player,
                new NodeDirectoryPage(
                        b.request.session(),
                        b.request.generation(),
                        b.request.sequence(),
                        true,
                        false,
                        false,
                        rows,
                        false,
                        false,
                        null,
                        List.of(),
                        0,
                        0,
                        List.of(),
                        List.of(),
                        null,
                        new NodeDirectoryPage.Catalog(b.catalogRevision, offset, snapshot.size())));
    }

    private void begin(Browser b) {
        b.snapshot = runtime.nodeSnapshot(b.network);
        b.found.clear();
        b.pending = true;
        b.fallback = false;
        b.query = b.request.query().strip().toLowerCase(Locale.ROOT);
        int low = 0, high = b.snapshot.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            long number = b.snapshot.get(mid).nodeNumber();
            if (number < b.request.anchor() || !b.request.before() && number == b.request.anchor()) low = mid + 1;
            else high = mid;
        }
        b.step = b.request.before() ? -1 : 1;
        b.index = b.request.before() ? low - 1 : low;
        enqueue(b.player.getUUID());
    }

    private void enqueue(UUID id) {
        if (queued.add(id)) ready.addLast(id);
    }

    public void tick() {
        check();
        long now = server.overworld().getGameTime();
        var iterator = browsers.values().iterator();
        while (iterator.hasNext()) {
            var b = iterator.next();
            if (b.pending || now < b.nextTick) continue;
            if (b.request.action() != NodeDirectoryRequest.Action.CATALOG) {
                begin(b);
                continue;
            }
            try {
                topology.inspectNetwork(b.player, b.network);
                if (runtime.nodeSnapshot(b.network) != b.catalogSnapshot) {
                    publish(b, null, false);
                    iterator.remove();
                    continue;
                }
                b.nextTick = now + 20;
            } catch (RuntimeException denied) {
                publish(b, null, false);
                iterator.remove();
            }
        }
        for (int budget = 0; budget < 128 && !ready.isEmpty(); budget++) {
            UUID id = ready.removeFirst();
            queued.remove(id);
            var b = browsers.get(id);
            if (b == null || !b.pending) continue;
            try {
                topology.inspectNetwork(b.player, b.network);
                if (b.rename != null) management.heartbeat(b.player, b.rename);
                var data = repository.findLoadedNetwork(b.network).orElseThrow();
                if (b.index >= 0 && b.index < b.snapshot.size() && b.found.size() < 65) {
                    var node = data.findNode(b.snapshot.get(b.index).nodeId()).orElse(null);
                    b.index += b.step;
                    if (node != null && matches(b, data, node)) b.found.add(row(b.player, b.network, data, node));
                    enqueue(id);
                    continue;
                }
                if (b.found.isEmpty() && b.request.anchor() > 0 && !b.fallback) {
                    b.fallback = true;
                    b.step = 1;
                    b.index = 0;
                    enqueue(id);
                    continue;
                }
                publish(b, data, true);
                b.pending = false;
                b.nextTick = now + 20;
            } catch (RuntimeException denied) {
                try {
                    cancelRename(b);
                } catch (RuntimeException expired) {
                    b.rename = null;
                }
                publish(b, null, false);
                browsers.remove(id);
            }
        }
    }

    private List<DirectNodeBinding> bindings(UUID network, NetworkSavedData data, NetworkNodeRecord node) {
        var old = cache.get(node.nodeId());
        if (old != null && old.network.equals(network) && old.revision == node.revision()) return old.bindings;
        var value = data.directBindings(node.nodeId());
        cache.put(node.nodeId(), new Cached(network, node.revision(), value));
        if (cache.size() > 1024) cache.remove(cache.keySet().iterator().next());
        return value;
    }

    private boolean matches(Browser b, NetworkSavedData data, NetworkNodeRecord node) {
        var q = b.request;
        if (!b.query.isEmpty()
                && !node.name().value().toLowerCase(Locale.ROOT).contains(b.query)
                && !(node.position().pos().getX() + ", " + node.position().pos().getY() + ", "
                                + node.position().pos().getZ())
                        .contains(b.query)) return false;
        if (q.dimension() != null && !node.position().dimension().location().equals(q.dimension())) return false;
        if (q.status() != NodeDirectoryRequest.Status.ALL && status(b.network, data, node) != q.status()) return false;
        if (q.resource() == null) return true;
        if (node.mode() == NodeMode.DOMAIN)
            return data.domainConfiguration(node.nodeId())
                    .map(c -> c.policy().scope().includes(q.resource()))
                    .orElse(false);
        for (var binding : bindings(b.network, data, node))
            if (binding.policy().scope().includes(q.resource())) return true;
        return false;
    }

    private NodeDirectoryRequest.Status status(UUID network, NetworkSavedData data, NetworkNodeRecord node) {
        if (!node.enabled()) return NodeDirectoryRequest.Status.DISABLED;
        var level = server.getLevel(node.position().dimension());
        if (level == null) return NodeDirectoryRequest.Status.ERROR;
        var p = node.position().pos();
        var chunk = level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4);
        if (chunk == null) return NodeDirectoryRequest.Status.OFFLINE;
        var entity = chunk.getBlockEntity(p);
        if (!(entity instanceof ResonanceNodeBlockEntity be)
                || be.state().isEmpty()
                || !be.state().orElseThrow().nodeId().equals(node.nodeId())) return NodeDirectoryRequest.Status.ERROR;
        if (node.mode() == NodeMode.DOMAIN && menus.domainFailed(network, node.nodeId()))
            return NodeDirectoryRequest.Status.ERROR;
        if (node.mode() == NodeMode.DIRECT)
            for (var binding : bindings(network, data, node))
                if (menus.directFailed(node.nodeId(), binding.channelId())) return NodeDirectoryRequest.Status.ERROR;
        return NodeDirectoryRequest.Status.ONLINE;
    }

    private NodeDirectoryPage.Row row(ServerPlayer player, UUID network, NetworkSavedData data, NetworkNodeRecord n) {
        var metadata = topology.inspectNetwork(player, network);
        int faces = 0, count = 0;
        if (n.mode() == NodeMode.DIRECT)
            for (var b : bindings(network, data, n)) {
                faces |= b.workingFaces().effectiveMask(n.facing());
                count++;
            }
        else if (n.mode() == NodeMode.DOMAIN) {
            var c = data.domainConfiguration(n.nodeId()).orElse(null);
            if (c != null) {
                faces = c.workingFaces().effectiveMask(n.facing());
                count = 1;
            }
        }
        return new NodeDirectoryPage.Row(
                new NodeMenuNodeSummary(
                        network,
                        metadata.name().value(),
                        n.nodeId(),
                        n.name().value(),
                        n.revision(),
                        n.position().dimension().location(),
                        n.position().pos(),
                        n.form(),
                        n.facing(),
                        n.enabled(),
                        n.chunkLoadingRequested(),
                        n.mode()),
                n.nodeNumber(),
                status(network, data, n),
                faces,
                count);
    }

    private NodeDirectoryPage.Card card(
            ServerPlayer player,
            UUID network,
            NetworkNodeRecord node,
            @Nullable UUID channel,
            String name,
            String route,
            boolean enabled,
            ResourceTransferPolicy policy,
            WorkingFaces faces) {
        String preset = "";
        if (policy.filterPresetId() != null) {
            var summary = filters.summary(player, network, policy.filterPresetId());
            preset = summary == null ? "?" : summary.name();
        }
        String scope;
        if (policy.scope().kind() == ResourceScope.Kind.ALL) scope = "*";
        else {
            var builder = new StringBuilder(256);
            for (var id : policy.scope().resourceTypeIds()) {
                String next = id.toString();
                if (builder.length() + next.length() + 2 > 250) {
                    builder.append("…");
                    break;
                }
                if (!builder.isEmpty()) builder.append(", ");
                builder.append(next);
            }
            scope = builder.toString();
        }
        return new NodeDirectoryPage.Card(
                channel,
                name,
                route,
                enabled,
                policy.direction() == TransferDirection.INPUT,
                scope,
                preset,
                policy.intervalTicks(),
                policy instanceof ResourceTransferPolicy.Input input
                        ? input.keepCount()
                        : ((ResourceTransferPolicy.Output) policy).priority(),
                policy.redstoneCondition().name(),
                faces.effectiveMask(node.facing()),
                policy.resourcePolicyOverrides().size());
    }

    private void publish(Browser b, @Nullable NetworkSavedData data, boolean available) {
        var rows = new ArrayList<NodeDirectoryPage.Row>(b.found);
        boolean more = rows.size() > 64;
        if (more) rows.removeLast();
        if (b.step < 0) Collections.reverse(rows);
        NodeDirectoryPage.Row selected = null;
        var cards = new ArrayList<NodeDirectoryPage.Card>();
        int total = 0, offset = 0;
        if (available && b.request.node() != null) {
            var node = data.findNode(b.request.node()).orElse(null);
            if (node != null) {
                selected = row(b.player, b.network, data, node);
                if (node.enabled()) {
                    if (node.mode() == NodeMode.DIRECT) {
                        var values = bindings(b.network, data, node);
                        total = values.size();
                        offset = Math.min(b.request.detailOffset(), Math.max(0, total - 1));
                        for (int i = offset; i < Math.min(total, offset + 16); i++) {
                            var binding = values.get(i);
                            var c = data.findChannel(binding.channelId()).orElseThrow();
                            var tunnel = data.findTunnel(c.tunnelId()).orElseThrow();
                            cards.add(card(
                                    b.player,
                                    b.network,
                                    node,
                                    c.channelId(),
                                    c.name().value(),
                                    tunnel.name().value(),
                                    tunnel.enabled(),
                                    binding.policy(),
                                    binding.workingFaces()));
                        }
                    } else if (node.mode() == NodeMode.DOMAIN) {
                        var c = data.domainConfiguration(node.nodeId()).orElse(null);
                        if (c != null) {
                            total = 1;
                            cards.add(card(
                                    b.player,
                                    b.network,
                                    node,
                                    null,
                                    "",
                                    "",
                                    !topology.domainStorageUnavailable(b.player, b.network),
                                    c.policy(),
                                    c.workingFaces()));
                        }
                    }
                }
            }
        }
        var dimensions = new ArrayList<ResourceLocation>();
        if (available)
            for (var level : server.getAllLevels())
                if (dimensions.size() < 256) dimensions.add(level.dimension().location());
        var resources = available ? topology.resourceAdapters().types() : List.<ResourceLocation>of();
        if (resources.size() > 256) resources = resources.subList(0, 256);
        io.github.loongin.omniresonance.networking.NodeChunkLoadingInfo loading = null;
        if (available && selected != null) {
            var node = selected.node();
            var status = runtime.status(node.nodeId());
            if (!node.chunkLoadingRequested())
                status = io.github.loongin.omniresonance.chunkloading.ChunkLoadingAllocator.Status.OFF;
            else if (!node.enabled())
                status = io.github.loongin.omniresonance.chunkloading.ChunkLoadingAllocator.Status.NODE_DISABLED;
            else if (status == io.github.loongin.omniresonance.chunkloading.ChunkLoadingAllocator.Status.OFF)
                status = io.github.loongin.omniresonance.chunkloading.ChunkLoadingAllocator.Status.QUEUED;
            var owner = topology.inspectNetwork(b.player, b.network).ownerId();
            var quota = topology.settingsSnapshot().chunkLoading();
            loading = new io.github.loongin.omniresonance.networking.NodeChunkLoadingInfo(
                    status, runtime.ownerCount(owner), quota.perOwner(), runtime.reservedCount(), quota.server());
        }
        sender.accept(
                b.player,
                new NodeDirectoryPage(
                        b.request.session(),
                        b.request.generation(),
                        b.request.sequence(),
                        available,
                        b.rejected,
                        b.rename != null,
                        available ? rows : List.of(),
                        available && (b.step < 0 ? more : b.request.anchor() > 0 && !b.fallback),
                        available && (b.step < 0 ? b.request.anchor() > 0 : more),
                        selected,
                        cards,
                        offset,
                        total,
                        dimensions,
                        resources,
                        loading));
        b.rejected = false;
    }

    public void unavailable(ServerPlayer player, NodeDirectoryRequest request) {
        check();
        closePlayer(player);
        sender.accept(
                player,
                new NodeDirectoryPage(
                        request.session(),
                        request.generation(),
                        request.sequence(),
                        false,
                        true,
                        false,
                        List.of(),
                        false,
                        false,
                        null,
                        List.of(),
                        0,
                        0,
                        List.of(),
                        List.of()));
    }

    private void cancelRename(Browser b) {
        if (b.rename != null) {
            try {
                management.cancel(b.player, b.rename);
            } finally {
                b.rename = null;
            }
        }
    }

    public void closePlayer(ServerPlayer player) {
        check();
        var browser = browsers.remove(player.getUUID());
        if (browser != null) cancelRename(browser);
        ready.remove(player.getUUID());
        queued.remove(player.getUUID());
    }

    public void close() {
        check();
        for (var browser : browsers.values()) cancelRename(browser);
        browsers.clear();
        ready.clear();
        queued.clear();
        cache.clear();
    }
}
