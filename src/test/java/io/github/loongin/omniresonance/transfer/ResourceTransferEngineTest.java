// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.config.ServerSettings;
import io.github.loongin.omniresonance.recovery.RecoveryBuffer;
import io.github.loongin.omniresonance.transfer.fixtures.FakeEnergyStorage;
import io.github.loongin.omniresonance.transfer.fixtures.FakeItemHandler;
import io.github.loongin.omniresonance.transfer.fixtures.NativePortFixtures;
import io.github.loongin.omniresonance.transfer.fixtures.TransferNativeFixtures;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;

final class ResourceTransferEngineTest {
    private static final HolderLookup.Provider PROVIDER =
            HolderLookup.Provider.create(BuiltInRegistries.REGISTRY.stream().map(registry -> registry.asLookup()));
    private static final ItemVariant IRON = ItemVariant.from(new ItemStack(Items.IRON_INGOT), PROVIDER);
    private final ResourceTransferEngine engine = new ResourceTransferEngine();
    private final RecoveryBuffer recovery = new RecoveryBuffer(() -> {});

    private static final class Endpoint implements ResourceTransferEngine.Handle {
        private final ResourcePort port;
        private Object identity = new Object();
        private boolean valid = true;

        Endpoint(ResourcePort port) {
            this.port = port;
        }

        public ResourcePort port() {
            return port;
        }

        public Object physicalIdentity() {
            return identity;
        }

        public boolean valid() {
            return valid;
        }
    }

    private static Endpoint endpoint(FakeItemHandler handler) {
        return new Endpoint(new ItemResourcePort(handler, PROVIDER));
    }

    private static FakeItemHandler items(int... amounts) {
        FakeItemHandler handler = new FakeItemHandler(amounts.length);
        for (int i = 0; i < amounts.length; i++)
            handler.stacks[i] = amounts[i] == 0 ? ItemStack.EMPTY : IRON.stack(amounts[i]);
        return handler;
    }

    private static TransferWorkBudget budget(int calls) {
        return new TransferWorkBudget(calls, Long.MAX_VALUE, Long.MAX_VALUE, () -> 0);
    }

    private ResourceTransferEngine.Result greedy(
            Endpoint source, Endpoint target, ResourceVariant variant, long amount, TransferWorkBudget budget) {
        return engine.commitGreedy(
                source, 0, target, 0, variant, amount, recovery, ServerSettings.RecoveryLimits.defaults(), budget);
    }

    private ResourceTransferEngine.Result exact(
            Endpoint source, Endpoint target, long amount, long keep, long batch, TransferWorkBudget budget) {
        return engine.commitExact(
                engine.prepareExact(source, target, IRON),
                amount,
                amount,
                keep,
                batch,
                recovery,
                ServerSettings.RecoveryLimits.defaults(),
                budget);
    }

    private static void conserved(ResourceTransferEngine.Result result) {
        assertEquals(result.extracted(), result.moved() + result.returned() + result.buffered() + result.stored());
    }

    @Test
    void greedyMovesAllNativeTypesAtIntBoundaryWithConstantCalls() {
        for (int quantity : new int[] {1, 65556, Integer.MAX_VALUE}) {
            FakeItemHandler itemSource = items(quantity), itemTarget = items(0);
            NativePortFixtures.Fluid fluidSource = new NativePortFixtures.Fluid(quantity);
            NativePortFixtures.Fluid fluidTarget = new NativePortFixtures.Fluid(0);
            FakeEnergyStorage energySource = new FakeEnergyStorage(Integer.MAX_VALUE, quantity);
            FakeEnergyStorage energyTarget = new FakeEnergyStorage(Integer.MAX_VALUE, 0);
            Endpoint[] sources = {
                endpoint(itemSource),
                new Endpoint(new FluidResourcePort(fluidSource, PROVIDER)),
                new Endpoint(new EnergyResourcePort(energySource))
            };
            Endpoint[] targets = {
                endpoint(itemTarget),
                new Endpoint(new FluidResourcePort(fluidTarget, PROVIDER)),
                new Endpoint(new EnergyResourcePort(energyTarget))
            };
            ResourceVariant[] variants = {
                IRON, FluidVariant.from(new FluidStack(Fluids.WATER, 1), PROVIDER), EnergyVariant.INSTANCE
            };
            for (int i = 0; i < sources.length; i++) {
                TransferWorkBudget b = budget(1);
                long request = quantity == Integer.MAX_VALUE ? Long.MAX_VALUE : quantity;
                ResourceTransferEngine.Result result = greedy(sources[i], targets[i], variants[i], request, b);
                assertEquals(quantity, result.moved());
                conserved(result);
                assertTrue(b.calls() <= ResourceTransferEngine.MAXIMUM_GREEDY_CALLS);
            }
            assertEquals(quantity, itemTarget.stacks[0].getCount());
            assertEquals(quantity, fluidTarget.tanks[0].getFluidAmount());
            assertEquals(quantity, energyTarget.getEnergyStored());
        }
    }

    @Test
    void greedyPartialAcceptanceReturnsOrBuffersOnlyKnownRemainderWithinTenCalls() {
        for (boolean refuseReturn : new boolean[] {false, true}) {
            FakeItemHandler source = items(64), target = items(0);
            target.actualInsertLimit = 60;
            source.refuseReturn = refuseReturn;
            TransferWorkBudget b = budget(1);
            ResourceTransferEngine.Result r = greedy(endpoint(source), endpoint(target), IRON, 64, b);
            assertEquals(60, r.moved());
            assertEquals(refuseReturn ? 0 : 4, r.returned());
            assertEquals(refuseReturn ? 4 : 0, r.buffered());
            assertEquals(10, b.calls());
            conserved(r);
        }
    }

    @Test
    void greedySimulationRefusalAndSamePhysicalEndpointNeverExtract() {
        FakeItemHandler source = items(64), target = items(0);
        target.refuseSimulation = true;
        Endpoint s = endpoint(source), t = endpoint(target);
        assertEquals(
                ResourceTransferEngine.Failure.REFUSED,
                greedy(s, t, IRON, 64, budget(1)).failure());
        assertEquals(0, source.extractionCalls);
        t.identity = s.identity;
        TransferWorkBudget b = budget(1);
        assertEquals(
                ResourceTransferEngine.Failure.INVALID_ENDPOINT,
                greedy(s, t, IRON, 64, b).failure());
        assertEquals(0, b.calls());
    }

    @Test
    void greedySourceOrTargetInvalidationDisposesKnownHoldings() {
        for (boolean invalidateSource : new boolean[] {false, true}) {
            FakeItemHandler source = items(64), target = items(0);
            Endpoint s = endpoint(source), t = endpoint(target);
            source.afterExtract = () -> {
                t.valid = false;
                s.valid = !invalidateSource;
            };
            ResourceTransferEngine.Result r = greedy(s, t, IRON, 64, budget(1));
            assertEquals(0, target.insertionCalls);
            assertEquals(invalidateSource ? 64 : 0, r.buffered());
            assertEquals(invalidateSource ? 0 : 64, r.returned());
            conserved(r);
        }
    }

    @Test
    void greedyUnknownMutationNeverBlindlyReturnsOrBuffersSubmittedQuantity() {
        for (int stage = 0; stage < 3; stage++) {
            FakeItemHandler source = items(64), target = items(0);
            source.throwExtract = stage == 0;
            target.throwInsert = stage == 1;
            source.wrongExtraction = stage == 2;
            ResourceTransferEngine.Result r = greedy(endpoint(source), endpoint(target), IRON, 64, budget(1));
            assertEquals(ResourceTransferEngine.Failure.UNKNOWN_MUTATION, r.failure());
            assertNotNull(r.cause());
            assertEquals(0, r.buffered());
            assertEquals(0, source.insertionCalls);
            assertEquals(
                    stage == 1
                            ? ResourceTransferEngine.Stage.TARGET_INSERT
                            : ResourceTransferEngine.Stage.SOURCE_EXTRACT,
                    r.unknownStage());
        }
    }

    @Test
    void exactSpansRealSlotsAndTrimsEveryRequestToValidatedAmounts() {
        FakeItemHandler source = items(30, 40, 70), target = items(0, 0, 0);
        target.capacity = 45;
        TransferWorkBudget b = budget(100);
        ResourceTransferEngine.Result r = exact(endpoint(source), endpoint(target), 140, 0, 64, b);
        assertEquals(128, r.moved());
        assertTrue(r.completeBatch());
        assertEquals(45, target.stacks[0].getCount());
        assertEquals(45, target.stacks[1].getCount());
        assertEquals(38, target.stacks[2].getCount());
        assertEquals(12, source.stacks[2].getCount());
        assertEquals(source.calls + target.calls, b.calls());
        conserved(r);
    }

    @Test
    void exactRetainedLateViewPositionsAvoidPrefixRescansWithoutRetainingPromises() {
        FakeItemHandler source = new FakeItemHandler(100000), target = new FakeItemHandler(100000);
        source.stacks[99999] = IRON.stack(64);
        ResourceTransferEngine.ExactCandidate candidate =
                engine.prepareExact(endpoint(source), endpoint(target), IRON, 99999, 99999);
        assertEquals(0, source.calls + target.calls);
        TransferWorkBudget b = budget(11);
        ResourceTransferEngine.Result r =
                engine.commitExact(candidate, 64, 64, 0, 64, recovery, ServerSettings.RecoveryLimits.defaults(), b);
        assertEquals(64, r.moved());
        assertEquals(64, target.stacks[99999].getCount());
        assertEquals(9, b.calls());
    }

    @Test
    void exactSparseHintsAvoidGapRescansAndRejectDuplicatePromises() {
        FakeItemHandler source = new FakeItemHandler(100000), target = new FakeItemHandler(100000);
        source.stacks[0] = IRON.stack(10);
        source.stacks[99999] = IRON.stack(10);
        target.capacity = 10;
        TransferWorkBudget b = budget(20);
        int[] sourceHints = {0, 99999, 0};
        int[] targetHints = {0, 99999, 99999};
        ResourceTransferEngine.ExactCandidate candidate =
                engine.prepareExact(endpoint(source), endpoint(target), IRON, sourceHints, targetHints, b);
        sourceHints[1] = 1;
        targetHints[1] = 1;
        ResourceTransferEngine.Result r =
                engine.commitExact(candidate, 20, 20, 0, 20, recovery, ServerSettings.RecoveryLimits.defaults(), b);
        assertEquals(20, r.moved());
        assertEquals(10, target.stacks[99999].getCount());
        assertEquals(16, b.calls());
        assertTrue(r.completeBatch());
    }

    @Test
    void exactCannotCombineDifferentTargetContainers() {
        FakeItemHandler source = items(64), a = items(0), b = items(0);
        a.capacity = 40;
        b.capacity = 40;
        assertEquals(
                0, exact(endpoint(source), endpoint(a), 64, 0, 64, budget(100)).extracted());
        assertEquals(
                0, exact(endpoint(source), endpoint(b), 64, 0, 64, budget(100)).extracted());
        assertEquals(64, source.stacks[0].getCount());
    }

    @Test
    void exactSourcePartialDoesNotForwardAnIncompleteBatch() {
        FakeItemHandler source = items(64), target = items(0);
        source.afterSimulatedExtract = () -> source.extractionLimit = 30;
        ResourceTransferEngine.Result r = exact(endpoint(source), endpoint(target), 64, 0, 64, budget(100));
        assertEquals(30, r.extracted());
        assertEquals(0, r.moved());
        assertEquals(30, r.returned());
        assertFalse(r.completeBatch());
        conserved(r);
    }

    @Test
    void exactPartialTargetAcceptanceRemainsKnownAndReturnsOrBuffers() {
        for (boolean refused : new boolean[] {false, true}) {
            FakeItemHandler source = items(32, 32), target = items(0, 0);
            target.capacity = 40;
            target.actualInsertLimit = 35;
            source.refuseReturn = refused;
            ResourceTransferEngine.Result r = exact(endpoint(source), endpoint(target), 64, 0, 64, budget(100));
            assertEquals(35, r.moved());
            assertFalse(r.completeBatch());
            assertEquals(refused ? 29 : 0, r.buffered());
            assertEquals(refused ? 0 : 29, r.returned());
            conserved(r);
        }
    }

    @Test
    void laterUnknownSourceBuffersEarlierKnownExtractionWithoutMoreNativeCalls() {
        FakeItemHandler source = items(32, 32), target = items(0);
        source.afterExtract = () -> source.throwExtract = true;
        ResourceTransferEngine.Result r = exact(endpoint(source), endpoint(target), 64, 0, 64, budget(100));
        assertEquals(ResourceTransferEngine.Failure.UNKNOWN_MUTATION, r.failure());
        assertEquals(ResourceTransferEngine.Stage.SOURCE_EXTRACT, r.unknownStage());
        assertEquals(32, r.extracted());
        assertEquals(32, r.buffered());
        assertEquals(32, recovery.amount(IRON.key()));
        assertEquals(2, source.extractionCalls);
        assertEquals(0, source.insertionCalls);
        assertEquals(0, target.insertionCalls);
    }

    @Test
    void unknownTargetBuffersOnlyQuantityNotSubmittedToThatSlot() {
        FakeItemHandler source = items(64), target = items(0, 0);
        target.capacity = 40;
        target.throwInsert = true;
        ResourceTransferEngine.Result r = exact(endpoint(source), endpoint(target), 64, 0, 64, budget(100));
        assertEquals(ResourceTransferEngine.Stage.TARGET_INSERT, r.unknownStage());
        assertEquals(24, r.buffered());
        assertEquals(40, r.unknownRequested());
        assertEquals(0, source.insertionCalls);
        assertEquals(1, target.insertionCalls);
    }

    @Test
    void exactFreshCandidateRechecksKeepCountAndSimulationsInCurrentCall() {
        FakeItemHandler source = items(40, 60), target = items(0);
        ResourceTransferEngine.ExactCandidate candidate = engine.prepareExact(endpoint(source), endpoint(target), IRON);
        assertEquals(0, source.calls + target.calls);
        source.stacks[0] = IRON.stack(10);
        ResourceTransferEngine.Result r = engine.commitExact(
                candidate, 100, 100, 40, 20, recovery, ServerSettings.RecoveryLimits.defaults(), budget(100));
        assertEquals(20, r.moved());
        assertEquals(50, source.stacks[0].getCount() + source.stacks[1].getCount());
        conserved(r);
    }

    @Test
    void exactNonzeroKeepRequiresCompleteSameTickContainerCount() {
        FakeItemHandler source = items(64, 64, 64, 64), target = items(0);
        ResourceTransferEngine.Result r = exact(endpoint(source), endpoint(target), 64, 64, 64, budget(4));
        assertEquals(ResourceTransferEngine.Failure.WAITING_BUDGET, r.failure());
        assertEquals(0, source.extractionCalls);
        assertEquals(0, target.calls);
    }

    @Test
    void exactBatchAboveEitherAllowanceAndLongMaximumShortCircuitBeforeNativeCalls() {
        FakeItemHandler source = items(64), target = items(0);
        ResourceTransferEngine.ExactCandidate candidate = engine.prepareExact(endpoint(source), endpoint(target), IRON);
        for (long batch : new long[] {65, Long.MAX_VALUE}) {
            ResourceTransferEngine.Result r = engine.commitExact(
                    candidate,
                    Integer.MAX_VALUE,
                    64,
                    0,
                    batch,
                    recovery,
                    ServerSettings.RecoveryLimits.defaults(),
                    budget(1));
            assertEquals(0, r.extracted());
        }
        assertEquals(0, source.calls + target.calls);
    }

    @Test
    void exactFluidCountsWholeContainerButPromisesHandlerOnlyOnce() {
        NativePortFixtures.Fluid source = new NativePortFixtures.Fluid(300, 700);
        NativePortFixtures.Fluid target = new NativePortFixtures.Fluid(0);
        ResourceVariant variant = FluidVariant.from(new FluidStack(Fluids.WATER, 1), PROVIDER);
        TransferWorkBudget b = budget(100);
        ResourceTransferEngine.Result r = engine.commitExact(
                engine.prepareExact(
                        new Endpoint(new FluidResourcePort(source, PROVIDER)),
                        new Endpoint(new FluidResourcePort(target, PROVIDER)),
                        variant),
                1000,
                1000,
                200,
                400,
                recovery,
                ServerSettings.RecoveryLimits.defaults(),
                b);
        assertEquals(800, r.moved());
        assertEquals(200, source.tanks[0].getFluidAmount() + source.tanks[1].getFluidAmount());
        // Count, two peeks, one simulation, final count, one actual drain.
        assertEquals(6, source.calls);
        assertEquals(2, target.calls);
        assertEquals(8, b.calls());
    }

    @Test
    void exactAdmissionRequiresFullWorstCaseBudgetAndAcceptsExactBoundary() {
        // Preparation: source count + peek + simulate + target count + simulate = 5.
        // Worst-case modification: count/extract + count/insert + count/return = 6.
        for (int calls : new int[] {10, 11}) {
            FakeItemHandler source = items(64), target = items(0);
            target.actualInsertLimit = 60;
            TransferWorkBudget b = budget(calls);
            ResourceTransferEngine.Result r = exact(endpoint(source), endpoint(target), 64, 0, 64, b);
            assertEquals(
                    calls == 10
                            ? ResourceTransferEngine.Failure.WAITING_BUDGET
                            : ResourceTransferEngine.Failure.INCONSISTENT,
                    r.failure());
            assertEquals(calls == 10 ? 0 : 1, source.extractionCalls);
            assertEquals(calls == 10 ? 5 : 11, b.calls());
            conserved(r);
        }
    }

    @Test
    void exactCpuBudgetExpirationAfterSimulationPreventsExtraction() {
        AtomicLong clock = new AtomicLong();
        FakeItemHandler source = items(64), target = items(0);
        source.afterSimulatedExtract = () -> clock.set(100);
        TransferWorkBudget b = new TransferWorkBudget(100, 100, 100, clock::get);
        ResourceTransferEngine.Result r = exact(endpoint(source), endpoint(target), 64, 0, 64, b);
        assertEquals(ResourceTransferEngine.Failure.WAITING_BUDGET, r.failure());
        assertEquals(0, source.extractionCalls);
    }

    @Test
    void recoveryOverflowRefusalLeavesBothModesUntouched() {
        recovery.restore(Map.of(IRON.key(), Long.MAX_VALUE));
        FakeItemHandler source = items(64), target = items(0);
        assertEquals(
                ResourceTransferEngine.Failure.RECOVERY_FULL,
                greedy(endpoint(source), endpoint(target), IRON, 64, budget(1)).failure());
        assertEquals(
                ResourceTransferEngine.Failure.RECOVERY_FULL,
                exact(endpoint(source), endpoint(target), 64, 0, 64, budget(100))
                        .failure());
        assertEquals(0, source.extractionCalls);
        assertEquals(Long.MAX_VALUE, recovery.amount(IRON.key()));
    }

    @Test
    void nativeFluidAndEnergyKnownRemaindersConserveInBothModes() {
        for (boolean exactMode : new boolean[] {false, true}) {
            for (boolean refuseReturn : new boolean[] {false, true}) {
                TransferNativeFixtures.Energy energySource = new TransferNativeFixtures.Energy(64);
                TransferNativeFixtures.Energy energyTarget = new TransferNativeFixtures.Energy(0);
                TransferNativeFixtures.Fluid fluidSource =
                        new TransferNativeFixtures.Fluid(new FluidStack(Fluids.WATER, 64));
                TransferNativeFixtures.Fluid fluidTarget = new TransferNativeFixtures.Fluid(FluidStack.EMPTY);
                energyTarget.actualReceiveLimit = 60;
                fluidTarget.actualReceiveLimit = 60;
                energySource.actualReceiveLimit = refuseReturn ? 0 : Integer.MAX_VALUE;
                fluidSource.actualReceiveLimit = refuseReturn ? 0 : Integer.MAX_VALUE;
                Endpoint[] sources = {
                    new Endpoint(new EnergyResourcePort(energySource)),
                    new Endpoint(new FluidResourcePort(fluidSource, PROVIDER))
                };
                Endpoint[] targets = {
                    new Endpoint(new EnergyResourcePort(energyTarget)),
                    new Endpoint(new FluidResourcePort(fluidTarget, PROVIDER))
                };
                ResourceVariant[] variants = {
                    EnergyVariant.INSTANCE, FluidVariant.from(new FluidStack(Fluids.WATER, 1), PROVIDER)
                };
                for (int i = 0; i < sources.length; i++) {
                    TransferWorkBudget b = budget(exactMode ? 100 : 1);
                    ResourceTransferEngine.Result r = exactMode
                            ? engine.commitExact(
                                    engine.prepareExact(sources[i], targets[i], variants[i]),
                                    64,
                                    64,
                                    0,
                                    64,
                                    recovery,
                                    ServerSettings.RecoveryLimits.defaults(),
                                    b)
                            : greedy(sources[i], targets[i], variants[i], 64, b);
                    assertEquals(60, r.moved());
                    assertEquals(refuseReturn ? 4 : 0, r.buffered());
                    assertEquals(refuseReturn ? 0 : 4, r.returned());
                    assertFalse(r.completeBatch());
                    conserved(r);
                }
                assertEquals(60, energyTarget.getEnergyStored());
                assertEquals(60, fluidTarget.getFluidAmount());
                assertEquals(refuseReturn ? 0 : 4, energySource.getEnergyStored());
                assertEquals(refuseReturn ? 0 : 4, fluidSource.getFluidAmount());
            }
        }
    }

    @Test
    void unknownReturnBuffersOnlyKnownHoldingsNotSubmittedToThatView() {
        FakeItemHandler source = items(32, 32), target = items(0);
        source.throwInsert = true;
        target.actualInsertLimit = 0;
        ResourceTransferEngine.Result r = exact(endpoint(source), endpoint(target), 64, 0, 64, budget(100));
        assertEquals(ResourceTransferEngine.Failure.UNKNOWN_MUTATION, r.failure());
        assertEquals(ResourceTransferEngine.Stage.SOURCE_RETURN, r.unknownStage());
        assertEquals(32, r.unknownRequested());
        assertEquals(32, r.buffered());
        assertEquals(1, source.insertionCalls);
        assertEquals(0, source.stacks[0].getCount() + source.stacks[1].getCount());
    }

    @Test
    void explicitHintsAreBoundedBeforeCopyingAndNegativeIndicesReject() {
        Endpoint source = endpoint(items(64)), target = endpoint(items(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> engine.prepareExact(source, target, IRON, new int[] {0, 1}, new int[] {0, 1}, budget(3)));
        assertThrows(
                IllegalArgumentException.class,
                () -> engine.prepareExact(source, target, IRON, new int[] {-1}, new int[] {0}, budget(3)));
        assertEquals(
                12_884_901_882L, ResourceTransferEngine.maximumModificationCalls(Integer.MAX_VALUE, Integer.MAX_VALUE));
    }

    @Test
    void exactScaleFollowsNativeViewsAndWorstCaseBudgetIncludesEveryReturnSlot() {
        for (int slots : new int[] {1, 10, 100}) {
            FakeItemHandler source = new FakeItemHandler(slots), target = new FakeItemHandler(slots);
            for (int view = 0; view < slots; view++) source.stacks[view] = IRON.stack(64);
            source.refuseReturn = true;
            target.capacity = 64;
            target.actualInsertLimit = 0;
            int preparationCalls = 2 + 3 * slots;
            int modificationCalls = 6 * slots;
            TransferWorkBudget b = budget(preparationCalls + modificationCalls);
            ResourceTransferEngine.Result r = exact(endpoint(source), endpoint(target), 64L * slots, 0, 64L * slots, b);
            assertEquals(64L * slots, r.buffered());
            assertEquals(slots, source.extractionCalls);
            assertEquals(slots, source.insertionCalls);
            assertEquals(1, target.insertionCalls);
            assertEquals(preparationCalls + 4L * slots + 2, b.calls());
            assertTrue(b.calls() <= preparationCalls + modificationCalls);
            conserved(r);
        }
    }

    @Test
    void exactMaximumLongAllowancesRemainOneIntBoundedRequest() {
        FakeItemHandler source = items(Integer.MAX_VALUE), target = items(0);
        ResourceTransferEngine.Result r =
                exact(endpoint(source), endpoint(target), Long.MAX_VALUE, 0, Integer.MAX_VALUE, budget(11));
        assertEquals(Integer.MAX_VALUE, r.moved());
        assertEquals(Integer.MAX_VALUE, source.maximumRequest);
        assertEquals(1, source.extractionCalls);
        assertEquals(1, target.insertionCalls);
    }

    @Test
    void invalidationByFinalSourceCountRejectsWithoutMutation() {
        for (boolean exactMode : new boolean[] {false, true}) {
            FakeItemHandler source = items(64), target = items(0);
            Endpoint s = endpoint(source), t = endpoint(target);
            source.afterSlots = () -> {
                if (source.slotQueries == 2) t.valid = false;
            };
            ResourceTransferEngine.Result r =
                    exactMode ? exact(s, t, 64, 0, 64, budget(100)) : greedy(s, t, IRON, 64, budget(1));
            assertEquals(ResourceTransferEngine.Failure.INVALID_ENDPOINT, r.failure());
            assertEquals(0, source.extractionCalls);
            assertEquals(0, target.insertionCalls);
        }
    }

    @Test
    void exactDuplicateSourceHintCannotPromiseOneSlotTwice() {
        FakeItemHandler source = items(32), target = items(0);
        TransferWorkBudget b = budget(100);
        ResourceTransferEngine.Result r = engine.commitExact(
                engine.prepareExact(endpoint(source), endpoint(target), IRON, new int[] {0, 0}, new int[] {0}, b),
                64,
                64,
                0,
                64,
                recovery,
                ServerSettings.RecoveryLimits.defaults(),
                b);
        assertEquals(0, r.extracted());
        assertEquals(0, source.extractionCalls);
        assertEquals(0, target.calls);
    }

    @Test
    void exactCpuExpirationDuringFinalSourceBoundCheckStillPreventsFirstExtraction() {
        AtomicLong clock = new AtomicLong();
        FakeItemHandler source = items(64), target = items(0);
        source.afterSlots = () -> {
            if (source.slotQueries == 2) clock.set(100);
        };
        TransferWorkBudget b = new TransferWorkBudget(11, 100, 100, clock::get);
        ResourceTransferEngine.Result r = exact(endpoint(source), endpoint(target), 64, 0, 64, b);
        assertEquals(ResourceTransferEngine.Failure.WAITING_BUDGET, r.failure());
        assertEquals(0, source.extractionCalls);
        assertEquals(6, b.calls());
        assertTrue(recovery.isEmpty());
    }

    @Test
    void exactCpuExpirationAfterExtractionStillDisposesAllKnownResourcesThisCall() {
        AtomicLong clock = new AtomicLong();
        FakeItemHandler source = items(32, 32), target = items(0, 0);
        source.afterExtract = () -> clock.set(100);
        target.capacity = 40;
        target.actualInsertLimit = 0;
        source.refuseReturn = true;
        TransferWorkBudget b = new TransferWorkBudget(20, 100, 100, clock::get);
        ResourceTransferEngine.Result r = exact(endpoint(source), endpoint(target), 64, 0, 64, b);
        assertEquals(64, r.buffered());
        assertEquals(64, recovery.amount(IRON.key()));
        conserved(r);
    }

    @Test
    void illegalObservedTargetResultIsUnknownAndDoesNotCompensateSubmittedResource() {
        FakeItemHandler source = items(64);
        NativePortFixtures.Item target = new NativePortFixtures.Item();
        source.afterExtract = () -> target.remainder = new ItemStack(Items.GOLD_INGOT, 1);
        ResourceTransferEngine.Result r =
                greedy(endpoint(source), new Endpoint(new ItemResourcePort(target, PROVIDER)), IRON, 64, budget(1));
        assertEquals(ResourceTransferEngine.Failure.UNKNOWN_MUTATION, r.failure());
        assertEquals(ResourceTransferEngine.Stage.TARGET_INSERT, r.unknownStage());
        assertEquals(64, r.unknownRequested());
        assertEquals(0, r.buffered());
        assertEquals(0, source.insertionCalls);
    }

    @Test
    void nonmodifyingReturnCountFailureBuffersKnownHoldingsWithoutUnknownClaim() {
        FakeItemHandler source = items(64), target = items(0);
        target.actualInsertLimit = 60;
        source.afterSlots = () -> {
            if (source.slotQueries == 3) throw new IllegalStateException("Count failed");
        };
        ResourceTransferEngine.Result r = greedy(endpoint(source), endpoint(target), IRON, 64, budget(1));
        assertEquals(ResourceTransferEngine.Failure.EXCEPTION, r.failure());
        assertEquals(ResourceTransferEngine.Stage.NONE, r.unknownStage());
        assertEquals(4, r.buffered());
        assertEquals(0, source.insertionCalls);
        conserved(r);
    }

    @Test
    void multiSlotOneShortBudgetDoesNotExtractAndCandidateRetryUsesFreshSimulations() {
        FakeItemHandler source = items(32, 32), target = items(0, 0);
        target.capacity = 40;
        ResourceTransferEngine.ExactCandidate candidate = engine.prepareExact(endpoint(source), endpoint(target), IRON);
        ResourceTransferEngine.Result first = engine.commitExact(
                candidate, 64, 64, 0, 64, recovery, ServerSettings.RecoveryLimits.defaults(), budget(19));
        assertEquals(ResourceTransferEngine.Failure.WAITING_BUDGET, first.failure());
        assertEquals(0, source.extractionCalls);
        target.refuseSimulation = true;
        ResourceTransferEngine.Result second = engine.commitExact(
                candidate, 64, 64, 0, 64, recovery, ServerSettings.RecoveryLimits.defaults(), budget(20));
        assertEquals(ResourceTransferEngine.Failure.REFUSED, second.failure());
        assertEquals(0, source.extractionCalls);
        assertTrue(recovery.isEmpty());
    }

    @Test
    void sparseKeepHintsStillCountEveryCurrentSourceSlot() {
        FakeItemHandler source = items(32, 100, 32), target = items(0);
        TransferWorkBudget b = budget(100);
        ResourceTransferEngine.Result r = engine.commitExact(
                engine.prepareExact(endpoint(source), endpoint(target), IRON, new int[] {0, 2}, new int[] {0}, b),
                64,
                64,
                100,
                64,
                recovery,
                ServerSettings.RecoveryLimits.defaults(),
                b);
        assertEquals(64, r.moved());
        assertEquals(100, source.stacks[1].getCount());
        assertEquals(0, source.stacks[0].getCount() + source.stacks[2].getCount());
        assertTrue(r.completeBatch());
        conserved(r);
    }

    @Test
    void preNativeSourceReconstructionFailureIsKnownAndNeverExtracts() {
        assertReconstructionFailure(3, true);
    }

    @Test
    void preNativeTargetReconstructionFailurePreservesAndDisposesAllOfferedQuantity() {
        assertReconstructionFailure(4, true);
        assertReconstructionFailure(4, false);
    }

    @Test
    void preNativeReturnReconstructionFailureBuffersTheEntireKnownRemainder() {
        assertReconstructionFailure(5, true);
    }

    private void assertReconstructionFailure(int failAt, boolean persistent) {
        for (boolean exactMode : new boolean[] {false, true}) {
            for (boolean fluid : new boolean[] {false, true}) {
                TransferNativeFixtures.SerializationProvider provider =
                        new TransferNativeFixtures.SerializationProvider(PROVIDER);
                FakeItemHandler itemSource = items(64), itemTarget = items(0);
                itemTarget.actualInsertLimit = 60;
                TransferNativeFixtures.Fluid fluidSource =
                        new TransferNativeFixtures.Fluid(new FluidStack(Fluids.WATER, 64));
                TransferNativeFixtures.Fluid fluidTarget = new TransferNativeFixtures.Fluid(FluidStack.EMPTY);
                fluidTarget.actualReceiveLimit = 60;
                ResourceVariant variant = fluid
                        ? FluidVariant.from(new FluidStack(Fluids.WATER, 1), provider)
                        : ItemVariant.from(new ItemStack(Items.IRON_INGOT), provider);
                provider.contexts = 0;
                provider.failAt = failAt;
                provider.persistent = persistent;
                Endpoint source =
                        fluid ? new Endpoint(new FluidResourcePort(fluidSource, PROVIDER)) : endpoint(itemSource);
                Endpoint target =
                        fluid ? new Endpoint(new FluidResourcePort(fluidTarget, PROVIDER)) : endpoint(itemTarget);
                TransferWorkBudget b = budget(exactMode ? 100 : 1);
                ResourceTransferEngine.Result r = exactMode
                        ? engine.commitExact(
                                engine.prepareExact(source, target, variant),
                                64,
                                64,
                                0,
                                64,
                                recovery,
                                ServerSettings.RecoveryLimits.defaults(),
                                b)
                        : greedy(source, target, variant, 64, b);
                assertEquals(ResourceTransferEngine.Failure.EXCEPTION, r.failure());
                assertEquals(ResourceTransferEngine.Stage.NONE, r.unknownStage());
                assertEquals(0, r.unknownRequested());
                assertNotNull(r.cause());
                assertEquals(failAt == 3 ? 0 : 64, r.extracted());
                assertEquals(failAt == 5 ? 60 : 0, r.moved());
                assertEquals(failAt == 4 && !persistent ? 64 : 0, r.returned());
                assertEquals(failAt == 5 ? 4 : failAt == 4 && persistent ? 64 : 0, r.buffered());
                assertEquals(failAt == 3 ? 0 : 1, fluid ? fluidSource.extractionCalls : itemSource.extractionCalls);
                assertEquals(failAt == 5 ? 1 : 0, fluid ? fluidTarget.insertionCalls : itemTarget.insertionCalls);
                assertEquals(
                        failAt == 4 && !persistent ? 1 : 0,
                        fluid ? fluidSource.insertionCalls : itemSource.insertionCalls);
                if (!fluid) assertEquals(itemSource.calls + itemTarget.calls, b.calls());
                conserved(r);
            }
        }
    }

    @Test
    void preNativeLaterExtractionFailureRetainsEarlierKnownExtraction() {
        TransferNativeFixtures.SerializationProvider provider =
                new TransferNativeFixtures.SerializationProvider(PROVIDER);
        ResourceVariant variant = ItemVariant.from(new ItemStack(Items.IRON_INGOT), provider);
        provider.contexts = 0;
        provider.failAt = 5;
        provider.persistent = true;
        FakeItemHandler source = items(32, 32), target = items(0);
        TransferWorkBudget b = budget(100);
        ResourceTransferEngine.Result r = engine.commitExact(
                engine.prepareExact(endpoint(source), endpoint(target), variant),
                64,
                64,
                0,
                64,
                recovery,
                ServerSettings.RecoveryLimits.defaults(),
                b);
        assertEquals(ResourceTransferEngine.Failure.EXCEPTION, r.failure());
        assertEquals(ResourceTransferEngine.Stage.NONE, r.unknownStage());
        assertEquals(32, r.extracted());
        assertEquals(32, r.buffered());
        assertEquals(32, source.stacks[1].getCount());
        assertEquals(1, source.extractionCalls);
        assertEquals(0, target.insertionCalls);
        assertEquals(0, source.insertionCalls);
        assertEquals(source.calls + target.calls, b.calls());
        conserved(r);
    }
}
