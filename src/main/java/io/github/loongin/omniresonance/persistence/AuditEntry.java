// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.persistence;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

/** Immutable server-authored audit metadata; summaries contain bounded aggregate facts, never resource keys or rules. */
public record AuditEntry(ResourceLocation action, UUID actor, UUID target, long gameTick, String summary) {
    /** Creates metadata from a live actor on the server thread; no mutation, simulation or retained player reference. */
    public static AuditEntry of(
            String action, net.minecraft.server.level.ServerPlayer actor, UUID target, String summary) {
        if (!actor.serverLevel().getServer().isSameThread())
            throw new IllegalStateException("Audit actor off server thread");
        return new AuditEntry(
                ResourceLocation.fromNamespaceAndPath("omniresonance", action),
                actor.getUUID(),
                target,
                actor.serverLevel().getServer().overworld().getGameTime(),
                summary);
    }

    public AuditEntry {
        Objects.requireNonNull(action);
        Objects.requireNonNull(actor);
        Objects.requireNonNull(target);
        Objects.requireNonNull(summary);
        if (!action.getNamespace().equals("omniresonance")
                || action.toString().length() > 128
                || gameTick < 0
                || summary.length() > 128
                || summary.getBytes(StandardCharsets.UTF_8).length > 512)
            throw new IllegalArgumentException("Invalid audit entry");
        for (int i = 0; i < summary.length(); i++)
            if (Character.isISOControl(summary.charAt(i))) throw new IllegalArgumentException("Invalid audit summary");
    }
}
