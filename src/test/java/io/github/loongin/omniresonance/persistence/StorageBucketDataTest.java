// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.storage.StorageBucketHash;
import io.github.loongin.omniresonance.transfer.ResourceVariantKey;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

final class StorageBucketDataTest {
    private static final UUID NETWORK = new UUID(1, 2);
    private static final ResourceVariantKey KEY =
            new ResourceVariantKey(ResourceLocation.parse("example:missing"), new byte[] {0, -1});
    private static final int BUCKET = StorageBucketHash.bucket(KEY);

    @Test
    void opaqueIdentityAndLongQuantityRoundTripWithoutAdapter() {
        StorageBucketData data = StorageBucketData.create(NETWORK, BUCKET);
        assertTrue(data.isDirty());
        data.setAmount(KEY, Long.MAX_VALUE);
        CompoundTag encoded = data.save(new CompoundTag(), null);
        assertEquals(
                Set.of("schema_version", "network_id", "bucket_index", "bucket_hash_version", "resources"),
                encoded.getAllKeys());
        StorageBucketData loaded = StorageBucketData.load(NETWORK, BUCKET, encoded);
        assertFalse(loaded.isDirty());
        assertEquals(Long.MAX_VALUE, loaded.amount(KEY));
        assertEquals(1, loaded.variantCount());
        assertEquals(data.snapshot(), loaded.snapshot());
        encoded.getList("resources", 10).clear();
        assertEquals(Long.MAX_VALUE, loaded.amount(KEY));
        loaded.snapshot().clear();
        assertEquals(Long.MAX_VALUE, loaded.amount(KEY));
    }

    @Test
    void noOpDoesNotDirtyAndEmptyBucketRetainsIdentity() {
        StorageBucketData data = StorageBucketData.create(NETWORK, BUCKET);
        data.setDirty(false);
        data.setAmount(KEY, 0);
        assertFalse(data.isDirty());
        data.setAmount(KEY, 7);
        assertTrue(data.isDirty());
        data.setDirty(false);
        data.setAmount(KEY, 7);
        assertFalse(data.isDirty());
        data.setAmount(KEY, 0);
        assertTrue(data.isDirty());
        assertEquals(0, data.variantCount());
        StorageBucketData loaded = StorageBucketData.load(NETWORK, BUCKET, data.save(new CompoundTag(), null));
        assertEquals(NETWORK, loaded.networkId());
        assertEquals(BUCKET, loaded.bucketIndex());
        assertEquals(0, loaded.variantCount());
    }

    @Test
    void invalidHeadersAndWrongOwnershipRejectWholeBucket() {
        CompoundTag good = encoded();
        assertThrows(IllegalArgumentException.class, () -> StorageBucketData.load(new UUID(9, 9), BUCKET, good));
        assertThrows(IllegalArgumentException.class, () -> StorageBucketData.load(NETWORK, (BUCKET + 1) % 64, good));
        assertThrows(IllegalArgumentException.class, () -> StorageBucketData.create(NETWORK, -1));
        assertThrows(IllegalArgumentException.class, () -> StorageBucketData.create(NETWORK, 64));
        for (String field : Set.of("schema_version", "bucket_index", "bucket_hash_version")) {
            CompoundTag bad = good.copy();
            bad.putLong(field, 1);
            assertThrows(IllegalArgumentException.class, () -> StorageBucketData.load(NETWORK, BUCKET, bad));
        }
        for (String field : Set.of("schema_version", "bucket_hash_version")) {
            CompoundTag bad = good.copy();
            bad.putInt(field, 2);
            assertThrows(IllegalArgumentException.class, () -> StorageBucketData.load(NETWORK, BUCKET, bad));
        }
        CompoundTag misplaced = good.copy();
        misplaced.putInt("bucket_index", (BUCKET + 1) % 64);
        assertThrows(
                IllegalArgumentException.class, () -> StorageBucketData.load(NETWORK, (BUCKET + 1) % 64, misplaced));
    }

    @Test
    void malformedRecordsAndDuplicatesIncludingZeroReject() {
        CompoundTag good = encoded();
        CompoundTag badList = good.copy();
        ListTag strings = new ListTag();
        strings.add(StringTag.valueOf("invalid"));
        badList.put("resources", strings);
        assertThrows(IllegalArgumentException.class, () -> StorageBucketData.load(NETWORK, BUCKET, badList));
        for (long amount : new long[] {-1, 0, 1}) {
            CompoundTag bad = good.copy();
            ListTag rows = bad.getList("resources", 10);
            CompoundTag duplicate = rows.getCompound(0).copy();
            duplicate.putLong("amount", amount);
            rows.add(duplicate);
            assertThrows(IllegalArgumentException.class, () -> StorageBucketData.load(NETWORK, BUCKET, bad));
        }
        for (String field : Set.of("type_id", "canonical_bytes", "amount")) {
            CompoundTag bad = good.copy();
            bad.getList("resources", 10).getCompound(0).remove(field);
            assertThrows(IllegalArgumentException.class, () -> StorageBucketData.load(NETWORK, BUCKET, bad));
        }
        CompoundTag oversized = good.copy();
        oversized.getList("resources", 10).getCompound(0).putByteArray("canonical_bytes", new byte[262145]);
        assertThrows(IllegalArgumentException.class, () -> StorageBucketData.load(NETWORK, BUCKET, oversized));
        CompoundTag zero = good.copy();
        zero.getList("resources", 10).getCompound(0).putLong("amount", 0);
        assertEquals(0, StorageBucketData.load(NETWORK, BUCKET, zero).variantCount());
    }

    @Test
    void mutationsValidateBeforeDirtyingAndEnforceOwnerThread() throws InterruptedException {
        StorageBucketData data = StorageBucketData.load(NETWORK, BUCKET, encoded());
        assertThrows(IllegalArgumentException.class, () -> data.setAmount(KEY, -1));
        ResourceVariantKey otherBucket = new ResourceVariantKey(ResourceLocation.parse("neoforge:energy"), new byte[0]);
        assertThrows(IllegalArgumentException.class, () -> data.setAmount(otherBucket, 1));
        assertEquals(4, data.amount(KEY));
        assertFalse(data.isDirty());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                data.setAmount(KEY, 2);
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        });
        thread.start();
        thread.join();
        assertTrue(failure.get() instanceof IllegalStateException);
        assertEquals(4, data.amount(KEY));
        assertFalse(data.isDirty());
    }

    private static CompoundTag encoded() {
        StorageBucketData data = StorageBucketData.create(NETWORK, BUCKET);
        data.setAmount(KEY, 4);
        return data.save(new CompoundTag(), null);
    }
}
