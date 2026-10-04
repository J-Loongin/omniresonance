// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.datagen;

import io.github.loongin.omniresonance.bootstrap.OmniResonanceMod;
import io.github.loongin.omniresonance.registry.ModParticles;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import net.neoforged.neoforge.common.data.ParticleDescriptionProvider;

/** Reproducible particle atlas descriptions, validated against the independent production sprites. */
public final class OmniParticleDescriptions extends ParticleDescriptionProvider {
    /** Creates a data-run provider without accessing world state or modifying source textures. */
    public OmniParticleDescriptions(PackOutput output, ExistingFileHelper fileHelper) {
        super(output, fileHelper);
    }

    @Override
    protected void addDescriptions() {
        sprite(
                ModParticles.RESONANCE_MOTE.get(),
                ResourceLocation.fromNamespaceAndPath(OmniResonanceMod.MOD_ID, "resonance_mote"));
    }
}
