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

    private NetworkPayloads() {}

    /**
     * Registers required version-3 PLAY payloads with main-thread handlers. The framework supplies the
     * real sending player and replies through its connection; server work never trusts a payload owner.
     * Missing client bootstrap fails explicitly rather than silently discarding successful responses.
     */
    public static void register(RegisterPayloadHandlersEvent event, NetworkRuntimeRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        PayloadRegistrar registrar = event.registrar("3").executesOn(HandlerThread.MAIN);
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
