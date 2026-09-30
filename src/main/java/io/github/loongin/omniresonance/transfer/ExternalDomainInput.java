// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

/** Server-thread single-configuration delivery lease. Owns no native stack or resource buffer. Every call
 * revalidates authority. Simulation is pure, actual insertion returns confirmed acceptance, and rejection
 * neither queues work nor promises a retry. Unknown mutation failures propagate without guessed amounts. */
public interface ExternalDomainInput {
    /** Pure readiness for a face/type; false never schedules preparation. */
    boolean available(Direction side, ResourceLocation type);
    /** Accepts at most maximum without changing the caller-owned variant. Never offers extraction. */
    long insert(Direction side, ResourceVariant variant, long maximum, boolean simulate);
}
