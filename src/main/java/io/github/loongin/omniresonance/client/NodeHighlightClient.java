// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.networking.NetworkPayloads;
import io.github.loongin.omniresonance.networking.NodeHighlightFrame;
import io.github.loongin.omniresonance.node.ResonanceNodeBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;

/** One connection-scoped authorized highlight. Loaded-chunk checks never request client chunks or tickets. */
final class NodeHighlightClient {
    private @Nullable NodeHighlightFrame current;
    private int remainingTicks;

    NodeHighlightClient() {
        NetworkPayloads.installHighlightReceiver(frame -> {
            current = frame.durationTicks() == 0 ? null : frame;
            remainingTicks = frame.durationTicks();
        });
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::logout);
        NeoForge.EVENT_BUS.addListener(this::renderWorld);
        NeoForge.EVENT_BUS.addListener(this::renderHud);
    }

    private void tick(ClientTickEvent.Post event) {
        if (remainingTicks > 0 && --remainingTicks == 0) current = null;
    }

    private void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        current = null;
        remainingTicks = 0;
    }

    private boolean loaded(Minecraft mc) {
        if (current == null
                || mc.level == null
                || !mc.level.dimension().equals(current.position().dimension())) return false;
        var p = current.position().pos();
        if (!mc.level.hasChunk(p.getX() >> 4, p.getZ() >> 4)) return false;
        var entity = mc.level.getBlockEntity(p);
        // Physical UUID is server-only; the authorized frame supplies identity and the client supplies visible
        // geometry.
        return canRender(entity);
    }

    static boolean canRender(net.minecraft.world.level.block.entity.BlockEntity entity) {
        return entity instanceof ResonanceNodeBlockEntity;
    }

    private void renderWorld(RenderLevelStageEvent event) {
        var mc = Minecraft.getInstance();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || mc.options.hideGui || !loaded(mc))
            return;
        var p = current.position().pos();
        var camera = event.getCamera().getPosition();
        var pose = event.getPoseStack();
        var buffers = mc.renderBuffers().bufferSource();
        pose.pushPose();
        try {
            pose.translate(p.getX() - camera.x, p.getY() - camera.y, p.getZ() - camera.z);
            var shape = mc.level.getBlockState(p).getShape(mc.level, p);
            var lines = buffers.getBuffer(RenderType.lines());
            shape.forAllBoxes((x0, y0, z0, x1, y1, z1) -> LevelRenderer.renderLineBox(
                    pose, lines, new AABB(x0, y0, z0, x1, y1, z1).inflate(0.003), 0.3f, 0.9f, 0.9f, 1f));
            BeaconRenderer.renderBeaconBeam(
                    pose,
                    buffers,
                    ResourceLocation.withDefaultNamespace("textures/entity/beacon_beam.png"),
                    event.getPartialTick().getGameTimeDeltaPartialTick(false),
                    1f,
                    mc.level.getGameTime(),
                    0,
                    Math.max(1, mc.level.getMaxBuildHeight() - p.getY()),
                    0xFF4CE5E5,
                    0.08f,
                    0.12f);
        } finally {
            pose.popPose();
        }
        buffers.endBatch();
    }

    private void renderHud(RenderGuiEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (current == null || mc.level == null || mc.player == null || mc.options.hideGui) return;
        var p = current.position().pos();
        Component location;
        if (!mc.level.dimension().equals(current.position().dimension())) {
            location = Component.literal(
                    current.position().dimension().location() + " · " + p.getX() + ", " + p.getY() + ", " + p.getZ());
        } else {
            double dx = p.getX() + 0.5 - mc.player.getX(), dz = p.getZ() + 0.5 - mc.player.getZ();
            double angle = Mth.wrapDegrees(Math.toDegrees(Math.atan2(-dx, dz)) - mc.player.getYRot());
            String direction =
                    Math.abs(angle) >= 150 ? "behind" : angle < -30 ? "left" : angle > 30 ? "right" : "ahead";
            location = Component.translatable(
                    "omniresonance.navigation." + direction,
                    Math.round(Math.sqrt(mc.player.distanceToSqr(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5))));
        }
        var font = TerminalText.font(mc);
        var graphics = event.getGuiGraphics();
        graphics.drawCenteredString(
                font,
                TerminalText.body(Component.literal(current.name())),
                graphics.guiWidth() / 2,
                12,
                TerminalTheme.TEXT);
        graphics.drawCenteredString(
                font, TerminalText.body(location), graphics.guiWidth() / 2, 24, TerminalTheme.ACCENT);
    }
}
