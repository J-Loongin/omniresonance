// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class DomainTransferWindowTest {
    @Test
    void budgetPauseRetainsSpentQuotaUntilActualWindowCompletion() {
        var window = new DomainTransferWindow();
        assertEquals(64, window.available(0, 64));
        window.moved(0, 32, 64);
        assertEquals(32, window.available(100, 64));
        window.moved(100, 32, 64);
        assertEquals(0, window.available(100, 64));
        window.finish(100, 20);
        assertEquals(0, window.available(119, 64));
        assertEquals(64, window.available(120, 64));
        assertEquals(64, window.available(10000, 64));
    }

    @Test
    void distinctResourcesNeverShareCooldownOrCredit() {
        var items = new DomainTransferWindow();
        var fluids = new DomainTransferWindow();
        items.moved(10, 64, 64);
        items.finish(10, 20);
        assertEquals(0, items.available(11, 64));
        assertEquals(1000, fluids.available(11, 1000));
        fluids.moved(11, 200, 1000);
        assertEquals(800, fluids.available(20, 1000));
        assertEquals(64, items.available(30, 64));
    }

    @Test
    void rateChangesAndRepeatedQueriesDoNotGiftQuotaAndOverflowRejectsAtomically() {
        var window = new DomainTransferWindow();
        window.moved(0, 32, 64);
        assertEquals(0, window.available(0, 16));
        assertEquals(96, window.available(0, 128));
        assertThrows(IllegalArgumentException.class, () -> window.moved(0, 97, 128));
        assertThrows(ArithmeticException.class, () -> window.finish(Long.MAX_VALUE, 1));
        assertEquals(32, window.available(1, 64));
        window.finish(1, 20);
        assertThrows(IllegalStateException.class, () -> window.finish(2, 20));
        assertThrows(IllegalArgumentException.class, () -> window.moved(2, 1, 64));
        assertEquals(0, window.available(20, 64));
        assertEquals(64, window.available(21, 64));
    }
}
