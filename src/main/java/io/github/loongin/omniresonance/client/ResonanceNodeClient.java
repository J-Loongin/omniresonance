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
    private static final NodePolicySaves SAVES = new NodePolicySaves();

    static NodePolicySaves saves() {
        return SAVES;
    }

    ResonanceNodeClient(IEventBus modBus) {
        modBus.addListener(this::registerScreens);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.ClientTickEvent.Post event) ->
                        SAVES.tick(net.neoforged.neoforge.network.PacketDistributor::sendToServer, this::timedOut));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) -> SAVES.clear());
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingIn event) -> SAVES.clear());
        NodeMenuPayloads.installClientReceiver(this::receive);
        NodeMenuPayloads.installChunkStatusReceiver(status -> {
            if (Minecraft.getInstance().screen instanceof ResonanceNodeScreen screen) screen.applyChunkStatus(status);
        });
        NodeMenuPayloads.installTransferReceiver(message -> {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.screen instanceof ResonanceNodeScreen screen) screen.applyTransfer(message);
        });
    }

    private void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.RESONANCE_NODE.get(), this::createScreen);
    }

    private ResonanceNodeScreen createScreen(
            io.github.loongin.omniresonance.node.ResonanceNodeMenu menu,
            net.minecraft.world.entity.player.Inventory inventory,
            net.minecraft.network.chat.Component title) {
        var screen = new ResonanceNodeScreen(menu, inventory, title);
        if (menu.remoteConfiguration() && Minecraft.getInstance().screen instanceof NetworkSetupScreen parent) {
            parent.suspendForConfiguration();
            screen.terminalParent(parent);
        }
        return screen;
    }

    private void receive(NodeMenuResponse response) {
        var completed = SAVES.receive(response);
        deliver(response, completed);
    }

    static void sendSaveRequest(io.github.loongin.omniresonance.networking.NodeMenuRequest request) {
        try {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(request);
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(ResonanceNodeClient.class)
                    .warn("Node save request transport failed; no automatic retry", failure);
            var result = SAVES.failed();
            deliver(result.response(), result);
        }
    }

    private void timedOut(NodePolicySaves.Completion completion) {
        deliver(completion.response(), completion);
    }

    private static void deliver(
            NodeMenuResponse response, @org.jetbrains.annotations.Nullable NodePolicySaves.Completion completed) {
        Minecraft minecraft = Minecraft.getInstance();
        ResonanceNodeScreen target =
                minecraft.screen instanceof ResonanceNodeScreen screen && screen.matches(response) ? screen : null;
        if (target != null) target.applyResponse(response);
        if (completed != null && response instanceof NodeMenuResponse.Failure) {
            var message = net.minecraft.network.chat.Component.translatable(
                    completed.uncertain()
                            ? "omniresonance.node_menu.save_unknown"
                            : "omniresonance.node_menu.save_failed");
            if (target != null && minecraft.screen == target) target.saveFeedback(message);
            else if (minecraft.player != null) minecraft.player.displayClientMessage(message, false);
        }
    }
}
