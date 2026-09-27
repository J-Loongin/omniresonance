// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.compat.mekanism;

import appeng.api.stacks.AEKey;
import io.github.loongin.omniresonance.transfer.ResourceVariantKey;
import me.ramidzkh.mekae2.ae2.MekanismKey;
import org.jetbrains.annotations.Nullable;

/** Optional lossless identity conversion using the addon's actual public key. No inventory or quantities are copied. */
public final class AppliedChemicalKeys {
    private AppliedChemicalKeys() {}

    public static AEKey encode(ResourceVariantKey key) {
        return MekanismKey.of(ChemicalVariant.restore(key).stack(1));
    }

    public static @Nullable ResourceVariantKey decode(AEKey key) {
        return key instanceof MekanismKey chemical
                ? ChemicalVariant.from(chemical.getStack()).key()
                : null;
    }
}
