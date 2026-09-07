// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.network;

import java.util.Objects;
import java.util.UUID;

/** Immutable membership of one node in one direct channel, with no unfinished transfer fields. */
public record DirectNodeBinding(UUID nodeId, UUID channelId, TransferDirection direction) {
    public DirectNodeBinding {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(direction, "direction");
    }
}
