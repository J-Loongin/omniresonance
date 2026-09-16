// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.github.loongin.omniresonance.bootstrap.NetworkRuntimeRegistry;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.jetbrains.annotations.Nullable;

/**
 * Explicit common-side protocol registration. Client dispatch uses only an immutable-value consumer,
 * never a client-only class reference. Registration itself does not access worlds or create networks.
 */
public final class NetworkPayloads {
    private static volatile @Nullable Consumer<NetworkTerminalResponse> clientReceiver;
    private static volatile @Nullable Consumer<DomainInventoryFrame> inventoryReceiver;

    public static void installInventoryReceiver(Consumer<DomainInventoryFrame> receiver) {
        inventoryReceiver = Objects.requireNonNull(receiver);
    }

    private static volatile @Nullable Consumer<TerminalStorageResponse> storageReceiver;

    public static void installStorageReceiver(Consumer<TerminalStorageResponse> receiver) {
        storageReceiver = Objects.requireNonNull(receiver);
    }

    private static volatile @Nullable Consumer<ChunkOverviewPage> chunkOverviewReceiver;

    public static void installChunkOverviewReceiver(Consumer<ChunkOverviewPage> receiver) {
        chunkOverviewReceiver = Objects.requireNonNull(receiver);
    }

    private static volatile @Nullable Consumer<NodeHighlightFrame> highlightReceiver;

    public static void installHighlightReceiver(Consumer<NodeHighlightFrame> receiver) {
        highlightReceiver = Objects.requireNonNull(receiver);
    }

    private static volatile @Nullable Consumer<NodeDirectoryPage> nodeDirectoryReceiver;

    public static void installNodeDirectoryReceiver(Consumer<NodeDirectoryPage> receiver) {
        nodeDirectoryReceiver = Objects.requireNonNull(receiver);
    }

    private NetworkPayloads() {}

    /**
     * Registers required version-18 PLAY payloads with main-thread handlers. The framework supplies the
     * real sending player and replies through its connection; server work never trusts a payload owner.
     * Missing client bootstrap fails explicitly rather than silently discarding successful responses.
     */
    public static void register(RegisterPayloadHandlersEvent event, NetworkRuntimeRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        NodeMenuPayloads.installTerminalTransferHandler(registry::handleTerminalTransfer);
        PayloadRegistrar registrar = event.registrar("19").executesOn(HandlerThread.MAIN);
        registrar.playToClient(NodeHighlightFrame.TYPE, NodeHighlightFrame.STREAM_CODEC, (frame, context) -> {
            var receiver = highlightReceiver;
            if (receiver == null) throw new IllegalStateException("Highlight receiver missing");
            receiver.accept(frame);
        });
        registrar.playToServer(NodeDirectoryRequest.TYPE, NodeDirectoryRequest.STREAM_CODEC, (request, context) -> {
            if (context.player() instanceof ServerPlayer player) registry.handleNodeDirectory(player, request);
        });
        registrar.playToClient(NodeDirectoryPage.TYPE, NodeDirectoryPage.STREAM_CODEC, (page, context) -> {
            var receiver = nodeDirectoryReceiver;
            if (receiver == null) throw new IllegalStateException("Node directory receiver missing");
            receiver.accept(page);
        });
        registrar.playToServer(ChunkOverviewRequest.TYPE, ChunkOverviewRequest.STREAM_CODEC, (request, context) -> {
            if (context.player() instanceof ServerPlayer player) registry.handleChunkOverview(player, request);
        });
        registrar.playToClient(ChunkOverviewPage.TYPE, ChunkOverviewPage.STREAM_CODEC, (page, context) -> {
            var receiver = chunkOverviewReceiver;
            if (receiver == null) throw new IllegalStateException("Overview client receiver missing");
            receiver.accept(page);
        });
        registrar.playToServer(TerminalStorageRequest.TYPE, TerminalStorageRequest.STREAM_CODEC, (request, context) -> {
            if (context.player() instanceof ServerPlayer player) registry.handleStorage(player, request);
        });
        registrar.playToClient(
                TerminalStorageResponse.TYPE, TerminalStorageResponse.STREAM_CODEC, (response, context) -> {
                    var receiver = storageReceiver;
                    if (receiver == null) throw new IllegalStateException("Storage client receiver was not installed");
                    receiver.accept(response);
                });
        registrar.playToServer(DomainInventoryRequest.TYPE, DomainInventoryRequest.STREAM_CODEC, (request, context) -> {
            if (context.player() instanceof ServerPlayer player) registry.handleInventory(player, request);
        });
        registrar.playToClient(DomainInventoryFrame.TYPE, DomainInventoryFrame.STREAM_CODEC, (frame, context) -> {
            var receiver = inventoryReceiver;
            if (receiver == null) throw new IllegalStateException("Inventory client receiver was not installed");
            receiver.accept(frame);
        });
        registrar.playToServer(NetworkTerminalRequest.TYPE, NetworkTerminalRequest.STREAM_CODEC, (request, context) -> {
            if (!(context.player() instanceof ServerPlayer sender)) {
                throw new IllegalStateException("Terminal request requires a server player");
            }
            NetworkTerminalResponse response = registry.handle(sender, request);
            if (response != null) {
                context.reply(response);
            }
        });
        registrar.playToClient(
                NetworkTerminalResponse.TYPE, NetworkTerminalResponse.STREAM_CODEC, (response, context) -> {
                    Consumer<NetworkTerminalResponse> receiver = clientReceiver;
                    if (receiver == null) {
                        throw new IllegalStateException("Terminal client receiver was not installed");
                    }
                    receiver.accept(response);
                });
    }

    /**
     * Installs the client-owned receiver during Dist.CLIENT bootstrap without world access or simulation.
     * The receiver runs on the client main thread and must reject closed-view responses. Null is rejected.
     */
    public static void installClientReceiver(Consumer<NetworkTerminalResponse> receiver) {
        clientReceiver = Objects.requireNonNull(receiver, "receiver");
    }
}
