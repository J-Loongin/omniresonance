// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.material;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Pure contracts for one bounded dragon-breath resonance batch. */
final class ResonanceProgressTest {
    @Test
    void startAndInjectionUseExactOneHundredGameTickDeadline() {
        ResonanceProgress started = ResonanceProgress.start(10);
        ResonanceProgress injected = started.inject(50);

        assertEquals(new ResonanceProgress(1, 110), started);
        assertEquals(new ResonanceProgress(2, 150), injected);
        assertEquals(new ResonanceProgress(1, 110), started, "Injection mutated the old immutable value");
    }

    @Test
    void fullBatchCannotAcceptAnotherBreath() {
        ResonanceProgress full = new ResonanceProgress(64, 100);

        assertTrue(full.isFull());
        assertThrows(IllegalStateException.class, () -> full.inject(1));
    }

    @Test
    void dueAndRemainingTicksHaveExactBoundaries() {
        ResonanceProgress progress = new ResonanceProgress(1, 100);

        assertFalse(progress.isDue(99));
        assertEquals(1, progress.remainingTicks(99));
        assertTrue(progress.isDue(100));
        assertEquals(0, progress.remainingTicks(100));
        assertTrue(progress.isDue(101));
        assertEquals(0, progress.remainingTicks(101));
    }

    @Test
    void invalidStateAndNegativeClockAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ResonanceProgress(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ResonanceProgress(65, 1));
        assertThrows(IllegalArgumentException.class, () -> new ResonanceProgress(1, -1));
        assertThrows(IllegalArgumentException.class, () -> ResonanceProgress.start(-1));
        assertThrows(IllegalArgumentException.class, () -> new ResonanceProgress(1, 1).inject(-1));
        assertThrows(IllegalArgumentException.class, () -> new ResonanceProgress(1, 1).isDue(-1));
        assertThrows(IllegalArgumentException.class, () -> new ResonanceProgress(1, 1).remainingTicks(-1));
    }

    @Test
    void deadlineOverflowIsRejectedBeforeAValueCanBeCreated() {
        assertThrows(ArithmeticException.class, () -> ResonanceProgress.start(Long.MAX_VALUE));
        assertThrows(ArithmeticException.class, () -> new ResonanceProgress(1, 1).inject(Long.MAX_VALUE));
    }
}
