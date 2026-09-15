// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TerminalClickQueueTest {
    @Test
    void deliberateClicksWaitInOrderButFailureAndCloseNeverReplayThem() {
        var queue = new TerminalClickQueue();
        var take = new TerminalClickQueue.Click(7, -1, 0, false);
        var place = new TerminalClickQueue.Click(0, 0, 1, true);
        assertTrue(queue.offer(take));
        assertEquals(take, queue.start());
        assertTrue(queue.offer(place));
        assertNull(queue.start());
        queue.finish(true);
        assertEquals(place, queue.start());
        queue.offer(take);
        queue.finish(false);
        assertNull(queue.start());
        for (int i = 0; i < 16; i++) assertTrue(queue.offer(place));
        assertFalse(queue.offer(take));
        queue.clear();
        assertNull(queue.start());
    }
}
