// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.networking.NodeFacePreview;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

final class NodeFacePreviewsTest {
    @Test
    void unloadedNeighborsNeverReadBlockStateAndPanelOnlyChecksAttachment() {
        List<BlockPos> probes = new ArrayList<>();
        var block = NodeFacePreviews.collect(
                BlockPos.ZERO,
                NodeForm.BLOCK,
                Direction.WEST,
                position -> {
                    probes.add(position);
                    return false;
                },
                position -> {
                    throw new AssertionError("Unloaded block state read");
                });
        assertEquals(6, probes.size());
        assertEquals(6, block.size());
        assertTrue(block.stream().allMatch(preview -> preview.status() == NodeFacePreview.Status.UNLOADED));
        probes.clear();
        var panel = NodeFacePreviews.collect(
                BlockPos.ZERO,
                NodeForm.PANEL,
                Direction.WEST,
                position -> {
                    probes.add(position);
                    return false;
                },
                position -> {
                    throw new AssertionError("Unloaded attached state read");
                });
        assertEquals(List.of(BlockPos.ZERO.west()), probes);
        assertEquals(1, panel.size());
    }

    @Test
    void loadedAirAndBlocksProduceHonestIdentityWithoutExtraReads() {
        List<BlockPos> reads = new ArrayList<>();
        var previews =
                NodeFacePreviews.collect(BlockPos.ZERO, NodeForm.BLOCK, Direction.DOWN, position -> true, position -> {
                    reads.add(position);
                    return position.equals(BlockPos.ZERO.above())
                            ? Blocks.CHEST.defaultBlockState()
                            : Blocks.AIR.defaultBlockState();
                });
        assertEquals(6, reads.size());
        assertEquals(
                1,
                previews.stream()
                        .filter(preview -> preview.status() == NodeFacePreview.Status.BLOCK)
                        .count());
        assertEquals(
                5,
                previews.stream()
                        .filter(preview -> preview.status() == NodeFacePreview.Status.AIR)
                        .count());
    }
}
