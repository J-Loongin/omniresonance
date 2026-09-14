// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.github.loongin.omniresonance.transfer.ResourcePolicyEdit;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.resources.ResourceLocation;

/** Count-only policy admission including the registered payload ID and every menu envelope field. */
public final class NodePolicyFrames {
    public static final int MAXIMUM_BYTES = 262144;

    private NodePolicyFrames() {}

    private static int idBytes(ResourceLocation id) {
        int bytes = id.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        return VarInt.getByteSize(bytes) + bytes;
    }

    public static int saveSize(int containerId, ResourcePolicyEdit policy) {
        int length = ResourcePolicyEditCodec.encodedSize(policy);
        return idBytes(NodeMenuRequest.TYPE.id())
                + 1
                + VarInt.getByteSize(containerId)
                + 16
                + 8
                + VarInt.getByteSize(length)
                + length
                + 2
                + 1;
    }

    public static int responseSize(NodeMenuResponse.State response) {
        if (!(response.state() instanceof NodeMenuState.DirectBindingEdit edit) || edit.policy() == null)
            throw new IllegalArgumentException("Expected complete policy edit");
        FriendlyByteBuf metadata = new FriendlyByteBuf(Unpooled.buffer());
        try {
            NodeMenuResponse.STREAM_CODEC.encode(
                    metadata,
                    new NodeMenuResponse.State(
                            response.containerId(), response.sessionId(), response.sequence(), edit.withPolicy(null)));
            int length = ResourcePolicyEditCodec.encodedSize(edit.policy());
            return idBytes(NodeMenuResponse.TYPE.id()) + metadata.readableBytes() + VarInt.getByteSize(length) + length;
        } finally {
            metadata.release();
        }
    }
}
