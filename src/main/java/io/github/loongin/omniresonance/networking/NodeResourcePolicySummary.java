// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.github.loongin.omniresonance.network.TransferDirection;
import io.github.loongin.omniresonance.transfer.ResourceScope;
import io.github.loongin.omniresonance.transfer.StoredResourcePolicy;
import io.netty.handler.codec.DecoderException;
import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;

/** Detached immutable status-only resource summary. No policy rows, raw values, simulation or authority writes. */
public record NodeResourcePolicySummary(
        TransferDirection direction,
        ResourceScope.Kind scopeKind,
        int scopeTypeCount,
        int overrideCount,
        int intervalTicks) {
    public NodeResourcePolicySummary {
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(scopeKind, "scopeKind");
        if (scopeTypeCount < 0
                || scopeTypeCount > ResourceScope.MAXIMUM_RESOURCE_TYPE_IDS
                || (scopeKind == ResourceScope.Kind.ALL) != (scopeTypeCount == 0)
                || overrideCount < 0
                || overrideCount > ResourceScope.MAXIMUM_RESOURCE_TYPE_IDS
                || intervalTicks < 1) throw new IllegalArgumentException("Invalid resource policy summary");
    }

    /** Pure thread-safe projection owning only bounded scalar values; missing rows count without exposing raw data. */
    public static NodeResourcePolicySummary from(StoredResourcePolicy stored) {
        var policy = Objects.requireNonNull(stored, "stored").effectivePolicy();
        return new NodeResourcePolicySummary(
                policy.direction(),
                policy.scope().kind(),
                policy.scope().resourceTypeIds().size(),
                policy.resourcePolicyOverrides().size()
                        + stored.missingTypeOverrides().size(),
                policy.intervalTicks());
    }

    /** Reads exactly fourteen bytes on the buffer owner's thread; malformed values fail without authority mutation. */
    public static NodeResourcePolicySummary read(FriendlyByteBuf buffer) {
        try {
            TransferDirection direction = NodeMenuCodecSupport.readTransferDirection(buffer);
            ResourceScope.Kind scope =
                    switch (buffer.readUnsignedByte()) {
                        case 0 -> ResourceScope.Kind.ALL;
                        case 1 -> ResourceScope.Kind.CUSTOM_SET;
                        default -> throw new DecoderException("Unknown resource scope");
                    };
            return new NodeResourcePolicySummary(
                    direction, scope, buffer.readInt(), buffer.readInt(), buffer.readInt());
        } catch (IllegalArgumentException | IndexOutOfBoundsException invalid) {
            throw new DecoderException("Invalid resource summary", invalid);
        }
    }

    /** Appends exactly fourteen bytes to the caller's buffer; no simulation or authority state is accessed. */
    public static void write(FriendlyByteBuf buffer, NodeResourcePolicySummary summary) {
        NodeMenuCodecSupport.writeTransferDirection(buffer, summary.direction());
        buffer.writeByte(summary.scopeKind() == ResourceScope.Kind.ALL ? 0 : 1);
        buffer.writeInt(summary.scopeTypeCount())
                .writeInt(summary.overrideCount())
                .writeInt(summary.intervalTicks());
    }
}
