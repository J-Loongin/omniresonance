// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExchangeTelemetryTest {
    @Test
    void eachCommitHasOneSenderAndOneReceiverAndReadsDoNotAgeTheWindow() {
        var telemetry = new ExchangeTelemetry(4);
        UUID a = new UUID(1, 1), b = new UUID(1, 2);
        telemetry.moved(a, b, 5);
        telemetry.moved(b, a, 6);
        assertEquals(1, telemetry.snapshot(a, 6).sent());
        assertEquals(1, telemetry.snapshot(a, 6).received());
        assertEquals(0, telemetry.snapshot(a, 26).sent());
        assertEquals(0, telemetry.snapshot(a, 26).received());
        assertEquals(1, telemetry.snapshot(a, 6).sent());
        assertEquals(1, telemetry.snapshot(b, 6).received());
    }

    @Test
    void boundedEvictionIsExplicitAndNeverPretendsToHaveCompleteCounts() {
        var telemetry = new ExchangeTelemetry(2);
        UUID a = new UUID(1, 1), b = new UUID(1, 2), c = new UUID(1, 3);
        telemetry.moved(a, b, 1);
        telemetry.snapshot(a, 1);
        telemetry.moved(c, b, 2);
        assertFalse(telemetry.snapshot(a, 2).observed());
        assertTrue(telemetry.snapshot(b, 2).lowerBound());
        assertFalse(telemetry.snapshot(b, 22).lowerBound());
        assertEquals(2, telemetry.scopeCount());
    }
}
