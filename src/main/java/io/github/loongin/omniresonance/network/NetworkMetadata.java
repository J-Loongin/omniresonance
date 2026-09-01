// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.network;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable identity and management metadata for one network.
 *
 * <p>Instances are thread-safe snapshots with no Minecraft object references. Construction copies the caller's
 * administrator set, performs no simulation or authoritative mutation, and rejects missing values with
 * {@link NullPointerException} or invalid relationships and bounds with {@link IllegalArgumentException}.
 * Callers must separately enforce authorization, name uniqueness, and gameplay quotas.
 */
public record NetworkMetadata(UUID id, UUID ownerId, ManagedName name, long creationOrder, Set<UUID> administrators) {
    /** Protocol hard limit, independent of any lower gameplay quota. */
    public static final int MAXIMUM_ADMINISTRATORS = 262144;

    /**
     * Creates a validated, independently owned snapshot without modifying caller data.
     *
     * <p>This pure operation may run on any thread. Negative creation orders, owner membership, and oversized
     * administrator sets are rejected; null fields or members are rejected. No simulation is performed.
     */
    public NetworkMetadata {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(administrators, "administrators");
        if (creationOrder < 0) {
            throw new IllegalArgumentException("Negative network creation order");
        }
        if (administrators.size() > MAXIMUM_ADMINISTRATORS) {
            throw new IllegalArgumentException("Too many network administrators");
        }
        if (administrators.contains(ownerId)) {
            throw new IllegalArgumentException("Network owner cannot be an administrator");
        }
        administrators = Set.copyOf(administrators);
    }
}
