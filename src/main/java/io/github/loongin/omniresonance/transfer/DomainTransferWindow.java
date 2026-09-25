// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

/**
 * Server-thread active input/output quota for exactly one domain configuration and resource type. Owned and
 * discarded with that type's scheduler state; contains no world references, persistent data or idle credit.
 * Budget pause does not finish the window. Queries are simulation-safe; callers debit only proven movement
 * and finish after the actual work round ends, applying separate failure/idle backoff outside this clock.
 */
public final class DomainTransferWindow {
    private final Thread owner = Thread.currentThread();
    private long spent;
    private long nextRunTick;

    /** Nonmutating owner-thread allowance; reloads retain spent credit, and overdue time does not accumulate it. */
    public long available(long tick, long rate) {
        checkThread();
        if (tick < 0 || rate <= 0) throw new IllegalArgumentException("Invalid active domain window query");
        return tick < nextRunTick ? 0 : Math.max(0, rate - spent);
    }

    /** Debits proven positive movement on the owner thread; rejected input leaves quota and deadline unchanged. */
    public void moved(long tick, long amount, long rate) {
        if (amount <= 0 || amount > available(tick, rate))
            throw new IllegalArgumentException("Movement exceeds active domain allowance");
        spent = Math.addExact(spent, amount);
    }

    /** Completes one due work round on the owner thread. Validates time/overflow before changing any state. */
    public void finish(long tick, int intervalTicks) {
        checkThread();
        if (tick < 0 || intervalTicks <= 0) throw new IllegalArgumentException("Invalid active domain interval");
        if (tick < nextRunTick) throw new IllegalStateException("Domain window is not due");
        long deadline = Math.addExact(tick, intervalTicks);
        spent = 0;
        nextRunTick = deadline;
    }

    /** Returns the minimum next start on the owner thread without advancing scheduling or allocating a window. */
    public long nextRunTick() {
        checkThread();
        return nextRunTick;
    }

    private void checkThread() {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Domain transfer window accessed off owner thread");
    }
}
