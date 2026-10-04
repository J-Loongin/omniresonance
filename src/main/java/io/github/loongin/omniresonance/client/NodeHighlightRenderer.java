// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.FaceInfo;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

/** One temporary target: merged shared edges and a 12-strip soft beam, independent of normal node rendering. */
final class NodeHighlightRenderer {
    private static final int[][] EDGES = {
        {0, 1}, {2, 3}, {4, 5}, {6, 7}, {0, 2}, {1, 3}, {4, 6}, {5, 7}, {0, 4}, {1, 5}, {2, 6}, {3, 7}
    };
    private static final Direction[] FACES = Direction.values();
    private static final int[][] FACE_EDGES = new int[6][4];
    private static final boolean[][] FORWARD = new boolean[6][4];
    // Twelve constant strip opacities for one temporary target; no per-frame profile calculation or world cache.
    private static final int[] BEAM_ALPHAS = beamAlphas();
    private final Vector3f position = new Vector3f();
    private final Vector3f normal = new Vector3f();

    static {
        int[] clockwise = {0, 3, 2, 1};
        for (Direction face : FACES) {
            var info = FaceInfo.fromFacing(face);
            for (int side = 0; side < 4; side++) {
                int start = corner(info.getVertexInfo(clockwise[side]));
                int end = corner(info.getVertexInfo(clockwise[(side + 1) % 4]));
                for (int edge = 0; edge < EDGES.length; edge++) {
                    if (EDGES[edge][0] == start && EDGES[edge][1] == end
                            || EDGES[edge][1] == start && EDGES[edge][0] == end) {
                        FACE_EDGES[face.ordinal()][side] = edge;
                        FORWARD[face.ordinal()][side] = EDGES[edge][0] == start;
                        break;
                    }
                }
            }
        }
    }

    private static int corner(FaceInfo.VertexInfo vertex) {
        return (vertex.xFace == FaceInfo.Constants.MAX_X ? 1 : 0)
                | (vertex.yFace == FaceInfo.Constants.MAX_Y ? 2 : 0)
                | (vertex.zFace == FaceInfo.Constants.MAX_Z ? 4 : 0);
    }

    private static int[] beamAlphas() {
        int[] alphas = new int[12];
        for (int strip = 0; strip < alphas.length; strip++) {
            double radial = (strip + 0.5) / 6.0 - 1;
            alphas[strip] = (int) (Math.exp(-radial * radial * 3.5) * 0.44 * 255);
        }
        return alphas;
    }

    static int beamAlpha(int strip, boolean top) {
        int alpha = BEAM_ALPHAS[strip];
        return top ? alpha * 65 / 100 : alpha;
    }

    void render(
            PoseStack poses,
            MultiBufferSource buffers,
            AABB bounds,
            @Nullable Direction exposed,
            double time,
            int colour,
            double height,
            float cameraYaw) {
        var lines = buffers.getBuffer(RenderType.lines());
        var pose = poses.last();
        double phase = time / 120.0;
        for (int edge = 0; edge < EDGES.length; edge++) {
            int start = EDGES[edge][0], end = EDGES[edge][1];
            for (int segment = 0; segment < 32; segment++) {
                double middle = (segment + 0.5) / 32.0;
                double maximum = 0;
                for (Direction face : FACES) {
                    if (exposed != null && exposed != face) continue;
                    int faceIndex = face.ordinal();
                    double head = (phase + (exposed == null ? faceIndex / 6.0 : 0)) * 4 + 0.5;
                    for (int side = 0; side < 4; side++) {
                        if (FACE_EDGES[faceIndex][side] != edge) continue;
                        double distance = side + (FORWARD[faceIndex][side] ? middle : 1 - middle);
                        double behind = ((head - distance) % 4 + 4) % 4;
                        double light =
                                behind < 0.16 ? 0.8 : behind < 0.8 ? 0.35 * Math.pow(1 - (behind - 0.16) / 0.64, 2) : 0;
                        maximum = Math.max(maximum, light);
                    }
                }
                int alpha = (int) ((0.25 + maximum * 0.6875) * 255);
                double t0 = segment / 32.0, t1 = (segment + 1) / 32.0;
                double x0 = coordinate(bounds.minX, bounds.maxX, start, end, 1, t0);
                double y0 = coordinate(bounds.minY, bounds.maxY, start, end, 2, t0);
                double z0 = coordinate(bounds.minZ, bounds.maxZ, start, end, 4, t0);
                double x1 = coordinate(bounds.minX, bounds.maxX, start, end, 1, t1);
                double y1 = coordinate(bounds.minY, bounds.maxY, start, end, 2, t1);
                double z1 = coordinate(bounds.minZ, bounds.maxZ, start, end, 4, t1);
                normal.set((float) (x1 - x0), (float) (y1 - y0), (float) (z1 - z0))
                        .normalize();
                pose.normal().transform(normal);
                vertex(lines, pose, x0, y0, z0, 0xD94D63, alpha, true);
                vertex(lines, pose, x1, y1, z1, 0xD94D63, alpha, true);
            }
        }
        double x = (bounds.minX + bounds.maxX) / 2, y = bounds.maxY;
        double z = (bounds.minZ + bounds.maxZ) / 2;
        if (exposed != null) {
            if (exposed == Direction.DOWN) y = bounds.minY;
            if (exposed == Direction.WEST) x = bounds.minX;
            if (exposed == Direction.EAST) x = bounds.maxX;
            if (exposed == Direction.NORTH) z = bounds.minZ;
            if (exposed == Direction.SOUTH) z = bounds.maxZ;
        }
        var quads = buffers.getBuffer(RenderType.debugQuads());
        double yaw = Math.toRadians(cameraYaw);
        double dx = Math.cos(yaw), dz = Math.sin(yaw);
        for (int strip = 0; strip < 12; strip++) {
            double a = (strip / 12.0 - 0.5) * 0.20;
            double b = ((strip + 1) / 12.0 - 0.5) * 0.20;
            int alpha = beamAlpha(strip, false);
            vertex(quads, pose, x + dx * a, y, z + dz * a, colour, alpha, false);
            vertex(quads, pose, x + dx * b, y, z + dz * b, colour, alpha, false);
            vertex(quads, pose, x + dx * b, y + height, z + dz * b, colour, beamAlpha(strip, true), false);
            vertex(quads, pose, x + dx * a, y + height, z + dz * a, colour, beamAlpha(strip, true), false);
        }
    }

    private static double coordinate(double minimum, double maximum, int start, int end, int bit, double t) {
        double a = (start & bit) == 0 ? minimum : maximum;
        double b = (end & bit) == 0 ? minimum : maximum;
        return a + (b - a) * t;
    }

    private void vertex(
            VertexConsumer consumer,
            PoseStack.Pose pose,
            double x,
            double y,
            double z,
            int colour,
            int alpha,
            boolean line) {
        position.set((float) x, (float) y, (float) z);
        pose.pose().transformPosition(position);
        consumer.addVertex(position.x, position.y, position.z)
                .setColor(colour >> 16 & 255, colour >> 8 & 255, colour & 255, alpha);
        if (line) consumer.setNormal(normal.x, normal.y, normal.z);
    }
}
