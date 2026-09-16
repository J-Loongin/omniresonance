// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.loongin.omniresonance.bootstrap.OmniResonanceMod;
import io.github.loongin.omniresonance.networking.NetworkPayloads;
import io.github.loongin.omniresonance.networking.NetworkTerminalRequest;
import io.github.loongin.omniresonance.networking.NetworkTerminalResponse;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.IKeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyModifier;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * Client-only terminal bootstrap and connection-lifetime response bridge.
 *
 * <p>All methods run on the Minecraft client thread. This class owns no authoritative records, performs no
 * simulation, and sends only explicit user intents. FML constructs it only on {@link Dist#CLIENT}; common protocol
 * registration therefore retains no client-class dependency on a dedicated server.
 */
@Mod(value = OmniResonanceMod.MOD_ID, dist = Dist.CLIENT)
public final class NetworkTerminalClient {
    private static final String KEY_NAME = "key.omniresonance.network_terminal";
    private static final String KEY_CATEGORY = "key.categories.omniresonance";
    private static final IKeyConflictContext TERMINAL_CONTEXT = new TerminalConflictContext();
    private static final KeyMapping TERMINAL_KEY = new KeyMapping(
            KEY_NAME, TERMINAL_CONTEXT, KeyModifier.SHIFT, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_E, KEY_CATEGORY);

    private boolean connected;
    private boolean firstPromptDismissed;
    private final ResonanceNodeClient nodeClient;

    /** Installs client events and the immutable protocol response consumer without accessing a world. */
    public NetworkTerminalClient(IEventBus modBus) {
        nodeClient = new ResonanceNodeClient(modBus);
        new NodeHighlightClient();
        modBus.addListener(this::registerKeyMappings);
        NeoForge.EVENT_BUS.addListener(this::onClientTick);
        NeoForge.EVENT_BUS.addListener(this::onLoggingIn);
        NeoForge.EVENT_BUS.addListener(this::onLoggingOut);
        NeoForge.EVENT_BUS.addListener(this::onTagsUpdated);
        NetworkPayloads.installClientReceiver(this::receive);
        NetworkPayloads.installNodeDirectoryReceiver(page -> {
            if (Minecraft.getInstance().screen instanceof NetworkSetupScreen screen) screen.receiveNodeDirectory(page);
        });
        NetworkPayloads.installChunkOverviewReceiver(page -> {
            if (Minecraft.getInstance().screen instanceof NetworkSetupScreen screen) screen.receiveChunkOverview(page);
        });
        NetworkPayloads.installStorageReceiver(response -> {
            if (Minecraft.getInstance().screen instanceof NetworkSetupScreen screen) screen.receiveStorage(response);
        });
        NetworkPayloads.installInventoryReceiver(frame -> {
            if (Minecraft.getInstance().screen instanceof NetworkSetupScreen screen) screen.receiveInventory(frame);
        });
        io.github.loongin.omniresonance.networking.NodeMenuPayloads.installTerminalTransferReceiver(message -> {
            if (Minecraft.getInstance().screen instanceof NetworkSetupScreen screen)
                screen.receiveFilterTransfer(message);
        });
    }

    boolean isTerminalKey(int keyCode, int scanCode) {
        return TERMINAL_KEY.matches(keyCode, scanCode) && TERMINAL_KEY.isConflictContextAndModifierActive();
    }

    Component translatedKey() {
        return terminalKeyText();
    }

    static Component terminalKeyText() {
        return TERMINAL_KEY.getTranslatedKeyMessage();
    }

    boolean canSend() {
        return connected && Minecraft.getInstance().getConnection() != null;
    }

    boolean send(NetworkTerminalRequest request) {
        if (!canSend()) {
            return false;
        }
        PacketDistributor.sendToServer(request);
        return true;
    }

    void sendStorage(io.github.loongin.omniresonance.networking.TerminalStorageRequest request) {
        if (canSend()) PacketDistributor.sendToServer(request);
    }

    void sendNodeDirectory(io.github.loongin.omniresonance.networking.NodeDirectoryRequest request) {
        if (canSend()) net.neoforged.neoforge.network.PacketDistributor.sendToServer(request);
    }

    void sendChunkOverview(io.github.loongin.omniresonance.networking.ChunkOverviewRequest request) {
        if (canSend()) PacketDistributor.sendToServer(request);
    }

    boolean sendInventory(io.github.loongin.omniresonance.networking.DomainInventoryRequest request) {
        if (!canSend()) return false;
        PacketDistributor.sendToServer(request);
        return true;
    }

    boolean firstPromptDismissed() {
        return firstPromptDismissed;
    }

    void dismissFirstPrompt() {
        firstPromptDismissed = true;
    }

    void retry(NetworkSetupScreen current) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != current || !canSend()) {
            return;
        }
        TerminalInteractionPolicy.RetrySnapshot snapshot = current.retrySnapshot();
        current.closeForReplacement();
        minecraft.setScreen(new NetworkSetupScreen(this, snapshot));
    }

    private void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(TERMINAL_KEY);
    }

    private void onClientTick(ClientTickEvent.Post event) {
        boolean clicked = false;
        while (TERMINAL_KEY.consumeClick()) {
            clicked = true;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (clicked && canSend() && minecraft.level != null && minecraft.player != null && minecraft.screen == null) {
            minecraft.setScreen(new NetworkSetupScreen(this));
        }
    }

    private void onTagsUpdated(net.neoforged.neoforge.event.TagsUpdatedEvent event) {
        if (event.getUpdateCause() != net.neoforged.neoforge.event.TagsUpdatedEvent.UpdateCause.CLIENT_PACKET_RECEIVED)
            return;
        Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().screen instanceof NetworkSetupScreen screen)
                screen.invalidateInventoryMetadata();
        });
    }

    private void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        connected = true;
        firstPromptDismissed = false;
    }

    private void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        connected = false;
        TerminalTagClipboard.clear();
        firstPromptDismissed = false;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof NetworkSetupScreen screen) {
            screen.disconnected();
        }
    }

    private void receive(NetworkTerminalResponse response) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof NetworkSetupScreen screen && screen.matchesView(response.viewId())) {
            screen.applyResponse(response);
            return;
        }
        if (minecraft.screen instanceof ResonanceNodeScreen child
                && child.terminalParent() != null
                && child.terminalParent().matchesView(response.viewId())) {
            child.terminalParent().applyResponse(response);
            return;
        }
        if (response instanceof NetworkTerminalResponse.Success success && success.sequence() == 0 && canSend()) {
            send(new NetworkTerminalRequest.Close(success.viewId(), success.sessionId()));
        }
    }

    private static final class TerminalConflictContext implements IKeyConflictContext {
        @Override
        public boolean isActive() {
            Minecraft minecraft = Minecraft.getInstance();
            return minecraft.level != null
                    && (minecraft.screen == null || minecraft.screen instanceof NetworkSetupScreen);
        }

        @Override
        public boolean conflicts(IKeyConflictContext other) {
            return other == this || other == KeyConflictContext.IN_GAME || other == KeyConflictContext.GUI;
        }
    }
}
