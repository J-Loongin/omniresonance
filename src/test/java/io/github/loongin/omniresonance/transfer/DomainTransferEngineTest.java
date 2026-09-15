// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.config.ServerSettings;
import io.github.loongin.omniresonance.persistence.StorageBucketData;
import io.github.loongin.omniresonance.recovery.RecoveryBuffer;
import io.github.loongin.omniresonance.storage.DomainLedger;
import io.github.loongin.omniresonance.transfer.fixtures.FakeItemHandler;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

final class DomainTransferEngineTest {
    private static final HolderLookup.Provider REGISTRIES =
            HolderLookup.Provider.create(BuiltInRegistries.REGISTRY.stream().map(registry -> registry.asLookup()));
    private static final ItemVariant IRON = ItemVariant.from(new ItemStack(Items.IRON_INGOT), REGISTRIES);
    private final DomainTransferEngine engine = new DomainTransferEngine();
    private final RecoveryBuffer recovery = new RecoveryBuffer(() -> {});
    private final DomainLedger ledger =
            new DomainLedger(new UUID(5, 9), Map.of(), index -> StorageBucketData.create(new UUID(5, 9), index));

    @Test
    void inputReservationPreventsReentrantCapacityOvercommit() {
        FakeItemHandler source = items(64);
        source.afterExtract = () ->
                assertTrue(ledger.reserveDeposit(IRON.key(), Long.MAX_VALUE, -1).isEmpty());
        var result = engine.depositGreedy(
                handle(source),
                0,
                ledger,
                IRON,
                64,
                -1,
                () -> true,
                recovery,
                ServerSettings.RecoveryLimits.defaults(),
                budget(100));
        assertEquals(64, result.moved());
        assertEquals(64, ledger.amount(IRON.key()));
        assertFalse(ledger.hasReservations());
    }

    @Test
    void revokedWorkStillReturnsOwnedLedgerRemainderWithoutNewAdmission() {
        try (var deposit = ledger.reserveDeposit(IRON.key(), Long.MAX_VALUE, -1).orElseThrow()) {
            deposit.commit(Long.MAX_VALUE);
        }
        java.util.concurrent.atomic.AtomicBoolean permitted = new java.util.concurrent.atomic.AtomicBoolean(true);
        FakeItemHandler target = items(0);
        target.actualInsertLimit = 60;
        target.afterSlots = () -> {
            if (target.slotQueries == 2) permitted.set(false);
        };
        var result = engine.withdrawGreedy(
                ledger,
                handle(target),
                0,
                IRON,
                64,
                permitted::get,
                recovery,
                ServerSettings.RecoveryLimits.defaults(),
                budget(100));
        assertEquals(60, result.moved());
        assertEquals(4, result.returned());
        assertEquals(Long.MAX_VALUE - 60, ledger.amount(IRON.key()));
        assertTrue(recovery.isEmpty());
        assertFalse(ledger.hasReservations());
    }

    @Test
    void unknownNativeInsertionDoesNotInventARefund() {
        try (var deposit = ledger.reserveDeposit(IRON.key(), 64, -1).orElseThrow()) {
            deposit.commit(64);
        }
        FakeItemHandler target = items(0);
        target.throwInsert = true;
        var result = engine.withdrawGreedy(
                ledger,
                handle(target),
                0,
                IRON,
                64,
                () -> true,
                recovery,
                ServerSettings.RecoveryLimits.defaults(),
                budget(100));
        assertEquals(ResourceTransferEngine.Failure.UNKNOWN_MUTATION, result.failure());
        assertEquals(0, ledger.amount(IRON.key()));
        assertTrue(recovery.isEmpty());
        assertFalse(ledger.hasReservations());
    }

    @Test
    void nativeInputDepositsWithoutChargingLedgerOperationsAsNativeCalls() {
        FakeItemHandler source = items(64);
        TransferWorkBudget budget = budget(100);
        var result = engine.depositGreedy(
                handle(source),
                0,
                ledger,
                IRON,
                64,
                -1,
                () -> true,
                recovery,
                ServerSettings.RecoveryLimits.defaults(),
                budget);
        assertEquals(64, result.moved());
        assertEquals(64, ledger.amount(IRON.key()));
        assertTrue(source.stacks[0].isEmpty());
        assertFalse(ledger.hasReservations());
        assertTrue(budget.calls() <= 5);
    }

    @Test
    void exactAdmissionDoesNotReservePhantomNativeCallsForLedger() {
        var result = engine.depositExact(
                handle(items(64)),
                0,
                ledger,
                IRON,
                64,
                0,
                64,
                -1,
                () -> true,
                recovery,
                ServerSettings.RecoveryLimits.defaults(),
                budget(7));
        assertTrue(result.completeBatch());
        assertEquals(64, ledger.amount(IRON.key()));
    }

    @Test
    void fullDomainRejectsBeforeExtractionAndEmptySourceDoesNotCreateBucket() {
        FakeItemHandler source = items(64);
        var result = engine.depositGreedy(
                handle(source),
                0,
                ledger,
                IRON,
                64,
                0,
                () -> true,
                recovery,
                ServerSettings.RecoveryLimits.defaults(),
                budget(100));
        assertEquals(0, result.extracted());
        assertEquals(64, source.stacks[0].getCount());
        assertEquals(0, ledger.variantCount());
        assertFalse(ledger.hasReservations());
        var unused = new DomainLedger(new UUID(5, 10), Map.of(), index -> {
            throw new AssertionError("Empty source created a bucket");
        });
        engine.depositGreedy(
                handle(items(0)),
                0,
                unused,
                IRON,
                64,
                -1,
                () -> true,
                recovery,
                ServerSettings.RecoveryLimits.defaults(),
                budget(100));
    }

    @Test
    void partialOutputReturnsKnownRemainderToReservedOriginalCapacity() {
        try (var deposit = ledger.reserveDeposit(IRON.key(), 64, -1).orElseThrow()) {
            deposit.commit(64);
        }
        FakeItemHandler target = items(0);
        target.actualInsertLimit = 60;
        var result = engine.withdrawGreedy(
                ledger,
                handle(target),
                0,
                IRON,
                64,
                () -> true,
                recovery,
                ServerSettings.RecoveryLimits.defaults(),
                budget(100));
        assertEquals(60, result.moved());
        assertEquals(4, result.returned());
        assertEquals(4, ledger.amount(IRON.key()));
        assertEquals(60, target.stacks[0].getCount());
        assertTrue(recovery.isEmpty());
        assertFalse(ledger.hasReservations());
    }

    @Test
    void exactInputSpansSlotsKeepsVariantAndWaitsForWholeBudget() {
        FakeItemHandler source = items(40, 40);
        var first = engine.depositExact(
                handle(source),
                0,
                ledger,
                IRON,
                64,
                16,
                64,
                -1,
                () -> true,
                recovery,
                ServerSettings.RecoveryLimits.defaults(),
                budget(1));
        assertEquals(ResourceTransferEngine.Failure.WAITING_BUDGET, first.failure());
        assertEquals(0, ledger.amount(IRON.key()));
        assertEquals(80, source.stacks[0].getCount() + source.stacks[1].getCount());
        var second = engine.depositExact(
                handle(source),
                0,
                ledger,
                IRON,
                64,
                16,
                64,
                -1,
                () -> true,
                recovery,
                ServerSettings.RecoveryLimits.defaults(),
                budget(100));
        assertTrue(second.completeBatch());
        assertEquals(64, ledger.amount(IRON.key()));
        assertEquals(16, source.stacks[0].getCount() + source.stacks[1].getCount());
        assertFalse(ledger.hasReservations());
    }

    @Test
    void fluidAndMaximumIntegerEnergyUseTheSameDomainCommitPath() {
        var water = FluidVariant.from(
                new net.neoforged.neoforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER, 1),
                REGISTRIES);
        var fluidSource = new net.neoforged.neoforge.fluids.capability.templates.FluidTank(16000);
        var fluidTarget = new net.neoforged.neoforge.fluids.capability.templates.FluidTank(16000);
        fluidSource.setFluid(water.stack(3000));
        var energySource = new io.github.loongin.omniresonance.transfer.fixtures.FakeEnergyStorage(
                Integer.MAX_VALUE, Integer.MAX_VALUE);
        var energyTarget =
                new io.github.loongin.omniresonance.transfer.fixtures.FakeEnergyStorage(Integer.MAX_VALUE, 0);
        ResourcePort[] sources = {new FluidResourcePort(fluidSource, REGISTRIES), new EnergyResourcePort(energySource)};
        ResourcePort[] targets = {new FluidResourcePort(fluidTarget, REGISTRIES), new EnergyResourcePort(energyTarget)};
        ResourceVariant[] variants = {water, EnergyVariant.INSTANCE};
        int[] amounts = {3000, Integer.MAX_VALUE};
        for (int index = 0; index < sources.length; index++) {
            var inBudget = budget(100);
            var deposited = engine.depositGreedy(
                    handle(sources[index]),
                    0,
                    ledger,
                    variants[index],
                    amounts[index],
                    -1,
                    () -> true,
                    recovery,
                    ServerSettings.RecoveryLimits.defaults(),
                    inBudget);
            assertEquals(amounts[index], deposited.moved());
            var outBudget = budget(100);
            var withdrawn = engine.withdrawGreedy(
                    ledger,
                    handle(targets[index]),
                    0,
                    variants[index],
                    amounts[index],
                    () -> true,
                    recovery,
                    ServerSettings.RecoveryLimits.defaults(),
                    outBudget);
            assertEquals(amounts[index], withdrawn.moved());
            assertEquals(0, ledger.amount(variants[index].key()));
            assertTrue(inBudget.calls() <= 5 && outBudget.calls() <= 5);
        }
        assertEquals(3000, fluidTarget.getFluidAmount());
        assertEquals(0, fluidSource.getFluidAmount());
        assertEquals(Integer.MAX_VALUE, energyTarget.getEnergyStored());
        assertEquals(0, energySource.getEnergyStored());
        assertFalse(ledger.hasReservations());
    }

    private static ResourceTransferEngine.Handle handle(ResourcePort port) {
        return new ResourceTransferEngine.Handle() {
            public ResourcePort port() {
                return port;
            }

            public Object physicalIdentity() {
                return port;
            }

            public boolean valid() {
                return true;
            }
        };
    }

    private static FakeItemHandler items(int... counts) {
        FakeItemHandler handler = new FakeItemHandler(counts.length);
        for (int index = 0; index < counts.length; index++)
            handler.stacks[index] = counts[index] == 0 ? ItemStack.EMPTY : IRON.stack(counts[index]);
        return handler;
    }

    private static TransferWorkBudget budget(int calls) {
        return new TransferWorkBudget(calls, Long.MAX_VALUE, Long.MAX_VALUE, () -> 0);
    }

    private static ResourceTransferEngine.Handle handle(FakeItemHandler handler) {
        ItemResourcePort port = new ItemResourcePort(handler, REGISTRIES);
        return new ResourceTransferEngine.Handle() {
            public ResourcePort port() {
                return port;
            }

            public Object physicalIdentity() {
                return handler;
            }

            public boolean valid() {
                return true;
            }
        };
    }
}
