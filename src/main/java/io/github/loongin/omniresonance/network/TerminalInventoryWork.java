// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.network;

import io.github.loongin.omniresonance.transfer.TransferWorkBudget;
import java.util.Objects;
import java.util.function.Consumer;

/** Server-thread four-call blocks avoid lockstep with the two-call sampling and one-call transfer rotations. */
public final class TerminalInventoryWork implements Consumer<TransferWorkBudget> {
    private final Consumer<TransferWorkBudget> access, sync;
    private int phase;

    public TerminalInventoryWork(Consumer<TransferWorkBudget> access, Consumer<TransferWorkBudget> sync) {
        this.access = Objects.requireNonNull(access);
        this.sync = Objects.requireNonNull(sync);
    }

    public void accept(TransferWorkBudget budget) {
        boolean accessFirst = phase < 4;
        phase = (phase + 1) & 7;
        if (accessFirst) access.accept(budget);
        sync.accept(budget);
        if (!accessFirst) access.accept(budget);
    }
}
