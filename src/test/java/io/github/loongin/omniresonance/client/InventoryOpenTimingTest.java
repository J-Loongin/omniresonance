// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;

final class InventoryOpenTimingTest {
    @Test
    void firstCompleteResultReportsOnceAndReopeningStartsANewMeasurement() {
        long[] now = {100};
        var reports = new ArrayList<InventoryOpenTiming.Sample>();
        var timing = new InventoryOpenTiming(() -> now[0], reports::add);
        timing.begin();
        now[0] = 120;
        timing.frame(50, false);
        timing.ready(2);
        assertTrue(reports.isEmpty(), "Partial mirrors must not count as ready");
        now[0] = 150;
        timing.frame(100, true);
        timing.metadata(10);
        timing.preparation(15);
        now[0] = 200;
        timing.ready(2);
        timing.ready(2);
        timing.metadata(10);
        assertEquals(1, reports.size());
        assertEquals(new InventoryOpenTiming.Sample(100, 20, 50, 15, 10, 1, 1, 2, 2, 150), reports.getFirst());
        assertFalse(timing.active());
        timing.begin();
        now[0] = 210;
        timing.frame(10, true);
        timing.ready(0);
        assertEquals(new InventoryOpenTiming.Sample(10, 10, 10, 0, 0, 0, 0, 0, 1, 10), reports.getLast());
    }

    @Test
    void closingBeforeCompletionCannotEmitALateSample() {
        var reports = new ArrayList<InventoryOpenTiming.Sample>();
        var timing = new InventoryOpenTiming(() -> 0, reports::add);
        timing.begin();
        timing.cancel();
        timing.frame(1, true);
        timing.ready(1);
        assertTrue(reports.isEmpty());
    }
}
