// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.datagen;

import io.github.loongin.omniresonance.bootstrap.OmniResonanceMod;
import io.github.loongin.omniresonance.registry.ModItems;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.data.recipes.ShapedRecipeBuilder;
import net.minecraft.data.recipes.ShapelessRecipeBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;

/** Generates the completed material and physical-node recipes. */
public final class OmniRecipeProvider extends RecipeProvider {
    public OmniRecipeProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
        super(output, registries);
    }

    @Override
    protected void buildRecipes(RecipeOutput output) {
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ModItems.OMNI_DUST.get(), 9)
                .define('R', Items.REDSTONE)
                .define('D', ModItems.OMNI_DUST.get())
                .pattern("RRR")
                .pattern("RDR")
                .pattern("RRR")
                .unlockedBy("has_omni_dust", has(ModItems.OMNI_DUST.get()))
                .save(output, id("omni_dust_multiplication"));
        ShapelessRecipeBuilder.shapeless(RecipeCategory.MISC, ModItems.RESONANCE_SUBSTRATE.get(), 4)
                .requires(ModItems.OMNI_DUST.get(), 4)
                .requires(Items.REDSTONE, 4)
                .requires(Items.OBSIDIAN)
                .unlockedBy("has_omni_dust", has(ModItems.OMNI_DUST.get()))
                .save(output, id("resonance_substrate"));
        ShapelessRecipeBuilder.shapeless(RecipeCategory.MISC, ModItems.RESONANCE_CORE.get(), 64)
                .requires(Items.AMETHYST_SHARD, 4)
                .requires(ModItems.OMNI_DUST.get(), 4)
                .requires(Items.NETHER_STAR)
                .unlockedBy("has_nether_star", has(Items.NETHER_STAR))
                .save(output, id("resonance_core"));
        ShapelessRecipeBuilder.shapeless(RecipeCategory.REDSTONE, ModItems.RESONANCE_TRANSFER_NODE.get())
                .requires(ModItems.RESONANCE_SUBSTRATE.get())
                .requires(ModItems.RESONANCE_CORE.get())
                .unlockedBy("has_resonance_substrate", has(ModItems.RESONANCE_SUBSTRATE.get()))
                .save(output, id("resonance_transfer_node"));
        ShapelessRecipeBuilder.shapeless(RecipeCategory.REDSTONE, ModItems.RESONANCE_TRANSFER_PANEL.get())
                .requires(ModItems.RESONANCE_TRANSFER_NODE.get())
                .unlockedBy("has_resonance_transfer_node", has(ModItems.RESONANCE_TRANSFER_NODE.get()))
                .save(output, id("resonance_transfer_panel"));
        ShapelessRecipeBuilder.shapeless(RecipeCategory.REDSTONE, ModItems.RESONANCE_TRANSFER_NODE.get())
                .requires(ModItems.RESONANCE_TRANSFER_PANEL.get())
                .unlockedBy("has_resonance_transfer_panel", has(ModItems.RESONANCE_TRANSFER_PANEL.get()))
                .save(output, id("resonance_transfer_node_from_panel"));
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(OmniResonanceMod.MOD_ID, path);
    }
}
