// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.bootstrap;

import io.github.loongin.omniresonance.transfer.TransferWorkBudget;
import java.util.Objects;
import java.util.function.Consumer;

/** Server-thread auxiliary rotation. Pairs alternate order every two calls to avoid lockstep with transfer's toggle. */
final class TerminalAuxiliaryWork implements Consumer<TransferWorkBudget> {
    private final Consumer<TransferWorkBudget> sample;
    private final Consumer<TransferWorkBudget> inventory;
    private int phase;

    TerminalAuxiliaryWork(Consumer<TransferWorkBudget> sample, Consumer<TransferWorkBudget> inventory) {
        this.sample = Objects.requireNonNull(sample);
        this.inventory = Objects.requireNonNull(inventory);
    }

    @Override
    public void accept(TransferWorkBudget budget) {
        boolean inventoryFirst = phase < 2;
        phase = (phase + 1) & 3;
        if (inventoryFirst) inventory.accept(budget);
        sample.accept(budget);
        if (!inventoryFirst) inventory.accept(budget);
    }
}
