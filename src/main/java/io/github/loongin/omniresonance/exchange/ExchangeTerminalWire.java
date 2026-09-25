// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.exchange;

import io.github.loongin.omniresonance.config.ServerSettings;
import io.github.loongin.omniresonance.filter.ResourceFilterRule;
import io.github.loongin.omniresonance.network.NetworkDirectory;
import io.github.loongin.omniresonance.networking.ExchangeFrame;
import io.github.loongin.omniresonance.networking.ExchangeIntent;
import io.github.loongin.omniresonance.networking.ExchangeIntentCodec;
import io.github.loongin.omniresonance.networking.ExchangeRequest;
import io.github.loongin.omniresonance.networking.ExchangeRuleView;
import io.github.loongin.omniresonance.networking.FilterPresetSummary;
import io.github.loongin.omniresonance.networking.ManagementTransferPool;
import io.github.loongin.omniresonance.persistence.SavedNetworkRepository;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/** Terminal-session-owned transport adapter. Caller authenticates the live session, page and sequence first. */
public final class ExchangeTerminalWire {
    public static final class State {
        public final UUID generation;
        private UUID transfer;
        private int kind, length, offset;
        private boolean upload;
        private boolean committed;

        public boolean committed() {
            return committed;
        }

        public State(UUID generation) {
            this.generation = generation;
        }
    }

    private final ExchangeTerminalController controller;
    private final SavedNetworkRepository repository;
    private final NetworkDirectory networks;
    private final ManagementTransferPool pool;

    public ExchangeTerminalWire(
            ExchangeTerminalController controller,
            SavedNetworkRepository repository,
            NetworkDirectory networks,
            ManagementTransferPool pool) {
        this.controller = controller;
        this.repository = repository;
        this.networks = networks;
        this.pool = pool;
    }

    public void close(ServerPlayer player, UUID session) {
        pool.cancelSession(player.getUUID(), session);
    }

    public ExchangeFrame handle(
            ServerPlayer player, UUID network, State state, ExchangeRequest request, ServerSettings settings) {
        var metadata = networks.find(network).orElseThrow();
        boolean owner = metadata.ownerId().equals(player.getUUID());
        long tick = player.server.overworld().getGameTime();
        if (request.kind() == ExchangeRequest.MORE) {
            if (state.transfer == null || state.upload || request.offset() != state.offset)
                throw new IllegalStateException("Stale exchange transfer");
            return fragment(player, state, request, owner, tick);
        }
        state.committed = false;
        if (request.kind() == ExchangeRequest.UPLOAD) {
            requireOwner(owner);
            close(player, request.session());
            pool.beginUpload(player.getUUID(), request.session(), state.generation, request.offset(), tick);
            state.transfer = state.generation;
            state.upload = true;
            state.length = request.offset();
            state.offset = 0;
            return empty(request, ExchangeFrame.ACK, owner);
        }
        if (request.kind() == ExchangeRequest.CHUNK) {
            requireOwner(owner);
            if (!state.upload) throw new IllegalStateException("No exchange upload");
            byte[] bytes = request.body();
            pool.upload(player.getUUID(), request.session(), state.transfer, request.offset(), bytes, tick);
            state.offset += bytes.length;
            return empty(request, ExchangeFrame.ACK, owner);
        }
        ExchangeIntent intent = null;
        ExchangeTerminalController.Result result = null;
        if (request.kind() == ExchangeRequest.COMMIT) {
            requireOwner(owner);
            if (!state.upload) throw new IllegalStateException("No exchange upload");
            ExchangeIntent[] parsed = {null};
            ExchangeTerminalController.Result[] committed = {null};
            pool.finishUpload(
                    player.getUUID(),
                    request.session(),
                    state.transfer,
                    tick,
                    object -> {
                        byte[] bytes = new byte[object.length()];
                        for (int i = 0; i < bytes.length; i++) bytes[i] = object.byteAt(i);
                        var b = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
                        try {
                            parsed[0] = ExchangeIntentCodec.decode(b);
                        } finally {
                            b.release();
                        }
                        if (!(parsed[0] instanceof ExchangeIntent.CreateChannel)
                                && !(parsed[0] instanceof ExchangeIntent.ReviseChannel))
                            throw new IllegalArgumentException("Unexpected uploaded intent");
                    },
                    () -> networks.find(network)
                            .map(n -> n.ownerId().equals(player.getUUID()))
                            .orElse(false),
                    object -> {
                        committed[0] = controller.execute(player, network, parsed[0]);
                        state.committed = true;
                        parsed[0] = null;
                    });
            result = committed[0];
            state.transfer = null;
            state.upload = false;
        } else if (request.kind() == ExchangeRequest.ACTION) {
            var b = new FriendlyByteBuf(Unpooled.wrappedBuffer(request.body()));
            try {
                intent = ExchangeIntentCodec.decode(b);
            } finally {
                b.release();
            }
            if (intent instanceof ExchangeIntent.Propose || intent instanceof ExchangeIntent.Revise)
                throw new IllegalArgumentException("Unpaired channel mutations are obsolete");
            result = controller.execute(player, network, intent);
            state.committed = !(intent instanceof ExchangeIntent.Detail)
                    && !(intent instanceof ExchangeIntent.ListRules)
                    && !(intent instanceof ExchangeIntent.ListTunnels)
                    && !(intent instanceof ExchangeIntent.ListChannels);
        }
        close(player, request.session());
        state.transfer = null;
        state.upload = false;
        if (request.kind() == ExchangeRequest.OPEN) {
            var types = repository.resourceAdapters().types();
            return send(
                    player,
                    state,
                    request,
                    owner,
                    ExchangeFrame.META,
                    Math.min(ManagementTransferPool.MAXIMUM_OBJECT_BYTES, 16 + types.size() * 256),
                    b -> {
                        b.writeVarInt(types.size());
                        for (var type : types) {
                            var d = repository.resourceAdapters().find(type).orElseThrow();
                            b.writeResourceLocation(type).writeUtf(d.unit(), 32).writeLong(d.defaultBatchSize());
                        }
                        b.writeInt(settings.exchange().invitesPerNetwork());
                    });
        }
        if (request.kind() == ExchangeRequest.PRESETS) {
            requireOwner(owner);
            var library = repository.findOwner(player.getUUID()).orElse(null);
            var presets = library == null
                    ? List.<io.github.loongin.omniresonance.filter.ResourceFilterPreset>of()
                    : library.presets().stream()
                            .sorted(Comparator.comparing(p -> p.name().value()))
                            .toList();
            long revision = library == null ? 0 : library.presetLibraryRevision();
            return send(
                    player,
                    state,
                    request,
                    owner,
                    ExchangeFrame.PRESETS,
                    ManagementTransferPool.MAXIMUM_OBJECT_BYTES,
                    b -> {
                        b.writeLong(revision).writeVarInt(presets.size());
                        for (var preset : presets)
                            new FilterPresetSummary(
                                            preset.id(),
                                            preset.name().value(),
                                            preset.revision(),
                                            preset.rules().size(),
                                            true)
                                    .write(b);
                    });
        }
        if (request.kind() == ExchangeRequest.RESOLVE) {
            requireOwner(owner);
            var b = new FriendlyByteBuf(Unpooled.wrappedBuffer(request.body()));
            String code;
            try {
                code = b.readUtf(22);
                if (b.isReadable()) throw new IllegalArgumentException("Trailing code");
            } finally {
                b.release();
            }
            var data = repository.exchangeRepository().find().orElseThrow();
            var invite = data.findUsableInvitation(code, tick)
                    .orElseThrow(() -> new IllegalStateException("Invitation unavailable"));
            var target = networks.find(invite.targetNetwork()).orElseThrow();
            if (!target.ownerId().equals(invite.targetOwner()) || target.id().equals(network))
                throw new IllegalArgumentException("Invalid receiving network");
            String name = player.server.getProfileCache() == null
                    ? target.ownerId().toString()
                    : player.server
                            .getProfileCache()
                            .get(target.ownerId())
                            .map(com.mojang.authlib.GameProfile::getName)
                            .orElse(target.ownerId().toString());
            return send(
                    player,
                    state,
                    request,
                    owner,
                    ExchangeFrame.RECEIVER,
                    1024,
                    out -> out.writeUUID(target.id())
                            .writeUtf(target.name().value(), 256)
                            .writeUtf(name, 256));
        }
        if (request.kind() == ExchangeRequest.CODES) {
            requireOwner(owner);
            var data = repository.exchangeRepository().find().orElse(null);
            var codes = new ArrayList<ExchangeInvitation>();
            if (data != null)
                for (var code : data.invitations())
                    if (code.targetNetwork().equals(network)
                            && code.targetOwner().equals(player.getUUID())
                            && code.usable(tick)) codes.add(code);
            codes.sort(Comparator.comparingLong(ExchangeInvitation::issuedTick));
            return send(
                    player,
                    state,
                    request,
                    owner,
                    ExchangeFrame.CODES,
                    ManagementTransferPool.MAXIMUM_OBJECT_BYTES,
                    b -> {
                        b.writeVarInt(codes.size());
                        for (var code : codes)
                            writeCode(
                                    b,
                                    new ExchangeTerminalController.Code(
                                            code.id(),
                                            code.revision(),
                                            code.expiresTick(),
                                            ExchangeInvitationCode.encode(code.id())));
                    });
        }
        if (request.kind() == ExchangeRequest.FILTER) {
            var b = new FriendlyByteBuf(Unpooled.wrappedBuffer(request.body()));
            UUID id;
            try {
                id = b.readUUID();
                if (b.isReadable()) throw new IllegalArgumentException("Trailing rule");
            } finally {
                b.release();
            }
            var rule = (ExchangeTerminalController.Rule)
                    controller.execute(player, network, new ExchangeIntent.Detail(id));
            var rows = new ArrayList<ResourceFilterRule.Match>();
            if (rule.agreement().terms().filter() != null)
                for (var preset : rule.agreement().terms().filter().presets().values())
                    for (var row : preset.rules()) if (row instanceof ResourceFilterRule.Match match) rows.add(match);
            return send(
                    player,
                    state,
                    request,
                    owner,
                    ExchangeFrame.FILTER,
                    ManagementTransferPool.MAXIMUM_OBJECT_BYTES,
                    out -> {
                        out.writeVarInt(rows.size());
                        for (var row : rows) {
                            out.writeResourceLocation(row.resourceTypeId());
                            String selector;
                            int kind;
                            if (row.selector() instanceof ResourceFilterRule.Exact exact) {
                                kind = 1;
                                selector = exact.resourceId().toString();
                            } else if (row.selector() instanceof ResourceFilterRule.TagSelector tag) {
                                kind = 2;
                                selector = tag.tagId().toString();
                            } else if (row.selector() instanceof ResourceFilterRule.Glob glob) {
                                kind = 3;
                                selector = glob.glob().pattern();
                            } else {
                                kind = 0;
                                selector = "";
                            }
                            out.writeByte(kind)
                                    .writeUtf(selector, 65535)
                                    .writeByte(row.components().mode().ordinal());
                        }
                    });
        }
        if (result instanceof ExchangeTerminalController.Tunnels list) {
            return send(
                    player,
                    state,
                    request,
                    owner,
                    ExchangeFrame.TUNNELS,
                    ManagementTransferPool.MAXIMUM_OBJECT_BYTES,
                    b -> {
                        b.writeBoolean(list.history())
                                .writeVarInt(list.entries().size());
                        for (var t : list.entries()) pairView(t).write(b);
                    });
        }
        if (result instanceof ExchangeTerminalController.Channels list) {
            return send(
                    player,
                    state,
                    request,
                    owner,
                    ExchangeFrame.CHANNELS,
                    ManagementTransferPool.MAXIMUM_OBJECT_BYTES,
                    b -> {
                        pairView(list.tunnel()).write(b);
                        b.writeBoolean(list.history())
                                .writeVarInt(list.entries().size());
                        for (var a : list.entries()) {
                            project(a).write(b);
                            b.writeByte(controller.filterStatus(a).ordinal());
                            writePlacement(b, controller.channelPlacement(a.id()));
                        }
                    });
        }
        if (result instanceof ExchangeTerminalController.Rules list) {
            return send(
                    player,
                    state,
                    request,
                    owner,
                    ExchangeFrame.RULES,
                    ManagementTransferPool.MAXIMUM_OBJECT_BYTES,
                    b -> {
                        b.writeBoolean(list.history())
                                .writeVarInt(list.entries().size());
                        for (var rule : list.entries()) {
                            project(rule).write(b);
                            b.writeByte(controller.filterStatus(rule).ordinal());
                        }
                    });
        }
        if (result instanceof ExchangeTerminalController.Rule rule) {
            var agreement = rule.agreement();
            var terms = agreement.terms();
            var draft = new ExchangeTermsDraft(
                    terms.scope(),
                    terms.filterMode(),
                    new ExchangeTermsDraft.KeepApproved(),
                    terms.defaultRate(),
                    terms.rates(),
                    terms.intervalTicks());
            return send(
                    player,
                    state,
                    request,
                    owner,
                    ExchangeFrame.DETAIL,
                    ManagementTransferPool.MAXIMUM_OBJECT_BYTES,
                    b -> {
                        project(agreement).write(b);
                        b.writeByte(controller.filterStatus(agreement).ordinal());
                        writePlacement(b, controller.channelPlacement(agreement.id()));
                        ExchangeIntentCodec.encode(
                                b,
                                new ExchangeIntent.Revise(
                                        agreement.id(), agreement.consent().revision(), draft));
                    });
        }
        if (result instanceof ExchangeTerminalController.Code code)
            return send(player, state, request, owner, ExchangeFrame.CODE, 128, b -> writeCode(b, code));
        if (result instanceof ExchangeTerminalController.Done) return empty(request, ExchangeFrame.DONE, owner);
        throw new IllegalArgumentException("Invalid exchange operation");
    }

    private io.github.loongin.omniresonance.networking.ExchangeTunnelView pairView(ExchangeTunnel t) {
        return io.github.loongin.omniresonance.networking.ExchangeTunnelView.from(
                t,
                networks.find(t.consent().sourceNetwork()).orElse(null),
                networks.find(t.consent().targetNetwork()).orElse(null));
    }

    private static void writePlacement(FriendlyByteBuf b, ExchangeChannel c) {
        b.writeBoolean(c != null);
        if (c != null) {
            new io.github.loongin.omniresonance.networking.ExchangeChannelView(
                            c.tunnel(), c.name().value())
                    .write(b);
        }
    }

    private ExchangeRuleView project(ExchangeAgreement a) {
        return ExchangeRuleView.from(
                a,
                networks.find(a.consent().sourceNetwork()).orElse(null),
                networks.find(a.consent().targetNetwork()).orElse(null));
    }

    private static void writeCode(FriendlyByteBuf b, ExchangeTerminalController.Code c) {
        b.writeUUID(c.id()).writeLong(c.revision()).writeLong(c.expiresTick()).writeUtf(c.value(), 22);
    }

    private ExchangeFrame send(
            ServerPlayer p,
            State s,
            ExchangeRequest r,
            boolean owner,
            int kind,
            int maximum,
            Consumer<FriendlyByteBuf> writer) {
        s.transfer = UUID.randomUUID();
        s.kind = kind;
        s.offset = 0;
        s.length = pool.beginBoundedDownload(
                p.getUUID(),
                r.session(),
                s.transfer,
                maximum,
                p.server.overworld().getGameTime(),
                () -> {
                    var b = new FriendlyByteBuf(Unpooled.buffer(Math.min(256, maximum), maximum));
                    try {
                        writer.accept(b);
                        byte[] bytes = new byte[b.readableBytes()];
                        b.readBytes(bytes);
                        return bytes;
                    } finally {
                        b.release();
                    }
                });
        return fragment(p, s, r, owner, p.server.overworld().getGameTime());
    }

    private ExchangeFrame fragment(ServerPlayer p, State s, ExchangeRequest r, boolean owner, long tick) {
        int offset = s.offset;
        byte[] bytes = pool.nextDownload(p.getUUID(), r.session(), s.transfer, tick);
        s.offset += bytes.length;
        var f = new ExchangeFrame(
                r.view(), r.session(), r.generation(), r.sequence(), s.kind, owner, s.length, offset, bytes);
        if (s.offset == s.length) s.transfer = null;
        return f;
    }

    public static ExchangeFrame error(ExchangeRequest r, String reason) {
        byte[] bytes = reason.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new ExchangeFrame(
                r.view(),
                r.session(),
                r.generation(),
                r.sequence(),
                ExchangeFrame.ERROR,
                false,
                bytes.length,
                0,
                bytes);
    }

    public static ExchangeFrame empty(ExchangeRequest r, int kind, boolean owner) {
        return new ExchangeFrame(r.view(), r.session(), r.generation(), r.sequence(), kind, owner, 0, 0, new byte[0]);
    }

    private static void requireOwner(boolean owner) {
        if (!owner) throw new SecurityException("Exchange owner required");
    }
}
