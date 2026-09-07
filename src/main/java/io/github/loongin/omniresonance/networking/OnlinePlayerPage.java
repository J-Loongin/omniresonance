// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;

/** One immutable contiguous slice of a menu-owned online-candidate snapshot; all allocation sizes are bounded. */
public record OnlinePlayerPage(
        UUID snapshotId, List<OnlinePlayerSummary> entries, int offset, int totalCount, boolean hasNext) {
    public OnlinePlayerPage {
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(entries, "entries");
        if (entries.size() > 128
                || offset < 0
                || totalCount < 0
                || totalCount > 262144
                || offset > totalCount
                || entries.size() > totalCount - offset
                || hasNext != (offset + entries.size() < totalCount)
                || (entries.isEmpty() && totalCount != 0)) {
            throw new IllegalArgumentException("Invalid online player page");
        }
        Set<UUID> ids = new HashSet<>();
        for (OnlinePlayerSummary player : entries) {
            if (!ids.add(player.playerId())) throw new IllegalArgumentException("Duplicate candidate identity");
        }
        entries = List.copyOf(entries);
    }

    static OnlinePlayerPage read(FriendlyByteBuf buffer) {
        UUID snapshot = buffer.readUUID();
        int size = buffer.readVarInt();
        if (size < 0 || size > 128) throw new DecoderException("Invalid candidate page size");
        List<OnlinePlayerSummary> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) entries.add(OnlinePlayerSummary.read(buffer));
        return new OnlinePlayerPage(snapshot, entries, buffer.readVarInt(), buffer.readVarInt(), buffer.readBoolean());
    }

    void write(FriendlyByteBuf buffer) {
        buffer.writeUUID(snapshotId);
        buffer.writeVarInt(entries.size());
        for (OnlinePlayerSummary player : entries) player.write(buffer);
        buffer.writeVarInt(offset);
        buffer.writeVarInt(totalCount);
        buffer.writeBoolean(hasNext);
    }
}
