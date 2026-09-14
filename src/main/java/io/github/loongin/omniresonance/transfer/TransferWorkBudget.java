// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import java.util.Objects;
import java.util.function.LongSupplier;

/** One server-tick soft budget. Count before invoking external code, including throwing calls. */
public final class TransferWorkBudget {
    private final int maximumCalls;
    private final long durationNanos, slowCallNanos, startNanos;
    private final LongSupplier clock;
    private long calls, callStartNanos, slowCalls;

    public TransferWorkBudget(int calls, long durationNanos, long slowCallNanos, LongSupplier clock) {
        if (calls <= 0 || durationNanos <= 0 || slowCallNanos <= 0)
            throw new IllegalArgumentException("Invalid work budget");
        maximumCalls = calls;
        this.durationNanos = durationNanos;
        this.slowCallNanos = slowCallNanos;
        this.clock = Objects.requireNonNull(clock);
        startNanos = clock.getAsLong();
    }

    public boolean canStart() {
        return calls < maximumCalls && elapsedNanos() < durationNanos;
    }

    /** Pure admission query; does not reserve calls or extend this tick's CPU budget. */
    public boolean canFit(long requiredCalls) {
        if (requiredCalls < 0) throw new IllegalArgumentException("Negative call requirement");
        return requiredCalls <= remainingCalls() && elapsedNanos() < durationNanos;
    }

    /** Remaining native calls, clamped after a permitted greedy overrun; does not access capabilities. */
    public long remainingCalls() {
        return Math.max(0, maximumCalls - calls);
    }

    public long calls() {
        return calls;
    }

    public long elapsedNanos() {
        return clock.getAsLong() - startNanos;
    }

    public boolean slowCall() {
        return slowCalls > 0;
    }

    public long slowCalls() {
        return slowCalls;
    }

    public void beforeCall() {
        calls++;
        callStartNanos = clock.getAsLong();
    }

    public void afterCall() {
        if (clock.getAsLong() - callStartNanos >= slowCallNanos) slowCalls++;
    }
}
