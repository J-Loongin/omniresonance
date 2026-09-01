// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

/** Pure contracts for target-side semantics and six panel shapes. */
final class NodeGeometryTest {
    private static final BlockPos ORIGIN = new BlockPos(10, 20, 30);

    @Test
    void facingAlwaysPointsFromNodeToTargetAndExposesOppositeTargetSide() {
        for (Direction facing : Direction.values()) {
            assertEquals(ORIGIN.relative(facing), NodeGeometry.targetPosition(ORIGIN, facing));
            assertEquals(facing.getOpposite(), NodeGeometry.targetSide(facing));
            assertEquals(facing.getOpposite(), NodeGeometry.placedFacing(facing));
        }
    }

    @Test
    void panelShapesMatchEveryLiteralTwoPixelFaceBox() {
        Map<Direction, List<Integer>> expected = new EnumMap<>(Direction.class);
        expected.put(Direction.DOWN, List.of(0, 0, 0, 16, 2, 16));
        expected.put(Direction.UP, List.of(0, 14, 0, 16, 16, 16));
        expected.put(Direction.NORTH, List.of(0, 0, 0, 16, 16, 2));
        expected.put(Direction.SOUTH, List.of(0, 0, 14, 16, 16, 16));
        expected.put(Direction.WEST, List.of(0, 0, 0, 2, 16, 16));
        expected.put(Direction.EAST, List.of(14, 0, 0, 16, 16, 16));

        for (Direction direction : Direction.values()) {
            AABB box = NodeGeometry.panelShape(direction).toAabbs().getFirst();
            assertEquals(
                    expected.get(direction),
                    List.of(
                            pixels(box.minX),
                            pixels(box.minY),
                            pixels(box.minZ),
                            pixels(box.maxX),
                            pixels(box.maxY),
                            pixels(box.maxZ)),
                    direction::getName);
            assertSame(NodeGeometry.panelShape(direction), NodeGeometry.panelShape(direction));
        }
    }

    @Test
    void rotationAndMirrorUseLiteralDirectionMappings() {
        assertEquals(Direction.EAST, NodeGeometry.rotate(Direction.NORTH, Rotation.CLOCKWISE_90));
        assertEquals(Direction.SOUTH, NodeGeometry.rotate(Direction.NORTH, Rotation.CLOCKWISE_180));
        assertEquals(Direction.WEST, NodeGeometry.rotate(Direction.NORTH, Rotation.COUNTERCLOCKWISE_90));
        assertEquals(Direction.SOUTH, NodeGeometry.mirror(Direction.NORTH, Mirror.LEFT_RIGHT));
        assertEquals(Direction.WEST, NodeGeometry.mirror(Direction.EAST, Mirror.FRONT_BACK));
        assertEquals(Direction.UP, NodeGeometry.mirror(Direction.UP, Mirror.FRONT_BACK));
    }

    @Test
    void missingGeometryInputsAreRejected() {
        assertThrows(NullPointerException.class, () -> NodeGeometry.targetPosition(null, Direction.DOWN));
        assertThrows(NullPointerException.class, () -> NodeGeometry.targetPosition(ORIGIN, null));
        assertThrows(NullPointerException.class, () -> NodeGeometry.targetSide(null));
        assertThrows(NullPointerException.class, () -> NodeGeometry.panelShape(null));
        assertThrows(NullPointerException.class, () -> NodeGeometry.rotate(null, Rotation.NONE));
        assertThrows(NullPointerException.class, () -> NodeGeometry.rotate(Direction.DOWN, null));
        assertThrows(NullPointerException.class, () -> NodeGeometry.mirror(null, Mirror.NONE));
        assertThrows(NullPointerException.class, () -> NodeGeometry.mirror(Direction.DOWN, null));
    }

    private static int pixels(double coordinate) {
        return (int) Math.round(coordinate * 16.0D);
    }
}
