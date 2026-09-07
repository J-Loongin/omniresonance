// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.network;

import java.util.Objects;
import java.util.UUID;

/** Immutable one-direction domain configuration for one node, without a ledger or resource policy. */
public record DomainNodeConfiguration(UUID nodeId, TransferDirection direction) {
    public DomainNodeConfiguration {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(direction, "direction");
    }
}
