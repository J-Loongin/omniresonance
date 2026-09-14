// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.node;

import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.Event;

/** Synchronous server-thread wakeup; listeners enqueue the ID and never retain the event or world. */
public final class NodeTransferWakeEvent extends Event {
    private final ServerLevel level;
    private final UUID nodeId;

    public NodeTransferWakeEvent(ServerLevel level, UUID nodeId) {
        this.level = level;
        this.nodeId = nodeId;
    }

    public ServerLevel level() {
        return level;
    }

    public UUID nodeId() {
        return nodeId;
    }
}
