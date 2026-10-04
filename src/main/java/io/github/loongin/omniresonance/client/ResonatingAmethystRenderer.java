// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.loongin.omniresonance.material.ResonatingAmethystBlockEntity;
import io.github.loongin.omniresonance.registry.ModParticles;
import net.minecraft.client.renderer.FaceInfo;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

/** Visible validated processes only: 24 vertices per block, bounded shared mote budget, no world scan. */
final class ResonatingAmethystRenderer implements BlockEntityRenderer<ResonatingAmethystBlockEntity> {
    private static final ResourceLocation VEINS =
            ResourceLocation.fromNamespaceAndPath("omniresonance", "textures/block/resonating_amethyst_veins.png");
    private static final float[] COORDINATES = {-0.001f, 1.001f, -0.001f, 1.001f, -0.001f, 1.001f};
    private static final Direction[] FACES = Direction.values();
    private final Vector3f position = new Vector3f();
    private final Vector3f normal = new Vector3f();

    ResonatingAmethystRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public int getViewDistance() {
        return 32;
    }

    @Override
    public void render(
            ResonatingAmethystBlockEntity entity,
            float partialTick,
            PoseStack poses,
            MultiBufferSource buffers,
            int packedLight,
            int packedOverlay) {
        var level = entity.getLevel();
        if (level == null || !entity.clientVisualActive()) return;
        double time = level.getGameTime() + partialTick;
        float strength = (float) (0.36 + 0.16 * Math.sin(time * Math.PI / 20.0))
                + 0.2f * entity.clientInjectionStrength(partialTick);
        int alpha = Math.clamp((int) (strength * 255), 0, 200);
        var buffer = buffers.getBuffer(RenderType.entityTranslucentEmissive(VEINS, false));
        var pose = poses.last();
        var blockPos = entity.getBlockPos();
        var state = entity.getBlockState();
        int exposedFaces = 0;
        for (Direction direction : FACES) {
            if (!Block.shouldRenderFace(state, level, blockPos, direction, blockPos.relative(direction))) continue;
            exposedFaces |= 1 << direction.get3DDataValue();
            var face = FaceInfo.fromFacing(direction);
            normal.set(direction.getStepX(), direction.getStepY(), direction.getStepZ());
            pose.normal().transform(normal);
            for (int index = 0; index < 4; index++) {
                var vertex = face.getVertexInfo(index);
                position.set(COORDINATES[vertex.xFace], COORDINATES[vertex.yFace], COORDINATES[vertex.zFace]);
                pose.pose().transformPosition(position);
                float u = index == 0 || index == 1 ? 0 : 1;
                float v = index == 0 || index == 3 ? 0 : 1;
                buffer.addVertex(position.x, position.y, position.z)
                        .setColor(255, 255, 255, alpha)
                        .setUv(u, v)
                        .setOverlay(OverlayTexture.NO_OVERLAY)
                        .setLight(LightTexture.FULL_BRIGHT)
                        .setNormal(normal.x, normal.y, normal.z);
            }
        }
        long tick = level.getGameTime();
        if (exposedFaces != 0
                && ResonanceParticle.canSpawn()
                && Math.floorMod(tick + blockPos.asLong(), 4) == 0
                && entity.claimClientMoteTick(tick)
                && ResonanceParticle.claimMoteAttempt()) {
            Direction face = moteFace(exposedFaces, level.random);
            if (face == null) return;
            Vec3 point = motePosition(
                    blockPos, face, 0.18 + level.random.nextDouble() * 0.64, 0.18 + level.random.nextDouble() * 0.64);
            level.addParticle(
                    ModParticles.RESONANCE_MOTE.get(),
                    point.x,
                    point.y,
                    point.z,
                    face.getStepX() * 0.004,
                    face.getStepY() * 0.004,
                    face.getStepZ() * 0.004);
        }
    }

    static @Nullable Direction moteFace(int exposedFaces, RandomSource random) {
        int count = Integer.bitCount(exposedFaces & 63);
        if (count == 0) return null;
        int selection = random.nextInt(count);
        for (Direction face : FACES) {
            if ((exposedFaces & (1 << face.get3DDataValue())) != 0 && selection-- == 0) return face;
        }
        return null;
    }

    static Vec3 motePosition(BlockPos pos, Direction face, double u, double v) {
        return switch (face.getAxis()) {
            case X -> new Vec3(pos.getX() + 0.5 + face.getStepX() * 0.52, pos.getY() + u, pos.getZ() + v);
            case Y -> new Vec3(pos.getX() + u, pos.getY() + 0.5 + face.getStepY() * 0.52, pos.getZ() + v);
            case Z -> new Vec3(pos.getX() + u, pos.getY() + v, pos.getZ() + 0.5 + face.getStepZ() * 0.52);
        };
    }
}
