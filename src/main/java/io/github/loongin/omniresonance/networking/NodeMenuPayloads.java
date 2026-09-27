// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.github.loongin.omniresonance.node.ResonanceNodeMenu;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.jetbrains.annotations.Nullable;

/** Common typed payload routing bound to the actual sender's currently open physical-node Menu. */
public final class NodeMenuPayloads {
    private static volatile @Nullable Consumer<NodeMenuResponse> clientReceiver;
    private static volatile @Nullable Consumer<ManagementTransferMessage> transferReceiver;
    private static volatile @Nullable Consumer<ManagementTransferMessage> terminalTransferReceiver;
    private static volatile @Nullable java.util.function.BiFunction<
                    ServerPlayer, ManagementTransferMessage, NetworkTerminalResponse>
            terminalTransferHandler;

    private static volatile @Nullable Consumer<NodeChunkStatus> chunkReceiver;

    public static void installChunkStatusReceiver(Consumer<NodeChunkStatus> receiver) {
        chunkReceiver = Objects.requireNonNull(receiver);
    }

    private NodeMenuPayloads() {}

    /**
     * Registers version-13 main-thread request/response handlers without loading client classes.
     * Invalid active-menu envelopes receive one privacy-safe failure and never reach business state.
     */
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("27").executesOn(HandlerThread.MAIN);
        registrar.playToClient(NodeChunkStatus.TYPE, NodeChunkStatus.STREAM_CODEC, (status, context) -> {
            var receiver = chunkReceiver;
            if (receiver == null) throw new IllegalStateException("Chunk status receiver missing");
            receiver.accept(status);
        });
        registrar.playToServer(NodeMenuRequest.TYPE, NodeMenuRequest.STREAM_CODEC, (request, context) -> {
            if (!(context.player() instanceof ServerPlayer sender)) {
                throw new IllegalStateException("Node Menu request requires a server player");
            }
            NodeMenuResponse response;
            if (sender.containerMenu instanceof ResonanceNodeMenu menu
                    && menu.containerId == request.containerId()
                    && menu.sessionId().equals(request.sessionId())) {
                response = menu.handle(sender, request);
            } else {
                response = new NodeMenuResponse.Failure(
                        request.containerId(),
                        request.sessionId(),
                        request.sequence(),
                        NodeMenuResponse.Reason.INVALID_REQUEST,
                        null);
            }
            if (response != null) {
                context.reply(response);
            }
        });
        registrar.playBidirectional(
                ManagementTransferMessage.TYPE, ManagementTransferMessage.STREAM_CODEC, (message, context) -> {
                    if (context.player() instanceof ServerPlayer sender) {
                        if (sender.containerMenu instanceof ResonanceNodeMenu menu
                                && menu.sessionId().equals(message.session())) {
                            NodeMenuResponse response = menu.handleTransfer(sender, message);
                            if (response != null) context.reply(response);
                        } else if (terminalTransferHandler != null) {
                            NetworkTerminalResponse response = terminalTransferHandler.apply(sender, message);
                            if (response != null) context.reply(response);
                        }
                    } else {
                        Consumer<ManagementTransferMessage> receiver = transferReceiver;
                        if (receiver != null) receiver.accept(message);
                        if (terminalTransferReceiver != null) terminalTransferReceiver.accept(message);
                    }
                });
        registrar.playToClient(NodeMenuResponse.TYPE, NodeMenuResponse.STREAM_CODEC, (response, context) -> {
            Consumer<NodeMenuResponse> receiver = clientReceiver;
            if (receiver == null) {
                throw new IllegalStateException("Node Menu client receiver was not installed");
            }
            receiver.accept(response);
        });
    }

    public static void installTerminalTransferHandler(
            java.util.function.BiFunction<ServerPlayer, ManagementTransferMessage, NetworkTerminalResponse> handler) {
        terminalTransferHandler = Objects.requireNonNull(handler);
    }

    public static void installTerminalTransferReceiver(Consumer<ManagementTransferMessage> receiver) {
        terminalTransferReceiver = Objects.requireNonNull(receiver);
    }

    public static void installTransferReceiver(Consumer<ManagementTransferMessage> receiver) {
        transferReceiver = Objects.requireNonNull(receiver);
    }

    /** Installs the Dist.CLIENT response consumer; null is rejected and no authority is transferred. */
    public static void installClientReceiver(Consumer<NodeMenuResponse> receiver) {
        clientReceiver = Objects.requireNonNull(receiver, "receiver");
    }
}
