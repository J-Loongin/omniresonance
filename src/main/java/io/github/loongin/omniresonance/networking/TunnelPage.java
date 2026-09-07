// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;

/** Immutable bounded tunnel page owned by one terminal response. */
public record TunnelPage(List<TunnelSummary> entries, int totalCount, boolean hasPrevious, boolean hasNext) {
    public TunnelPage {
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        if (entries.size() > 128 || totalCount < entries.size()) {
            throw new IllegalArgumentException("Invalid tunnel page");
        }
    }

    static TunnelPage read(FriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        if (size < 0 || size > 128) {
            throw new DecoderException("Invalid tunnel page size");
        }
        List<TunnelSummary> entries = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            entries.add(TunnelSummary.read(buffer));
        }
        return new TunnelPage(entries, buffer.readVarInt(), buffer.readBoolean(), buffer.readBoolean());
    }

    void write(FriendlyByteBuf buffer) {
        buffer.writeVarInt(entries.size());
        for (TunnelSummary entry : entries) {
            entry.write(buffer);
        }
        buffer.writeVarInt(totalCount);
        buffer.writeBoolean(hasPrevious);
        buffer.writeBoolean(hasNext);
    }
}
