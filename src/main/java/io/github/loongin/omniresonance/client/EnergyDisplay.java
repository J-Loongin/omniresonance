// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Client-only energy visual shared by the domain and JEI. It is a numeric FE resource, never a fluid identity. */
public final class EnergyDisplay {
    public static final ResourceLocation TEXTURE = ResourceLocation.withDefaultNamespace("block/water_still");
    public static final int TINT = 0xFF50E060;

    private EnergyDisplay() {}

    public static Component name() {
        return Component.translatable("omniresonance.resource_policy.type.energy");
    }

    public static void render(GuiGraphics graphics, int x, int y) {
        DomainFluidDisplay.render(graphics, TEXTURE, TINT, x, y);
    }
}
