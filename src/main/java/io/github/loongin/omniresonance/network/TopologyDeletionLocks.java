// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.network;

import io.github.loongin.omniresonance.persistence.NetworkSavedData;
import io.github.loongin.omniresonance.security.EditLockTable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Server-thread, read-only cascade preflight. Visits only topology objects removed by this deletion;
 * no lease is acquired/released and no snapshot escapes. Cost is linear in the affected topology. */
final class TopologyDeletionLocks {
    private TopologyDeletionLocks() {}

    static boolean networkConflict(
            NetworkSavedData data, EditLockTable locks, EditLockTable.Token allowed, long nowTicks) {
        UUID network = data.metadata().id();
        if (locks.hasConflictingLocks(
                List.of(new TopologyEditKey.TunnelCollection(network).lockId()), allowed, nowTicks)) return true;
        for (var tunnel : data.tunnels())
            if (tunnelConflict(data, tunnel.tunnelId(), locks, allowed, nowTicks)) return true;
        return false;
    }

    static boolean tunnelConflict(
            NetworkSavedData data, UUID tunnel, EditLockTable locks, EditLockTable.Token allowed, long nowTicks) {
        UUID network = data.metadata().id();
        var affected = new ArrayList<UUID>();
        affected.add(new TopologyEditKey.Tunnel(network, tunnel).lockId());
        affected.add(new TopologyEditKey.ChannelCollection(network, tunnel).lockId());
        for (var channel : data.channels(tunnel))
            affected.add(new TopologyEditKey.Channel(network, channel.channelId()).lockId());
        return locks.hasConflictingLocks(affected, allowed, nowTicks);
    }
}
