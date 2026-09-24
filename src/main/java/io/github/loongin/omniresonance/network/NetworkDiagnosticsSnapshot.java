// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.network;

import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Detached immutable aggregate diagnostics, safe on any thread; no world objects, rules, exception messages or resource keys. */
public record NetworkDiagnosticsSnapshot(
        UUID network,
        UUID owner,
        long gameTick,
        int nodes,
        int tunnels,
        int channels,
        int administrators,
        String storageState,
        long knownVariants,
        int recoveryVariants,
        long recoveryBytes,
        int ownerChunks,
        int serverChunks,
        int physicalChunks,
        int ownerLimit,
        int serverLimit,
        @org.jetbrains.annotations.Nullable RuntimeStats runtime) {
    public record RuntimeStats(
            io.github.loongin.omniresonance.transfer.TransferTelemetry.Snapshot transfers,
            int due,
            int backoff,
            int syncTasks) {
        public RuntimeStats {
            Objects.requireNonNull(transfers);
            if (due < 0 || backoff < 0 || syncTasks < 0) throw new IllegalArgumentException("Invalid runtime counts");
        }
    }

    public NetworkDiagnosticsSnapshot(
            UUID network,
            UUID owner,
            long gameTick,
            int nodes,
            int tunnels,
            int channels,
            int administrators,
            String storageState,
            long knownVariants,
            int recoveryVariants,
            long recoveryBytes,
            int ownerChunks,
            int serverChunks,
            int physicalChunks,
            int ownerLimit,
            int serverLimit) {
        this(
                network,
                owner,
                gameTick,
                nodes,
                tunnels,
                channels,
                administrators,
                storageState,
                knownVariants,
                recoveryVariants,
                recoveryBytes,
                ownerChunks,
                serverChunks,
                physicalChunks,
                ownerLimit,
                serverLimit,
                null);
    }

    public NetworkDiagnosticsSnapshot withRuntime(RuntimeStats value) {
        return new NetworkDiagnosticsSnapshot(
                network,
                owner,
                gameTick,
                nodes,
                tunnels,
                channels,
                administrators,
                storageState,
                knownVariants,
                recoveryVariants,
                recoveryBytes,
                ownerChunks,
                serverChunks,
                physicalChunks,
                ownerLimit,
                serverLimit,
                value);
    }

    public NetworkDiagnosticsSnapshot {
        Objects.requireNonNull(network);
        Objects.requireNonNull(owner);
        if (gameTick < 0
                || nodes < 0
                || tunnels < 0
                || channels < 0
                || administrators < 0
                || !java.util.Set.of("not_loaded", "available", "unavailable").contains(storageState)
                || knownVariants < -1
                || storageState.equals("available") != (knownVariants >= 0)
                || recoveryVariants < 0
                || recoveryBytes < 0
                || ownerChunks < 0
                || serverChunks < 0
                || physicalChunks < 0
                || ownerLimit < -1
                || serverLimit < -1) throw new IllegalArgumentException("Invalid diagnostics snapshot");
    }

    /** Pure bounded export of explicit aggregate fields; rejects oversized/invalid version strings before returning. */
    private static JsonObject incidentJson(io.github.loongin.omniresonance.transfer.TransferIncident incident) {
        var json = new JsonObject();
        json.add("node", endpointJson(incident.node()));
        if (incident.peer() != null) json.add("peer", endpointJson(incident.peer()));
        if (incident.channelId() != null)
            json.addProperty("channel_id", incident.channelId().toString());
        json.addProperty("channel_name", incident.channelName());
        json.addProperty("resource_type", incident.type().toString());
        json.addProperty("reason", incident.reason().name().toLowerCase(java.util.Locale.ROOT));
        json.addProperty("stage", incident.stage().name().toLowerCase(java.util.Locale.ROOT));
        return json;
    }

    private static JsonObject endpointJson(
            io.github.loongin.omniresonance.transfer.TransferIncident.Endpoint endpoint) {
        var json = new JsonObject();
        json.addProperty("id", endpoint.id().toString());
        json.addProperty("name", endpoint.name());
        if (endpoint.position() != null) {
            json.addProperty(
                    "dimension", endpoint.position().dimension().location().toString());
            json.addProperty("x", endpoint.position().pos().getX());
            json.addProperty("y", endpoint.position().pos().getY());
            json.addProperty("z", endpoint.position().pos().getZ());
        }
        return json;
    }

    public String export(String version) {
        if (version == null || version.length() > 256) throw new IllegalArgumentException("Invalid diagnostic version");
        var json = new JsonObject();
        json.addProperty("version", version);
        json.addProperty("network_id", network.toString());
        json.addProperty("owner_id", owner.toString());
        json.addProperty("game_tick", gameTick);
        json.addProperty("nodes", nodes);
        json.addProperty("tunnels", tunnels);
        json.addProperty("channels", channels);
        json.addProperty("administrators", administrators);
        json.addProperty("storage_state", storageState);
        json.addProperty("known_variants", knownVariants);
        json.addProperty("recovery_variants", recoveryVariants);
        json.addProperty("recovery_bytes", recoveryBytes);
        json.addProperty("owner_chunks", ownerChunks);
        json.addProperty("server_chunks", serverChunks);
        json.addProperty("physical_chunks", physicalChunks);
        json.addProperty("owner_chunk_limit", ownerLimit);
        json.addProperty("server_chunk_limit", serverLimit);
        if (runtime != null) {
            var stats = runtime.transfers();
            json.addProperty("sample_tick", stats.tick());
            json.addProperty("calls", stats.calls());
            json.addProperty("nanos", stats.nanos());
            json.addProperty("due_configurations", runtime.due());
            json.addProperty("backoff_configurations", runtime.backoff());
            json.addProperty("sync_tasks", runtime.syncTasks());
            json.addProperty("recent_error", stats.error());
            if (stats.incident() != null) json.add("incident", incidentJson(stats.incident()));
            json.addProperty("error_tick", stats.errorTick());
            json.addProperty("omitted_movement_types", stats.omittedTypes());
            var moved = new com.google.gson.JsonArray();
            for (var value : stats.moved()) {
                var row = new JsonObject();
                row.addProperty("type", value.type().toString());
                row.addProperty("amount", value.amount());
                row.addProperty("at_least", value.saturated());
                moved.add(row);
            }
            json.add("moved_20_ticks", moved);
        }
        String encoded = json.toString();
        if (encoded.getBytes(StandardCharsets.UTF_8).length > 65536)
            throw new IllegalArgumentException("Diagnostic export too large");
        return encoded;
    }
}
