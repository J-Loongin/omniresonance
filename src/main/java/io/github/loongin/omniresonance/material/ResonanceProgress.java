// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.material;

/**
 * Immutable, world-independent state for one bounded dragon-breath resonance batch.
 *
 * <p>Values are safe to share between threads, but callers may apply returned progress to world state only on the
 * owning server thread. Every operation is a pure calculation: no simulation, persistence, inventory or world
 * mutation occurs here. Invalid counts, clocks and exact deadline overflow fail before a new value is returned.
 */
public record ResonanceProgress(int pendingCount, long settleAtGameTick) {
    private static final long RESET_TICKS = 100;

    public ResonanceProgress {
        if (pendingCount < 1 || pendingCount > 64) {
            throw new IllegalArgumentException("Pending resonance count is outside 1..64");
        }
        requireClock(settleAtGameTick);
    }

    /** Starts a one-bottle batch with a deadline exactly 100 game ticks after the supplied clock. */
    public static ResonanceProgress start(long currentGameTick) {
        requireClock(currentGameTick);
        return new ResonanceProgress(1, Math.addExact(currentGameTick, RESET_TICKS));
    }

    /** Returns the next immutable count and reset deadline; a full batch rejects before any value changes. */
    public ResonanceProgress inject(long currentGameTick) {
        requireClock(currentGameTick);
        if (isFull()) {
            throw new IllegalStateException("Resonance batch is full");
        }
        long deadline = Math.addExact(currentGameTick, RESET_TICKS);
        return new ResonanceProgress(pendingCount + 1, deadline);
    }

    /** Reports the exact 64-bottle boundary without mutation. */
    public boolean isFull() {
        return pendingCount == 64;
    }

    /** Reports whether the supplied nonnegative game tick reached this immutable deadline. */
    public boolean isDue(long currentGameTick) {
        requireClock(currentGameTick);
        return currentGameTick >= settleAtGameTick;
    }

    /** Returns a nonnegative long delay, or zero once due, without narrowing to the block scheduler's int range. */
    public long remainingTicks(long currentGameTick) {
        requireClock(currentGameTick);
        return currentGameTick >= settleAtGameTick ? 0 : settleAtGameTick - currentGameTick;
    }

    private static void requireClock(long gameTick) {
        if (gameTick < 0) {
            throw new IllegalArgumentException("Game tick must be nonnegative");
        }
    }
}
