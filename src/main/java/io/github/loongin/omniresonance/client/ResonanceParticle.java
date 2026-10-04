// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.particles.SimpleParticleType;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.jetbrains.annotations.Nullable;

/** Native translucent crystal motes with a bounded client-thread allocation budget per tick. */
final class ResonanceParticle extends TextureSheetParticle {
    private static final int MAX_NEW_PER_TICK = 24;
    private static int remainingBudget = MAX_NEW_PER_TICK;
    private static int remainingMoteAttempts = MAX_NEW_PER_TICK;

    private ResonanceParticle(
            ClientLevel level, double x, double y, double z, double xSpeed, double ySpeed, double zSpeed) {
        super(level, x, y, z);
        hasPhysics = false;
        friction = 0.94f;
        lifetime = 24;
        quadSize = 0.075f;
        xd = xSpeed;
        yd = ySpeed + 0.002;
        zd = zSpeed;
        // Position-derived orientation keeps repeated renders stable without another random stream.
        float phase = (float) Math.sin(x * 13.37 + y * 3.11 + z * 7.19);
        roll = oRoll = phase * 1.4f;
        alpha = 0;
    }

    static void resetParticleBudget(ClientTickEvent.Post event) {
        remainingBudget = MAX_NEW_PER_TICK;
        remainingMoteAttempts = MAX_NEW_PER_TICK;
    }

    static boolean canSpawn() {
        return remainingBudget > 0;
    }

    static boolean claimMoteAttempt() {
        // Native particle settings can reject before the provider; bound those attempts as well.
        if (!canSpawn() || remainingMoteAttempts == 0) return false;
        remainingMoteAttempts--;
        return true;
    }

    static @Nullable ResonanceParticle mote(
            SimpleParticleType type,
            ClientLevel level,
            double x,
            double y,
            double z,
            double xSpeed,
            double ySpeed,
            double zSpeed) {
        if (!canSpawn()) return null;
        remainingBudget--;
        return new ResonanceParticle(level, x, y, z, xSpeed, ySpeed, zSpeed);
    }

    @Override
    public void tick() {
        super.tick();
        if (age >= lifetime) remove();
        oRoll = roll;
        roll += 0.012f;
    }

    float opacity(float partialTick) {
        float progress = progress(partialTick);
        float fadeIn = smooth(Math.min(1, progress / 0.18f));
        float fadeOut = smooth(Math.min(1, (1 - progress) / 0.38f));
        return 0.78f * fadeIn * fadeOut;
    }

    private float progress(float partialTick) {
        return Math.clamp((age + partialTick) / lifetime, 0, 1);
    }

    private static float smooth(float value) {
        return value * value * (3 - 2 * value);
    }

    @Override
    public void render(VertexConsumer buffer, Camera camera, float partialTick) {
        alpha = opacity(partialTick);
        super.render(buffer, camera, partialTick);
    }

    @Override
    public float getQuadSize(float partialTick) {
        float progress = progress(partialTick);
        return quadSize * (1 - 0.18f * progress);
    }

    @Override
    protected int getLightColor(float partialTick) {
        return LightTexture.FULL_BRIGHT;
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }
}
