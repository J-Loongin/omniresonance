// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;

/** Immutable bounded page of node-facing channel summaries. */
public record NodeChannelPage(List<NodeChannelSummary> entries, int totalCount, boolean hasPrevious, boolean hasNext) {
    public NodeChannelPage {
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        if (entries.size() > NodeMenuCodecSupport.MAXIMUM_PAGE_ENTRIES || totalCount < entries.size()) {
            throw new IllegalArgumentException("Invalid node channel page");
        }
    }

    static NodeChannelPage read(FriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        if (size < 0 || size > NodeMenuCodecSupport.MAXIMUM_PAGE_ENTRIES) {
            throw new DecoderException("Invalid node channel page size");
        }
        List<NodeChannelSummary> entries = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            entries.add(NodeChannelSummary.read(buffer));
        }
        return new NodeChannelPage(entries, buffer.readVarInt(), buffer.readBoolean(), buffer.readBoolean());
    }

    void write(FriendlyByteBuf buffer) {
        buffer.writeVarInt(entries.size());
        for (NodeChannelSummary entry : entries) {
            entry.write(buffer);
        }
        buffer.writeVarInt(totalCount);
        buffer.writeBoolean(hasPrevious);
        buffer.writeBoolean(hasNext);
    }
}
