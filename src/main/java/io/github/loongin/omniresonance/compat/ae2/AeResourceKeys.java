// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.compat.ae2;

import appeng.api.stacks.AEKey;
import io.github.loongin.omniresonance.transfer.ResourceVariantKey;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;

/** AE-facing identity policy. Read on the registry-owning game thread; no inventory access or quantity mutation.
 * Enumerate canonical keys once. Legacy keys remain accepted aliases, never additional inventory entries. */
public final class AeResourceKeys {
    private AeResourceKeys() {}

    public static boolean nativeChemicals() {
        var mods = ModList.get();
        return mods != null && mods.isLoaded("mekanism") && mods.isLoaded("appmek");
    }
    /** Encodes a supported optional resource, rejecting invalid identity rather than guessing a fallback. */
    public static AEKey canonical(ResourceVariantKey key) {
        if (nativeChemicals() && key.typeId().equals(ResonanceKeys.CHEMICAL_RESOURCE))
            return io.github.loongin.omniresonance.compat.mekanism.AppliedChemicalKeys.encode(key);
        return ResonanceKeys.from(key);
    }
    /** Returns detached identity only; caller must still validate the installed resource adapter and authorization. */
    public static @Nullable ResourceVariantKey decode(AEKey key) {
        if (key instanceof ResonanceKey own) return own.resourceKey();
        return nativeChemicals()
                ? io.github.loongin.omniresonance.compat.mekanism.AppliedChemicalKeys.decode(key)
                : null;
    }
}
