// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.compat.ae2;

import io.github.loongin.omniresonance.networking.NetworkProtocol;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** Optional bounded owner-selector transport, authenticated by actual player, physical UUID and session sequence. */
public final class Ae2InterfacePayloads {
    private static Consumer<Frame> receiver = frame -> {};

    public static void receiver(Consumer<Frame> value) {
        receiver = Objects.requireNonNull(value);
    }

    public record Choice(UUID id, String name) {
        public Choice {
            Objects.requireNonNull(id);
            if (!new io.github.loongin.omniresonance.network.ManagedName(name)
                    .value()
                    .equals(name)) throw new IllegalArgumentException("Invalid network name");
        }
    }

    public record Request(
            UUID session,
            long sequence,
            int action,
            @Nullable UUID network,
            String prefix) implements CustomPacketPayload {
        public static final Type<Request> TYPE =
                new Type<>(ResourceLocation.parse("omniresonance:ae_interface_request"));

        public Request {
            Objects.requireNonNull(session);
            Objects.requireNonNull(prefix);
            if (sequence < 1 || action < 0 || action > 4 || prefix.length() > 64 || action == 1 && network == null)
                throw new IllegalArgumentException("Invalid interface request");
        }

        @Override
        public Type<Request> type() {
            return TYPE;
        }

        public static final StreamCodec<FriendlyByteBuf, Request> CODEC = new StreamCodec<>() {
            public void encode(FriendlyByteBuf b, Request r) {
                b.writeUUID(r.session).writeLong(r.sequence).writeByte(r.action).writeBoolean(r.network != null);
                if (r.network != null) b.writeUUID(r.network);
                b.writeUtf(r.prefix, 64);
            }

            public Request decode(FriendlyByteBuf b) {
                if (b.readableBytes() > 1024) throw new IllegalArgumentException("Oversize interface request");
                var r = new Request(
                        b.readUUID(), b.readLong(), b.readUnsignedByte(), bool(b) ? b.readUUID() : null, b.readUtf(64));
                if (b.isReadable()) throw new IllegalArgumentException("Trailing interface request");
                return r;
            }
        };
    }

    public record Frame(
            UUID session,
            long sequence,
            boolean initial,
            @Nullable UUID selected,
            String status,
            String error,
            List<Choice> entries,
            int total,
            boolean more)
            implements CustomPacketPayload {
        public static final Type<Frame> TYPE = new Type<>(ResourceLocation.parse("omniresonance:ae_interface_frame"));

        public Frame {
            Objects.requireNonNull(session);
            entries = List.copyOf(entries);
            if (sequence < 0
                    || entries.size() > 128
                    || total < -1
                    || total > 262144
                    || total >= 0 && total < entries.size()
                    || !java.util.Set.of("unbound", "preparing", "offline", "ready", "conflict", "unavailable")
                            .contains(status)
                    || !java.util.Set.of("", "unavailable").contains(error))
                throw new IllegalArgumentException("Invalid interface frame");
        }

        @Override
        public Type<Frame> type() {
            return TYPE;
        }

        public static final StreamCodec<FriendlyByteBuf, Frame> CODEC = new StreamCodec<>() {
            public void encode(FriendlyByteBuf b, Frame f) {
                b.writeUUID(f.session)
                        .writeLong(f.sequence)
                        .writeBoolean(f.initial)
                        .writeBoolean(f.selected != null);
                if (f.selected != null) b.writeUUID(f.selected);
                b.writeUtf(f.status, 32)
                        .writeUtf(f.error, 32)
                        .writeInt(f.total)
                        .writeBoolean(f.more)
                        .writeVarInt(f.entries.size());
                for (var e : f.entries) b.writeUUID(e.id).writeUtf(e.name, 256);
            }

            public Frame decode(FriendlyByteBuf b) {
                if (b.readableBytes() > 65536) throw new IllegalArgumentException("Oversize interface frame");
                UUID id = b.readUUID();
                long seq = b.readLong();
                boolean initial = bool(b);
                UUID selected = bool(b) ? b.readUUID() : null;
                String status = b.readUtf(32), error = b.readUtf(32);
                int total = b.readInt();
                boolean more = bool(b);
                int n = b.readVarInt();
                if (n < 0 || n > 128 || n > b.readableBytes() / 17)
                    throw new IllegalArgumentException("Invalid interface page");
                var entries = new java.util.ArrayList<Choice>();
                for (int i = 0; i < n; i++) entries.add(new Choice(b.readUUID(), b.readUtf(256)));
                var f = new Frame(id, seq, initial, selected, status, error, entries, total, more);
                if (b.isReadable()) throw new IllegalArgumentException("Trailing interface frame");
                return f;
            }
        };
    }

    private static boolean bool(FriendlyByteBuf b) {
        int v = b.readUnsignedByte();
        if (v > 1) throw new IllegalArgumentException("Invalid flag");
        return v == 1;
    }

    public static void register(net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(NetworkProtocol.VERSION)
                .executesOn(net.neoforged.neoforge.network.registration.HandlerThread.MAIN);
        registrar.playToServer(Request.TYPE, Request.CODEC, (request, context) -> {
            var player = (net.minecraft.server.level.ServerPlayer) context.player();
            var runtime = Ae2InterfaceRuntime.find(player.server);
            if (runtime != null) runtime.request(player, request);
        });
        registrar.playToClient(Frame.TYPE, Frame.CODEC, (frame, context) -> receiver.accept(frame));
    }

    private Ae2InterfacePayloads() {}
}
