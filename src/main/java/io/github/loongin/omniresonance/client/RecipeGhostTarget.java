// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.transfer.ResourceTypes;
import java.util.UUID;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

/** Client-thread ghost bridge. Preview is read-only; acceptance requests an unsaved draft, never moves resources. */
public interface RecipeGhostTarget {
    record Area(int x, int y, int width, int height) {}

    record Target(UUID network, UUID preset, Area area) {}

    /** Immutable display snapshot; retains no screen, inventory or live layout references. */
    record Geometry(Area area, int screenWidth, int screenHeight) {}

    /**
     * Client-thread adapter snapshot. Before screen initialization, returns null without reading layout. Otherwise
     * samples bounds exactly once and returns detached native coordinates; does not resize UI or modify resources.
     */
    static @Nullable Geometry geometry(int width, int height, java.util.function.Supplier<Area> bounds) {
        if (width < 1 || height < 1 || width >= 1000000000 || height >= 1000000000) return null;
        var area = bounds.get();
        if (area == null
                || area.width() < 1
                || area.height() < 1
                || area.width() >= 1000000000
                || area.height() >= 1000000000
                || area.x() <= -1000000000
                || area.x() >= 1000000000
                || area.y() <= -1000000000
                || area.y() >= 1000000000) return null;
        return new Geometry(area, width, height);
    }

    /** Detached hovered ingredient and its exact native screen hit box; no server inventory reference escapes. */
    record Hover(Object value, Area area) {}

    default @Nullable Hover recipeHover(double mouseX, double mouseY) {
        return null;
    }

    record Ingredient(ResourceLocation type, ResourceLocation id) {
        public Ingredient {
            if (!type.equals(ResourceTypes.ENERGY)
                    && !io.github.loongin.omniresonance.bootstrap.ResourceAdapters.registryType(type))
                throw new IllegalArgumentException("Unsupported ghost resource type");
            java.util.Objects.requireNonNull(id);
            if (type.equals(ResourceTypes.ENERGY) && !id.equals(ResourceTypes.ENERGY))
                throw new IllegalArgumentException("Invalid FE ingredient identity");
        }
    }
    /** Returns immutable current target metadata, or null when editing is unavailable. */
    @Nullable
    Target ghostTarget();
    /** Revalidates captured context and requests a draft; false leaves state unchanged. */
    boolean acceptGhost(Target target, Ingredient ingredient);
    /** Returns a value snapshot of the GUI bounds on the client thread. */
    Area recipeGuiBounds();
    /** Screen registration token without exposing the terminal implementation. */
    static Class<? extends Screen> screenType() {
        return NetworkSetupScreen.class;
    }
    /** Copies only registry identity; does not retain or mutate the supplied stack. */
    static @Nullable Ingredient ingredient(Object value) {
        if (value instanceof ItemStack stack && !stack.isEmpty())
            return new Ingredient(ResourceTypes.ITEM, BuiltInRegistries.ITEM.getKey(stack.getItem()));
        if (value instanceof FluidStack stack && !stack.isEmpty())
            return new Ingredient(ResourceTypes.FLUID, BuiltInRegistries.FLUID.getKey(stack.getFluid()));
        if (value == io.github.loongin.omniresonance.transfer.EnergyVariant.INSTANCE)
            return new Ingredient(ResourceTypes.ENERGY, ResourceTypes.ENERGY);
        var optional = io.github.loongin.omniresonance.bootstrap.ResourceAdapters.recipeVariant(value);
        return optional == null ? null : new Ingredient(optional.key().typeId(), optional.resourceId());
    }
}
