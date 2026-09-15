// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.persistence.StorageBucketData;
import io.github.loongin.omniresonance.storage.DomainLedger;
import io.github.loongin.omniresonance.transfer.ResourceVariantKey;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

final class RecoveryDrainTest {
    private static final UUID NETWORK = new UUID(8, 10);
    private static final ResourceVariantKey A = key(1);
    private static final ResourceVariantKey B = key(2);

    @Test
    void wholeLongAmountMovesInOneStepAndFreesBufferCapacity() {
        AtomicInteger dirty = new AtomicInteger();
        RecoveryBuffer buffer = new RecoveryBuffer(dirty::incrementAndGet);
        buffer.restore(Map.of(A, Long.MAX_VALUE));
        DomainLedger ledger = ledger();
        assertEquals(Long.MAX_VALUE, buffer.drainOne(ledger, -1));
        assertEquals(Long.MAX_VALUE, ledger.amount(A));
        assertTrue(buffer.isEmpty());
        assertEquals(0, buffer.encodedBytes());
        assertEquals(1, dirty.get());
        try (var reservation = buffer.reserve(B, 1, 1, 1000).orElseThrow()) {
            assertTrue(buffer.isEmpty());
        }
    }

    @Test
    void partialDrainRetainsKnownRemainderAndLiveReservations() {
        RecoveryBuffer buffer = new RecoveryBuffer(() -> {});
        buffer.restore(Map.of(A, 10L));
        DomainLedger ledger = ledger();
        try (var deposit = ledger.reserveDeposit(A, Long.MAX_VALUE - 4, -1).orElseThrow()) {
            deposit.commit(Long.MAX_VALUE - 4);
        }
        try (var reserved = buffer.reserve(A, 5, 1, 1000).orElseThrow()) {
            assertEquals(4, buffer.drainOne(ledger, 0));
            assertEquals(6, buffer.amount(A));
            assertEquals(Long.MAX_VALUE, ledger.amount(A));
            reserved.commit(5);
        }
        assertEquals(11, buffer.amount(A));
        assertFalse(buffer.hasActiveReservations());
    }

    @Test
    void blockedVariantDoesNotStarveAnotherVariantAndOnlyOneMovesPerStep() {
        RecoveryBuffer buffer = new RecoveryBuffer(() -> {});
        buffer.restore(Map.of(A, 4L, B, 6L));
        DomainLedger ledger = ledger();
        try (var deposit = ledger.reserveDeposit(B, 1, -1).orElseThrow()) {
            deposit.commit(1);
        }
        long moved = buffer.drainOne(ledger, 1) + buffer.drainOne(ledger, 1);
        assertEquals(6, moved);
        assertEquals(4, buffer.amount(A));
        assertEquals(7, ledger.amount(B));
        assertEquals(1, buffer.variantCount());
    }

    @Test
    void failedDestinationCreationPreservesBufferAndDirtyState() {
        AtomicInteger dirty = new AtomicInteger();
        RecoveryBuffer buffer = new RecoveryBuffer(dirty::incrementAndGet);
        buffer.restore(Map.of(A, 4L));
        DomainLedger rejected = new DomainLedger(NETWORK, Map.of(), index -> {
            throw new IllegalStateException("Registration rejected");
        });
        assertThrows(IllegalStateException.class, () -> buffer.drainOne(rejected, -1));
        assertEquals(4, buffer.amount(A));
        assertEquals(0, dirty.get());
        assertFalse(buffer.hasActiveReservations());
    }

    private static DomainLedger ledger() {
        return new DomainLedger(NETWORK, Map.of(), index -> StorageBucketData.create(NETWORK, index));
    }

    private static ResourceVariantKey key(int value) {
        return new ResourceVariantKey(ResourceLocation.parse("example:opaque"), new byte[] {(byte) value});
    }
}
