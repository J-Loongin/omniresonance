// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.network;

import java.util.Objects;
import java.util.UUID;

/** Immutable channel identity scoped to one tunnel; channels intentionally have no enabled or resource-child state. */
public record NetworkChannelRecord(UUID channelId, UUID tunnelId, long channelNumber, ManagedName name, long revision) {
    public NetworkChannelRecord {
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(tunnelId, "tunnelId");
        Objects.requireNonNull(name, "name");
        if (channelNumber < 1 || revision < 0) {
            throw new IllegalArgumentException("Invalid channel number or revision");
        }
    }

    /** Creates a channel whose caller has already reserved its exact tunnel-local number. */
    public static NetworkChannelRecord fresh(UUID channelId, UUID tunnelId, long channelNumber, ManagedName name) {
        return new NetworkChannelRecord(channelId, tunnelId, channelNumber, name, 0);
    }

    public NetworkChannelRecord withName(ManagedName newName) {
        Objects.requireNonNull(newName, "newName");
        return name.equals(newName)
                ? this
                : new NetworkChannelRecord(channelId, tunnelId, channelNumber, newName, Math.incrementExact(revision));
    }
}
