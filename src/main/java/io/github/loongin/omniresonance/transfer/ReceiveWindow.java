// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;
/** Server-thread-owned passive quota; reads never create, renew or spend a window. */
public final class ReceiveWindow {
    private long expiresTick;
    private long received;
    private long uncertainUntilTick;

    public long available(long currentTick, long rate, int intervalTicks) {
        if (rate <= 0 || intervalTicks <= 0) throw new IllegalArgumentException("Invalid window policy");
        if (currentTick >= expiresTick) Math.addExact(currentTick, intervalTicks);
        if (currentTick < uncertainUntilTick) return 0;
        return currentTick >= expiresTick ? rate : Math.max(0, rate - received);
    }

    public void received(long currentTick, long amount, long rate, int intervalTicks) {
        if (amount < 0 || amount > available(currentTick, rate, intervalTicks))
            throw new IllegalArgumentException("Invalid reception");
        if (amount == 0) return;
        if (currentTick >= expiresTick) {
            expiresTick = Math.addExact(currentTick, intervalTicks);
            received = 0;
        }
        received = Math.addExact(received, amount);
    }

    /**
     * Quarantines quota after an uncertain target insertion without recording a guessed received amount.
     * This non-simulating mutation must run on the owning server thread.
     */
    public void quarantineUncertainTargetInsert(long currentTick, int intervalTicks) {
        if (intervalTicks <= 0) throw new IllegalArgumentException("Invalid window policy");
        if (currentTick < uncertainUntilTick) return;
        uncertainUntilTick = currentTick < expiresTick ? expiresTick : Math.addExact(currentTick, intervalTicks);
    }

    public long expiresTick() {
        return expiresTick;
    }

    /** Returns the exclusive quarantine deadline without modifying state. */
    public long uncertainUntilTick() {
        return uncertainUntilTick;
    }
}
