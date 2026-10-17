// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.transfer.RegisteredResourceVariant;
import io.github.loongin.omniresonance.transfer.ScalarResourceVariant;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

/** Client-owned detached display sample; no quantities, capabilities or authoritative filter decisions. */
record RulePreviewImage(
        ItemStack item,
        FluidStack fluid,
        @Nullable ResourceLocation texture,
        int tint,
        @Nullable Component name) {
    static RulePreviewImage item(ItemStack item) {
        return new RulePreviewImage(item.copyWithCount(1), FluidStack.EMPTY, null, -1, item.getHoverName());
    }

    static RulePreviewImage fluid(FluidStack fluid) {
        return new RulePreviewImage(ItemStack.EMPTY, fluid.copyWithAmount(1000), null, -1, fluid.getHoverName());
    }

    static RulePreviewImage registered(RegisteredResourceVariant resource) {
        return new RulePreviewImage(
                ItemStack.EMPTY, FluidStack.EMPTY, resource.texture(), resource.tint(), resource.displayName());
    }

    static RulePreviewImage scalar(ScalarResourceVariant resource) {
        var item = resource.texture() == null
                ? new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(resource.iconItem()))
                : ItemStack.EMPTY;
        return new RulePreviewImage(
                item, FluidStack.EMPTY, resource.texture(), resource.tint(), resource.displayName());
    }

    static RulePreviewImage status(String key) {
        ItemStack item =
                switch (key) {
                    case "loading" -> new ItemStack(Items.CLOCK);
                    case "reference" -> new ItemStack(Items.CHAIN);
                    case "unavailable" -> new ItemStack(Items.BARRIER);
                    default -> ItemStack.EMPTY;
                };
        return new RulePreviewImage(item, FluidStack.EMPTY, null, -1, null);
    }

    void render(GuiGraphics graphics, int x, int y) {
        if (!item.isEmpty()) graphics.renderItem(item, x, y);
        else if (!fluid.isEmpty()) DomainFluidDisplay.render(graphics, fluid, x, y);
        else if (texture != null) DomainFluidDisplay.render(graphics, texture, tint, x, y);
        else TerminalGlyph.HELP.render(graphics, x + 2, y + 2, TerminalTheme.DISABLED_TEXT);
    }
}
