// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.network;

import io.github.loongin.omniresonance.chunkloading.ChunkLoadingRuntime;
import io.github.loongin.omniresonance.config.ServerSettings;
import io.github.loongin.omniresonance.persistence.SavedNetworkRepository;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

/** Runtime-owned read-only projection. UI/command adapters enforce their distinct access rules before calling. */
public final class NetworkDiagnosticsService {
    private final MinecraftServer server;
    private final SavedNetworkRepository repository;
    private final NetworkDirectory directory;
    private final ChunkLoadingRuntime chunks;
    private final Supplier<ServerSettings> settings;

    private java.util.function.Function<UUID, NetworkDiagnosticsSnapshot.RuntimeStats> runtimeSource;

    private record Cached(long tick, NetworkDiagnosticsSnapshot.RuntimeStats value) {}
    /** At most 128 observed networks; values expire after 20gt and are discarded with this server runtime. */
    private final java.util.LinkedHashMap<UUID, Cached> cache = new java.util.LinkedHashMap<>(16, 0.75f, true);

    public void installRuntime(java.util.function.Function<UUID, NetworkDiagnosticsSnapshot.RuntimeStats> source) {
        check();
        if (runtimeSource != null) throw new IllegalStateException("Runtime observer already installed");
        runtimeSource = Objects.requireNonNull(source);
    }

    private NetworkDiagnosticsSnapshot.RuntimeStats runtime(UUID id, long tick) {
        if (runtimeSource == null) return null;
        var old = cache.get(id);
        if (old != null && tick >= old.tick() && tick - old.tick() < 20) return old.value();
        var value = runtimeSource.apply(id);
        cache.put(id, new Cached(tick, value));
        if (cache.size() > 128) cache.remove(cache.keySet().iterator().next());
        return value;
    }

    public NetworkDiagnosticsService(
            MinecraftServer server,
            SavedNetworkRepository repository,
            NetworkDirectory directory,
            ChunkLoadingRuntime chunks,
            Supplier<ServerSettings> settings) {
        this.server = Objects.requireNonNull(server);
        this.repository = Objects.requireNonNull(repository);
        this.directory = Objects.requireNonNull(directory);
        this.chunks = Objects.requireNonNull(chunks);
        this.settings = Objects.requireNonNull(settings);
    }

    /** Reads only indexed metadata on the server thread, with a bounded output; no authority or storage mutation. */
    public NetworkDirectory.DiagnosticListing list(@Nullable UUID owner) {
        check();
        return directory.diagnosticList(owner, 128);
    }

    /** Returns a detached snapshot or empty for absent/inconsistent authority; never activates a ledger or scans blocks. */
    public Optional<NetworkDiagnosticsSnapshot> inspect(UUID id) {
        check();
        var metadata = directory.find(id).orElse(null);
        var data = repository.findLoadedNetwork(id).orElse(null);
        if (metadata == null || data == null || !metadata.equals(data.metadata())) return Optional.empty();
        var domain = repository.inspectDomain(id);
        String state = domain == null ? "not_loaded" : domain.state().name().toLowerCase(java.util.Locale.ROOT);
        long variants = domain == null ? -1 : domain.knownVariantCount();
        var limits = settings.get().chunkLoading();
        return Optional.of(new NetworkDiagnosticsSnapshot(
                id,
                metadata.ownerId(),
                server.overworld().getGameTime(),
                data.nodeCount(),
                data.tunnelCount(),
                data.channelCount(),
                data.administratorCount(),
                state,
                variants,
                data.recovery().variantCount(),
                data.recovery().encodedBytes(),
                chunks.ownerCount(metadata.ownerId()),
                chunks.reservedCount(),
                chunks.physicalCount(),
                limits.perOwner(),
                limits.server(),
                enrich(data, runtime(id, server.overworld().getGameTime()))));
    }

    static @Nullable NetworkDiagnosticsSnapshot.RuntimeStats enrich(
            io.github.loongin.omniresonance.persistence.NetworkSavedData data,
            @Nullable NetworkDiagnosticsSnapshot.RuntimeStats stats) {
        if (stats == null || stats.transfers().incident() == null) return stats;
        var old = stats.transfers().incident();
        var incident = new io.github.loongin.omniresonance.transfer.TransferIncident(
                endpoint(data, old.node()),
                old.peer() == null ? null : endpoint(data, old.peer()),
                old.channelId(),
                old.channelId() == null
                        ? ""
                        : data.findChannel(old.channelId())
                                .map(channel -> channel.name().value())
                                .orElse(""),
                old.type(),
                old.reason(),
                old.stage());
        return new NetworkDiagnosticsSnapshot.RuntimeStats(
                stats.transfers().withIncident(incident),
                stats.due(),
                stats.backoff(),
                stats.syncTasks(),
                stats.exchange());
    }

    private static io.github.loongin.omniresonance.transfer.TransferIncident.Endpoint endpoint(
            io.github.loongin.omniresonance.persistence.NetworkSavedData data,
            io.github.loongin.omniresonance.transfer.TransferIncident.Endpoint original) {
        return data.findNode(original.id())
                .map(node -> new io.github.loongin.omniresonance.transfer.TransferIncident.Endpoint(
                        node.nodeId(), node.name().value(), node.position()))
                .orElseGet(() -> new io.github.loongin.omniresonance.transfer.TransferIncident.Endpoint(
                        original.id(), "", null));
    }

    private void check() {
        if (!server.isSameThread()) throw new IllegalStateException("Diagnostics off server thread");
    }
}
