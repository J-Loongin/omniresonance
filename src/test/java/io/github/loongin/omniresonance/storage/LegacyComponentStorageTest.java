// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.persistence.StorageBucketData;
import io.github.loongin.omniresonance.transfer.CanonicalResourceNbt;
import io.github.loongin.omniresonance.transfer.ItemVariant;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import io.github.loongin.omniresonance.transfer.ResourceVariantKey;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

class LegacyComponentStorageTest {
    private static final UUID NETWORK = new UUID(414, 51);
    private static final HolderLookup.Provider PROVIDER =
            net.minecraft.data.registries.VanillaRegistries.createLookup();

    @Test
    void equivalentLegacyRecordsRemainReadableWithoutRewritingTheirBuckets() {
        var key = ItemVariant.from(new ItemStack(Items.ENCHANTED_GOLDEN_APPLE), PROVIDER)
                .key();
        var old = reversed(key);
        Map<Integer, StorageBucketData> buckets = new HashMap<>();
        put(buckets, key, 32);
        put(buckets, old, 64);
        buckets.values().forEach(b -> b.setDirty(false));
        var ledger = ledger(buckets);
        assertEquals(96, ledger.amount(key));
        assertEquals(96, ledger.amount(old));
        assertEquals(1, ledger.variantCount());
        assertEquals(ledger.sequence(key), ledger.sequence(old));
        assertEquals(32, buckets.get(StorageBucketHash.bucket(key)).amount(key));
        assertEquals(64, buckets.get(StorageBucketHash.bucket(old)).amount(old));
        assertTrue(buckets.values().stream().noneMatch(StorageBucketData::isDirty));
        assertEquals(Long.MAX_VALUE - 96, ledger.insertCapacity(key, 0));
        try (var withdrawal = ledger.withdraw(old, 80).orElseThrow()) {
            withdrawal.returnRemainder(7);
        }
        assertEquals(23, ledger.amount(key));
        try (var deposit = ledger.reserveDeposit(key, 41, 0).orElseThrow()) {
            deposit.commit(41);
        }
        assertEquals(64, ledger.amount(old));
        Map<Integer, StorageBucketData> reloaded = new HashMap<>();
        for (var row : buckets.entrySet())
            reloaded.put(
                    row.getKey(),
                    StorageBucketData.load(NETWORK, row.getKey(), row.getValue().save(new CompoundTag(), PROVIDER)));
        var reopened = ledger(reloaded);
        assertEquals(64, reopened.amount(key));
        try (var all = reopened.withdraw(key, 64).orElseThrow()) {}
        assertEquals(0, reopened.variantCount());
        assertTrue(reloaded.values().stream().allMatch(b -> b.variantCount() == 0));
    }

    @Test
    void equivalentLegacyQuantityOverflowRejectsWithoutMutatingOriginals() {
        var key = ItemVariant.from(new ItemStack(Items.ENCHANTED_GOLDEN_APPLE), PROVIDER)
                .key();
        var old = reversed(key);
        Map<Integer, StorageBucketData> buckets = new HashMap<>();
        put(buckets, key, Long.MAX_VALUE);
        put(buckets, old, 1);
        buckets.values().forEach(b -> b.setDirty(false));
        assertThrows(ArithmeticException.class, () -> ledger(buckets));
        assertEquals(Long.MAX_VALUE, buckets.get(StorageBucketHash.bucket(key)).amount(key));
        assertEquals(1, buckets.get(StorageBucketHash.bucket(old)).amount(old));
        assertTrue(buckets.values().stream().noneMatch(StorageBucketData::isDirty));
    }

    private static ResourceVariantKey reversed(ResourceVariantKey key) {
        var root = (CompoundTag) CanonicalResourceNbt.decode(key.canonicalBytes());
        var effect = root.getCompound("components")
                .getCompound("minecraft:food")
                .getList("effects", Tag.TAG_COMPOUND)
                .getCompound(0)
                .getCompound("effect");
        var cures = effect.getList("neoforge:cures", Tag.TAG_STRING);
        var reverse = new ListTag();
        for (int i = cures.size() - 1; i >= 0; i--) reverse.add(cures.get(i).copy());
        effect.put("neoforge:cures", reverse);
        return new ResourceVariantKey(ResourceTypes.ITEM, CanonicalResourceNbt.encode(root));
    }

    private static void put(Map<Integer, StorageBucketData> buckets, ResourceVariantKey key, long amount) {
        buckets.computeIfAbsent(StorageBucketHash.bucket(key), i -> StorageBucketData.create(NETWORK, i))
                .setAmount(key, amount);
    }

    private static DomainLedger ledger(Map<Integer, StorageBucketData> buckets) {
        return new DomainLedger(NETWORK, buckets, i -> {
            var bucket = StorageBucketData.create(NETWORK, i);
            buckets.put(i, bucket);
            return bucket;
        });
    }
}
