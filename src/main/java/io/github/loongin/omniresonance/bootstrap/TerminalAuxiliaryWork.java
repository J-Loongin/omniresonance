// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.bootstrap;

import io.github.loongin.omniresonance.transfer.TransferWorkBudget;
import java.util.List;
import java.util.function.Consumer;

/** Server-thread auxiliary rotation; each starting group lasts two calls to avoid lockstep with the outer toggle. */
final class TerminalAuxiliaryWork implements Consumer<TransferWorkBudget> {
    private final List<Consumer<TransferWorkBudget>> groups;
    private int phase;

    TerminalAuxiliaryWork(Consumer<TransferWorkBudget> sample, Consumer<TransferWorkBudget> inventory) {
        groups = List.of(inventory, sample);
    }

    TerminalAuxiliaryWork(
            Consumer<TransferWorkBudget> sample,
            Consumer<TransferWorkBudget> inventory,
            Consumer<TransferWorkBudget> exchange) {
        groups = List.of(inventory, sample, exchange);
    }

    @Override
    public void accept(TransferWorkBudget budget) {
        int first = phase / 2;
        phase = (phase + 1) % (groups.size() * 2);
        for (int i = 0; i < groups.size(); i++)
            groups.get((first + i) % groups.size()).accept(budget);
    }
}
