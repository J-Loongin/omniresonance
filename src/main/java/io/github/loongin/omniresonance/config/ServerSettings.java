// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.config;

/**
 * An immutable, world-independent configuration snapshot safe to read on any thread. Construction
 * validates the whole candidate without changing native configuration or authoritative state; invalid
 * values are rejected before publication. This value performs no simulation or resource operations.
 */
public record ServerSettings(int networksPerOwner) {
    public ServerSettings {
        ServerConfig.validateNetworksPerOwner(networksPerOwner);
    }

    /** Returns a validated, independently owned default snapshot without accessing native values. */
    public static ServerSettings defaults() {
        return new ServerSettings(ServerConfig.defaultNetworksPerOwner());
    }
}
