// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.compat.souls;

import io.github.loongin.omniresonance.transfer.ResourceVariantKey;
import io.github.loongin.omniresonance.transfer.ScalarResourceVariant;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Immutable warden soul identity, separate from Essence fluid and machine acceleration time. */
public enum SoulVariant implements ScalarResourceVariant {
    INSTANCE;
    public static final ResourceLocation TYPE = io.github.loongin.omniresonance.transfer.ResourceTypes.SOUL;
    private static final ResourceVariantKey KEY = new ResourceVariantKey(TYPE, new byte[0]);

    public ResourceVariantKey key() {
        return KEY;
    }

    public Component displayName() {
        return Component.translatable("omniresonance.resource_type.industrialforegoingsouls.soul");
    }

    public ResourceLocation iconItem() {
        return ResourceLocation.parse("industrialforegoingsouls:soul_laser_base");
    }

    @Override
    public ResourceLocation texture() {
        return ResourceLocation.parse("minecraft:block/water_still");
    }

    @Override
    public int tint() {
        return 0xFF20CFC9;
    }

    public String unit() {
        return "Soul";
    }

    public static SoulVariant restore(ResourceVariantKey key) {
        if (!KEY.equals(key)) throw new IllegalArgumentException("Invalid soul identity");
        return INSTANCE;
    }
}
