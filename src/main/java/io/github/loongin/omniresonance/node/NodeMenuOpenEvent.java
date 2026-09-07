// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.node;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.Event;

/**
 * Synchronous server-only request produced by an actual physical-node block interaction.
 * Listeners may use the player only during dispatch and must not infer authority from the event itself.
 */
public final class NodeMenuOpenEvent extends Event {
    private final ServerPlayer player;
    private final BlockPos position;

    public NodeMenuOpenEvent(ServerPlayer player, BlockPos position) {
        this.player = Objects.requireNonNull(player, "player");
        this.position = Objects.requireNonNull(position, "position").immutable();
    }

    public ServerPlayer player() {
        return player;
    }

    public BlockPos position() {
        return position;
    }
}
