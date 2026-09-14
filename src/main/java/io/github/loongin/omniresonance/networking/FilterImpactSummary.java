// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import net.minecraft.network.FriendlyByteBuf;

/** Authorized aggregate impact; no private network identity or name is transmitted. */
public record FilterImpactSummary(int networkCount, int nodeCount, int bindingCount, boolean complete) {
    public FilterImpactSummary {
        if (networkCount < 0
                || nodeCount < 0
                || bindingCount < 0
                || networkCount > nodeCount
                || nodeCount > bindingCount) throw new IllegalArgumentException("Invalid filter impact");
    }

    static FilterImpactSummary read(FriendlyByteBuf buffer) {
        return new FilterImpactSummary(buffer.readInt(), buffer.readInt(), buffer.readInt(), buffer.readBoolean());
    }

    void write(FriendlyByteBuf buffer) {
        buffer.writeInt(networkCount).writeInt(nodeCount).writeInt(bindingCount).writeBoolean(complete);
    }
}
