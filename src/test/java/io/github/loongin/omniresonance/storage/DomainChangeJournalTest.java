// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.persistence.StorageBucketData;
import io.github.loongin.omniresonance.transfer.ResourceVariantKey;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class DomainChangeJournalTest {
    private static final UUID NETWORK = new UUID(10, 20);

    private static ResourceVariantKey key(int value) {
        return new ResourceVariantKey(ResourceLocation.parse("example:opaque"), new byte[] {(byte) value});
    }

    private static DomainLedger ledger() {
        return new DomainLedger(NETWORK, Map.of(), i -> StorageBucketData.create(NETWORK, i));
    }

    private static void put(DomainLedger ledger, int key, long amount) {
        try (var deposit = ledger.reserveDeposit(key(key), amount, -1).orElseThrow()) {
            deposit.commit(amount);
        }
    }

    @Test
    void snapshotFenceAndAbsoluteChangesIncludeDeletionAndReintroducedIdentity() {
        var ledger = ledger();
        put(ledger, 1, 5);
        try (var changes = ledger.openChanges(8)) {
            long ceiling = ledger.sequenceCeiling();
            assertEquals(1, ledger.nextWithin(0, ceiling).orElseThrow().sequence());
            assertTrue(ledger.nextWithin(ceiling, ceiling).isEmpty());
            try (var taken = ledger.withdraw(key(1), 5).orElseThrow()) {}
            put(ledger, 1, 7);
            put(ledger, 2, 9);
            assertTrue(ledger.nextWithin(0, ceiling).isEmpty());
            assertEquals(3, changes.pendingCount());
            var zero = changes.poll().orElseThrow();
            assertEquals(0, zero.amount());
            assertEquals(1, zero.sequence());
            var reintroduced = changes.poll().orElseThrow();
            assertEquals(7, reintroduced.amount());
            assertTrue(reintroduced.sequence() > ceiling);
            assertTrue(reintroduced.revision() > zero.revision());
            assertEquals(9, changes.poll().orElseThrow().amount());
        }
    }

    @Test
    void coalescesLatestAbsoluteAmountsAndFailsClosedOnOverflowWithoutBlockingInventory() {
        var ledger = ledger();
        try (var changes = ledger.openChanges(1)) {
            put(ledger, 1, 2);
            put(ledger, 1, 3);
            assertEquals(1, changes.pendingCount());
            assertEquals(5, changes.poll().orElseThrow().amount());
            put(ledger, 1, 1);
            put(ledger, 2, 1);
            assertTrue(changes.failed());
            assertEquals(0, changes.pendingCount());
            assertThrows(IllegalStateException.class, changes::poll);
            assertEquals(6, ledger.amount(key(1)));
            assertEquals(1, ledger.amount(key(2)));
        }
        try (var reopened = ledger.openChanges(2)) {
            assertFalse(reopened.failed());
        }
    }

    @Test
    void reservationsAndSimulationDoNotGenerateChangesAndClosingReleasesTheJournal() {
        var ledger = ledger();
        try (var changes = ledger.openChanges(2)) {
            assertThrows(IllegalStateException.class, () -> ledger.openChanges(2));
            ledger.insertCapacity(key(1), -1);
            try (var empty = ledger.reserveDeposit(key(1), 3, -1).orElseThrow()) {
                empty.commit(0);
            }
            assertEquals(0, ledger.revision());
            assertEquals(0, changes.pendingCount());
            put(ledger, 1, 2);
            ledger.invalidate();
            assertTrue(changes.failed());
            assertThrows(IllegalStateException.class, changes::poll);
        }
    }

    @Test
    void revisionCapacityIsReservedForKnownReturnsBeforeAnotherExtraction() throws ReflectiveOperationException {
        var ledger = ledger();
        put(ledger, 1, 5);
        var field = DomainLedger.class.getDeclaredField("revision");
        field.setAccessible(true);
        field.setLong(ledger, Long.MAX_VALUE - 2);
        try (var changes = ledger.openChanges(4);
                var held = ledger.withdraw(key(1), 1).orElseThrow()) {
            assertTrue(ledger.reserveDeposit(key(2), 1, -1).isEmpty());
            held.returnRemainder(1);
            assertEquals(Long.MAX_VALUE, ledger.revision());
            assertEquals(5, changes.poll().orElseThrow().amount());
            assertTrue(ledger.withdraw(key(1), 1).isEmpty());
            assertEquals(5, ledger.amount(key(1)));
        }
    }

    @Test
    void tenThousandSameKeyUpdatesKeepExactlyOnePendingRecord() {
        var ledger = ledger();
        try (var changes = ledger.openChanges(1)) {
            for (int i = 0; i < 10000; i++) put(ledger, 1, 1);
            assertEquals(1, changes.pendingCount());
            assertEquals(10000, changes.poll().orElseThrow().amount());
            assertEquals(10000, ledger.revision());
            assertTrue(changes.poll().isEmpty());
        }
    }
}
