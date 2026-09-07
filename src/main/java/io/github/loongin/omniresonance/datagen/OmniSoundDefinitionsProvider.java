// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.datagen;

import io.github.loongin.omniresonance.bootstrap.OmniResonanceMod;
import io.github.loongin.omniresonance.registry.ModSounds;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import net.neoforged.neoforge.common.data.SoundDefinitionsProvider;

/** Generates client sound definitions from the registered common sound identities. */
public final class OmniSoundDefinitionsProvider extends SoundDefinitionsProvider {
    /** Creates a data-run provider without retaining or accessing world state. */
    public OmniSoundDefinitionsProvider(PackOutput output, ExistingFileHelper helper) {
        super(output, OmniResonanceMod.MOD_ID, helper);
    }

    @Override
    public void registerSounds() {
        add(
                ModSounds.UI_CLICK,
                definition()
                        .subtitle("omniresonance.subtitle.ui_click")
                        .with(sound(ModSounds.UI_CLICK_ID).preload()));
    }
}
