// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.persistence;

import io.github.loongin.omniresonance.storage.StorageBucketHash;
import io.github.loongin.omniresonance.transfer.ResourceVariantKey;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Constructing-server-thread-owned resource bucket. Raw immutable keys survive missing adapters unchanged.
 * Access never loads a world, simulates a transfer or flushes disk. Mutations validate before changing quantities
 * and dirty only this shard; callers must authorize and reserve transactions before invoking them.
 * The authoritative map lasts for this shard's lifetime, bounded by native collection capacity and the ledger's
 * admission policy rather than management-list quotas. Detached snapshots are only for activation and saving.
 */
public final class StorageBucketData extends SavedData {
    public static final int SCHEMA_VERSION = 1;
    private static final Set<String> FIELDS =
            Set.of("schema_version", "network_id", "bucket_index", "bucket_hash_version", "resources");
    private static final Set<String> ENTRY_FIELDS = Set.of("type_id", "canonical_bytes", "amount");
    private final Thread owner = Thread.currentThread();
    private final UUID networkId;
    private final int bucketIndex;
    private final Map<ResourceVariantKey, Long> amounts;

    private StorageBucketData(UUID networkId, int bucketIndex, Map<ResourceVariantKey, Long> amounts) {
        this.networkId = Objects.requireNonNull(networkId);
        requireBucket(bucketIndex);
        this.bucketIndex = bucketIndex;
        this.amounts = amounts;
    }

    /** Creates a dirty empty bucket; requires proven absent storage identity, no implicit disk replacement. */
    public static StorageBucketData create(UUID networkId, int bucketIndex) {
        StorageBucketData data = new StorageBucketData(networkId, bucketIndex, new HashMap<>());
        data.setDirty();
        return data;
    }

    /**
     * Strictly decodes an entire bucket before publication, without adapting keys, dirtying or retaining tags.
     * Invalid shape, identity, version, ownership or quantity rejects the entire load; no repair is attempted.
     * Raw NBT framing/allocation is owned by native SavedData, not this already-decoded-tag validator.
     */
    public static StorageBucketData load(UUID networkId, int bucketIndex, CompoundTag root) {
        Objects.requireNonNull(networkId);
        Objects.requireNonNull(root);
        requireBucket(bucketIndex);
        if (!root.getAllKeys().equals(FIELDS)) throw new IllegalArgumentException("Invalid storage bucket fields");
        ManagedDataNbt.requireType(root, "schema_version", Tag.TAG_INT);
        ManagedDataNbt.requireType(root, "network_id", Tag.TAG_INT_ARRAY);
        ManagedDataNbt.requireType(root, "bucket_index", Tag.TAG_INT);
        ManagedDataNbt.requireType(root, "bucket_hash_version", Tag.TAG_INT);
        ManagedDataNbt.requireType(root, "resources", Tag.TAG_LIST);
        if (root.getInt("schema_version") != SCHEMA_VERSION
                || !root.hasUUID("network_id")
                || !networkId.equals(root.getUUID("network_id"))
                || root.getInt("bucket_index") != bucketIndex
                || root.getInt("bucket_hash_version") != StorageBucketHash.VERSION) {
            throw new IllegalArgumentException("Invalid storage bucket identity or version");
        }
        ListTag rows = (ListTag) root.get("resources");
        if (rows.getElementType() != Tag.TAG_COMPOUND && !(rows.isEmpty() && rows.getElementType() == Tag.TAG_END)) {
            throw new IllegalArgumentException("Invalid storage resource collection");
        }
        Map<ResourceVariantKey, Long> amounts = new HashMap<>();
        for (int index = 0; index < rows.size(); index++) {
            CompoundTag row = rows.getCompound(index);
            if (!row.getAllKeys().equals(ENTRY_FIELDS))
                throw new IllegalArgumentException("Invalid storage resource fields");
            ManagedDataNbt.requireType(row, "type_id", Tag.TAG_STRING);
            ManagedDataNbt.requireType(row, "canonical_bytes", Tag.TAG_BYTE_ARRAY);
            ManagedDataNbt.requireType(row, "amount", Tag.TAG_LONG);
            String type = row.getString("type_id");
            if (type.length() > 128) throw new IllegalArgumentException("Storage resource type exceeds limit");
            ResourceLocation typeId = ResourceLocation.parse(type);
            if (!typeId.toString().equals(type))
                throw new IllegalArgumentException("Noncanonical storage resource type");
            ResourceVariantKey key = new ResourceVariantKey(typeId, row.getByteArray("canonical_bytes"));
            long amount = row.getLong("amount");
            if (amount < 0
                    || StorageBucketHash.bucket(key) != bucketIndex
                    || amounts.putIfAbsent(key, amount) != null) {
                throw new IllegalArgumentException("Invalid quantity, duplicate resource or bucket ownership");
            }
        }
        amounts.values().removeIf(amount -> amount == 0);
        return new StorageBucketData(networkId, bucketIndex, amounts);
    }

    /** Immutable identity on the owning thread, without mutation or simulation. */
    public UUID networkId() {
        checkThread();
        return networkId;
    }

    /** Stable partition number on the owning thread, without mutation or simulation. */
    public int bucketIndex() {
        checkThread();
        return bucketIndex;
    }

    /** O(1) quantity lookup on the owning thread; absent keys return zero without admission or mutation. */
    public long amount(ResourceVariantKey key) {
        checkThread();
        return amounts.getOrDefault(Objects.requireNonNull(key), 0L);
    }

    /** O(1) positive-quantity key count on the owning thread; does not count empty historical records. */
    public int variantCount() {
        checkThread();
        return amounts.size();
    }

    /**
     * Stores an absolute nonnegative quantity on the owning thread; zero removes the record, never the shard.
     * Existing-key updates are O(1); only admission of a missing key hashes its bytes. Invalid input rejects
     * before mutation. Does not authorize, reserve, simulate, notify clients or force a save.
     */
    public void setAmount(ResourceVariantKey key, long amount) {
        checkThread();
        Objects.requireNonNull(key);
        if (amount < 0) throw new IllegalArgumentException("Negative storage quantity");
        Long previous = amounts.get(key);
        if (previous == null && StorageBucketHash.bucket(key) != bucketIndex) {
            throw new IllegalArgumentException("Resource belongs to another storage bucket");
        }
        if ((previous == null ? 0 : previous) == amount) return;
        if (amount == 0) amounts.remove(key);
        else amounts.put(key, amount);
        setDirty();
    }

    /** Detached O(n) activation/save snapshot; caller may mutate it, but immutable keys remain shared. */
    public Map<ResourceVariantKey, Long> snapshot() {
        checkThread();
        return new HashMap<>(amounts);
    }

    /** Encodes on the owner thread into the caller's tag without changing quantities, dirty state or disk. */
    @Override
    public CompoundTag save(CompoundTag root, HolderLookup.Provider registries) {
        checkThread();
        root.putInt("schema_version", SCHEMA_VERSION);
        root.putUUID("network_id", networkId);
        root.putInt("bucket_index", bucketIndex);
        root.putInt("bucket_hash_version", StorageBucketHash.VERSION);
        ListTag rows = new ListTag();
        for (Map.Entry<ResourceVariantKey, Long> entry : amounts.entrySet()) {
            CompoundTag row = new CompoundTag();
            row.putString("type_id", entry.getKey().typeId().toString());
            row.putByteArray("canonical_bytes", entry.getKey().canonicalBytes());
            row.putLong("amount", entry.getValue());
            rows.add(row);
        }
        root.put("resources", rows);
        return root;
    }

    private static void requireBucket(int bucket) {
        if (bucket < 0 || bucket >= StorageBucketHash.COUNT)
            throw new IllegalArgumentException("Invalid storage bucket");
    }

    private void checkThread() {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Storage bucket accessed off owner thread");
    }
}
