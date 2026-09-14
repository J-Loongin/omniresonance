// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

/** Immutable, thread-independent FE identity; quantities are carried separately. */
public enum EnergyVariant implements ResourceVariant {
    INSTANCE;
    private final ResourceVariantKey key = new ResourceVariantKey(ResourceTypes.ENERGY, new byte[0]);

    @Override
    public ResourceVariantKey key() {
        return key;
    }
}
