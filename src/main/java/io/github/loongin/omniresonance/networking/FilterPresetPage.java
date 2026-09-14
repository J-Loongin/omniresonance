// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;

/** At most 128 immutable summaries from one current library revision; no rules or authority objects are retained. */
public record FilterPresetPage(List<FilterPresetSummary> entries, int offset, int totalCount, long libraryRevision) {
    public static final int MAXIMUM_ENTRIES = 128;

    public FilterPresetPage {
        if (entries.size() > MAXIMUM_ENTRIES
                || offset < 0
                || totalCount < offset
                || totalCount > 262144
                || entries.size() > totalCount - offset
                || libraryRevision < 0) throw new IllegalArgumentException("Invalid preset page");
        entries = List.copyOf(entries);
    }

    public boolean hasNext() {
        return offset + entries.size() < totalCount;
    }

    public static FilterPresetPage read(FriendlyByteBuf buffer) {
        int offset = buffer.readInt();
        int total = buffer.readInt();
        long revision = buffer.readLong();
        int count = buffer.readVarInt();
        if (count < 0 || count > MAXIMUM_ENTRIES) throw new DecoderException("Invalid preset count");
        List<FilterPresetSummary> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) entries.add(FilterPresetSummary.read(buffer));
        return new FilterPresetPage(entries, offset, total, revision);
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeInt(offset).writeInt(totalCount).writeLong(libraryRevision).writeVarInt(entries.size());
        for (FilterPresetSummary entry : entries) entry.write(buffer);
    }
}
