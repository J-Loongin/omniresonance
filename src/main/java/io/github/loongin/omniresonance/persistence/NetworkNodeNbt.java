// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.persistence;

import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.node.NetworkNodeRecord;
import io.github.loongin.omniresonance.node.NodeForm;
import io.github.loongin.omniresonance.node.NodeMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

/** Strict network-v3 node-list codec; decoded values and output tags are independently owned. */
final class NetworkNodeNbt {
    static final int MAXIMUM_NODES = 262144;
    private static final int MAXIMUM_DIMENSION_UTF8_BYTES = 256;
    private static final Set<String> FIELDS = Set.of(
            "node_id",
            "node_number",
            "name",
            "dimension",
            "x",
            "y",
            "z",
            "form",
            "facing",
            "revision",
            "enabled",
            "chunk_loading_requested",
            "mode");
    private static final Comparator<NetworkNodeRecord> ORDER =
            Comparator.comparingLong(NetworkNodeRecord::nodeNumber).thenComparing(NetworkNodeRecord::nodeId);

    private NetworkNodeNbt() {}

    static Decoded decode(CompoundTag tag) {
        ManagedDataNbt.requireType(tag, "last_node_number", Tag.TAG_LONG);
        long lastNodeNumber = tag.getLong("last_node_number");
        if (lastNodeNumber < 0) {
            throw new IllegalArgumentException("Negative last node number");
        }
        ManagedDataNbt.requireType(tag, "nodes", Tag.TAG_LIST);
        ListTag encoded = (ListTag) tag.get("nodes");
        int size = encoded.size();
        if (size > MAXIMUM_NODES) {
            throw new IllegalArgumentException("Too many network nodes");
        }
        if (encoded.getElementType() != Tag.TAG_COMPOUND && !(size == 0 && encoded.getElementType() == Tag.TAG_END)) {
            throw new IllegalArgumentException("Expected compound node list");
        }

        Map<UUID, NetworkNodeRecord> nodes = new HashMap<>(size);
        Set<Long> numbers = new HashSet<>(size);
        Set<String> names = new HashSet<>(size);
        Set<GlobalPos> positions = new HashSet<>(size);
        for (int index = 0; index < size; index++) {
            NetworkNodeRecord record = decodeRecord(encoded.getCompound(index));
            if (record.nodeNumber() > lastNodeNumber) {
                throw new IllegalArgumentException("Node number exceeds last allocated number");
            }
            if (nodes.putIfAbsent(record.nodeId(), record) != null
                    || !numbers.add(record.nodeNumber())
                    || !names.add(record.name().uniquenessKey())
                    || !positions.add(record.position())) {
                throw new IllegalArgumentException("Duplicate network node identity, number, name or position");
            }
        }
        return new Decoded(lastNodeNumber, nodes);
    }

    static List<NetworkNodeRecord> sorted(Collection<NetworkNodeRecord> nodes) {
        List<NetworkNodeRecord> sorted = new ArrayList<>(nodes);
        sorted.sort(ORDER);
        return List.copyOf(sorted);
    }

    static ListTag encode(Collection<NetworkNodeRecord> nodes) {
        ListTag encoded = new ListTag();
        for (NetworkNodeRecord record : sorted(nodes)) {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("node_id", record.nodeId());
            tag.putLong("node_number", record.nodeNumber());
            tag.putString("name", record.name().value());
            tag.putString("dimension", record.position().dimension().location().toString());
            tag.putInt("x", record.position().pos().getX());
            tag.putInt("y", record.position().pos().getY());
            tag.putInt("z", record.position().pos().getZ());
            tag.putString("form", record.form().serializedName());
            tag.putString("facing", record.facing().getSerializedName());
            tag.putLong("revision", record.revision());
            tag.putBoolean("enabled", record.enabled());
            tag.putBoolean("chunk_loading_requested", record.chunkLoadingRequested());
            tag.putString("mode", record.mode().serializedName());
            encoded.add(tag);
        }
        return encoded;
    }

    private static NetworkNodeRecord decodeRecord(CompoundTag tag) {
        if (!tag.getAllKeys().equals(FIELDS)) {
            throw new IllegalArgumentException("Missing or unexpected network node fields");
        }
        UUID nodeId = ManagedDataNbt.readUuid(tag, "node_id");
        ManagedDataNbt.requireType(tag, "node_number", Tag.TAG_LONG);
        long nodeNumber = tag.getLong("node_number");
        ManagedName name = ManagedDataNbt.readName(tag);
        ResourceLocation dimensionId = readDimension(tag);
        ManagedDataNbt.requireType(tag, "x", Tag.TAG_INT);
        ManagedDataNbt.requireType(tag, "y", Tag.TAG_INT);
        ManagedDataNbt.requireType(tag, "z", Tag.TAG_INT);
        GlobalPos position = GlobalPos.of(
                ResourceKey.create(Registries.DIMENSION, dimensionId),
                new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z")));
        ManagedDataNbt.requireType(tag, "form", Tag.TAG_STRING);
        NodeForm form = NodeForm.fromSerialized(tag.getString("form"));
        ManagedDataNbt.requireType(tag, "facing", Tag.TAG_STRING);
        Direction facing = Direction.byName(tag.getString("facing"));
        if (facing == null) {
            throw new IllegalArgumentException("Unknown node facing");
        }
        ManagedDataNbt.requireType(tag, "revision", Tag.TAG_LONG);
        long revision = tag.getLong("revision");
        boolean enabled = readBoolean(tag, "enabled");
        boolean chunkLoadingRequested = readBoolean(tag, "chunk_loading_requested");
        ManagedDataNbt.requireType(tag, "mode", Tag.TAG_STRING);
        NodeMode mode = NodeMode.fromSerialized(tag.getString("mode"));
        return new NetworkNodeRecord(
                nodeId, nodeNumber, name, position, form, facing, revision, enabled, chunkLoadingRequested, mode);
    }

    private static boolean readBoolean(CompoundTag tag, String key) {
        ManagedDataNbt.requireType(tag, key, Tag.TAG_BYTE);
        byte value = tag.getByte(key);
        if (value != 0 && value != 1) {
            throw new IllegalArgumentException("Invalid boolean node field: " + key);
        }
        return value == 1;
    }

    private static ResourceLocation readDimension(CompoundTag tag) {
        ManagedDataNbt.requireType(tag, "dimension", Tag.TAG_STRING);
        String encoded = tag.getString("dimension");
        if (encoded.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_DIMENSION_UTF8_BYTES) {
            throw new IllegalArgumentException("Dimension identifier is too large");
        }
        ResourceLocation dimension = ResourceLocation.tryParse(encoded);
        if (dimension == null || !dimension.toString().equals(encoded)) {
            throw new IllegalArgumentException("Invalid or noncanonical dimension identifier");
        }
        return dimension;
    }

    record Decoded(long lastNodeNumber, Map<UUID, NetworkNodeRecord> nodes) {
        Decoded {
            nodes = Map.copyOf(nodes);
        }
    }
}
