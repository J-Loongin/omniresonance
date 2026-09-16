// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import java.util.UUID;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** Server-authorized single highlight; zero duration clears it. Never grants client authority. */
public record NodeHighlightFrame(
        @Nullable UUID node, @Nullable GlobalPos position, String name, int durationTicks)
        implements CustomPacketPayload {
    public static final Type<NodeHighlightFrame> TYPE =
            new Type<>(ResourceLocation.parse("omniresonance:node_highlight"));
    public static final StreamCodec<FriendlyByteBuf, NodeHighlightFrame> STREAM_CODEC = new StreamCodec<>() {
        public void encode(FriendlyByteBuf b, NodeHighlightFrame f) {
            b.writeInt(f.durationTicks);
            if (f.durationTicks > 0) {
                b.writeUUID(f.node);
                b.writeGlobalPos(f.position);
                NetworkSummary.writeName(b, f.name);
            }
        }

        public NodeHighlightFrame decode(FriendlyByteBuf b) {
            if (b.readableBytes() > 1024) throw new IllegalArgumentException("Highlight too large");
            int ticks = b.readInt();
            if (ticks < 0 || ticks > 72000) throw new IllegalArgumentException("Invalid highlight duration");
            var f = ticks == 0
                    ? new NodeHighlightFrame(null, null, "", 0)
                    : new NodeHighlightFrame(b.readUUID(), b.readGlobalPos(), NetworkSummary.readName(b), ticks);
            if (b.isReadable()) throw new IllegalArgumentException("Trailing highlight data");
            return f;
        }
    };

    public NodeHighlightFrame {
        if (durationTicks < 0
                || durationTicks > 72000
                || name == null
                || durationTicks == 0 && (node != null || position != null || !name.isEmpty())
                || durationTicks > 0
                        && (node == null
                                || position == null
                                || position.dimension().location().toString().length() > 128))
            throw new IllegalArgumentException("Invalid highlight");
        if (durationTicks > 0
                && !new io.github.loongin.omniresonance.network.ManagedName(name)
                        .value()
                        .equals(name)) throw new IllegalArgumentException("Invalid highlight name");
    }

    public Type<NodeHighlightFrame> type() {
        return TYPE;
    }
}
