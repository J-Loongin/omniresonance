// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class ReceiveWindowTest {
    @Test
    void sharedWindowAnchorsOnlyActualReceptionAndDoesNotAccumulate() {
        ReceiveWindow w = new ReceiveWindow();
        assertEquals(64, w.available(100, 64, 20));
        assertEquals(0, w.expiresTick());
        w.received(100, 32, 64, 20);
        assertEquals(32, w.available(105, 64, 20));
        assertEquals(120, w.expiresTick());
        w.received(105, 32, 64, 20);
        assertEquals(0, w.available(106, 64, 20));
        assertEquals(120, w.expiresTick());
        assertEquals(64, w.available(120, 64, 20));
        assertEquals(120, w.expiresTick());
        assertEquals(64, w.available(1000, 64, 20));
    }

    @Test
    void expiryOverflowRejectsBeforeACommitCanStart() {
        ReceiveWindow w = new ReceiveWindow();
        assertThrows(ArithmeticException.class, () -> w.available(Long.MAX_VALUE, 64, 20));
    }

    @Test
    void policyChangesKeepExistingDeadlineAndCharge() {
        ReceiveWindow w = new ReceiveWindow();
        w.received(100, 32, 64, 20);
        assertEquals(0, w.available(101, 16, 1));
        assertEquals(96, w.available(101, 128, 100));
        assertEquals(120, w.expiresTick());
        assertThrows(IllegalArgumentException.class, () -> w.received(101, 97, 128, 100));
        w.received(101, 0, 128, 100);
        assertEquals(120, w.expiresTick());
    }

    @Test
    void uncertainInsertWithoutReceptionQuarantinesOneIntervalWithoutOpeningAReceiptWindow() {
        ReceiveWindow w = new ReceiveWindow();

        w.quarantineUncertainTargetInsert(100, 20);

        assertEquals(0, w.expiresTick());
        assertEquals(120, w.uncertainUntilTick());
        assertEquals(0, w.available(100, 64, 20));
        assertThrows(IllegalArgumentException.class, () -> w.received(100, 1, 64, 20));
        w.received(100, 0, 64, 20);
        assertEquals(0, w.expiresTick());
        assertEquals(120, w.uncertainUntilTick());
    }

    @Test
    void uncertainInsertDuringReceiptWindowPreservesItsDeadlineAndCharge() {
        ReceiveWindow w = new ReceiveWindow();
        w.received(100, 24, 64, 20);

        w.quarantineUncertainTargetInsert(105, 200);

        assertEquals(120, w.expiresTick());
        assertEquals(120, w.uncertainUntilTick());
        assertEquals(0, w.available(105, 128, 1));
        assertEquals(64, w.available(120, 64, 20));
    }

    @Test
    void repeatedQuarantineAndPolicyQueriesCannotMoveTheBarrier() {
        ReceiveWindow w = new ReceiveWindow();
        w.quarantineUncertainTargetInsert(100, 20);

        assertEquals(0, w.available(101, 1, 1));
        w.quarantineUncertainTargetInsert(110, 1_000);
        assertEquals(0, w.available(119, 1_000, 1_000));
        assertEquals(120, w.uncertainUntilTick());

        assertEquals(1_000, w.available(120, 1_000, 1));
        w.quarantineUncertainTargetInsert(120, 5);
        assertEquals(125, w.uncertainUntilTick());
        assertEquals(0, w.available(120, 1_000, 1_000));
    }

    @Test
    void quarantineOverflowAndInvalidIntervalRejectWithoutChangingState() {
        ReceiveWindow w = new ReceiveWindow();

        assertThrows(IllegalArgumentException.class, () -> w.quarantineUncertainTargetInsert(100, 0));
        assertThrows(ArithmeticException.class, () -> w.quarantineUncertainTargetInsert(Long.MAX_VALUE, 1));

        assertEquals(0, w.expiresTick());
        assertEquals(0, w.uncertainUntilTick());
        assertEquals(64, w.available(100, 64, 20));
    }

    @Test
    void availabilityQueriesDoNotModifyQuarantineOrReceiptState() {
        ReceiveWindow w = new ReceiveWindow();
        w.received(100, 16, 64, 20);
        w.quarantineUncertainTargetInsert(105, 20);

        assertEquals(0, w.available(106, 128, 1));
        assertEquals(0, w.available(119, 1, 1_000));

        assertEquals(120, w.expiresTick());
        assertEquals(120, w.uncertainUntilTick());
        assertEquals(128, w.available(120, 128, 20));
    }
}
