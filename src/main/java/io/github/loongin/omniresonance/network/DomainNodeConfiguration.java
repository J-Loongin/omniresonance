// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.network;

import io.github.loongin.omniresonance.transfer.ResourceTransferPolicy;
import io.github.loongin.omniresonance.transfer.StoredResourcePolicy;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable one-direction resource configuration; pending legacy records never authorize runtime work. */
public record DomainNodeConfiguration(
        UUID nodeId, StoredResourcePolicy storedPolicy, WorkingFaces workingFaces, boolean configured) {
    public DomainNodeConfiguration {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(storedPolicy, "storedPolicy");
        Objects.requireNonNull(workingFaces, "workingFaces");
    }

    /** Direction-only compatibility intent remains pending until a complete resource form is saved. */
    public DomainNodeConfiguration(UUID nodeId, TransferDirection direction) {
        this(
                nodeId,
                new StoredResourcePolicy(ResourceTransferPolicy.defaults(direction), Map.of()),
                WorkingFaces.attachedFace(),
                false);
    }

    public ResourceTransferPolicy policy() {
        return storedPolicy.effectivePolicy();
    }

    public TransferDirection direction() {
        return policy().direction();
    }
}
