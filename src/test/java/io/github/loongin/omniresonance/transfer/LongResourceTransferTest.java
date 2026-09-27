// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.config.ServerSettings;
import io.github.loongin.omniresonance.recovery.RecoveryBuffer;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class LongResourceTransferTest {
    @Test
    void nativeLongGreedyAndExactMoveFullAmountsWithConstantCalls() {
        for (boolean exact : new boolean[] {false, true}) {
            for (long amount : new long[] {1, (long) Integer.MAX_VALUE + 29, Long.MAX_VALUE}) {
                var source = new Port(amount);
                var target = new Port(0);
                var budget = budget(100);
                var engine = new ResourceTransferEngine();
                var recovery = new RecoveryBuffer(() -> {});
                var result = exact
                        ? engine.commitExact(
                                engine.prepareExact(source, target, EnergyVariant.INSTANCE),
                                amount,
                                amount,
                                0,
                                amount,
                                recovery,
                                ServerSettings.RecoveryLimits.defaults(),
                                budget)
                        : engine.commitGreedy(
                                source,
                                0,
                                target,
                                0,
                                EnergyVariant.INSTANCE,
                                amount,
                                recovery,
                                ServerSettings.RecoveryLimits.defaults(),
                                budget);
                assertEquals(ResourceTransferEngine.Failure.NONE, result.failure());
                assertEquals(amount, result.moved());
                assertEquals(0, source.balance);
                assertEquals(amount, target.balance);
                assertTrue(budget.calls() <= 10);
            }
        }
    }

    @Test
    void longPartialAcceptanceReturnsKnownRemainderWithoutQuantityLoops() {
        long amount = (long) Integer.MAX_VALUE * 4;
        var source = new Port(amount);
        var target = new Port(0);
        target.realLimit = 3;
        var result = new ResourceTransferEngine()
                .commitGreedy(
                        source,
                        0,
                        target,
                        0,
                        EnergyVariant.INSTANCE,
                        amount,
                        new RecoveryBuffer(() -> {}),
                        ServerSettings.RecoveryLimits.defaults(),
                        budget(100));
        assertEquals(3, result.moved());
        assertEquals(amount - 3, result.returned());
        assertEquals(amount - 3, source.balance);
        assertEquals(3, target.balance);
    }

    @Test
    void unknownLongMutationRetainsFullRequestedEvidence() {
        long amount = (long) Integer.MAX_VALUE + 91;
        var source = new Port(amount);
        var target = new Port(0);
        target.failMutation = true;
        var result = new ResourceTransferEngine()
                .commitGreedy(
                        source,
                        0,
                        target,
                        0,
                        EnergyVariant.INSTANCE,
                        amount,
                        new RecoveryBuffer(() -> {}),
                        ServerSettings.RecoveryLimits.defaults(),
                        budget(100));
        assertEquals(ResourceTransferEngine.Failure.UNKNOWN_MUTATION, result.failure());
        assertEquals(ResourceTransferEngine.Stage.TARGET_INSERT, result.unknownStage());
        assertEquals(amount, result.unknownRequested());
        assertEquals(0, result.returned());
        assertEquals(0, result.buffered());
    }

    @Test
    void exactLongBatchWaitsBeforeExtractionWhenCommitBudgetIsInsufficient() {
        long amount = (long) Integer.MAX_VALUE + 9;
        var source = new Port(amount);
        var target = new Port(0);
        var engine = new ResourceTransferEngine();
        var result = engine.commitExact(
                engine.prepareExact(source, target, EnergyVariant.INSTANCE),
                amount,
                amount,
                0,
                amount,
                new RecoveryBuffer(() -> {}),
                ServerSettings.RecoveryLimits.defaults(),
                budget(4));
        assertEquals(ResourceTransferEngine.Failure.WAITING_BUDGET, result.failure());
        assertEquals(amount, source.balance);
        assertEquals(0, target.balance);
    }

    private static TransferWorkBudget budget(int calls) {
        return new TransferWorkBudget(calls, Long.MAX_VALUE, Long.MAX_VALUE, () -> 0);
    }

    private static final class Port implements ResourcePort, ResourceTransferEngine.Handle {
        long balance;
        long realLimit = Long.MAX_VALUE;
        boolean failMutation;

        Port(long balance) {
            this.balance = balance;
        }

        public ResourceLocation typeId() {
            return ResourceTypes.ENERGY;
        }

        public ExtractionScope extractionScope() {
            return ExtractionScope.HANDLER;
        }

        public int sourceViews(TransferWorkBudget budget) {
            return 1;
        }

        public int targetViews(TransferWorkBudget budget) {
            return 1;
        }

        public Optional<ResourceAmount> peek(int view, TransferWorkBudget budget) {
            budget.beforeCall();
            try {
                return balance == 0
                        ? Optional.empty()
                        : Optional.of(new ResourceAmount(EnergyVariant.INSTANCE, balance));
            } finally {
                budget.afterCall();
            }
        }

        public long extract(
                int view, ResourceVariant variant, long amount, boolean simulate, TransferWorkBudget budget) {
            budget.beforeCall();
            try {
                long result = Math.min(balance, amount);
                if (!simulate) balance -= result;
                return result;
            } finally {
                budget.afterCall();
            }
        }

        public long insert(
                int view, ResourceVariant variant, long amount, boolean simulate, TransferWorkBudget budget) {
            budget.beforeCall();
            try {
                long result = Math.min(amount, Long.MAX_VALUE - balance);
                if (!simulate) {
                    result = Math.min(result, realLimit);
                    balance += result;
                    if (failMutation) throw new IllegalStateException("Unknown insertion result");
                }
                return result;
            } finally {
                budget.afterCall();
            }
        }

        public ResourcePort port() {
            return this;
        }

        public Object physicalIdentity() {
            return this;
        }

        public boolean valid() {
            return true;
        }
    }
}
