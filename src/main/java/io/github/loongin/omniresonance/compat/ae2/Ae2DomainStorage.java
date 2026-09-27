// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.compat.ae2;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import io.github.loongin.omniresonance.transfer.FluidVariant;
import io.github.loongin.omniresonance.transfer.ItemVariant;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import io.github.loongin.omniresonance.transfer.ResourceVariantKey;
import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * Optional native AE storage facade. Calls run on the server thread; access owns live permission checks. Quantity
 * stays long, simulation touches no authoritative state, and unsupported/lossy identities remain in the domain.
 * Full enumeration is synchronous O(variants), as required by MEStorage; it publishes only a complete valid view.
 * No machine/player object from an AE action is treated as a substitute for the owner's interface authorization.
 */
public final class Ae2DomainStorage implements MEStorage {
    private final Ae2DomainAccess access;
    private final Supplier<HolderLookup.Provider> registries;

    public Ae2DomainStorage(Ae2DomainAccess access, Supplier<HolderLookup.Provider> registries) {
        this.access = Objects.requireNonNull(access);
        this.registries = Objects.requireNonNull(registries);
    }

    @Override
    public long insert(AEKey key, long amount, Actionable mode, IActionSource source) {
        MEStorage.checkPreconditions(key, amount, mode, source);
        if (amount == 0 || access.current() == null) return 0;
        var variant = decode(key);
        return variant == null ? 0 : access.insert(variant, amount, mode == Actionable.SIMULATE);
    }

    @Override
    public long extract(AEKey key, long amount, Actionable mode, IActionSource source) {
        MEStorage.checkPreconditions(key, amount, mode, source);
        if (amount == 0 || access.current() == null) return 0;
        var variant = decode(key);
        return variant == null ? 0 : access.extract(variant, amount, mode == Actionable.SIMULATE);
    }

    private @Nullable ResourceVariantKey decode(AEKey key) {
        try {
            if (key instanceof AEItemKey item)
                return ItemVariant.from(item.toStack(1), registries.get()).key();
            if (key instanceof AEFluidKey fluid)
                return FluidVariant.from(fluid.toStack(1), registries.get()).key();
        } catch (IllegalArgumentException unsupported) {
            return null;
        }
        return null;
    }

    private @Nullable AEKey encode(ResourceVariantKey key) {
        try {
            AEKey result = key.typeId().equals(ResourceTypes.ITEM)
                    ? AEItemKey.of(ItemVariant.restore(key, registries.get()).stack(1))
                    : key.typeId().equals(ResourceTypes.FLUID)
                            ? AEFluidKey.of(
                                    FluidVariant.restore(key, registries.get()).stack(1))
                            : null;
            return result != null && key.equals(decode(result)) ? result : null;
        } catch (IllegalArgumentException unsupported) {
            return null;
        }
    }

    @Override
    public void getAvailableStacks(KeyCounter out) {
        Objects.requireNonNull(out);
        var ledger = access.current();
        if (ledger == null) return;
        long revision = ledger.revision();
        var complete = new KeyCounter();
        ledger.enumerate(row -> {
            var key = encode(row.key());
            if (key != null) complete.add(key, row.amount());
        });
        if (access.current() != ledger || ledger.revision() != revision) return;
        for (var entry : complete) out.add(entry.getKey(), entry.getLongValue());
    }

    @Override
    public boolean isPreferredStorageFor(AEKey key, IActionSource source) {
        var ledger = access.current();
        if (ledger == null) return false;
        var variant = decode(key);
        return variant != null && ledger.amount(variant) > 0;
    }

    @Override
    public Component getDescription() {
        return Component.translatable("block.omniresonance.ae_domain_interface");
    }
}
