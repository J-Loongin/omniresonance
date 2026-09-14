// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class FairDueSchedulerTest {
    @Test
    void sleepingTenThousandConfigurationsRequireNoTickScan() {
        FairDueScheduler<Integer> q = new FairDueScheduler<>();
        UUID n = new UUID(0, 1);
        for (int i = 0; i < 10000; i++) q.schedule(i, n, 1000);
        for (int tick = 0; tick < 1000; tick++) assertNull(q.poll(tick));
        assertEquals(0, q.examinedEntries());
        assertEquals(10000, q.queuedEntries());
        assertNotNull(q.poll(1000));
    }

    @Test
    void networksRotateAndRepeatedWakeupsRemainBounded() {
        FairDueScheduler<Integer> q = new FairDueScheduler<>();
        UUID a = new UUID(0, 1), b = new UUID(0, 2);
        q.schedule(1, a, 0);
        q.schedule(2, a, 0);
        q.schedule(3, b, 0);
        q.schedule(4, b, 0);
        assertEquals(1, q.poll(0));
        assertEquals(3, q.poll(0));
        assertEquals(2, q.poll(0));
        assertEquals(4, q.poll(0));
        for (int i = 0; i < 10000; i++) q.schedule(5, a, 1);
        assertTrue(q.queuedEntries() <= 64);
        assertEquals(5, q.poll(1));
        assertNull(q.poll(1));
    }

    @Test
    void duePromotionCanYieldWithoutLosingKeys() {
        FairDueScheduler<Integer> q = new FairDueScheduler<>();
        for (int i = 0; i < 100; i++) q.schedule(i, new UUID(0, i % 10), 0);
        java.util.concurrent.atomic.AtomicInteger remaining = new java.util.concurrent.atomic.AtomicInteger(5);
        q.poll(0, () -> remaining.getAndDecrement() > 0);
        assertTrue(q.examinedEntries() <= 5);
        int found = 1;
        while (q.poll(0) != null) found++;
        assertEquals(100, found);
    }

    @Test
    void thousandActiveConfigurationsRotateAcrossNetworks() {
        FairDueScheduler<Integer> q = new FairDueScheduler<>();
        for (int i = 0; i < 1000; i++) q.schedule(i, new UUID(0, i % 10), 0);
        for (int i = 0; i < 10; i++) assertEquals(i, q.poll(0));
        q.remove(10);
        assertEquals(20, q.poll(0));
        q.clear();
        assertNull(q.poll(100));
        assertEquals(0, q.queuedEntries());
    }
}
