// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.network;

import io.github.loongin.omniresonance.transfer.ItemTransferPolicy;
import io.github.loongin.omniresonance.transfer.ResourceTransferPolicy;
import io.github.loongin.omniresonance.transfer.StoredResourcePolicy;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable direct membership owning one stored resource policy; no simulation or runtime state. */
public record DirectNodeBinding(
        UUID nodeId, UUID channelId, StoredResourcePolicy storedPolicy, WorkingFaces workingFaces) {
    public DirectNodeBinding {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(storedPolicy, "storedPolicy");
        Objects.requireNonNull(workingFaces, "workingFaces");
    }

    /** Explicit one-way migration for legacy callers; never converts resource state back to item state. */
    public DirectNodeBinding(UUID nodeId, UUID channelId, ItemTransferPolicy policy, WorkingFaces faces) {
        this(nodeId, channelId, new StoredResourcePolicy(ResourceTransferPolicy.legacy(policy), Map.of()), faces);
    }

    public DirectNodeBinding(UUID nodeId, UUID channelId, ItemTransferPolicy policy) {
        this(nodeId, channelId, policy, WorkingFaces.attachedFace());
    }

    public DirectNodeBinding(UUID nodeId, UUID channelId, ResourceTransferPolicy policy, WorkingFaces faces) {
        this(nodeId, channelId, new StoredResourcePolicy(policy, Map.of()), faces);
    }

    public DirectNodeBinding(UUID nodeId, UUID channelId, TransferDirection direction) {
        this(nodeId, channelId, ResourceTransferPolicy.defaults(direction), WorkingFaces.explicit(0));
    }

    public ResourceTransferPolicy policy() {
        return storedPolicy.effectivePolicy();
    }

    public TransferDirection direction() {
        return policy().direction();
    }
}
