// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.loongin.omniresonance.persistence.StorageBucketData;
import io.github.loongin.omniresonance.transfer.ResourceVariantKey;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class DomainExchangeTest {
    private static final UUID SOURCE = new UUID(1, 1), TARGET = new UUID(1, 2);
    private static final ResourceVariantKey KEY =
            new ResourceVariantKey(ResourceLocation.parse("example:resource"), new byte[] {1, 2, 3});

    private static DomainLedger ledger(UUID id, AtomicInteger created) {
        return new DomainLedger(id, Map.of(), index -> {
            created.incrementAndGet();
            return StorageBucketData.create(id, index);
        });
    }

    private static void deposit(DomainLedger ledger, long amount) {
        try (var slot = ledger.reserveDeposit(KEY, amount, -1).orElseThrow()) {
            slot.commit(amount);
        }
    }

    @Test
    void repeatedBidirectionalMovesConserveResourcesWithFixedSeed() {
        AtomicInteger created = new AtomicInteger();
        DomainLedger source = ledger(SOURCE, created), target = ledger(TARGET, created);
        deposit(source, 1000000);
        java.util.Random random = new java.util.Random(713);
        for (int step = 0; step < 2000; step++) {
            DomainLedger from = random.nextBoolean() ? source : target;
            DomainLedger to = from == source ? target : source;
            long requested = random.nextInt(10000);
            long expected = Math.min(requested, from.amount(KEY));
            assertEquals(expected, from.transferTo(to, KEY, requested, -1));
            assertEquals(1000000, source.amount(KEY) + target.amount(KEY));
            assertFalse(source.hasReservations());
            assertFalse(target.hasReservations());
        }
        assertEquals(2, created.get());
    }

    @Test
    void transfersLongMaximumWithoutQuantitySizedWork() {
        AtomicInteger created = new AtomicInteger();
        DomainLedger source = ledger(SOURCE, created), target = ledger(TARGET, created);
        deposit(source, Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, source.transferTo(target, KEY, Long.MAX_VALUE, -1));
        assertEquals(0, source.amount(KEY));
        assertEquals(Long.MAX_VALUE, target.amount(KEY));
        assertEquals(2, created.get());
        assertEquals(2, source.revision());
        assertEquals(1, target.revision());
        assertFalse(source.hasReservations());
        assertFalse(target.hasReservations());
    }

    @Test
    void capacityAndVariantQuotaClampBeforeWithdrawal() {
        AtomicInteger created = new AtomicInteger();
        DomainLedger source = ledger(SOURCE, created), target = ledger(TARGET, created);
        deposit(source, 100);
        assertEquals(0, source.transferTo(target, KEY, 100, 0));
        assertEquals(1, created.get());
        deposit(target, Long.MAX_VALUE - 7);
        assertEquals(7, source.transferTo(target, KEY, 100, 0));
        assertEquals(93, source.amount(KEY));
        assertEquals(Long.MAX_VALUE, target.amount(KEY));
        assertEquals(0, source.transferTo(target, KEY, 100, -1));
        assertFalse(source.hasReservations());
        assertFalse(target.hasReservations());
    }

    @Test
    void invalidAndSelfTransfersNeverChangeQuantities() {
        AtomicInteger created = new AtomicInteger();
        DomainLedger source = ledger(SOURCE, created),
                alias = ledger(SOURCE, created),
                target = ledger(TARGET, created);
        deposit(source, 100);
        assertThrows(IllegalArgumentException.class, () -> source.transferTo(source, KEY, 1, -1));
        assertThrows(IllegalArgumentException.class, () -> source.transferTo(alias, KEY, 1, -1));
        assertThrows(IllegalArgumentException.class, () -> source.transferTo(target, KEY, -1, -1));
        assertThrows(IllegalArgumentException.class, () -> source.transferTo(target, KEY, 1, -2));
        assertEquals(0, source.transferTo(target, KEY, 0, -1));
        assertEquals(100, source.amount(KEY));
        assertEquals(1, created.get());
    }

    @Test
    void failedOrInvalidatingBucketCreationCannotExtractSource() {
        AtomicInteger created = new AtomicInteger();
        DomainLedger source = ledger(SOURCE, created);
        deposit(source, 100);
        DomainLedger broken = new DomainLedger(TARGET, Map.of(), index -> {
            throw new IllegalStateException("Injected registration failure");
        });
        assertThrows(IllegalStateException.class, () -> source.transferTo(broken, KEY, 64, -1));
        assertEquals(100, source.amount(KEY));
        DomainLedger[] target = new DomainLedger[1];
        target[0] = new DomainLedger(TARGET, Map.of(), index -> {
            target[0].invalidate();
            return StorageBucketData.create(TARGET, index);
        });
        assertEquals(0, source.transferTo(target[0], KEY, 64, -1));
        assertEquals(100, source.amount(KEY));
        assertFalse(source.hasReservations());
    }
}
