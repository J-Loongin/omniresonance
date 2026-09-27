// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.compat.jei;

import com.mojang.serialization.Codec;
import io.github.loongin.omniresonance.client.EnergyDisplay;
import io.github.loongin.omniresonance.transfer.EnergyVariant;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import java.util.List;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientRenderer;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.registration.IModIngredientRegistration;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.TooltipFlag;

/** A logical FE ingredient for bookmarking and typed preset drops, without registering a fake item or fluid. */
final class JeiEnergyIngredient implements IIngredientHelper<EnergyVariant>, IIngredientRenderer<EnergyVariant> {
    private static final IIngredientType<EnergyVariant> TYPE = new IIngredientType<>() {
        public Class<? extends EnergyVariant> getIngredientClass() {
            return EnergyVariant.class;
        }

        public String getUid() {
            return "omniresonance:energy";
        }
    };

    static void register(IModIngredientRegistration registration) {
        var helper = new JeiEnergyIngredient();
        registration.register(
                TYPE, List.of(EnergyVariant.INSTANCE), helper, helper, Codec.unit(EnergyVariant.INSTANCE));
    }

    public IIngredientType<EnergyVariant> getIngredientType() {
        return TYPE;
    }

    public String getDisplayName(EnergyVariant ingredient) {
        return EnergyDisplay.name().getString();
    }

    // JEI 19 still requires this legacy abstract method; getUid is the modern identity entry point.
    @Deprecated(forRemoval = true)
    @SuppressWarnings("removal")
    public String getUniqueId(EnergyVariant ingredient, UidContext context) {
        return "neoforge:energy";
    }

    public Object getUid(EnergyVariant ingredient, UidContext context) {
        return ResourceTypes.ENERGY;
    }

    public ResourceLocation getResourceLocation(EnergyVariant ingredient) {
        return ResourceTypes.ENERGY;
    }

    public EnergyVariant copyIngredient(EnergyVariant ingredient) {
        return ingredient;
    }

    public String getErrorInfo(EnergyVariant ingredient) {
        return "neoforge:energy";
    }

    public void render(GuiGraphics graphics, EnergyVariant ingredient) {
        EnergyDisplay.render(graphics, 0, 0);
    }

    // Required legacy abstract renderer contract in the supported JEI 19 API.
    @Deprecated(forRemoval = true)
    @SuppressWarnings("removal")
    public List<Component> getTooltip(EnergyVariant ingredient, TooltipFlag flag) {
        return List.of(EnergyDisplay.name());
    }
}
