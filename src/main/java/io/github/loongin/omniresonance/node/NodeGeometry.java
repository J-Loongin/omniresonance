// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.node;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Pure shared targeting and panel geometry.
 *
 * <p>All six shapes are allocated once and are safe immutable Minecraft geometry values. Methods retain no world
 * reference, perform no capability discovery or simulation, and enforce the single target-side interpretation.
 */
public final class NodeGeometry {
    private static final Map<Direction, VoxelShape> PANEL_SHAPES = createPanelShapes();

    private NodeGeometry() {}

    /** Returns the adjacent target position in the direction from node to target. */
    public static BlockPos targetPosition(BlockPos nodePos, Direction facing) {
        return Objects.requireNonNull(nodePos, "nodePos").relative(Objects.requireNonNull(facing, "facing"));
    }

    /** Returns the side exposed by the target back toward this node. */
    public static Direction targetSide(Direction facing) {
        return Objects.requireNonNull(facing, "facing").getOpposite();
    }

    /** Converts a clicked target face into the placed block's node-to-target facing. */
    public static Direction placedFacing(Direction clickedFace) {
        return Objects.requireNonNull(clickedFace, "clickedFace").getOpposite();
    }

    /** Applies the vanilla block rotation to the stable facing. */
    public static Direction rotate(Direction facing, Rotation rotation) {
        return Objects.requireNonNull(rotation, "rotation").rotate(Objects.requireNonNull(facing, "facing"));
    }

    /** Applies the vanilla block mirror to the stable facing. */
    public static Direction mirror(Direction facing, Mirror mirror) {
        return Objects.requireNonNull(mirror, "mirror").mirror(Objects.requireNonNull(facing, "facing"));
    }

    /** Returns one cached 2/16-thick shape attached to the target-facing boundary. */
    public static VoxelShape panelShape(Direction facing) {
        return PANEL_SHAPES.get(Objects.requireNonNull(facing, "facing"));
    }

    private static Map<Direction, VoxelShape> createPanelShapes() {
        EnumMap<Direction, VoxelShape> shapes = new EnumMap<>(Direction.class);
        shapes.put(Direction.DOWN, Block.box(0, 0, 0, 16, 2, 16));
        shapes.put(Direction.UP, Block.box(0, 14, 0, 16, 16, 16));
        shapes.put(Direction.NORTH, Block.box(0, 0, 0, 16, 16, 2));
        shapes.put(Direction.SOUTH, Block.box(0, 0, 14, 16, 16, 16));
        shapes.put(Direction.WEST, Block.box(0, 0, 0, 2, 16, 16));
        shapes.put(Direction.EAST, Block.box(14, 0, 0, 16, 16, 16));
        return Collections.unmodifiableMap(shapes);
    }
}
