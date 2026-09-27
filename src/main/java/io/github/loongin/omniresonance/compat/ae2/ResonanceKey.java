// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.compat.ae2;

import appeng.api.stacks.AEKey;
import io.github.loongin.omniresonance.bootstrap.ResourceAdapters;
import io.github.loongin.omniresonance.transfer.ResourceVariantKey;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Immutable independent AE identity. Quantities remain in MEStorage and never become physical item drops. */
public abstract sealed class ResonanceKey extends AEKey
        permits ResonanceKeys.Energy, ResonanceKeys.Source, ResonanceKeys.Soul, ResonanceKeys.Chemical {
    private static final io.github.loongin.omniresonance.transfer.ResourceAdapterDirectory ADAPTERS =
            ResourceAdapters.create();

    public abstract ResourceVariantKey resourceKey();

    @Override
    public final AEKey dropSecondary() {
        return this;
    }

    @Override
    public Object getPrimaryKey() {
        return getId();
    }

    @Override
    public final boolean hasComponents() {
        return false;
    }

    @Override
    public final void addDrops(long amount, List<ItemStack> drops, Level level, BlockPos pos) {
        // These resources have no physical dropped-item representation. Interface removal never removes domain stock.
    }

    @Override
    protected final Component computeDisplayName() {
        var registries =
                HolderLookup.Provider.create(BuiltInRegistries.REGISTRY.stream().map(r -> r.asLookup()));
        var value = ADAPTERS.decode(resourceKey(), registries).orElse(null);
        if (value instanceof io.github.loongin.omniresonance.transfer.RegisteredResourceVariant registered)
            return registered.displayName();
        if (value instanceof io.github.loongin.omniresonance.transfer.ScalarResourceVariant scalar)
            return scalar.displayName();
        if (this instanceof ResonanceKeys.Energy)
            return Component.translatable("omniresonance.resource_policy.type.energy");
        return Component.literal(getId().toString());
    }

    @Override
    public final boolean equals(Object other) {
        return other != null && getClass() == other.getClass() && getId().equals(((ResonanceKey) other).getId());
    }

    @Override
    public final int hashCode() {
        return 31 * getClass().hashCode() + getId().hashCode();
    }
}
