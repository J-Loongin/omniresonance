// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.datagen;

import java.util.List;
import java.util.Set;
import net.minecraft.data.loot.LootTableProvider;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.neoforged.neoforge.data.event.GatherDataEvent;

/** Installs reproducible data providers for completed content only. */
public final class OmniDataGenerators {
    private OmniDataGenerators() {}

    /** Registers providers for the current mod data run without accessing a live world. */
    public static void gatherData(GatherDataEvent event) {
        event.createProvider(OmniRecipeProvider::new);
        event.createProvider(OmniAeInterfaceData::new);
        event.createProvider(output -> new OmniItemModelProvider(output, event.getExistingFileHelper()));
        event.createProvider(output -> new OmniBlockStateProvider(output, event.getExistingFileHelper()));
        event.createProvider(output -> new OmniSoundDefinitionsProvider(output, event.getExistingFileHelper()));
        event.createProvider(output -> new OmniParticleDescriptions(output, event.getExistingFileHelper()));
        event.createProvider((output, registries) -> new LootTableProvider(
                output,
                Set.of(),
                List.of(new LootTableProvider.SubProviderEntry(
                        OmniBlockLootSubProvider::new, LootContextParamSets.BLOCK)),
                registries));
    }
}
