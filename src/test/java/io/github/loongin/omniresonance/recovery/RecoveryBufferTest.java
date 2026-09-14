// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.transfer.ResourceVariantKey;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

final class RecoveryBufferTest {
    private static ResourceVariantKey key(int value) {
        return new ResourceVariantKey(ResourceLocation.parse("omniresonance:item"), new byte[] {(byte) value});
    }

    @Test
    void reservationsChargeOneVariantAndOnlyCommittedRemaindersDirty() {
        AtomicInteger dirty = new AtomicInteger();
        RecoveryBuffer b = new RecoveryBuffer(dirty::incrementAndGet);
        ResourceVariantKey k = key(1);
        try (var first = b.reserve(k, 64, 1, 100).orElseThrow();
                var second = b.reserve(k, 32, 1, 100).orElseThrow()) {
            assertEquals(0, b.amount(k));
            assertTrue(b.isEmpty());
            assertEquals(0, dirty.get());
            assertTrue(b.reserve(key(2), 1, 1, 100).isEmpty());
            first.commit(4);
            second.commit(2);
        }
        assertEquals(6, b.amount(k));
        assertEquals(1, b.variantCount());
        assertEquals(k.encodedSizeBytes() + 52, b.encodedBytes());
        assertEquals(2, dirty.get());
        var r = b.reserve(k, 64, 1, 100).orElseThrow();
        r.close();
        r.close();
        assertEquals(2, dirty.get());
    }

    @Test
    void overlapOverflowAndLoweredCapacityAreRejectedWithoutLosingData() {
        RecoveryBuffer b = new RecoveryBuffer(() -> {});
        ResourceVariantKey k = key(1);
        try (var r = b.reserve(k, Long.MAX_VALUE, 2, 100).orElseThrow()) {
            assertTrue(b.reserve(k, 1, 2, 100).isEmpty());
            r.commit(Long.MAX_VALUE);
        }
        assertTrue(b.reserve(k, 1, 2, 100).isEmpty());
        assertTrue(b.reserve(key(2), 1, 0, 100).isEmpty());
        assertTrue(b.reserve(key(2), 1, 2, 1).isEmpty());
        assertEquals(Long.MAX_VALUE, b.amount(k));
    }

    @Test
    void restoreIsAtomicDetachedAndDoesNotDirty() {
        AtomicInteger dirty = new AtomicInteger();
        RecoveryBuffer b = new RecoveryBuffer(dirty::incrementAndGet);
        Map<ResourceVariantKey, Long> input = new HashMap<>();
        input.put(key(1), 4L);
        b.restore(input);
        input.clear();
        Map<ResourceVariantKey, Long> snapshot = b.snapshot();
        snapshot.clear();
        assertEquals(4, b.amount(key(1)));
        assertEquals(0, dirty.get());
        assertThrows(IllegalArgumentException.class, () -> b.restore(Map.of(key(2), -1L)));
        assertEquals(4, b.amount(key(1)));
        try (var r = b.reserve(key(1), 1, 2, 100).orElseThrow()) {
            assertThrows(IllegalStateException.class, () -> b.restore(Map.of()));
            assertThrows(IllegalArgumentException.class, () -> r.commit(2));
            r.commit(1);
            assertThrows(IllegalStateException.class, () -> r.commit(0));
        }
    }

    @Test
    void threadOwnershipIsEnforced() throws InterruptedException {
        RecoveryBuffer b = new RecoveryBuffer(() -> {});
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                b.isEmpty();
            } catch (Throwable ex) {
                failure.set(ex);
            }
        });
        thread.start();
        thread.join();
        assertInstanceOf(IllegalStateException.class, failure.get());
    }

    @Test
    void byteAdmissionMatchesThePersistedNbtEntryExactly() throws java.io.IOException {
        ResourceVariantKey k = key(1);
        net.minecraft.nbt.CompoundTag record = new net.minecraft.nbt.CompoundTag();
        record.putString("type_id", k.typeId().toString());
        record.putByteArray("canonical_bytes", k.canonicalBytes());
        record.putLong("amount", 4);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        record.write(new java.io.DataOutputStream(bytes));
        assertEquals(71, bytes.size());
        RecoveryBuffer buffer = new RecoveryBuffer(() -> {});
        assertTrue(buffer.reserve(k, 64, 1, 70).isEmpty());
        try (var first = buffer.reserve(k, 64, 1, 71).orElseThrow()) {
            first.commit(4);
        }
        assertEquals(bytes.size(), buffer.encodedBytes());
        assertTrue(buffer.reserve(k, 1, 1, 70).isEmpty());
        assertEquals(4, buffer.amount(k));
        assertThrows(IllegalArgumentException.class, () -> buffer.reserve(k, 0, 1, 71));
        try (var reservation = buffer.reserve(k, 1, 1, 71).orElseThrow()) {
            reservation.commit(0);
        }
        assertEquals(4, buffer.amount(k));
    }

    @Test
    void emptyEnergyAndUnknownPayloadsSurviveRestoreSnapshotAndByteAccounting() {
        ResourceVariantKey energy = new ResourceVariantKey(ResourceLocation.parse("neoforge:energy"), new byte[0]);
        ResourceVariantKey unknown = new ResourceVariantKey(ResourceLocation.parse("unknown:resource"), new byte[0]);
        AtomicInteger dirty = new AtomicInteger();
        RecoveryBuffer buffer = new RecoveryBuffer(dirty::incrementAndGet);
        Map<ResourceVariantKey, Long> saved = Map.of(energy, 1000L, unknown, 7L);
        buffer.restore(saved);
        assertEquals(saved, buffer.snapshot());
        assertEquals(1000, buffer.amount(energy));
        assertEquals(7, buffer.amount(unknown));
        assertEquals(2, buffer.variantCount());
        assertEquals(135, buffer.encodedBytes());
        assertEquals(0, dirty.get());
        RecoveryBuffer reloaded = new RecoveryBuffer(() -> {});
        reloaded.restore(buffer.snapshot());
        assertEquals(saved, reloaded.snapshot());
        assertEquals(135, reloaded.encodedBytes());
    }
}
