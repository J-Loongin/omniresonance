// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.loongin.omniresonance.transfer.TransferWorkBudget;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class TerminalAuxiliaryWorkTest {
    @Test
    void exchangeSharesCpuAndReceivesAFirstTurnAlongsideExistingWork() {
        long[] clock = {0};
        var order = new ArrayList<String>();
        java.util.function.Function<String, Consumer<TransferWorkBudget>> group = name -> budget -> {
            if (budget.canFit(0)) {
                order.add(name);
                clock[0] += 10;
            }
        };
        var auxiliary =
                new TerminalAuxiliaryWork(group.apply("sample"), group.apply("inventory"), group.apply("exchange"));
        for (int tick = 0; tick < 6; tick++) {
            var budget = new TransferWorkBudget(1, 10, 1, () -> clock[0]);
            if ((tick & 1) == 0) auxiliary.accept(budget);
            group.apply("transfer").accept(budget);
            if ((tick & 1) != 0) auxiliary.accept(budget);
        }
        assertEquals(List.of("inventory", "transfer", "sample", "transfer", "exchange", "transfer"), order);
    }

    @Test
    void storageWritesAndSyncCannotLockstepWithTheTwoOuterRotations() {
        long[] clock = {0};
        var order = new ArrayList<String>();
        java.util.function.Function<String, Consumer<TransferWorkBudget>> group = name -> budget -> {
            if (budget.canFit(0)) {
                order.add(name);
                clock[0] += 10;
            }
        };
        var inventory = new io.github.loongin.omniresonance.network.TerminalInventoryWork(
                group.apply("access"), group.apply("sync"));
        var auxiliary = new TerminalAuxiliaryWork(group.apply("sample"), inventory);
        for (int tick = 0; tick < 8; tick++) {
            var budget = new TransferWorkBudget(1, 10, 1, () -> clock[0]);
            if ((tick & 1) == 0) auxiliary.accept(budget);
            group.apply("transfer").accept(budget);
            if ((tick & 1) != 0) auxiliary.accept(budget);
        }
        assertEquals(
                List.of("access", "transfer", "sample", "transfer", "sync", "transfer", "sample", "transfer"), order);
    }

    @Test
    void inventorySamplingAndTransferAllReceiveFirstWorkWhenEveryGroupExhaustsCpu() {
        long[] clock = {0};
        var order = new ArrayList<String>();
        Consumer<TransferWorkBudget> sample = budget -> {
            if (budget.canFit(0)) {
                order.add("sample");
                clock[0] += 10;
            }
        };
        Consumer<TransferWorkBudget> inventory = budget -> {
            if (budget.canFit(0)) {
                order.add("inventory");
                clock[0] += 10;
            }
        };
        var auxiliary = new TerminalAuxiliaryWork(sample, inventory);
        for (int tick = 0; tick < 4; tick++) {
            var budget = new TransferWorkBudget(1, 10, 1, () -> clock[0]);
            if ((tick & 1) == 0) auxiliary.accept(budget);
            if (budget.canStart()) {
                order.add("transfer");
                clock[0] += 10;
            }
            if ((tick & 1) != 0) auxiliary.accept(budget);
        }
        assertEquals(List.of("inventory", "transfer", "sample", "transfer"), order);
    }
}
