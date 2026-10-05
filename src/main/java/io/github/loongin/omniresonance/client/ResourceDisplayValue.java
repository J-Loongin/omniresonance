// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.transfer.FluidVariant;
import io.github.loongin.omniresonance.transfer.ItemVariant;
import io.github.loongin.omniresonance.transfer.ResourceAdapterDirectory;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import io.github.loongin.omniresonance.transfer.ResourceVariantKey;
import net.minecraft.core.HolderLookup;
import org.jetbrains.annotations.Nullable;

/** Client-thread display reconstruction. Native stacks reuse the owned snapshot produced by strict validation;
 * optional resource types retain their registered decoder. No authority, cross-view cache or quantity prediction. */
final class ResourceDisplayValue {
    private ResourceDisplayValue() {}

    static @Nullable Object decode(
            ResourceVariantKey key, ResourceAdapterDirectory adapters, HolderLookup.Provider provider) {
        try {
            if (key.typeId().equals(ResourceTypes.ITEM)) return ItemVariant.restoreStack(key, provider);
            if (key.typeId().equals(ResourceTypes.FLUID)) return FluidVariant.restoreStack(key, provider);
            return adapters.decode(key, provider).orElse(null);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }
}
