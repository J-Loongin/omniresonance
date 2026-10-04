// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class ResonanceParticleTest {
    @BeforeEach
    void beginTick() {
        ResonanceParticle.resetParticleBudget(null);
    }

    @Test
    void realMoteFactoryRespectsTheBudgetAndResetsOnTheNextClientTick() {
        for (int index = 0; index < 24; index++) {
            var particle = ResonanceParticle.mote(null, null, index, 1, 2, 0, 0, 0);
            assertNotNull(particle);
        }
        assertFalse(ResonanceParticle.canSpawn());
        assertNull(ResonanceParticle.mote(null, null, 0, 0, 0, 0, 0, 0));
        ResonanceParticle.resetParticleBudget(null);
        assertTrue(ResonanceParticle.canSpawn());
        assertNotNull(ResonanceParticle.mote(null, null, 0, 0, 0, 0, 0, 0));
    }

    @Test
    void nativeSettingsRejectionsCannotCauseUnboundedMoteWork() {
        for (int attempt = 0; attempt < 24; attempt++) {
            assertTrue(ResonanceParticle.claimMoteAttempt());
        }
        assertFalse(ResonanceParticle.claimMoteAttempt());
        assertTrue(ResonanceParticle.canSpawn());
        assertNotNull(ResonanceParticle.mote(null, null, 0, 0, 0, 0, 0, 0));
        ResonanceParticle.resetParticleBudget(null);
        assertTrue(ResonanceParticle.claimMoteAttempt());
    }

    @Test
    void actualMotesFadeAtBothEndsAndExpireWithoutWorldQueries() {
        for (double xSpeed : new double[] {0, 0.004}) {
            var particle = ResonanceParticle.mote(null, null, 2, 3, 4, xSpeed, 0, 0);
            assertNotNull(particle);
            assertEquals(24, particle.getLifetime());
            assertEquals(0.075f, particle.getQuadSize(0));
            assertEquals(0, particle.opacity(0));
            assertSame(ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT, particle.getRenderType());
            assertEquals(LightTexture.FULL_BRIGHT, particle.getLightColor(0));
            assertTrue(particle.getLifetime() <= 24);
            for (int tick = 0; tick < particle.getLifetime(); tick++) {
                particle.tick();
                float opacity = particle.opacity(0.5f);
                assertTrue(opacity >= 0 && opacity < 0.8f);
                if (tick == particle.getLifetime() / 2) assertTrue(opacity > 0.4f);
            }
            assertEquals(0, particle.opacity(0));
            assertFalse(particle.isAlive());
            assertTrue(particle.getPos().distanceTo(new net.minecraft.world.phys.Vec3(2, 3, 4)) < 0.15);
        }
    }

    @Test
    void visibleFaceSelectionNeverEmitsIntoCoveredFaces() {
        var random = RandomSource.create(44123);
        int mask = (1 << Direction.UP.get3DDataValue()) | (1 << Direction.EAST.get3DDataValue());
        int up = 0;
        int east = 0;
        for (int sample = 0; sample < 512; sample++) {
            Direction selected = ResonatingAmethystRenderer.moteFace(mask, random);
            assertTrue(selected == Direction.UP || selected == Direction.EAST);
            if (selected == Direction.UP) up++;
            else east++;
        }
        assertTrue(up > 150 && east > 150);
        assertNull(ResonatingAmethystRenderer.moteFace(0, random));
    }

    @Test
    void faceCoordinatesStayJustOutsideTheOriginalCube() {
        var pos = new BlockPos(12, 64, -10);
        for (Direction face : Direction.values()) {
            var point = ResonatingAmethystRenderer.motePosition(pos, face, 0.25, 0.75);
            double normalCoordinate =
                    switch (face.getAxis()) {
                        case X -> point.x - pos.getX();
                        case Y -> point.y - pos.getY();
                        case Z -> point.z - pos.getZ();
                    };
            assertEquals(
                    face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1.02 : -0.02,
                    normalCoordinate,
                    0.000001);
            assertTrue(point.distanceTo(new net.minecraft.world.phys.Vec3(12.5, 64.5, -9.5)) < 0.65);
        }
    }
}
