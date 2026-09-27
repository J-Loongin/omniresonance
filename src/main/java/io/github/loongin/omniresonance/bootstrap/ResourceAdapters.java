// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.bootstrap;

import io.github.loongin.omniresonance.transfer.ResourceAdapterDirectory;
import io.github.loongin.omniresonance.transfer.ResourceScope;
import net.neoforged.fml.ModList;

/** One explicit optional registration boundary shared by server services and client registry decoding. */
public final class ResourceAdapters {
    private ResourceAdapters() {}
    /** Pure installed-type check for registry selectors; no world access, capability discovery or registration. */
    public static boolean registryType(net.minecraft.resources.ResourceLocation type) {
        if (type.equals(io.github.loongin.omniresonance.transfer.ResourceTypes.ITEM)
                || type.equals(io.github.loongin.omniresonance.transfer.ResourceTypes.FLUID)) return true;
        return ModList.get() != null
                && ModList.get().isLoaded("mekanism")
                && type.equals(io.github.loongin.omniresonance.compat.mekanism.ChemicalVariant.TYPE);
    }

    /** Converts only known optional recipe ingredients; caller owns registry thread and no stack is retained. */
    public static @org.jetbrains.annotations.Nullable io.github.loongin.omniresonance.transfer.RegisteredResourceVariant
            recipeVariant(Object ingredient) {
        if (ModList.get() != null && ModList.get().isLoaded("mekanism"))
            return io.github.loongin.omniresonance.compat.mekanism.MekanismResources.recipeVariant(ingredient);
        return null;
    }

    /** Known single-identity types have no registry tag, ID or component editor. */
    public static boolean scalarType(net.minecraft.resources.ResourceLocation type) {
        return io.github.loongin.omniresonance.transfer.ResourceTypes.scalar(type);
    }

    public static ResourceAdapterDirectory create() {
        var result = new ResourceAdapterDirectory(ResourceScope.MAXIMUM_RESOURCE_TYPE_IDS);
        ResourceAdapterDirectory.registerNative(result);
        if (ModList.get() != null && ModList.get().isLoaded("mekanism"))
            io.github.loongin.omniresonance.compat.mekanism.MekanismResources.register(result);
        if (ModList.get() != null && ModList.get().isLoaded("ars_nouveau"))
            io.github.loongin.omniresonance.compat.ars.ArsResources.register(result);
        if (ModList.get() != null && ModList.get().isLoaded("industrialforegoingsouls"))
            io.github.loongin.omniresonance.compat.souls.SoulResources.register(result);
        result.freeze();
        return result;
    }
}
