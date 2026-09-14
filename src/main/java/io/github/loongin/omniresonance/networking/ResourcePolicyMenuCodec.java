// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.github.loongin.omniresonance.transfer.ResourcePolicyEdit;
import net.minecraft.network.FriendlyByteBuf;

/** Inline edit codec; the enclosing registered frame enforces its complete payload bound. */
final class ResourcePolicyMenuCodec {
    private ResourcePolicyMenuCodec() {}

    static ResourcePolicyEdit read(FriendlyByteBuf buffer) {
        int length = buffer.readVarInt();
        if (length < 1 || length > NodePolicyFrames.MAXIMUM_BYTES || length > buffer.readableBytes())
            throw new io.netty.handler.codec.DecoderException("Invalid inline policy length");
        byte[] bytes = new byte[length];
        buffer.readBytes(bytes);
        return ResourcePolicyEditCodec.decode(bytes);
    }

    static void write(FriendlyByteBuf buffer, ResourcePolicyEdit edit) {
        buffer.writeByteArray(ResourcePolicyEditCodec.encode(edit));
    }
}
