// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.persistence.StorageBucketData;
import io.github.loongin.omniresonance.transfer.ResourceVariantKey;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

final class DomainLedgerTest {
    @Test
    void failedRuntimeObserverCannotTurnSettledOwnershipIntoACommitException() {
        DomainLedger ledger = ledger(new HashMap<>());
        ledger.onRetired(key -> {
            throw new IllegalStateException("Derived cache failure");
        });
        deposit(ledger, A, 1);
        var consumed = ledger.withdraw(A, 1).orElseThrow();
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(consumed::close);
        assertEquals(0, ledger.amount(A));
        assertFalse(ledger.hasReservations());
        ledger.onRetired(null);
    }

    @Test
    void runtimeRetirementOccursOnlyWhenZeroQuantityHasNoOutstandingOwnership() {
        DomainLedger ledger = ledger(new HashMap<>());
        java.util.List<ResourceVariantKey> retired = new java.util.ArrayList<>();
        ledger.onRetired(retired::add);
        deposit(ledger, A, 64);
        long sequence = ledger.sequence(A);
        try (var held = ledger.withdraw(A, 64).orElseThrow()) {
            assertEquals(0, ledger.amount(A));
            assertTrue(retired.isEmpty());
            held.returnRemainder(4);
        }
        assertEquals(sequence, ledger.sequence(A));
        assertTrue(retired.isEmpty());
        try (var used = ledger.withdraw(A, 4).orElseThrow()) {
            assertTrue(retired.isEmpty());
        }
        assertEquals(java.util.List.of(A), retired);
        deposit(ledger, A, 1);
        assertTrue(ledger.sequence(A) > sequence);
        assertTrue(ledger.sequenceCeiling() >= ledger.sequence(A));
    }

    private static final UUID NETWORK = new UUID(4, 5);
    private static final ResourceVariantKey A = key(1);
    private static final ResourceVariantKey B = key(2);

    @Test
    void simulationDoesNotCreateBucketsOrChangeSequence() {
        Map<Integer, StorageBucketData> buckets = new HashMap<>();
        DomainLedger ledger = ledger(buckets);
        assertEquals(Long.MAX_VALUE, ledger.insertCapacity(A, -1));
        assertEquals(0, ledger.insertCapacity(A, 0));
        assertEquals(0, ledger.amount(A));
        assertEquals(0, ledger.variantCount());
        assertTrue(ledger.nextAfter(0).isEmpty());
        assertTrue(buckets.isEmpty());
        assertFalse(ledger.hasReservations());
    }

    @Test
    void reservationsPreventDoubleAdmissionAndOverflowWithoutDirtyingExistingBucket() {
        Map<Integer, StorageBucketData> buckets = new HashMap<>();
        DomainLedger ledger = ledger(buckets);
        try (DomainLedger.Deposit first = ledger.reserveDeposit(A, 10, 1).orElseThrow()) {
            assertTrue(ledger.hasReservations());
            assertEquals(0, ledger.variantCount());
            assertTrue(ledger.reserveDeposit(B, 1, 1).isEmpty());
            assertEquals(Long.MAX_VALUE - 10, ledger.insertCapacity(A, 1));
            assertTrue(ledger.reserveDeposit(A, Long.MAX_VALUE, 1).isEmpty());
            first.commit(7);
        }
        assertFalse(ledger.hasReservations());
        assertEquals(7, ledger.amount(A));
        StorageBucketData bucket = buckets.get(StorageBucketHash.bucket(A));
        bucket.setDirty(false);
        try (DomainLedger.Deposit cancelled = ledger.reserveDeposit(A, 1, 0).orElseThrow()) {
            assertFalse(bucket.isDirty());
            assertThrows(IllegalArgumentException.class, () -> cancelled.commit(2));
        }
        assertFalse(bucket.isDirty());
        assertEquals(7, ledger.amount(A));
        assertTrue(ledger.reserveDeposit(B, 1, 0).isEmpty());
    }

    @Test
    void withdrawalKeepsCapacityForKnownRemainderEvenAfterQuotaReduction() {
        Map<Integer, StorageBucketData> buckets = new HashMap<>();
        DomainLedger ledger = ledger(buckets);
        deposit(ledger, A, Long.MAX_VALUE);
        try (DomainLedger.Withdrawal withdrawal =
                ledger.withdraw(A, Long.MAX_VALUE).orElseThrow()) {
            assertEquals(0, ledger.amount(A));
            assertEquals(0, ledger.variantCount());
            assertEquals(0, ledger.insertCapacity(A, -1));
            assertTrue(ledger.reserveDeposit(B, 1, 1).isEmpty());
            assertThrows(IllegalArgumentException.class, () -> withdrawal.returnRemainder(-1));
            withdrawal.returnRemainder(Long.MAX_VALUE - 3);
        }
        assertEquals(Long.MAX_VALUE - 3, ledger.amount(A));
        assertFalse(ledger.hasReservations());
        assertEquals(3, ledger.insertCapacity(A, 0));
        assertEquals(ledger.amount(A), buckets.get(StorageBucketHash.bucket(A)).amount(A));
    }

    @Test
    void quantityChangesPreserveSequenceAndRemovedResourcesRejoinAtTail() {
        DomainLedger ledger = ledger(new HashMap<>());
        deposit(ledger, A, 10);
        deposit(ledger, B, 20);
        DomainLedger.Cursor first = ledger.nextAfter(0).orElseThrow();
        assertEquals(A, first.key());
        deposit(ledger, A, 30);
        assertEquals(B, ledger.nextAfter(first.sequence()).orElseThrow().key());
        try (DomainLedger.Withdrawal removed = ledger.withdraw(A, 40).orElseThrow()) {
            assertEquals(B, ledger.nextAfter(0).orElseThrow().key());
        }
        deposit(ledger, A, 1);
        DomainLedger.Cursor second = ledger.nextAfter(0).orElseThrow();
        assertEquals(B, second.key());
        assertEquals(A, ledger.nextAfter(second.sequence()).orElseThrow().key());
        assertEquals(B, ledger.nextAfter(Long.MAX_VALUE).orElseThrow().key());
    }

    @Test
    void restoredBucketsAreValidatedAndOnlyChangedBucketIsDirtied() {
        Map<Integer, StorageBucketData> buckets = new HashMap<>();
        StorageBucketData a = StorageBucketData.create(NETWORK, StorageBucketHash.bucket(A));
        a.setAmount(A, 12);
        buckets.put(a.bucketIndex(), a);
        a.setDirty(false);
        DomainLedger ledger = ledger(buckets);
        assertEquals(12, ledger.amount(A));
        assertFalse(a.isDirty());
        assertTrue(ledger.withdraw(A, 13).isEmpty());
        assertFalse(a.isDirty());
        deposit(ledger, B, 9);
        if (StorageBucketHash.bucket(A) != StorageBucketHash.bucket(B)) assertFalse(a.isDirty());
        StorageBucketData wrongNetwork = StorageBucketData.create(new UUID(8, 9), 0);
        assertThrows(
                IllegalArgumentException.class,
                () -> new DomainLedger(NETWORK, Map.of(0, wrongNetwork), index -> wrongNetwork));
    }

    @Test
    void failedBucketCreationCannotLoseIncomingResourcesOrLeaveReservations() {
        DomainLedger ledger = new DomainLedger(NETWORK, Map.of(), index -> {
            throw new IllegalStateException("Rejected registration");
        });
        assertThrows(IllegalStateException.class, () -> ledger.reserveDeposit(A, 1, -1));
        assertEquals(0, ledger.amount(A));
        assertFalse(ledger.hasReservations());
        assertTrue(ledger.nextAfter(0).isEmpty());
    }

    private static DomainLedger ledger(Map<Integer, StorageBucketData> buckets) {
        return new DomainLedger(NETWORK, buckets, index -> {
            StorageBucketData bucket = StorageBucketData.create(NETWORK, index);
            buckets.put(index, bucket);
            return bucket;
        });
    }

    private static void deposit(DomainLedger ledger, ResourceVariantKey key, long amount) {
        try (DomainLedger.Deposit deposit =
                ledger.reserveDeposit(key, amount, -1).orElseThrow()) {
            deposit.commit(amount);
        }
    }

    private static ResourceVariantKey key(int value) {
        return new ResourceVariantKey(ResourceLocation.parse("example:resource"), new byte[] {(byte) value});
    }
}
