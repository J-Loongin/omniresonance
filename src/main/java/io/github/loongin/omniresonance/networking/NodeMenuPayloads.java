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

    private NodeMenuPayloads() {}

    /**
     * Registers version-2 main-thread request/response handlers without loading client classes.
     * Invalid active-menu envelopes receive one privacy-safe failure and never reach business state.
     */
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("2").executesOn(HandlerThread.MAIN);
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
        registrar.playToClient(NodeMenuResponse.TYPE, NodeMenuResponse.STREAM_CODEC, (response, context) -> {
            Consumer<NodeMenuResponse> receiver = clientReceiver;
            if (receiver == null) {
                throw new IllegalStateException("Node Menu client receiver was not installed");
            }
            receiver.accept(response);
        });
    }

    /** Installs the Dist.CLIENT response consumer; null is rejected and no authority is transferred. */
    public static void installClientReceiver(Consumer<NodeMenuResponse> receiver) {
        clientReceiver = Objects.requireNonNull(receiver, "receiver");
    }
}
