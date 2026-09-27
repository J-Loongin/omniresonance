// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import java.math.BigDecimal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.inventory.InventoryMenu;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.FluidStack;

/** Client-only native fluid sprite/tint and bucket-unit presentation; authoritative quantities remain integral mB. */
final class DomainFluidDisplay {
    private DomainFluidDisplay() {}

    static String exactBuckets(long milliBuckets) {
        if (milliBuckets < 0) throw new IllegalArgumentException("Negative fluid amount");
        return BigDecimal.valueOf(milliBuckets, 3).stripTrailingZeros().toPlainString();
    }

    static String compact(long milliBuckets) {
        return (milliBuckets < 1000000 ? exactBuckets(milliBuckets) : DomainInventoryView.compact(milliBuckets / 1000))
                + "B";
    }

    static String slotQuantity(long milliBuckets) {
        return milliBuckets < 1000 ? exactBuckets(milliBuckets) : DomainInventoryView.compact(milliBuckets / 1000);
    }

    static void render(GuiGraphics graphics, FluidStack stack, int x, int y) {
        var extension = IClientFluidTypeExtensions.of(stack.getFluid());
        render(graphics, extension.getStillTexture(stack), extension.getTintColor(stack), x, y);
    }

    static void render(GuiGraphics graphics, net.minecraft.resources.ResourceLocation texture, int tint, int x, int y) {
        var sprite = Minecraft.getInstance()
                .getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                .apply(texture);
        graphics.blit(
                x,
                y,
                0,
                16,
                16,
                sprite,
                ((tint >>> 16) & 255) / 255f,
                ((tint >>> 8) & 255) / 255f,
                (tint & 255) / 255f,
                1f);
    }
}
