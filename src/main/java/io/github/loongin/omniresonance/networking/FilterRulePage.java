// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/** Rule pages are limited by both entries and encoded bytes; full legal IDs are retained without truncation. */
public record FilterRulePage(
        List<String> entries,
        int offset,
        int totalCount,
        int previousOffset,
        List<java.util.UUID> ruleIds,
        boolean fullDomain) {
    public static final int MAXIMUM_ENTRIES = 128;
    public static final int MAXIMUM_ENCODED_RULE_BYTES = 200000;

    public FilterRulePage(List<String> entries, int offset, int totalCount, int previousOffset) {
        this(entries, offset, totalCount, previousOffset, List.of(), false);
    }

    public FilterRulePage(
            List<String> entries, int offset, int totalCount, int previousOffset, List<java.util.UUID> ruleIds) {
        this(entries, offset, totalCount, previousOffset, ruleIds, true);
    }

    public FilterRulePage {
        if (fullDomain && ruleIds.size() != entries.size() || !fullDomain && !ruleIds.isEmpty())
            throw new IllegalArgumentException("Rule identity mismatch");
        ruleIds = List.copyOf(ruleIds);
        if (previousOffset < 0 || previousOffset > offset || (offset > 0 && previousOffset == offset))
            throw new IllegalArgumentException("Invalid previous rule page offset");
        if (entries.size() > MAXIMUM_ENTRIES
                || offset < 0
                || totalCount < offset
                || totalCount > 262144
                || entries.size() > totalCount - offset) throw new IllegalArgumentException("Invalid rule page");
        int bytes = 0;
        for (String entry : entries) {
            ResourceLocation id = ResourceLocation.tryParse(entry);
            if (entry.length() > 65535
                    || !fullDomain && (id == null || !id.toString().equals(entry)))
                throw new IllegalArgumentException("Invalid exact item ID");
            bytes = Math.addExact(bytes, entry.length() + 3 + (fullDomain ? 16 : 0));
        }
        if (bytes > MAXIMUM_ENCODED_RULE_BYTES) throw new IllegalArgumentException("Rule page too large");
        entries = List.copyOf(entries);
    }

    public boolean hasNext() {
        return offset + entries.size() < totalCount;
    }

    public static FilterRulePage read(FriendlyByteBuf buffer) {
        int offset = buffer.readInt();
        int total = buffer.readInt();
        int previous = buffer.readInt();
        boolean typed = buffer.readBoolean();
        int count = buffer.readVarInt();
        if (count < 0 || count > MAXIMUM_ENTRIES) throw new DecoderException("Invalid rule count");
        List<String> entries = new ArrayList<>(count);
        List<java.util.UUID> identities = new ArrayList<>();
        int bytes = 0;
        for (int i = 0; i < count; i++) {
            int before = buffer.readerIndex();
            if (typed) identities.add(buffer.readUUID());
            String entry = FilterMenuCodec.readText(buffer);
            bytes = Math.addExact(bytes, buffer.readerIndex() - before);
            if (bytes > MAXIMUM_ENCODED_RULE_BYTES) throw new DecoderException("Rule page too large");
            entries.add(entry);
        }
        return new FilterRulePage(entries, offset, total, previous, identities, typed);
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeInt(offset)
                .writeInt(totalCount)
                .writeInt(previousOffset)
                .writeBoolean(fullDomain)
                .writeVarInt(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            if (fullDomain) buffer.writeUUID(ruleIds.get(i));
            FilterMenuCodec.writeText(buffer, entries.get(i));
        }
    }
}
