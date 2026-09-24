// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.network;

import io.github.loongin.omniresonance.transfer.ResourceTypes;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Shared presentation of the same detached aggregate snapshot for the terminal and operator commands. */
public final class NetworkDiagnosticsText {
    private NetworkDiagnosticsText() {}

    public static List<Component> details(NetworkDiagnosticsSnapshot s) {
        var text = new ArrayList<Component>();
        text.add(Component.translatable(
                "omniresonance.diagnostics.counts", s.nodes(), s.tunnels(), s.channels(), s.administrators()));
        text.add(Component.translatable(
                "omniresonance.diagnostics.storage",
                Component.translatable("omniresonance.diagnostics."
                        + (s.storageState().equals("unavailable") ? "unavailable_state" : s.storageState())),
                s.knownVariants() < 0
                        ? Component.translatable("omniresonance.diagnostics.unknown")
                        : Component.literal(Long.toString(s.knownVariants()))));
        text.add(Component.translatable("omniresonance.diagnostics.recovery", s.recoveryVariants(), s.recoveryBytes()));
        text.add(text(
                "chunks",
                s.ownerChunks(),
                limit(s.ownerLimit()),
                s.serverChunks(),
                limit(s.serverLimit()),
                s.physicalChunks()));
        if (s.runtime() != null) {
            var r = s.runtime();
            var t = r.transfers();
            if (t.tick() >= 0)
                text.add(
                        text("work", String.format(java.util.Locale.ROOT, "%.3f", t.nanos() / 1_000_000.0), t.calls()));
            text.add(text("queues", r.due(), r.backoff(), r.syncTasks()));
            text.add(text("movement"));
            if (t.moved().isEmpty()) text.add(text("none"));
            for (var amount : t.moved()) {
                String value = amount.type().equals(io.github.loongin.omniresonance.transfer.ResourceTypes.FLUID)
                        ? java.math.BigDecimal.valueOf(amount.amount(), 3)
                                        .stripTrailingZeros()
                                        .toPlainString() + " B"
                        : amount.amount()
                                + (amount.type().equals(io.github.loongin.omniresonance.transfer.ResourceTypes.ENERGY)
                                        ? " FE"
                                        : "");
                text.add(typeName(amount.type()).copy().append(": ").append((amount.saturated() ? ">= " : "") + value));
            }
            if (t.omittedTypes() > 0) text.add(text("omitted", t.omittedTypes()));
            if (!t.error().isEmpty())
                text.add(text(
                        "error",
                        t.incident() != null
                                ? Component.translatable("omniresonance.status.incident.reason."
                                        + t.incident().reason().name().toLowerCase(java.util.Locale.ROOT))
                                : Component.translatable("omniresonance.status.error." + t.error()),
                        t.errorTick()));
            if (t.incident() != null) {
                var incident = t.incident();
                text.add(text(
                        "incident.command",
                        incident.node().name().isEmpty()
                                ? incident.node().id().toString()
                                : incident.node().name(),
                        incident.channelId() == null
                                ? "-"
                                : incident.channelName().isEmpty()
                                        ? incident.channelId().toString()
                                        : incident.channelName(),
                        typeName(incident.type())));
            }
        }
        return List.copyOf(text);
    }

    private static Component typeName(ResourceLocation id) {
        if (id.equals(ResourceTypes.ITEM)) return Component.translatable("omniresonance.resource_policy.type.item");
        if (id.equals(ResourceTypes.FLUID)) return Component.translatable("omniresonance.resource_policy.type.fluid");
        if (id.equals(ResourceTypes.ENERGY)) return Component.translatable("omniresonance.resource_policy.type.energy");
        return Component.literal(id.toString());
    }

    private static Component limit(int value) {
        return value < 0
                ? Component.translatable("omniresonance.nodes.unlimited")
                : Component.literal(Integer.toString(value));
    }

    private static Component text(String key, Object... args) {
        return Component.translatable("omniresonance.status." + key, args);
    }
}
