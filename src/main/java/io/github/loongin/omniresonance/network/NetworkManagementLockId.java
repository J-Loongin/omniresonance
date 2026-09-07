// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.network;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Stable pure mapping from one network identity to its shared metadata edit-lock identity. */
public final class NetworkManagementLockId {
    private NetworkManagementLockId() {}

    /** Returns the immutable deterministic lock key without acquiring a lock or accessing authority state. */
    public static UUID of(UUID networkId) {
        return UUID.nameUUIDFromBytes(
                ("omniresonance:network_management/" + Objects.requireNonNull(networkId, "networkId"))
                        .getBytes(StandardCharsets.UTF_8));
    }
}
