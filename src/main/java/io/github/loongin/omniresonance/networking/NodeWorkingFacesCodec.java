// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.github.loongin.omniresonance.network.WorkingFaces;
import net.minecraft.network.FriendlyByteBuf;

/** Pure two-byte encoding for bounded working-face values; no authority or world access. */
final class NodeWorkingFacesCodec {
    private NodeWorkingFacesCodec() {}

    static WorkingFaces read(FriendlyByteBuf buffer) {
        return new WorkingFaces(buffer.readUnsignedByte(), buffer.readBoolean());
    }

    static void write(FriendlyByteBuf buffer, WorkingFaces faces) {
        buffer.writeByte(faces.mask());
        buffer.writeBoolean(faces.attached());
    }
}
