// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Optional registry-backed identity. Read on the owning game thread; getters do not mutate or simulate.
 * Presentation is read only on the client thread from its own registry instance. Returned tags are detached. */
public interface RegisteredResourceVariant extends ResourceVariant {
    ResourceLocation resourceId();

    Component displayName();

    ResourceLocation texture();

    int tint();

    List<ResourceLocation> tags();

    /** Returns detached native brief descriptions on the client game thread. Read-only, no simulation or
     * authoritative mutation; failures propagate to the caller for presentation fallback. */
    default List<Component> tooltipLines(net.minecraft.world.item.Item.TooltipContext context) {
        return List.of();
    }

    /** Returns a detached native recipe-viewer ingredient, without native capabilities or quantity mutation. */
    Object recipeIngredient();
}
