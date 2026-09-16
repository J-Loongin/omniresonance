// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.node;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;

/** Fixed deterministic bounded search; callers validate candidates without loading neighboring chunks. */
final class NodeTravelCandidates {
    private static final List<BlockPos> OFFSETS;

    static {
        var horizontal = new ArrayList<BlockPos>(25);
        for (int z = -2; z <= 2; z++) for (int x = -2; x <= 2; x++) horizontal.add(new BlockPos(x, 0, z));
        horizontal.sort(Comparator.comparingInt((BlockPos p) -> p.getX() * p.getX() + p.getZ() * p.getZ())
                .thenComparingInt(BlockPos::getZ)
                .thenComparingInt(BlockPos::getX));
        var offsets = new ArrayList<BlockPos>(75);
        for (var p : horizontal) {
            offsets.add(p);
            offsets.add(p.above());
            offsets.add(p.below());
        }
        OFFSETS = List.copyOf(offsets);
    }

    private NodeTravelCandidates() {}

    static List<BlockPos> around(BlockPos node) {
        var result = new ArrayList<BlockPos>(75);
        for (var offset : OFFSETS) result.add(node.offset(offset));
        return List.copyOf(result);
    }
}
