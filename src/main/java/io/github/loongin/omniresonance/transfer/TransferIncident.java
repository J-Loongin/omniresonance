// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** Immutable bounded incident evidence; safe to share, never retains exceptions, capabilities or world objects. */
public record TransferIncident(
        Endpoint node,
        @Nullable Endpoint peer,
        @Nullable UUID channelId,
        String channelName,
        ResourceLocation type,
        Reason reason,
        ResourceTransferEngine.Stage stage) {
    public enum Reason {
        UNKNOWN,
        SLOW_CALL,
        EXCEPTION,
        INVALID_ENDPOINT,
        RECOVERY_FULL,
        INCONSISTENT,
        UNKNOWN_MUTATION
    }

    public record Endpoint(UUID id, String name, @Nullable GlobalPos position) {
        public Endpoint {
            Objects.requireNonNull(id);
            Objects.requireNonNull(name);
            if (name.length() > 256) throw new IllegalArgumentException("Incident name too long");
            if (position != null) {
                if (position.dimension().location().toString().length() > 256)
                    throw new IllegalArgumentException("Incident dimension too long");
                position = GlobalPos.of(position.dimension(), position.pos().immutable());
            }
        }
    }

    public TransferIncident {
        Objects.requireNonNull(node);
        Objects.requireNonNull(channelName);
        Objects.requireNonNull(reason);
        Objects.requireNonNull(stage);
        ResourceScope.validateResourceTypeId(type);
        if (channelName.length() > 256 || channelId == null && !channelName.isEmpty())
            throw new IllegalArgumentException("Invalid incident channel");
    }
    /** Captures identity and known cause only; caller owns metadata resolution. No world access or mutation. */
    public static TransferIncident of(
            UUID node,
            @Nullable UUID peer,
            @Nullable UUID channel,
            ResourceLocation type,
            Reason reason,
            ResourceTransferEngine.Stage stage) {
        return new TransferIncident(
                new Endpoint(node, "", null),
                peer == null ? null : new Endpoint(peer, "", null),
                channel,
                "",
                type,
                reason,
                stage);
    }
    /** Maps only proven engine failure classifications, without interpreting exception messages. */
    public static Reason reason(ResourceTransferEngine.Failure failure) {
        return switch (failure) {
            case EXCEPTION -> Reason.EXCEPTION;
            case INVALID_ENDPOINT -> Reason.INVALID_ENDPOINT;
            case RECOVERY_FULL -> Reason.RECOVERY_FULL;
            case INCONSISTENT -> Reason.INCONSISTENT;
            case UNKNOWN_MUTATION -> Reason.UNKNOWN_MUTATION;
            default -> Reason.UNKNOWN;
        };
    }
}
