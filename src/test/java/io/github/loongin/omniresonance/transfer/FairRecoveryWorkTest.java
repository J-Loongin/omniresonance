// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class FairRecoveryWorkTest {
    @Test
    void ordinaryWorkPrecedesRecoveryInsideTheSameNetwork() {
        FairDueScheduler<String> queue = new FairDueScheduler<>();
        UUID network = new UUID(0, 1);
        queue.scheduleLowPriority("recovery", network, 0);
        queue.schedule("first", network, 0);
        queue.schedule("second", network, 0);
        assertEquals("first", queue.poll(0));
        assertEquals("second", queue.poll(0));
        assertEquals("recovery", queue.poll(0));
        assertNull(queue.poll(0));
    }

    @Test
    void priorityDoesNotBreakNetworkRoundRobinAndRemovalClearsReadyEntries() {
        FairDueScheduler<String> queue = new FairDueScheduler<>();
        UUID a = new UUID(0, 1);
        UUID b = new UUID(0, 2);
        queue.scheduleLowPriority("recovery", a, 0);
        queue.schedule("a", a, 0);
        queue.schedule("b", b, 0);
        assertEquals("a", queue.poll(0));
        assertEquals("b", queue.poll(0));
        queue.remove("recovery");
        assertNull(queue.poll(0));
        assertEquals(0, queue.queuedEntries());
    }

    @Test
    void reschedulingSameKeyCanChangePriorityWithoutDuplicatingIt() {
        FairDueScheduler<String> queue = new FairDueScheduler<>();
        UUID a = new UUID(0, 1);
        queue.scheduleLowPriority("promoted", a, 0);
        queue.schedule("ordinary", a, 0);
        queue.schedule("promoted", a, 0);
        assertEquals("ordinary", queue.poll(0));
        assertEquals("promoted", queue.poll(0));
        assertNull(queue.poll(0));
    }
}
