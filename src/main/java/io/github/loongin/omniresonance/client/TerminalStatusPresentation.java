// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.network.NetworkDiagnosticsSnapshot;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Pure display model for an authorized snapshot; it never infers health from inactivity or sums unlike units. */
final class TerminalStatusPresentation {
    enum Tone {
        TEXT,
        MUTED,
        ACCENT,
        WARNING,
        ERROR
    }

    record Row(Component label, Component value, Tone tone, Component help, boolean section) {}

    private TerminalStatusPresentation() {}

    static long transferredTypes(NetworkDiagnosticsSnapshot s) {
        if (s.runtime() == null) return 0;
        long count = s.runtime().transfers().omittedTypes();
        for (var movement : s.runtime().transfers().moved()) if (movement.amount() > 0) count++;
        return count;
    }

    static List<Row> overview(NetworkDiagnosticsSnapshot s) {
        var rows = new ArrayList<Row>();
        long count = transferredTypes(s);
        boolean known = s.runtime() != null && s.runtime().transfers().tick() >= 0;
        rows.add(new Row(
                text("auto_transfer"),
                !known ? text("transfer_unknown") : count == 0 ? text("idle") : text("activity", count),
                known && count > 0 ? Tone.ACCENT : Tone.MUTED,
                text("recent_help"),
                false));
        boolean unavailable = s.storageState().equals("unavailable");
        boolean inventoryKnown = s.knownVariants() >= 0;
        rows.add(new Row(
                text("inventory"),
                unavailable
                        ? text("inventory_unavailable")
                        : inventoryKnown ? text("inventory_count", s.knownVariants()) : text("inventory_unknown"),
                unavailable ? Tone.ERROR : inventoryKnown ? Tone.TEXT : Tone.MUTED,
                text("inventory_help"),
                false));
        if (s.recoveryVariants() > 0)
            rows.add(new Row(
                    text("recovery"),
                    text("recovery_count", s.recoveryVariants()),
                    Tone.WARNING,
                    text("recovery_help"),
                    false));
        history(rows, s);
        return List.copyOf(rows);
    }

    static List<Row> details(NetworkDiagnosticsSnapshot s) {
        var rows = new ArrayList<Row>();
        section(rows, "network_section");
        add(rows, "nodes", number(s.nodes()), "counts_help");
        add(rows, "tunnels", number(s.tunnels()), "counts_help");
        add(rows, "channels", number(s.channels()), "counts_help");
        add(rows, "administrators", number(s.administrators()), "counts_help");
        section(rows, "scheduler_section");
        if (s.runtime() == null) add(rows, "runtime", text("unknown"), "runtime_help");
        else {
            var runtime = s.runtime();
            var t = runtime.transfers();
            add(
                    rows,
                    "duration",
                    t.tick() < 0
                            ? text("unknown")
                            : Component.literal(String.format(Locale.ROOT, "%.3f ms", t.nanos() / 1_000_000.0)),
                    "duration_help");
            add(rows, "calls", t.tick() < 0 ? text("unknown") : number(t.calls()), "calls_help");
            add(rows, "due", number(runtime.due()), "due_help");
            add(rows, "backoff", number(runtime.backoff()), "backoff_help");
            add(rows, "sync", number(runtime.syncTasks()), "sync_help");
        }
        section(rows, "storage_section");
        storage(rows, s);
        add(rows, "recovery", text("recovery_count", s.recoveryVariants()), "recovery_help");
        add(rows, "recovery_size", Component.literal(s.recoveryBytes() + " B"), "recovery_size_help");
        section(rows, "chunks_section");
        add(rows, "owner_chunks", quota(s.ownerChunks(), s.ownerLimit()), "owner_chunks_help");
        add(rows, "server_chunks", quota(s.serverChunks(), s.serverLimit()), "server_chunks_help");
        add(rows, "physical_chunks", number(s.physicalChunks()), "physical_chunks_help");
        section(rows, "movement_section");
        if (s.runtime() == null || s.runtime().transfers().tick() < 0)
            add(rows, "recent", text("unknown"), "recent_help");
        else {
            var t = s.runtime().transfers();
            if (transferredTypes(s) == 0)
                rows.add(new Row(text("recent"), text("none"), Tone.MUTED, text("recent_help"), false));
            for (var movement : t.moved()) {
                String amount = movement.type().equals(ResourceTypes.FLUID)
                        ? BigDecimal.valueOf(movement.amount(), 3)
                                        .stripTrailingZeros()
                                        .toPlainString() + " B"
                        : movement.amount() + (movement.type().equals(ResourceTypes.ENERGY) ? " FE" : "");
                Component name = type(movement.type());
                rows.add(new Row(
                        name,
                        Component.literal((movement.saturated() ? ">= " : "") + amount),
                        Tone.TEXT,
                        text("recent_help"),
                        false));
            }
            if (t.omittedTypes() > 0)
                rows.add(new Row(
                        text("omitted_types"), number(t.omittedTypes()), Tone.MUTED, text("omitted_help"), false));
        }
        if (s.runtime() != null && !s.runtime().transfers().error().isEmpty()) {
            section(rows, "history_section");
            history(rows, s);
            incidentDetails(rows, s);
        }
        return List.copyOf(rows);
    }

    private static void storage(List<Row> rows, NetworkDiagnosticsSnapshot s) {
        Tone storageTone = s.storageState().equals("unavailable")
                ? Tone.ERROR
                : s.storageState().equals("available") ? Tone.ACCENT : Tone.MUTED;
        rows.add(new Row(
                text("storage"),
                Component.translatable("omniresonance.diagnostics."
                        + (s.storageState().equals("unavailable") ? "unavailable_state" : s.storageState())),
                storageTone,
                text("storage_help"),
                false));
        rows.add(new Row(
                text("variants"),
                s.knownVariants() < 0 ? text("unread") : number(s.knownVariants()),
                s.knownVariants() < 0 ? Tone.MUTED : Tone.TEXT,
                text("variants_help"),
                false));
    }

    private static void history(List<Row> rows, NetworkDiagnosticsSnapshot s) {
        if (s.runtime() == null || s.runtime().transfers().error().isEmpty()) return;
        var t = s.runtime().transfers();
        Component value = text("error." + t.error());
        if (t.incident() != null) {
            var incident = t.incident();
            String name = incident.node().name();
            if (name.codePointCount(0, name.length()) > 14)
                name = name.substring(0, name.offsetByCodePoints(0, 14)) + "…";
            value = text(
                    "incident.summary",
                    name.isEmpty() ? text("incident.node_unknown") : Component.literal(name),
                    text("incident.reason." + incident.reason().name().toLowerCase(Locale.ROOT)));
        }
        rows.add(new Row(text("history"), value, Tone.WARNING, text("history_help", t.errorTick()), false));
    }

    private static void incidentDetails(List<Row> rows, NetworkDiagnosticsSnapshot s) {
        var t = s.runtime().transfers();
        add(
                rows,
                "incident.time",
                t.errorTick() < 0 ? text("unknown") : text("incident.age", Math.max(0, s.gameTick() - t.errorTick())),
                "incident.time_help");
        var incident = t.incident();
        if (incident == null) {
            add(rows, "incident.node", text("unknown"), "incident.legacy_help");
            add(rows, "incident.position", text("unknown"), "incident.legacy_help");
            add(rows, "incident.channel", text("unknown"), "incident.legacy_help");
            add(rows, "incident.resource", text("unknown"), "incident.legacy_help");
            add(rows, "incident.reason", text("incident.reason.unknown"), "incident.legacy_help");
            add(rows, "incident.stage", text("unknown"), "incident.legacy_help");
            return;
        }
        endpoint(rows, "incident.node", incident.node());
        if (incident.peer() != null) endpoint(rows, "incident.peer", incident.peer());
        add(
                rows,
                "incident.channel",
                incident.channelId() == null
                        ? text("incident.domain")
                        : incident.channelName().isEmpty()
                                ? text("unknown")
                                : Component.literal(incident.channelName()),
                "incident.metadata_help");
        add(rows, "incident.resource", type(incident.type()), "recent_help");
        add(
                rows,
                "incident.reason",
                text("incident.reason." + incident.reason().name().toLowerCase(Locale.ROOT)),
                "incident.reason_help");
        add(
                rows,
                "incident.stage",
                text("incident.stage." + incident.stage().name().toLowerCase(Locale.ROOT)),
                "incident.stage_help");
    }

    private static void endpoint(
            List<Row> rows, String label, io.github.loongin.omniresonance.transfer.TransferIncident.Endpoint endpoint) {
        add(
                rows,
                label,
                endpoint.name().isEmpty() ? text("incident.node_unknown") : Component.literal(endpoint.name()),
                "incident.metadata_help");
        Component position = text("unknown");
        if (endpoint.position() != null) {
            var pos = endpoint.position();
            position = Component.literal(
                    pos.dimension().location() + " · " + pos.pos().getX() + ", "
                            + pos.pos().getY() + ", " + pos.pos().getZ());
        }
        add(rows, "incident.position", position, "incident.metadata_help");
        if (endpoint.name().isEmpty())
            add(rows, "incident.id", Component.literal(endpoint.id().toString()), "incident.metadata_help");
    }

    private static Component type(ResourceLocation id) {
        if (id.equals(ResourceTypes.ITEM)) return Component.translatable("omniresonance.resource_policy.type.item");
        if (id.equals(ResourceTypes.FLUID)) return Component.translatable("omniresonance.resource_policy.type.fluid");
        if (id.equals(ResourceTypes.ENERGY)) return Component.translatable("omniresonance.resource_policy.type.energy");
        return Component.literal(id.toString());
    }

    private static Component quota(int used, int limit) {
        return Component.literal(used + " / ")
                .append(limit < 0 ? Component.translatable("omniresonance.nodes.unlimited") : number(limit));
    }

    private static void section(List<Row> rows, String key) {
        rows.add(new Row(text(key), Component.empty(), Tone.MUTED, Component.empty(), true));
    }

    private static void add(List<Row> rows, String key, Component value, String help) {
        rows.add(new Row(text(key), value, Tone.TEXT, text(help), false));
    }

    private static Component number(long value) {
        return Component.literal(Long.toString(value));
    }

    static Component text(String key, Object... args) {
        return Component.translatable("omniresonance.status." + key, args);
    }
}
