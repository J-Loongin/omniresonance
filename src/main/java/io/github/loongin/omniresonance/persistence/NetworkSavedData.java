// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.persistence;

import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.network.NetworkMetadata;
import io.github.loongin.omniresonance.node.NetworkNodeRecord;
import io.github.loongin.omniresonance.node.NodeForm;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Server-thread-owned v2 network shard containing immutable metadata and authoritative physical-node records.
 *
 * <p>Create/load and every accessor or mutation run on the constructing server thread. Tags and caller collections
 * are never retained. Mutations validate their full intent before changing indexes and mark dirty only on a real
 * change. No method simulates, performs disk I/O, accesses a world, authorizes a player or exposes mutable state.
 * Malformed v1/v2 input is rejected rather than migrated, repaired or replaced.
 */
public final class NetworkSavedData extends SavedData {
    private final Thread owningThread = Thread.currentThread();
    private final NetworkMetadata metadata;
    private final Map<UUID, NetworkNodeRecord> nodes = new HashMap<>();
    private final Map<String, UUID> nodesByName = new HashMap<>();
    private final Map<GlobalPos, UUID> nodesByPosition = new HashMap<>();
    private long lastNodeNumber;

    private NetworkSavedData(NetworkMetadata metadata, long lastNodeNumber, Map<UUID, NetworkNodeRecord> initialNodes) {
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.lastNodeNumber = lastNodeNumber;
        for (NetworkNodeRecord record : initialNodes.values()) {
            nodes.put(record.nodeId(), record);
            nodesByName.put(record.name().uniquenessKey(), record.nodeId());
            nodesByPosition.put(record.position(), record.nodeId());
        }
    }

    /** Creates an empty dirty v2 shard without simulation, I/O, world access or caller mutation. */
    public static NetworkSavedData create(NetworkMetadata metadata) {
        NetworkSavedData data = new NetworkSavedData(metadata, 0, Map.of());
        data.setDirty();
        return data;
    }

    /**
     * Strictly decodes one clean v2 shard without retaining or changing the caller tag.
     * Missing inputs throw {@link NullPointerException}; malformed/v1 data throws {@link IllegalArgumentException}.
     */
    public static NetworkSavedData load(UUID expectedId, CompoundTag tag) {
        ManagedDataNbt.validateSchemaAndFields(
                tag, ManagedDataNbt.NETWORK_SCHEMA_VERSION, ManagedDataNbt.NETWORK_FIELDS);
        UUID id = ManagedDataNbt.readIdentity(tag, "network_id", expectedId);
        NetworkMetadata metadata = new NetworkMetadata(
                id,
                ManagedDataNbt.readUuid(tag, "owner_id"),
                ManagedDataNbt.readName(tag),
                ManagedDataNbt.readCreationOrder(tag),
                ManagedDataNbt.readAdministrators(tag));
        NetworkNodeNbt.Decoded decoded = NetworkNodeNbt.decode(tag);
        return new NetworkSavedData(metadata, decoded.lastNodeNumber(), decoded.nodes());
    }

    /** Returns immutable metadata without mutation or simulation; wrong-thread access is rejected. */
    public NetworkMetadata metadata() {
        requireOwningThread();
        return metadata;
    }

    /** Returns a stable immutable node snapshot without world access or mutation. */
    public List<NetworkNodeRecord> nodes() {
        requireOwningThread();
        return NetworkNodeNbt.sorted(nodes.values());
    }

    /** Returns the last allocated positive node number without mutation. */
    public long lastNodeNumber() {
        requireOwningThread();
        return lastNodeNumber;
    }

    /** Finds one immutable node record without mutation or implicit creation. */
    public Optional<NetworkNodeRecord> findNode(UUID nodeId) {
        requireOwningThread();
        return Optional.ofNullable(nodes.get(Objects.requireNonNull(nodeId, "nodeId")));
    }

    /**
     * Creates one authoritative record after validating capacity and all network-scoped unique keys.
     * Failure occurs before number/index/dirty mutation; this method performs no player authorization or I/O.
     */
    public NetworkNodeRecord createNode(
            UUID nodeId, ManagedName name, GlobalPos position, NodeForm form, Direction facing) {
        requireOwningThread();
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(form, "form");
        Objects.requireNonNull(facing, "facing");
        if (nodes.size() >= NetworkNodeNbt.MAXIMUM_NODES) {
            throw new IllegalArgumentException("Network node hard limit reached");
        }
        if (nodes.containsKey(nodeId)
                || nodesByName.containsKey(name.uniquenessKey())
                || nodesByPosition.containsKey(position)) {
            throw new IllegalArgumentException("Duplicate network node identity, name or position");
        }
        long nodeNumber = Math.incrementExact(lastNodeNumber);
        NetworkNodeRecord record = new NetworkNodeRecord(nodeId, nodeNumber, name, position, form, facing);
        nodes.put(nodeId, record);
        nodesByName.put(name.uniquenessKey(), nodeId);
        nodesByPosition.put(position, nodeId);
        lastNodeNumber = nodeNumber;
        setDirty();
        return record;
    }

    /**
     * Replaces only form/facing for an exact UUID/location match and dirties only when either snapshot changes.
     * Missing or mismatched records return empty without mutation, simulation or world access.
     */
    public Optional<NetworkNodeRecord> updateNodePhysicalSnapshot(
            UUID nodeId, GlobalPos position, NodeForm form, Direction facing) {
        requireOwningThread();
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(form, "form");
        Objects.requireNonNull(facing, "facing");
        NetworkNodeRecord current = nodes.get(nodeId);
        if (current == null || !current.position().equals(position)) {
            return Optional.empty();
        }
        NetworkNodeRecord updated = current.withPhysicalSnapshot(form, facing);
        if (updated != current) {
            nodes.put(nodeId, updated);
            setDirty();
        }
        return Optional.of(updated);
    }

    /**
     * Removes only an exact UUID/location record, preserving historical numbering.
     * Missing/mismatched records return empty without dirtying or changing indexes.
     */
    public Optional<NetworkNodeRecord> removeNode(UUID nodeId, GlobalPos position) {
        requireOwningThread();
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(position, "position");
        NetworkNodeRecord current = nodes.get(nodeId);
        if (current == null || !current.position().equals(position)) {
            return Optional.empty();
        }
        nodes.remove(nodeId);
        nodesByName.remove(current.name().uniquenessKey());
        nodesByPosition.remove(current.position());
        setDirty();
        return Optional.of(current);
    }

    /** Writes exact v2 fields into caller-owned tags without changing dirty state or performing I/O. */
    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        requireOwningThread();
        Objects.requireNonNull(tag, "tag");
        tag.putInt("schema_version", ManagedDataNbt.NETWORK_SCHEMA_VERSION);
        tag.putUUID("network_id", metadata.id());
        tag.putUUID("owner_id", metadata.ownerId());
        tag.putString("name", metadata.name().value());
        tag.putLong("creation_order", metadata.creationOrder());
        tag.put("administrators", ManagedDataNbt.writeAdministrators(metadata.administrators()));
        tag.putLong("last_node_number", lastNodeNumber);
        tag.put("nodes", NetworkNodeNbt.encode(nodes.values()));
        return tag;
    }

    /** Sets owned dirty state without simulation; rejects wrong-thread access before mutation. */
    @Override
    public void setDirty(boolean dirty) {
        requireOwningThread();
        super.setDirty(dirty);
    }

    /** Reads owned dirty state without mutation or simulation; rejects wrong-thread access. */
    @Override
    public boolean isDirty() {
        requireOwningThread();
        return super.isDirty();
    }

    private void requireOwningThread() {
        if (Thread.currentThread() != owningThread) {
            throw new IllegalStateException("Network SavedData accessed outside its owning server thread");
        }
    }
}
