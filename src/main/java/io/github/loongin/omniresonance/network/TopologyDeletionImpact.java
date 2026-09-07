// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.network;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable server-computed cascade impact bound to one exact network topology revision. */
public record TopologyDeletionImpact(
        UUID objectId, int channelCount, int bindingCount, List<UUID> affectedNodeIds, long topologyRevision) {
    public TopologyDeletionImpact {
        Objects.requireNonNull(objectId, "objectId");
        affectedNodeIds = List.copyOf(Objects.requireNonNull(affectedNodeIds, "affectedNodeIds"));
        if (channelCount < 0 || bindingCount < 0 || topologyRevision < 0) {
            throw new IllegalArgumentException("Invalid topology deletion impact");
        }
    }
}
