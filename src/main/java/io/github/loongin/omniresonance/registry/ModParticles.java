// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.registry;

import io.github.loongin.omniresonance.bootstrap.OmniResonanceMod;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Common particle identities only; providers and visual lifetime belong to the client. */
public final class ModParticles {
    private static final DeferredRegister<ParticleType<?>> PARTICLES =
            DeferredRegister.create(BuiltInRegistries.PARTICLE_TYPE, OmniResonanceMod.MOD_ID);
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> RESONANCE_MOTE =
            PARTICLES.register("resonance_mote", () -> new SimpleParticleType(false));

    private ModParticles() {}

    /** Attaches deferred registrations without world access, simulation, or client class loading. */
    public static void register(IEventBus modBus) {
        PARTICLES.register(modBus);
    }
}
