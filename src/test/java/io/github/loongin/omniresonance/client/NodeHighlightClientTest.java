// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.node.ResonanceNodeBlockEntity;
import io.github.loongin.omniresonance.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class NodeHighlightClientTest {
    @Test
    void authorizedGeometryDoesNotRequireUnsynchronizedServerIdentity() {
        var node = new ResonanceNodeBlockEntity(
                BlockPos.ZERO, ModBlocks.RESONANCE_TRANSFER_NODE.get().defaultBlockState());
        assertTrue(node.state().isEmpty());
        assertTrue(NodeHighlightClient.canRender(node));
        assertFalse(NodeHighlightClient.canRender(null));
    }
}
