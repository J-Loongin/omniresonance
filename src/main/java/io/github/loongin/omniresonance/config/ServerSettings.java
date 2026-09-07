// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.config;

/**
 * An immutable, world-independent configuration snapshot safe to read on any thread. Construction
 * validates the whole candidate without changing native configuration or authoritative state; invalid
 * values are rejected before publication. This value performs no simulation or resource operations.
 */
public record ServerSettings(
        int networksPerOwner,
        int tunnelsPerNetwork,
        int channelsPerTunnel,
        int channelBindingsPerDirectNode,
        int administratorsPerNetwork) {
    public ServerSettings {
        ServerConfig.validateNetworksPerOwner(networksPerOwner);
        ServerConfig.validateTunnelsPerNetwork(tunnelsPerNetwork);
        ServerConfig.validateChannelsPerTunnel(channelsPerTunnel);
        ServerConfig.validateChannelBindingsPerDirectNode(channelBindingsPerDirectNode);
        ServerConfig.validateAdministratorsPerNetwork(administratorsPerNetwork);
    }

    /** Retains the established topology construction contract while membership uses its configured default. */
    public ServerSettings(
            int networksPerOwner, int tunnelsPerNetwork, int channelsPerTunnel, int channelBindingsPerDirectNode) {
        this(
                networksPerOwner,
                tunnelsPerNetwork,
                channelsPerTunnel,
                channelBindingsPerDirectNode,
                ServerConfig.defaultAdministratorsPerNetwork());
    }

    /** Retains the established one-argument construction contract while topology settings use defaults. */
    public ServerSettings(int networksPerOwner) {
        this(
                networksPerOwner,
                ServerConfig.defaultTunnelsPerNetwork(),
                ServerConfig.defaultChannelsPerTunnel(),
                ServerConfig.defaultChannelBindingsPerDirectNode());
    }

    /** Returns a validated, independently owned default snapshot without accessing native values. */
    public static ServerSettings defaults() {
        return new ServerSettings(
                ServerConfig.defaultNetworksPerOwner(),
                ServerConfig.defaultTunnelsPerNetwork(),
                ServerConfig.defaultChannelsPerTunnel(),
                ServerConfig.defaultChannelBindingsPerDirectNode());
    }
}
