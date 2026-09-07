// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.network;

import java.util.Objects;
import java.util.UUID;

/** Immutable network-local tunnel identity and management state without channels or world references. */
public record NetworkTunnelRecord(
        UUID tunnelId, long tunnelNumber, ManagedName name, long revision, boolean enabled, long lastChannelNumber) {
    public NetworkTunnelRecord {
        Objects.requireNonNull(tunnelId, "tunnelId");
        Objects.requireNonNull(name, "name");
        if (tunnelNumber < 1 || revision < 0 || lastChannelNumber < 0) {
            throw new IllegalArgumentException("Invalid tunnel number or revision");
        }
    }

    /** Creates one default-enabled tunnel; the caller owns identity, number and uniqueness checks. */
    public static NetworkTunnelRecord fresh(UUID tunnelId, long tunnelNumber, ManagedName name) {
        return new NetworkTunnelRecord(tunnelId, tunnelNumber, name, 0, true, 0);
    }

    /** Creates one default-enabled tunnel whose mandatory initial channel has already allocated number one. */
    public static NetworkTunnelRecord freshWithInitialChannel(UUID tunnelId, long tunnelNumber, ManagedName name) {
        return new NetworkTunnelRecord(tunnelId, tunnelNumber, name, 0, true, 1);
    }

    public NetworkTunnelRecord withName(ManagedName newName) {
        Objects.requireNonNull(newName, "newName");
        return name.equals(newName)
                ? this
                : new NetworkTunnelRecord(tunnelId, tunnelNumber, newName, nextRevision(), enabled, lastChannelNumber);
    }

    public NetworkTunnelRecord withEnabled(boolean newEnabled) {
        return enabled == newEnabled
                ? this
                : new NetworkTunnelRecord(tunnelId, tunnelNumber, name, nextRevision(), newEnabled, lastChannelNumber);
    }

    /** Advances the channel counter by exactly one; unchanged input returns this value. */
    public NetworkTunnelRecord withLastChannelNumber(long newLastChannelNumber) {
        if (newLastChannelNumber == lastChannelNumber) {
            return this;
        }
        long expected = Math.incrementExact(lastChannelNumber);
        if (newLastChannelNumber != expected) {
            throw new IllegalArgumentException("Tunnel channel number must advance exactly once");
        }
        return new NetworkTunnelRecord(tunnelId, tunnelNumber, name, nextRevision(), enabled, newLastChannelNumber);
    }

    private long nextRevision() {
        return Math.incrementExact(revision);
    }
}
