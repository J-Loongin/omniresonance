// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.compat.ars;

import io.github.loongin.omniresonance.transfer.ResourceVariantKey;
import io.github.loongin.omniresonance.transfer.ScalarResourceVariant;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Immutable Source identity, separate from player spell mana and from jar item contents. */
public enum SourceVariant implements ScalarResourceVariant {
    INSTANCE;
    public static final ResourceLocation TYPE = io.github.loongin.omniresonance.transfer.ResourceTypes.SOURCE;
    private static final ResourceVariantKey KEY = new ResourceVariantKey(TYPE, new byte[0]);

    public ResourceVariantKey key() {
        return KEY;
    }

    public Component displayName() {
        return Component.translatable("omniresonance.resource_type.ars_nouveau.source");
    }

    public ResourceLocation iconItem() {
        return ResourceLocation.parse("ars_nouveau:source_jar");
    }

    @Override
    public ResourceLocation texture() {
        return ResourceLocation.parse("ars_nouveau:block/source_still");
    }

    public String unit() {
        return "Source";
    }

    public static SourceVariant restore(ResourceVariantKey key) {
        if (!KEY.equals(key)) throw new IllegalArgumentException("Invalid Source identity");
        return INSTANCE;
    }
}
