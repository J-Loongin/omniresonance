// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;

/** Immutable bounded network-selection window owned by one node Menu response. */
public record NodeNetworkPage(List<NodeNetworkSummary> entries, int totalCount, boolean hasPrevious, boolean hasNext) {
    public NodeNetworkPage {
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        if (entries.size() > NodeMenuCodecSupport.MAXIMUM_PAGE_ENTRIES
                || totalCount < entries.size()
                || (totalCount == 0 && (hasPrevious || hasNext))) {
            throw new IllegalArgumentException("Invalid node network page");
        }
    }

    static NodeNetworkPage read(FriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        if (size < 0 || size > NodeMenuCodecSupport.MAXIMUM_PAGE_ENTRIES) {
            throw new DecoderException("Invalid node network page size");
        }
        List<NodeNetworkSummary> entries = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            entries.add(NodeNetworkSummary.read(buffer));
        }
        int totalCount = buffer.readVarInt();
        return new NodeNetworkPage(entries, totalCount, buffer.readBoolean(), buffer.readBoolean());
    }

    void write(FriendlyByteBuf buffer) {
        buffer.writeVarInt(entries.size());
        for (NodeNetworkSummary entry : entries) {
            entry.write(buffer);
        }
        buffer.writeVarInt(totalCount);
        buffer.writeBoolean(hasPrevious);
        buffer.writeBoolean(hasNext);
    }
}
