// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.github.loongin.omniresonance.exchange.ExchangeTelemetry;
import net.minecraft.network.FriendlyByteBuf;

/** Bounded caller-thread codec for one authorized network's exchange measurements; no exception or resource bytes. */
final class ExchangeDiagnosticsCodec {
    private ExchangeDiagnosticsCodec() {}

    static void write(FriendlyByteBuf b, ExchangeTelemetry.Snapshot s) {
        b.writeBoolean(s.available())
                .writeBoolean(s.observed())
                .writeLong(s.sent())
                .writeLong(s.received())
                .writeBoolean(s.lowerBound())
                .writeBoolean(s.incident() != null);
        if (s.incident() == null) return;
        var i = s.incident();
        b.writeUUID(i.channel()).writeUUID(i.source()).writeUUID(i.target()).writeBoolean(i.type() != null);
        if (i.type() != null) b.writeUtf(i.type().toString(), 128);
        b.writeLong(i.tick())
                .writeByte(i.stage().ordinal())
                .writeByte(i.reason().ordinal());
        NetworkSummary.writeName(b, i.channelName());
        NetworkSummary.writeName(b, i.sourceName());
        NetworkSummary.writeName(b, i.targetName());
    }

    static ExchangeTelemetry.Snapshot read(FriendlyByteBuf b) {
        boolean available = bool(b), observed = bool(b);
        long sent = b.readLong(), received = b.readLong();
        boolean lower = bool(b);
        ExchangeTelemetry.Incident incident = null;
        if (bool(b)) {
            var channel = b.readUUID();
            var source = b.readUUID();
            var target = b.readUUID();
            var type = bool(b) ? net.minecraft.resources.ResourceLocation.parse(b.readUtf(128)) : null;
            long tick = b.readLong();
            int stage = b.readUnsignedByte(), reason = b.readUnsignedByte();
            if (stage >= ExchangeTelemetry.Stage.values().length || reason >= ExchangeTelemetry.Reason.values().length)
                throw new IllegalArgumentException("Invalid exchange diagnostic enum");
            incident = new ExchangeTelemetry.Incident(
                    channel,
                    source,
                    target,
                    type,
                    tick,
                    ExchangeTelemetry.Stage.values()[stage],
                    ExchangeTelemetry.Reason.values()[reason],
                    NetworkSummary.readName(b),
                    NetworkSummary.readName(b),
                    NetworkSummary.readName(b));
        }
        return new ExchangeTelemetry.Snapshot(available, observed, sent, received, lower, incident);
    }

    private static boolean bool(FriendlyByteBuf b) {
        int v = b.readUnsignedByte();
        if (v > 1) throw new IllegalArgumentException("Invalid exchange diagnostic flag");
        return v == 1;
    }
}
