// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;

/** Immutable bounded page of node-facing tunnel summaries. */
public record NodeTunnelPage(List<NodeTunnelSummary> entries, int totalCount, boolean hasPrevious, boolean hasNext) {
    public NodeTunnelPage {
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        if (entries.size() > NodeMenuCodecSupport.MAXIMUM_PAGE_ENTRIES
                || totalCount < entries.size()
                || totalCount > 65535) {
            throw new IllegalArgumentException("Invalid node tunnel page");
        }
    }

    static NodeTunnelPage read(FriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        if (size < 0 || size > NodeMenuCodecSupport.MAXIMUM_PAGE_ENTRIES) {
            throw new DecoderException("Invalid node tunnel page size");
        }
        List<NodeTunnelSummary> entries = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            entries.add(NodeTunnelSummary.read(buffer));
        }
        return new NodeTunnelPage(entries, buffer.readVarInt(), buffer.readBoolean(), buffer.readBoolean());
    }

    void write(FriendlyByteBuf buffer) {
        buffer.writeVarInt(entries.size());
        for (NodeTunnelSummary entry : entries) {
            entry.write(buffer);
        }
        buffer.writeVarInt(totalCount);
        buffer.writeBoolean(hasPrevious);
        buffer.writeBoolean(hasNext);
    }
}
