// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.compat.mekanism;

import io.github.loongin.omniresonance.transfer.ResourceAdapterDirectory;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.IChemicalHandler;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.ItemCapability;

/** Explicit optional bootstrap; only public API types and capability identities, no implementation classes. */
public final class MekanismResources {
    public static final BlockCapability<IChemicalHandler, Direction> BLOCK =
            BlockCapability.createSided(ResourceLocation.parse("mekanism:chemical_handler"), IChemicalHandler.class);
    public static final ItemCapability<IChemicalHandler, Void> ITEM =
            ItemCapability.createVoid(ResourceLocation.parse("mekanism:chemical_handler"), IChemicalHandler.class);

    private MekanismResources() {}

    public static @org.jetbrains.annotations.Nullable ChemicalVariant recipeVariant(Object ingredient) {
        return ingredient instanceof mekanism.api.chemical.ChemicalStack stack && !stack.isEmpty()
                ? ChemicalVariant.from(stack)
                : null;
    }

    /** Stable registry identity for AE indexing; read on the owning game thread without capability access. */
    public static @org.jetbrains.annotations.Nullable Object primaryIdentity(ResourceLocation id) {
        return MekanismAPI.CHEMICAL_REGISTRY.containsKey(id) ? MekanismAPI.CHEMICAL_REGISTRY.get(id) : null;
    }

    public static void register(ResourceAdapterDirectory directory) {
        directory.register(
                new ResourceAdapterDirectory.Descriptor(ChemicalVariant.TYPE, "mB", 1000),
                BLOCK,
                (handler, provider) -> new ChemicalResourcePort(handler),
                (key, provider) -> ChemicalVariant.restore(key));
        directory.registerTagRegistry(ChemicalVariant.TYPE, MekanismAPI.CHEMICAL_REGISTRY_NAME);
        directory.registerCarrier(
                ChemicalVariant.TYPE, ITEM, (handler, provider) -> new ChemicalResourcePort(handler, true));
    }
}
