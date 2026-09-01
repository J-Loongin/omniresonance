// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.Nullable;

/**
 * Independently owned current page, never a full directory mirror. Thread-safe construction is pure,
 * copies at most 128 entries, and rejects invalid count or quota bounds without modifying caller inputs.
 */
public record NetworkTerminalPage(
        List<NetworkSummary> entries,
        @Nullable NetworkSummary preferred,
        int ownedCount,
        int totalCount,
        int networksPerOwner,
        boolean hasPrevious,
        boolean hasNext) {
    public NetworkTerminalPage {
        if (entries.size() > 128
                || ownedCount < 0
                || totalCount < 0
                || networksPerOwner < -1
                || networksPerOwner > 1024) {
            throw new IllegalArgumentException("Invalid terminal page bounds");
        }
        entries = List.copyOf(entries);
    }

    static NetworkTerminalPage read(FriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        if (count < 0 || count > 128) {
            throw new DecoderException("Invalid terminal page entry count");
        }
        List<NetworkSummary> entries = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            entries.add(NetworkSummary.read(buffer));
        }
        NetworkSummary preferred = buffer.readBoolean() ? NetworkSummary.read(buffer) : null;
        return new NetworkTerminalPage(
                entries,
                preferred,
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readBoolean(),
                buffer.readBoolean());
    }

    void write(FriendlyByteBuf buffer) {
        buffer.writeVarInt(entries.size());
        for (NetworkSummary entry : entries) {
            entry.write(buffer);
        }
        buffer.writeBoolean(preferred != null);
        if (preferred != null) {
            preferred.write(buffer);
        }
        buffer.writeVarInt(ownedCount);
        buffer.writeVarInt(totalCount);
        buffer.writeVarInt(networksPerOwner);
        buffer.writeBoolean(hasPrevious);
        buffer.writeBoolean(hasNext);
    }
}
