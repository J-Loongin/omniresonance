// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.networking.NodeMenuPayloads;
import io.github.loongin.omniresonance.networking.NodeMenuResponse;
import io.github.loongin.omniresonance.registry.ModMenus;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/** Client-only node Screen registration and active-Menu response routing. */
final class ResonanceNodeClient {
    ResonanceNodeClient(IEventBus modBus) {
        modBus.addListener(this::registerScreens);
        NodeMenuPayloads.installClientReceiver(this::receive);
    }

    private void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.RESONANCE_NODE.get(), ResonanceNodeScreen::new);
    }

    private void receive(NodeMenuResponse response) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof ResonanceNodeScreen screen && screen.matches(response)) {
            screen.applyResponse(response);
        }
    }
}
