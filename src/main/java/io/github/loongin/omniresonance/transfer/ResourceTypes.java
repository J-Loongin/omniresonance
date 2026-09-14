// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/**
 * Built-in stable resource type identities and their editor-facing exact-batch samples. These immutable values are
 * thread-safe, own no external state, perform no resource simulation or mutation, and reject a null lookup ID.
 */
public final class ResourceTypes {
    public static final ResourceLocation ITEM = ResourceLocation.withDefaultNamespace("item");
    public static final ResourceLocation FLUID = ResourceLocation.withDefaultNamespace("fluid");
    public static final ResourceLocation ENERGY = ResourceLocation.fromNamespaceAndPath("neoforge", "energy");

    private ResourceTypes() {}

    /**
     * Returns the exact-batch sample for a built-in type, or one for an opaque valid type. This pure lookup is
     * thread-safe, retains no caller value, performs no simulation or mutation, and rejects null.
     */
    public static long defaultExactBatchSize(ResourceLocation resourceTypeId) {
        Objects.requireNonNull(resourceTypeId, "resourceTypeId");
        if (ITEM.equals(resourceTypeId)) return 64;
        if (FLUID.equals(resourceTypeId)) return 1000;
        if (ENERGY.equals(resourceTypeId)) return 10000;
        return 1;
    }
}
