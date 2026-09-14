// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.loongin.omniresonance.network.TransferDirection;
import io.github.loongin.omniresonance.network.WorkingFaces;
import io.github.loongin.omniresonance.transfer.ResourcePolicyEdit;
import io.github.loongin.omniresonance.transfer.ResourceTransferPolicy;
import io.github.loongin.omniresonance.transfer.StoredResourcePolicy;
import io.netty.buffer.Unpooled;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class NodePolicyFramesTest {
    @Test
    void saveAdmissionCountsExactRegisteredEnvelopeAndFaces() {
        var policy = ResourcePolicyEdit.fromStored(
                new StoredResourcePolicy(ResourceTransferPolicy.defaults(TransferDirection.INPUT), Map.of()));
        var request = new NodeMenuRequest.SaveResourcePolicy(
                200, new UUID(1, 2), 1, policy, WorkingFaces.explicit(48), false);
        FriendlyByteBuf frame = new FriendlyByteBuf(Unpooled.buffer());
        try {
            frame.writeResourceLocation(request.type().id());
            NodeMenuRequest.STREAM_CODEC.encode(frame, request);
            assertEquals(frame.readableBytes(), NodePolicyFrames.saveSize(200, policy));
        } finally {
            frame.release();
        }
    }
}
