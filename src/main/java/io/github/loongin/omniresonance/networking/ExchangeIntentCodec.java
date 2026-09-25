// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.github.loongin.omniresonance.exchange.ExchangeTermsDraft;
import io.github.loongin.omniresonance.filter.FilterMode;
import io.github.loongin.omniresonance.persistence.ResourcePolicyNbt;
import io.github.loongin.omniresonance.transfer.ResourceScope;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * Pure management-object codec, not a registered packet or authority boundary. One body is at most 16 MiB;
 * transport must additionally enforce its terminal session envelope and single-packet/chunk limits. Callers own
 * buffers and must discard partial encodes after failure. Decode rejects wrong tags, counts, aliases, duplicate
 * entries and trailing bytes before returning an immutable intent; no world state is read or modified.
 */
public final class ExchangeIntentCodec {
    public static final int MAXIMUM_BYTES = ResourcePolicyNbt.MAX_BYTES;

    private ExchangeIntentCodec() {}

    public static void encode(FriendlyByteBuf buffer, ExchangeIntent value) {
        Objects.requireNonNull(value);
        int start = buffer.writerIndex();
        if (value instanceof ExchangeIntent.ListRules list) buffer.writeByte(0).writeBoolean(list.history());
        else if (value instanceof ExchangeIntent.Detail detail)
            buffer.writeByte(1).writeUUID(detail.agreement());
        else if (value instanceof ExchangeIntent.IssueCode) buffer.writeByte(2);
        else if (value instanceof ExchangeIntent.RevokeCode revoke)
            buffer.writeByte(3).writeUUID(revoke.invitation()).writeLong(revoke.revision());
        else if (value instanceof ExchangeIntent.Propose proposal) {
            buffer.writeByte(4).writeUtf(proposal.code(), 22);
            writeDraft(buffer, proposal.draft(), start);
        } else if (value instanceof ExchangeIntent.Revise revision) {
            buffer.writeByte(5).writeUUID(revision.agreement()).writeLong(revision.revision());
            writeDraft(buffer, revision.draft(), start);
        } else if (value instanceof ExchangeIntent.Change change) {
            buffer.writeByte(
                    switch (change.action()) {
                        case APPROVE -> 6;
                        case PAUSE -> 7;
                        case RESUME -> 8;
                        case TERMINATE -> 9;
                    });
            buffer.writeUUID(change.agreement()).writeLong(change.revision());
        } else if (value instanceof ExchangeIntent.ListTunnels v)
            buffer.writeByte(10).writeBoolean(v.history());
        else if (value instanceof ExchangeIntent.Pair v) buffer.writeByte(11).writeUtf(v.code(), 22);
        else if (value instanceof ExchangeIntent.ApprovePair v)
            buffer.writeByte(12).writeUUID(v.tunnel()).writeLong(v.revision());
        else if (value instanceof ExchangeIntent.ClosePair v)
            buffer.writeByte(13).writeUUID(v.tunnel()).writeLong(v.revision());
        else if (value instanceof ExchangeIntent.ListChannels v)
            buffer.writeByte(14).writeUUID(v.tunnel()).writeBoolean(v.history());
        else if (value instanceof ExchangeIntent.CreateChannel v) {
            buffer.writeByte(15).writeUUID(v.tunnel()).writeLong(v.revision());
            writeChannelDraft(buffer, v.draft(), start);
        } else if (value instanceof ExchangeIntent.ReviseChannel v) {
            buffer.writeByte(16).writeUUID(v.channel()).writeLong(v.revision());
            writeChannelDraft(buffer, v.draft(), start);
        } else throw new IllegalArgumentException("Unknown exchange intent");
        bound(buffer.writerIndex() - start);
    }

    public static ExchangeIntent decode(FriendlyByteBuf buffer) {
        bound(buffer.readableBytes());
        try {
            ExchangeIntent value =
                    switch (buffer.readUnsignedByte()) {
                        case 0 -> new ExchangeIntent.ListRules(booleanValue(buffer));
                        case 1 -> new ExchangeIntent.Detail(buffer.readUUID());
                        case 2 -> new ExchangeIntent.IssueCode();
                        case 3 -> new ExchangeIntent.RevokeCode(buffer.readUUID(), buffer.readLong());
                        case 4 -> new ExchangeIntent.Propose(buffer.readUtf(22), readDraft(buffer));
                        case 5 -> new ExchangeIntent.Revise(buffer.readUUID(), buffer.readLong(), readDraft(buffer));
                        case 6 ->
                            new ExchangeIntent.Change(
                                    buffer.readUUID(), buffer.readLong(), ExchangeIntent.Action.APPROVE);
                        case 7 ->
                            new ExchangeIntent.Change(
                                    buffer.readUUID(), buffer.readLong(), ExchangeIntent.Action.PAUSE);
                        case 8 ->
                            new ExchangeIntent.Change(
                                    buffer.readUUID(), buffer.readLong(), ExchangeIntent.Action.RESUME);
                        case 9 ->
                            new ExchangeIntent.Change(
                                    buffer.readUUID(), buffer.readLong(), ExchangeIntent.Action.TERMINATE);
                        case 10 -> new ExchangeIntent.ListTunnels(booleanValue(buffer));
                        case 11 -> new ExchangeIntent.Pair(buffer.readUtf(22));
                        case 12 -> new ExchangeIntent.ApprovePair(buffer.readUUID(), buffer.readLong());
                        case 13 -> new ExchangeIntent.ClosePair(buffer.readUUID(), buffer.readLong());
                        case 14 -> new ExchangeIntent.ListChannels(buffer.readUUID(), booleanValue(buffer));
                        case 15 ->
                            new ExchangeIntent.CreateChannel(
                                    buffer.readUUID(), buffer.readLong(), readChannelDraft(buffer));
                        case 16 ->
                            new ExchangeIntent.ReviseChannel(
                                    buffer.readUUID(), buffer.readLong(), readChannelDraft(buffer));
                        default -> throw new IllegalArgumentException("Unknown exchange operation");
                    };
            if (buffer.isReadable()) throw new IllegalArgumentException("Trailing exchange intent bytes");
            return value;
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Malformed exchange intent", failure);
        }
    }

    private static void writeChannelDraft(
            FriendlyByteBuf buffer, io.github.loongin.omniresonance.exchange.ExchangeChannelDraft draft, int start) {
        NetworkSummary.writeName(buffer, draft.name().value());
        buffer.writeBoolean(draft.sending());
        writeDraft(buffer, draft.terms(), start);
    }

    private static io.github.loongin.omniresonance.exchange.ExchangeChannelDraft readChannelDraft(
            FriendlyByteBuf buffer) {
        var name = new io.github.loongin.omniresonance.network.ManagedName(NetworkSummary.readName(buffer));
        return new io.github.loongin.omniresonance.exchange.ExchangeChannelDraft(
                name, booleanValue(buffer), readDraft(buffer));
    }

    private static void writeDraft(FriendlyByteBuf buffer, ExchangeTermsDraft draft, int start) {
        if (draft.scope().kind() == ResourceScope.Kind.ALL) buffer.writeByte(0);
        else {
            buffer.writeByte(1).writeVarInt(draft.scope().resourceTypeIds().size());
            for (ResourceLocation type :
                    draft.scope().resourceTypeIds().stream().sorted().toList()) {
                buffer.writeUtf(type.toString(), 128);
                bound(buffer.writerIndex() - start);
            }
        }
        buffer.writeByte(draft.filterMode() == FilterMode.WHITELIST ? 0 : 1);
        if (draft.filter() instanceof ExchangeTermsDraft.None) buffer.writeByte(0);
        else if (draft.filter() instanceof ExchangeTermsDraft.KeepApproved) buffer.writeByte(1);
        else {
            var preset = (ExchangeTermsDraft.OwnerPreset) draft.filter();
            buffer.writeByte(2).writeUUID(preset.presetId()).writeLong(preset.libraryRevision());
        }
        buffer.writeLong(draft.defaultRate())
                .writeInt(draft.intervalTicks())
                .writeVarInt(draft.rates().size());
        for (ResourceLocation type : draft.rates().keySet().stream().sorted().toList()) {
            buffer.writeUtf(type.toString(), 128).writeLong(draft.rates().get(type));
            bound(buffer.writerIndex() - start);
        }
    }

    private static ExchangeTermsDraft readDraft(FriendlyByteBuf buffer) {
        ResourceScope scope;
        int kind = buffer.readUnsignedByte();
        if (kind == 0) scope = ResourceScope.all();
        else if (kind == 1) {
            int count = count(buffer, 2);
            var types = new HashSet<ResourceLocation>();
            for (int i = 0; i < count; i++)
                if (!types.add(type(buffer))) throw new IllegalArgumentException("Duplicate exchange scope type");
            scope = ResourceScope.customSet(types);
        } else throw new IllegalArgumentException("Unknown exchange scope");
        FilterMode mode =
                switch (buffer.readUnsignedByte()) {
                    case 0 -> FilterMode.WHITELIST;
                    case 1 -> FilterMode.BLACKLIST;
                    default -> throw new IllegalArgumentException("Unknown exchange filter mode");
                };
        ExchangeTermsDraft.FilterChoice filter =
                switch (buffer.readUnsignedByte()) {
                    case 0 -> new ExchangeTermsDraft.None();
                    case 1 -> new ExchangeTermsDraft.KeepApproved();
                    case 2 -> new ExchangeTermsDraft.OwnerPreset(buffer.readUUID(), buffer.readLong());
                    default -> throw new IllegalArgumentException("Unknown exchange filter selection");
                };
        long rate = buffer.readLong();
        int interval = buffer.readInt(), count = count(buffer, 10);
        var rates = new HashMap<ResourceLocation, Long>();
        for (int i = 0; i < count; i++)
            if (rates.putIfAbsent(type(buffer), buffer.readLong()) != null)
                throw new IllegalArgumentException("Duplicate exchange rate type");
        return new ExchangeTermsDraft(scope, mode, filter, rate, rates, interval);
    }

    private static ResourceLocation type(FriendlyByteBuf buffer) {
        String text = buffer.readUtf(128);
        ResourceLocation type = ResourceLocation.parse(text);
        if (!type.toString().equals(text)) throw new IllegalArgumentException("Noncanonical exchange type ID");
        return type;
    }

    private static int count(FriendlyByteBuf buffer, int minimumBytes) {
        int count = buffer.readVarInt();
        if (count < 0
                || count > ResourceScope.MAXIMUM_RESOURCE_TYPE_IDS
                || count > buffer.readableBytes() / minimumBytes)
            throw new IllegalArgumentException("Invalid exchange collection count");
        return count;
    }

    private static boolean booleanValue(FriendlyByteBuf buffer) {
        int value = buffer.readUnsignedByte();
        if (value > 1) throw new IllegalArgumentException("Invalid exchange boolean");
        return value == 1;
    }

    private static void bound(int size) {
        if (size < 1 || size > MAXIMUM_BYTES) throw new IllegalArgumentException("Invalid exchange body length");
    }
}
