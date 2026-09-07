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

/** One immutable bounded member page, including at most the owner and a subset of administrators. */
public record NetworkMemberPage(
        List<NetworkMemberSummary> entries, int totalCount, boolean hasPrevious, boolean hasNext) {
    public NetworkMemberPage {
        Objects.requireNonNull(entries, "entries");
        if (entries.size() > 128 || totalCount < entries.size() || totalCount < 1 || totalCount > 262145) {
            throw new IllegalArgumentException("Invalid member page");
        }
        Set<UUID> ids = new HashSet<>();
        int owners = 0;
        for (NetworkMemberSummary member : entries) {
            if (!ids.add(member.playerId())) throw new IllegalArgumentException("Duplicate member identity");
            if (member.role() == NetworkMemberSummary.Role.OWNER && ++owners > 1)
                throw new IllegalArgumentException("Multiple owners");
        }
        entries = List.copyOf(entries);
    }

    static NetworkMemberPage read(FriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        if (size < 0 || size > 128) throw new DecoderException("Invalid member page size");
        List<NetworkMemberSummary> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) entries.add(NetworkMemberSummary.read(buffer));
        return new NetworkMemberPage(entries, buffer.readVarInt(), buffer.readBoolean(), buffer.readBoolean());
    }

    void write(FriendlyByteBuf buffer) {
        buffer.writeVarInt(entries.size());
        for (NetworkMemberSummary member : entries) member.write(buffer);
        buffer.writeVarInt(totalCount);
        buffer.writeBoolean(hasPrevious);
        buffer.writeBoolean(hasNext);
    }
}
