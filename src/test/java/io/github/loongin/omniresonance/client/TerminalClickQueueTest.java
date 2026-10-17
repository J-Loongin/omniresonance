// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.networking.TerminalStorageRequest;
import org.junit.jupiter.api.Test;

class TerminalClickQueueTest {
    @Test
    void boundedWindowAdmitsContinuousIntentsAndRetiresOnlyMatchingResults() {
        var window = new TerminalClickQueue();
        assertFalse(window.busy());
        for (int i = 1; i <= TerminalStorageRequest.MAX_PENDING; i++) assertTrue(window.offer(i));
        assertFalse(window.offer(65));
        assertFalse(window.offer(1));
        assertTrue(window.finish(32));
        assertFalse(window.finish(32));
        assertTrue(window.contains(1));
        assertTrue(window.contains(64));
        assertTrue(window.offer(65));
        assertFalse(window.finish(999));
        window.clear();
        assertFalse(window.busy());
        assertFalse(window.contains(65));
    }
}
