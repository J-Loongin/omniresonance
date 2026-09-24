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

    record Ingredient(ResourceLocation type, ResourceLocation id) {
        public Ingredient {
            if (!type.equals(ResourceTypes.ITEM) && !type.equals(ResourceTypes.FLUID))
                throw new IllegalArgumentException("Unsupported ghost resource type");
            java.util.Objects.requireNonNull(id);
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
        return null;
    }
}
