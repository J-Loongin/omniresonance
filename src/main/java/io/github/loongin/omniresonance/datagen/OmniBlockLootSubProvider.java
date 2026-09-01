// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.datagen;

import io.github.loongin.omniresonance.registry.ModBlocks;
import java.util.List;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.loot.BlockLootSubProvider;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/** Generates plain self-drops for public physical nodes without copying block-entity data. */
public final class OmniBlockLootSubProvider extends BlockLootSubProvider {
    public OmniBlockLootSubProvider(HolderLookup.Provider registries) {
        super(Set.<Item>of(), FeatureFlags.REGISTRY.allFlags(), registries);
    }

    @Override
    protected void generate() {
        dropSelf(ModBlocks.RESONANCE_TRANSFER_NODE.get());
        dropSelf(ModBlocks.RESONANCE_TRANSFER_PANEL.get());
    }

    @Override
    protected Iterable<Block> getKnownBlocks() {
        return List.of(ModBlocks.RESONANCE_TRANSFER_NODE.get(), ModBlocks.RESONANCE_TRANSFER_PANEL.get());
    }
}
