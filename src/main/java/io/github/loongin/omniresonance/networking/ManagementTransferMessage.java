// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Registered shared management transport messages. Values are immutable and contain no authoritative player
 * identity. Decoding grants no permission: connection routers must check direction, current
 * session/context/purpose and role before calling the owner-thread pool. No simulation or world access.
 */
public sealed interface ManagementTransferMessage extends CustomPacketPayload {
    Type<ManagementTransferMessage> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("omniresonance", "management_transfer"));
    StreamCodec<FriendlyByteBuf, ManagementTransferMessage> STREAM_CODEC =
            StreamCodec.of(ManagementTransferCodec::write, ManagementTransferCodec::read);

    UUID session();

    UUID transfer();

    @Override
    default Type<ManagementTransferMessage> type() {
        return TYPE;
    }

    /** Explicit protocol direction; wire values are defined by the codec, never enum ordinals. */
    enum Direction {
        UPLOAD,
        DOWNLOAD
    }
    /** Current authorized management surface; its actual identity is carried separately. */
    enum Context {
        TERMINAL,
        NODE
    }
    /** Stable protocol purposes, requiring purpose-specific parsing and authorization by the router. */
    enum Purpose {
        RULE_SNAPSHOT,
        NODE_POLICY,
        BATCH_PASTE
    }

    /** Declares one entire object; construction validates bounds without allocating its content. */
    record Begin(
            UUID session,
            UUID transfer,
            Direction direction,
            Context context,
            UUID contextId,
            Purpose purpose,
            int totalLength)
            implements ManagementTransferMessage {
        public Begin {
            identity(session, transfer);
            Objects.requireNonNull(direction, "direction");
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(contextId, "contextId");
            Objects.requireNonNull(purpose, "purpose");
            if (totalLength <= 0 || totalLength > ManagementTransferPool.MAXIMUM_OBJECT_BYTES)
                throw new IllegalArgumentException("Invalid managed object length");
        }
    }

    /** Owns a bounded copy; callers own independent accessor copies and cannot mutate packet content. */
    record Chunk(UUID session, UUID transfer, int offset, byte[] data) implements ManagementTransferMessage {
        public Chunk {
            identity(session, transfer);
            Objects.requireNonNull(data, "data");
            if (offset < 0
                    || data.length == 0
                    || data.length > ManagementTransferPool.MAXIMUM_FRAGMENT_BYTES
                    || offset > ManagementTransferPool.MAXIMUM_OBJECT_BYTES - data.length)
                throw new IllegalArgumentException("Invalid managed fragment");
            data = data.clone();
        }

        @Override
        public byte[] data() {
            return data.clone();
        }
    }

    /** Upload completion declaration; the pool rejects truncated objects before validation. */
    record Finish(UUID session, UUID transfer) implements ManagementTransferMessage {
        public Finish {
            identity(session, transfer);
        }
    }

    /** Exact-identity cancellation, never a command to replace another active transfer. */
    record Abort(UUID session, UUID transfer) implements ManagementTransferMessage {
        public Abort {
            identity(session, transfer);
        }
    }

    private static void identity(UUID session, UUID transfer) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(transfer, "transfer");
    }
}
