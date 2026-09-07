// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;

/** Immutable bounded channel page owned by one terminal response. */
public record ChannelPage(List<ChannelSummary> entries, int totalCount, boolean hasPrevious, boolean hasNext) {
    public ChannelPage {
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        if (entries.size() > 128 || totalCount < entries.size()) {
            throw new IllegalArgumentException("Invalid channel page");
        }
    }

    static ChannelPage read(FriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        if (size < 0 || size > 128) {
            throw new DecoderException("Invalid channel page size");
        }
        List<ChannelSummary> entries = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            entries.add(ChannelSummary.read(buffer));
        }
        return new ChannelPage(entries, buffer.readVarInt(), buffer.readBoolean(), buffer.readBoolean());
    }

    void write(FriendlyByteBuf buffer) {
        buffer.writeVarInt(entries.size());
        for (ChannelSummary entry : entries) {
            entry.write(buffer);
        }
        buffer.writeVarInt(totalCount);
        buffer.writeBoolean(hasPrevious);
        buffer.writeBoolean(hasNext);
    }
}
