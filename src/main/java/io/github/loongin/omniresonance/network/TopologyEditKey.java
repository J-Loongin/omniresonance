// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.network;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.UUID;

/** Typed topology edit identity converted to a deterministic in-memory lease UUID without string composition. */
public sealed interface TopologyEditKey {
    UUID networkId();

    UUID lockId();

    record TunnelCollection(UUID networkId) implements TopologyEditKey {
        public TunnelCollection {
            Objects.requireNonNull(networkId, "networkId");
        }

        @Override
        public UUID lockId() {
            return derive(1, networkId, null);
        }
    }

    record ChannelCollection(UUID networkId, UUID tunnelId) implements TopologyEditKey {
        public ChannelCollection {
            Objects.requireNonNull(networkId, "networkId");
            Objects.requireNonNull(tunnelId, "tunnelId");
        }

        @Override
        public UUID lockId() {
            return derive(2, networkId, tunnelId);
        }
    }

    record Tunnel(UUID networkId, UUID tunnelId) implements TopologyEditKey {
        public Tunnel {
            Objects.requireNonNull(networkId, "networkId");
            Objects.requireNonNull(tunnelId, "tunnelId");
        }

        @Override
        public UUID lockId() {
            return derive(3, networkId, tunnelId);
        }
    }

    record Channel(UUID networkId, UUID channelId) implements TopologyEditKey {
        public Channel {
            Objects.requireNonNull(networkId, "networkId");
            Objects.requireNonNull(channelId, "channelId");
        }

        @Override
        public UUID lockId() {
            return derive(4, networkId, channelId);
        }
    }

    /** Nodes intentionally use their globally indexed identity so all node editors share the exact same lease. */
    record Node(UUID networkId, UUID nodeId) implements TopologyEditKey {
        public Node {
            Objects.requireNonNull(networkId, "networkId");
            Objects.requireNonNull(nodeId, "nodeId");
        }

        @Override
        public UUID lockId() {
            return nodeId;
        }
    }

    private static UUID derive(int kind, UUID networkId, UUID objectId) {
        ByteBuffer bytes = ByteBuffer.allocate(33);
        bytes.put((byte) kind);
        bytes.putLong(networkId.getMostSignificantBits());
        bytes.putLong(networkId.getLeastSignificantBits());
        UUID object = objectId == null ? new UUID(0, 0) : objectId;
        bytes.putLong(object.getMostSignificantBits());
        bytes.putLong(object.getLeastSignificantBits());
        return UUID.nameUUIDFromBytes(bytes.array());
    }
}
