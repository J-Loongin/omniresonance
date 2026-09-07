// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Server-thread adapter for current connections and local UUID profile names; no external lookup or new cache. */
public final class ServerPlayerDirectory implements NetworkAdministrationService.PlayerDirectory {
    private final MinecraftServer server;

    /** Retains the caller's server lifecycle; never loads profiles, creates players or saves data. */
    public ServerPlayerDirectory(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    @Override
    public Optional<NetworkAdministrationService.PlayerIdentity> online(UUID id) {
        requireServerThread();
        ServerPlayer player = server.getPlayerList().getPlayer(Objects.requireNonNull(id, "id"));
        return player == null ? Optional.empty() : Optional.of(identity(player));
    }

    @Override
    public List<NetworkAdministrationService.PlayerIdentity> snapshotOnline(int maximum) {
        requireServerThread();
        List<ServerPlayer> online = server.getPlayerList().getPlayers();
        if (maximum < 0 || online.size() > maximum) throw new IllegalArgumentException("Online roster exceeds limit");
        List<NetworkAdministrationService.PlayerIdentity> result = new ArrayList<>(online.size());
        for (ServerPlayer player : online) result.add(identity(player));
        return List.copyOf(result);
    }

    @Override
    public String knownName(UUID id) {
        requireServerThread();
        Optional<NetworkAdministrationService.PlayerIdentity> connected = online(id);
        if (connected.isPresent()) return connected.orElseThrow().name();
        if (server.getProfileCache() != null) {
            return server.getProfileCache()
                    .get(id)
                    .map(profile -> profile.getName())
                    .orElse(id.toString());
        }
        return id.toString();
    }

    private static NetworkAdministrationService.PlayerIdentity identity(ServerPlayer player) {
        return new NetworkAdministrationService.PlayerIdentity(
                player.getUUID(), player.getGameProfile().getName());
    }

    private void requireServerThread() {
        if (!server.isSameThread()) throw new IllegalStateException("Player directory accessed outside server thread");
    }
}
