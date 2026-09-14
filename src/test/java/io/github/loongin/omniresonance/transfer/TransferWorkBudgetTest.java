// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

final class TransferWorkBudgetTest {
    @Test
    void countsFailedAndBoundedOverrunWithoutClamping() {
        AtomicLong time = new AtomicLong();
        TransferWorkBudget b = new TransferWorkBudget(1, 100, 20, time::get);
        assertTrue(b.canStart());
        b.beforeCall();
        time.set(21);
        b.afterCall();
        assertTrue(b.slowCall());
        assertFalse(b.canStart());
        b.beforeCall();
        time.set(40);
        b.afterCall();
        assertEquals(2, b.calls());
        assertEquals(40, b.elapsedNanos());
    }

    @Test
    void monotonicCpuBudgetStopsBeforeFirstCall() {
        AtomicLong time = new AtomicLong();
        TransferWorkBudget b = new TransferWorkBudget(100, 50, 20, time::get);
        time.set(50);
        assertFalse(b.canStart());
        assertEquals(0, b.calls());
    }

    @Test
    void wholeSegmentAdmissionIsPureAndUsesLongWithoutOverflow() {
        AtomicLong time = new AtomicLong();
        TransferWorkBudget b = new TransferWorkBudget(6, 50, 20, time::get);
        assertEquals(6, b.remainingCalls());
        assertTrue(b.canFit(6));
        assertFalse(b.canFit(7));
        assertFalse(b.canFit(Long.MAX_VALUE));
        assertEquals(0, b.calls());
        b.beforeCall();
        b.afterCall();
        assertEquals(5, b.remainingCalls());
        assertFalse(b.canFit(6));
        assertTrue(b.canFit(5));
        time.set(50);
        assertFalse(b.canFit(1));
        assertEquals(1, b.calls());
    }
}
