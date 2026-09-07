// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.persistence;

import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.network.NetworkMetadata;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;

/** Strict versioned checks shared by managed SavedData codecs; inputs are never retained or modified. */
final class ManagedDataNbt {
    static final int NETWORK_SCHEMA_VERSION = 5;
    static final int OWNER_SCHEMA_VERSION = 1;
    static final Set<String> NETWORK_V3_FIELDS = Set.of(
            "schema_version",
            "network_id",
            "owner_id",
            "name",
            "creation_order",
            "administrators",
            "last_node_number",
            "nodes");
    static final Set<String> NETWORK_V4_FIELDS = Set.of(
            "schema_version",
            "network_id",
            "owner_id",
            "name",
            "creation_order",
            "administrators",
            "last_node_number",
            "nodes",
            "last_tunnel_number",
            "topology_revision",
            "tunnels",
            "channels",
            "direct_bindings",
            "domain_configurations");
    static final Set<String> NETWORK_FIELDS = currentNetworkFields();
    static final Set<String> OWNER_FIELDS = Set.of("schema_version", "owner_id", "default_network_id");

    private ManagedDataNbt() {}

    private static Set<String> currentNetworkFields() {
        Set<String> fields = new HashSet<>(NETWORK_V4_FIELDS);
        fields.add("management_revision");
        return Set.copyOf(fields);
    }

    static long readManagementRevision(CompoundTag tag) {
        requireType(tag, "management_revision", Tag.TAG_LONG);
        long revision = tag.getLong("management_revision");
        if (revision < 0) {
            throw new IllegalArgumentException("Negative network management revision");
        }
        return revision;
    }

    static int readSchemaVersion(CompoundTag tag) {
        Objects.requireNonNull(tag, "tag");
        requireType(tag, "schema_version", Tag.TAG_INT);
        return tag.getInt("schema_version");
    }

    static void validateSchemaAndFields(CompoundTag tag, int expectedVersion, Set<String> allowedFields) {
        Objects.requireNonNull(tag, "tag");
        requireType(tag, "schema_version", Tag.TAG_INT);
        if (tag.getInt("schema_version") != expectedVersion) {
            throw new IllegalArgumentException("Unsupported managed data schema");
        }
        if (tag.getAllKeys().size() > allowedFields.size()) {
            throw new IllegalArgumentException("Unexpected managed data fields");
        }
        for (String key : tag.getAllKeys()) {
            if (!allowedFields.contains(key)) {
                throw new IllegalArgumentException("Unexpected managed data field: " + key);
            }
        }
    }

    static UUID readIdentity(CompoundTag tag, String key, UUID expectedId) {
        Objects.requireNonNull(expectedId, "expectedId");
        UUID actualId = readUuid(tag, key);
        if (!expectedId.equals(actualId)) {
            throw new IllegalArgumentException("Managed data identity mismatch: " + key);
        }
        return actualId;
    }

    static UUID readUuid(CompoundTag tag, String key) {
        requireType(tag, key, Tag.TAG_INT_ARRAY);
        return readUuid(tag.get(key));
    }

    static ManagedName readName(CompoundTag tag) {
        requireType(tag, "name", Tag.TAG_STRING);
        return new ManagedName(tag.getString("name"));
    }

    static long readCreationOrder(CompoundTag tag) {
        requireType(tag, "creation_order", Tag.TAG_LONG);
        long creationOrder = tag.getLong("creation_order");
        if (creationOrder < 0) {
            throw new IllegalArgumentException("Negative network creation order");
        }
        return creationOrder;
    }

    static Set<UUID> readAdministrators(CompoundTag tag) {
        requireType(tag, "administrators", Tag.TAG_LIST);
        ListTag entries = (ListTag) tag.get("administrators");
        int size = entries.size();
        if (size > NetworkMetadata.MAXIMUM_ADMINISTRATORS) {
            throw new IllegalArgumentException("Too many network administrators");
        }
        if (entries.getElementType() != Tag.TAG_INT_ARRAY && !(size == 0 && entries.getElementType() == Tag.TAG_END)) {
            throw new IllegalArgumentException("Expected UUID administrator list");
        }
        Set<UUID> administrators = new HashSet<>(size);
        for (int index = 0; index < size; index++) {
            if (!administrators.add(readUuid(entries.get(index)))) {
                throw new IllegalArgumentException("Duplicate network administrator");
            }
        }
        return administrators;
    }

    static ListTag writeAdministrators(Set<UUID> administrators) {
        List<UUID> sorted = new ArrayList<>(administrators);
        sorted.sort(UUID::compareTo);
        ListTag entries = new ListTag(sorted.size());
        for (UUID administrator : sorted) {
            entries.add(NbtUtils.createUUID(administrator));
        }
        return entries;
    }

    private static UUID readUuid(Tag value) {
        if (!(value instanceof IntArrayTag array) || array.getAsIntArray().length != 4) {
            throw new IllegalArgumentException("Expected four-integer UUID");
        }
        return NbtUtils.loadUUID(array);
    }

    static void requireType(CompoundTag tag, String key, int type) {
        if (!tag.contains(key, type)) {
            throw new IllegalArgumentException("Missing or incorrectly typed managed data field: " + key);
        }
    }
}
