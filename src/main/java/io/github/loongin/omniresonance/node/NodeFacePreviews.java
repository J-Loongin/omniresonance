// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.node;

import io.github.loongin.omniresonance.networking.NodeFacePreview;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

/** Server-thread snapshot builder: at most six load checks and state reads; never invokes a capability or NBT hook. */
final class NodeFacePreviews {
    private NodeFacePreviews() {}

    static List<NodeFacePreview> collect(
            BlockPos origin,
            NodeForm form,
            Direction facing,
            Predicate<BlockPos> loaded,
            Function<BlockPos, BlockState> readState) {
        List<NodeFacePreview> previews = new ArrayList<>(6);
        for (Direction direction : Direction.values()) {
            if (form == NodeForm.PANEL && direction != facing) continue;
            BlockPos target = origin.relative(direction);
            NodeFacePreview.Status status = NodeFacePreview.Status.UNLOADED;
            ResourceLocation blockId = null;
            if (loaded.test(target)) {
                BlockState state = readState.apply(target);
                status = state.isAir() ? NodeFacePreview.Status.AIR : NodeFacePreview.Status.BLOCK;
                if (!state.isAir()) blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            }
            previews.add(new NodeFacePreview(direction, status, blockId));
        }
        return List.copyOf(previews);
    }
}
