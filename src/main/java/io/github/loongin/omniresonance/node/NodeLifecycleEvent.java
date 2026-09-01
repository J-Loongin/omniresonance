// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.node;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.Event;

/**
 * Synchronous server-only physical-node lifecycle notifications.
 *
 * <p>Listeners may inspect the supplied world/entity only during event dispatch and must not retain them. Events
 * perform no simulation, mutation, chunk loading or authorization. Removed snapshots copy their position and
 * contain only immutable identity afterward.
 */
public abstract class NodeLifecycleEvent extends Event {
    /** One block entity has completed its server onLoad callback with decoded local state. */
    public static final class Loaded extends NodeLifecycleEvent {
        private final ServerLevel level;
        private final ResonanceNodeBlockEntity entity;

        public Loaded(ServerLevel level, ResonanceNodeBlockEntity entity) {
            this.level = Objects.requireNonNull(level, "level");
            this.entity = Objects.requireNonNull(entity, "entity");
        }

        public ServerLevel level() {
            return level;
        }

        public ResonanceNodeBlockEntity entity() {
            return entity;
        }
    }

    /** One valid physical node was actually replaced by a different block. */
    public static final class Removed extends NodeLifecycleEvent {
        private final ServerLevel level;
        private final UUID nodeId;
        private final BlockPos position;

        public Removed(ServerLevel level, UUID nodeId, BlockPos position) {
            this.level = Objects.requireNonNull(level, "level");
            this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
            this.position = Objects.requireNonNull(position, "position").immutable();
        }

        public ServerLevel level() {
            return level;
        }

        public UUID nodeId() {
            return nodeId;
        }

        public BlockPos position() {
            return position;
        }
    }
}
