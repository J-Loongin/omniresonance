// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.security;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Pure base role predicates for a network.
 *
 * <p>These thread-safe methods read caller-owned authoritative role inputs without caching, simulating, or mutating
 * them. They do not replace operation-specific preconditions, such as preset reference closure or network-deletion
 * emptiness checks.
 */
public final class NetworkPermissions {
    private NetworkPermissions() {}

    /**
     * Determines whether an actor has the owner or administrator network role.
     *
     * <p>This method is thread-safe, pure, and non-mutating; callers retain ownership of all inputs and must provide
     * authoritative role data. It performs no simulation and does not validate operation-specific preconditions. A
     * missing argument causes {@link NullPointerException}.
     *
     * @param actor the actor requesting management access
     * @param owner the network owner
     * @param administrators the current administrator role set
     * @return whether the actor is the owner or a current administrator
     */
    public static boolean canManage(UUID actor, UUID owner, Set<UUID> administrators) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(administrators, "administrators");
        return actor.equals(owner) || administrators.contains(actor);
    }

    /**
     * Determines whether an actor may manage the administrator role set.
     *
     * <p>This method is thread-safe, pure, and non-mutating; callers retain ownership of authoritative inputs. It
     * performs no simulation and does not validate operation-specific preconditions. A missing argument causes {@link
     * NullPointerException}.
     *
     * @param actor the actor requesting administrator management
     * @param owner the network owner
     * @return whether the actor is the owner
     */
    public static boolean canManageAdministrators(UUID actor, UUID owner) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(owner, "owner");
        return actor.equals(owner);
    }

    /**
     * Determines whether an actor has the base owner role required to delete a network.
     *
     * <p>This method is thread-safe, pure, and non-mutating; callers retain ownership of authoritative inputs. It
     * performs no simulation and does not validate operation-specific deletion preconditions. A missing argument
     * causes {@link NullPointerException}.
     *
     * @param actor the actor requesting deletion
     * @param owner the network owner
     * @return whether the actor is the owner
     */
    public static boolean canDeleteNetwork(UUID actor, UUID owner) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(owner, "owner");
        return actor.equals(owner);
    }
}
