// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.github.loongin.omniresonance.network.NetworkDiagnosticsSnapshot;
import io.github.loongin.omniresonance.transfer.TransferTelemetry;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** Bounded aggregate frame with optional incident metadata; no resource keys, rules, exception messages or world objects. */
public record NetworkStatusFrame(
        UUID session,
        long generation,
        long sequence,
        @Nullable NetworkDiagnosticsSnapshot snapshot,
        String version) implements CustomPacketPayload {
    public static final Type<NetworkStatusFrame> TYPE =
            new Type<>(ResourceLocation.parse("omniresonance:network_status_frame"));

    public NetworkStatusFrame {
        Objects.requireNonNull(session);
        Objects.requireNonNull(version);
        if (generation < 1 || sequence < 1 || version.length() > 256)
            throw new IllegalArgumentException("Invalid status frame");
    }

    public static final StreamCodec<FriendlyByteBuf, NetworkStatusFrame> STREAM_CODEC = new StreamCodec<>() {
        public void encode(FriendlyByteBuf b, NetworkStatusFrame frame) {
            b.writeUUID(frame.session)
                    .writeLong(frame.generation)
                    .writeLong(frame.sequence)
                    .writeUtf(frame.version, 256)
                    .writeBoolean(frame.snapshot != null);
            if (frame.snapshot == null) return;
            var s = frame.snapshot;
            b.writeUUID(s.network())
                    .writeUUID(s.owner())
                    .writeLong(s.gameTick())
                    .writeInt(s.nodes())
                    .writeInt(s.tunnels())
                    .writeInt(s.channels())
                    .writeInt(s.administrators())
                    .writeUtf(s.storageState(), 32)
                    .writeLong(s.knownVariants())
                    .writeInt(s.recoveryVariants())
                    .writeLong(s.recoveryBytes())
                    .writeInt(s.ownerChunks())
                    .writeInt(s.serverChunks())
                    .writeInt(s.physicalChunks())
                    .writeInt(s.ownerLimit())
                    .writeInt(s.serverLimit())
                    .writeBoolean(s.runtime() != null);
            if (s.runtime() == null) return;
            var r = s.runtime();
            var t = r.transfers();
            b.writeInt(r.due())
                    .writeInt(r.backoff())
                    .writeInt(r.syncTasks())
                    .writeLong(t.tick())
                    .writeLong(t.calls())
                    .writeLong(t.nanos())
                    .writeUtf(t.error(), 32)
                    .writeLong(t.errorTick())
                    .writeInt(t.omittedTypes())
                    .writeInt(t.moved().size());
            for (var m : t.moved())
                b.writeUtf(m.type().toString(), 128).writeLong(m.amount()).writeBoolean(m.saturated());
            b.writeBoolean(t.incident() != null);
            if (t.incident() != null) writeIncident(b, t.incident());
        }

        public NetworkStatusFrame decode(FriendlyByteBuf b) {
            if (b.readableBytes() > 32768) throw new IllegalArgumentException("Status frame too large");
            UUID session = b.readUUID();
            long generation = b.readLong(), sequence = b.readLong();
            String version = b.readUtf(256);
            NetworkDiagnosticsSnapshot snapshot = null;
            if (b.readBoolean()) {
                snapshot = new NetworkDiagnosticsSnapshot(
                        b.readUUID(),
                        b.readUUID(),
                        b.readLong(),
                        b.readInt(),
                        b.readInt(),
                        b.readInt(),
                        b.readInt(),
                        b.readUtf(32),
                        b.readLong(),
                        b.readInt(),
                        b.readLong(),
                        b.readInt(),
                        b.readInt(),
                        b.readInt(),
                        b.readInt(),
                        b.readInt());
                if (b.readBoolean()) {
                    int due = b.readInt(), backoff = b.readInt(), sync = b.readInt();
                    long tick = b.readLong(), calls = b.readLong(), nanos = b.readLong();
                    String error = b.readUtf(32);
                    long errorTick = b.readLong();
                    int omitted = b.readInt(), size = b.readInt();
                    if (size < 0 || size > 128) throw new IllegalArgumentException("Invalid movement window");
                    var moved = new ArrayList<TransferTelemetry.Movement>(size);
                    for (int i = 0; i < size; i++)
                        moved.add(new TransferTelemetry.Movement(
                                ResourceLocation.parse(b.readUtf(128)), b.readLong(), b.readBoolean()));
                    snapshot = snapshot.withRuntime(new NetworkDiagnosticsSnapshot.RuntimeStats(
                            new TransferTelemetry.Snapshot(
                                    tick,
                                    calls,
                                    nanos,
                                    moved,
                                    omitted,
                                    error,
                                    errorTick,
                                    b.readBoolean() ? readIncident(b) : null),
                            due,
                            backoff,
                            sync));
                }
            }
            if (b.isReadable()) throw new IllegalArgumentException("Trailing status bytes");
            return new NetworkStatusFrame(session, generation, sequence, snapshot, version);
        }
    };

    private static void writeIncident(
            FriendlyByteBuf b, io.github.loongin.omniresonance.transfer.TransferIncident incident) {
        writeEndpoint(b, incident.node());
        b.writeBoolean(incident.peer() != null);
        if (incident.peer() != null) writeEndpoint(b, incident.peer());
        b.writeBoolean(incident.channelId() != null);
        if (incident.channelId() != null) b.writeUUID(incident.channelId());
        b.writeUtf(incident.channelName(), 256)
                .writeUtf(incident.type().toString(), 128)
                .writeUtf(incident.reason().name(), 32)
                .writeUtf(incident.stage().name(), 32);
    }

    private static io.github.loongin.omniresonance.transfer.TransferIncident readIncident(FriendlyByteBuf b) {
        var node = readEndpoint(b);
        var peer = b.readBoolean() ? readEndpoint(b) : null;
        var channel = b.readBoolean() ? b.readUUID() : null;
        return new io.github.loongin.omniresonance.transfer.TransferIncident(
                node,
                peer,
                channel,
                b.readUtf(256),
                ResourceLocation.parse(b.readUtf(128)),
                io.github.loongin.omniresonance.transfer.TransferIncident.Reason.valueOf(b.readUtf(32)),
                io.github.loongin.omniresonance.transfer.ResourceTransferEngine.Stage.valueOf(b.readUtf(32)));
    }

    private static void writeEndpoint(
            FriendlyByteBuf b, io.github.loongin.omniresonance.transfer.TransferIncident.Endpoint endpoint) {
        b.writeUUID(endpoint.id()).writeUtf(endpoint.name(), 256).writeBoolean(endpoint.position() != null);
        if (endpoint.position() != null)
            b.writeUtf(endpoint.position().dimension().location().toString(), 256)
                    .writeBlockPos(endpoint.position().pos());
    }

    private static io.github.loongin.omniresonance.transfer.TransferIncident.Endpoint readEndpoint(FriendlyByteBuf b) {
        UUID id = b.readUUID();
        String name = b.readUtf(256);
        net.minecraft.core.GlobalPos position = b.readBoolean()
                ? net.minecraft.core.GlobalPos.of(
                        net.minecraft.resources.ResourceKey.create(
                                net.minecraft.core.registries.Registries.DIMENSION,
                                ResourceLocation.parse(b.readUtf(256))),
                        b.readBlockPos())
                : null;
        return new io.github.loongin.omniresonance.transfer.TransferIncident.Endpoint(id, name, position);
    }

    public Type<NetworkStatusFrame> type() {
        return TYPE;
    }
}
