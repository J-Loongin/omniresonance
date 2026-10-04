// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderTooltipEvent;
import org.jetbrains.annotations.Nullable;

/** Uses the supported tooltip color hook only while a resonance screen owns the presentation. */
@EventBusSubscriber(modid = "omniresonance", value = Dist.CLIENT)
public final class StarFissureTooltips {
    private StarFissureTooltips() {}

    static boolean owns(@Nullable Screen screen) {
        return screen instanceof NetworkSetupScreen
                || screen instanceof ResonanceNodeScreen
                || screen instanceof ExchangeScreen
                || screen instanceof Ae2InterfaceScreen;
    }

    @SubscribeEvent
    public static void colors(RenderTooltipEvent.Color event) {
        if (!owns(Minecraft.getInstance().screen)) return;
        event.setBackground(TerminalTheme.INPUT);
        event.setBorderStart(TerminalTheme.LINE);
        event.setBorderEnd(TerminalTheme.LINE);
    }
}
