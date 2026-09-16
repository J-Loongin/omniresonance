// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** Bounded list and independently paged existing configuration cards. No resource keys or full policy bodies. */
public record NodeDirectoryPage(
        UUID session,
        long generation,
        long sequence,
        boolean available,
        boolean rejected,
        boolean editing,
        List<Row> rows,
        boolean previous,
        boolean next,
        @Nullable Row selected,
        List<Card> cards,
        int detailOffset,
        int detailTotal,
        List<ResourceLocation> dimensions,
        List<ResourceLocation> resources,
        @Nullable NodeChunkLoadingInfo chunkLoading,
        @Nullable Catalog catalog)
        implements CustomPacketPayload {
    public record Catalog(long revision, int offset, int total) {
        public Catalog {
            if (revision < 1 || offset < 0 || total < offset || total > 262144)
                throw new IllegalArgumentException("Invalid node catalog window");
        }
    }

    public record Row(
            NodeMenuNodeSummary node, long number, NodeDirectoryRequest.Status status, int faces, int configurations) {
        public Row {
            if (node == null
                    || number < 1
                    || status == null
                    || status == NodeDirectoryRequest.Status.ALL
                    || faces < 0
                    || faces > 63
                    || configurations < 0) throw new IllegalArgumentException("Invalid node row");
        }
    }

    public record Card(
            @Nullable UUID channel,
            String name,
            String route,
            boolean enabled,
            boolean input,
            String scope,
            String preset,
            int intervalTicks,
            long quantity,
            String redstone,
            int faces,
            int overrides) {
        public Card {
            if (name == null
                    || name.length() > 128
                    || route == null
                    || route.length() > 256
                    || scope == null
                    || scope.length() > 256
                    || preset == null
                    || preset.length() > 128
                    || redstone == null
                    || redstone.length() > 64
                    || intervalTicks < 1
                    || input && quantity < 0
                    || faces < 0
                    || faces > 63
                    || overrides < 0) throw new IllegalArgumentException("Invalid node card");
        }
    }

    public static final Type<NodeDirectoryPage> TYPE =
            new Type<>(ResourceLocation.parse("omniresonance:node_directory_page"));

    private static void row(FriendlyByteBuf b, Row r) {
        r.node.write(b);
        b.writeLong(r.number).writeByte(r.status.ordinal()).writeByte(r.faces).writeInt(r.configurations);
    }

    private static Row row(FriendlyByteBuf b) {
        var node = NodeMenuNodeSummary.read(b);
        long number = b.readLong();
        int state = b.readUnsignedByte();
        if (state >= NodeDirectoryRequest.Status.values().length)
            throw new IllegalArgumentException("Invalid node state");
        return new Row(node, number, NodeDirectoryRequest.Status.values()[state], b.readUnsignedByte(), b.readInt());
    }

    private static void ids(FriendlyByteBuf b, List<ResourceLocation> ids) {
        b.writeInt(ids.size());
        for (var id : ids) b.writeUtf(id.toString(), 128);
    }

    private static List<ResourceLocation> ids(FriendlyByteBuf b) {
        int n = b.readInt();
        if (n < 0 || n > 256) throw new IllegalArgumentException("Invalid filter catalog");
        var values = new ArrayList<ResourceLocation>(n);
        for (int i = 0; i < n; i++) values.add(ResourceLocation.parse(b.readUtf(128)));
        return values;
    }

    public static final StreamCodec<FriendlyByteBuf, NodeDirectoryPage> STREAM_CODEC = new StreamCodec<>() {
        public void encode(FriendlyByteBuf b, NodeDirectoryPage p) {
            b.writeUUID(p.session)
                    .writeLong(p.generation)
                    .writeLong(p.sequence)
                    .writeBoolean(p.available)
                    .writeBoolean(p.rejected)
                    .writeBoolean(p.editing)
                    .writeInt(p.rows.size());
            for (var r : p.rows) row(b, r);
            b.writeBoolean(p.previous).writeBoolean(p.next).writeBoolean(p.selected != null);
            if (p.selected != null) row(b, p.selected);
            b.writeInt(p.cards.size());
            for (var c : p.cards) {
                NodeDirectoryRequest.writeUuid(b, c.channel);
                b.writeUtf(c.name, 128)
                        .writeUtf(c.route, 256)
                        .writeBoolean(c.enabled)
                        .writeBoolean(c.input)
                        .writeUtf(c.scope, 256)
                        .writeUtf(c.preset, 128)
                        .writeInt(c.intervalTicks)
                        .writeLong(c.quantity)
                        .writeUtf(c.redstone, 64)
                        .writeByte(c.faces)
                        .writeInt(c.overrides);
            }
            b.writeInt(p.detailOffset).writeInt(p.detailTotal);
            ids(b, p.dimensions);
            ids(b, p.resources);
            b.writeBoolean(p.chunkLoading != null);
            if (p.chunkLoading != null) p.chunkLoading.write(b);
            b.writeBoolean(p.catalog != null);
            if (p.catalog != null)
                b.writeLong(p.catalog.revision).writeInt(p.catalog.offset).writeInt(p.catalog.total);
        }

        public NodeDirectoryPage decode(FriendlyByteBuf b) {
            if (b.readableBytes() > 262144) throw new IllegalArgumentException("Node page too large");
            UUID session = b.readUUID();
            long generation = b.readLong(), sequence = b.readLong();
            boolean available = b.readBoolean(), rejected = b.readBoolean(), editing = b.readBoolean();
            int n = b.readInt();
            if (n < 0 || n > 64) throw new IllegalArgumentException("Invalid node page size");
            var rows = new ArrayList<Row>(n);
            for (int i = 0; i < n; i++) rows.add(row(b));
            boolean previous = b.readBoolean(), next = b.readBoolean();
            var selected = b.readBoolean() ? row(b) : null;
            int count = b.readInt();
            if (count < 0 || count > 16) throw new IllegalArgumentException("Invalid configuration window");
            var cards = new ArrayList<Card>(count);
            for (int i = 0; i < count; i++)
                cards.add(new Card(
                        NodeDirectoryRequest.readUuid(b),
                        b.readUtf(128),
                        b.readUtf(256),
                        b.readBoolean(),
                        b.readBoolean(),
                        b.readUtf(256),
                        b.readUtf(128),
                        b.readInt(),
                        b.readLong(),
                        b.readUtf(64),
                        b.readUnsignedByte(),
                        b.readInt()));
            var p = new NodeDirectoryPage(
                    session,
                    generation,
                    sequence,
                    available,
                    rejected,
                    editing,
                    rows,
                    previous,
                    next,
                    selected,
                    cards,
                    b.readInt(),
                    b.readInt(),
                    ids(b),
                    ids(b),
                    b.readBoolean() ? NodeChunkLoadingInfo.read(b) : null,
                    b.readBoolean() ? new Catalog(b.readLong(), b.readInt(), b.readInt()) : null);
            if (b.isReadable()) throw new IllegalArgumentException("Trailing node page");
            return p;
        }
    };

    public NodeDirectoryPage(
            UUID session,
            long generation,
            long sequence,
            boolean available,
            boolean rejected,
            boolean editing,
            List<Row> rows,
            boolean previous,
            boolean next,
            @Nullable Row selected,
            List<Card> cards,
            int detailOffset,
            int detailTotal,
            List<ResourceLocation> dimensions,
            List<ResourceLocation> resources) {
        this(
                session,
                generation,
                sequence,
                available,
                rejected,
                editing,
                rows,
                previous,
                next,
                selected,
                cards,
                detailOffset,
                detailTotal,
                dimensions,
                resources,
                null);
    }

    public NodeDirectoryPage(
            UUID session,
            long generation,
            long sequence,
            boolean available,
            boolean rejected,
            boolean editing,
            List<Row> rows,
            boolean previous,
            boolean next,
            @Nullable Row selected,
            List<Card> cards,
            int detailOffset,
            int detailTotal,
            List<ResourceLocation> dimensions,
            List<ResourceLocation> resources,
            @Nullable NodeChunkLoadingInfo chunkLoading) {
        this(
                session,
                generation,
                sequence,
                available,
                rejected,
                editing,
                rows,
                previous,
                next,
                selected,
                cards,
                detailOffset,
                detailTotal,
                dimensions,
                resources,
                chunkLoading,
                null);
    }

    public NodeDirectoryPage {
        if (chunkLoading != null && (!available || selected == null))
            throw new IllegalArgumentException("Loading info requires authorized selection");
        if (catalog != null
                && (!available
                        || selected != null
                        || !cards.isEmpty()
                        || rows.size() > catalog.total - catalog.offset
                        || rows.isEmpty() && catalog.offset < catalog.total))
            throw new IllegalArgumentException("Invalid node catalog contents");
        rows = List.copyOf(rows);
        cards = List.copyOf(cards);
        dimensions = List.copyOf(dimensions);
        resources = List.copyOf(resources);
        if (session == null
                || generation < 1
                || sequence < 1
                || rows.size() > 64
                || cards.size() > 16
                || dimensions.size() > 256
                || resources.size() > 256
                || detailOffset < 0
                || detailTotal < detailOffset
                || cards.size() > detailTotal - detailOffset) throw new IllegalArgumentException("Invalid node page");
    }

    public Type<NodeDirectoryPage> type() {
        return TYPE;
    }
}
