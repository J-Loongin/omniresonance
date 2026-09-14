// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.transfer.fixtures.FakeItemHandler;
import io.github.loongin.omniresonance.transfer.fixtures.NativePortFixtures;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import org.junit.jupiter.api.Test;

final class NativeResourcePortTest {
    private static final HolderLookup.Provider PROVIDER =
            HolderLookup.Provider.create(BuiltInRegistries.REGISTRY.stream().map(registry -> registry.asLookup()));

    private static TransferWorkBudget budget() {
        return new TransferWorkBudget(100, 1000, 100, () -> 0);
    }

    private static ItemVariant iron() {
        return ItemVariant.from(new ItemStack(Items.IRON_INGOT), PROVIDER);
    }

    private static FluidVariant water() {
        return FluidVariant.from(new FluidStack(Fluids.WATER, 1), PROVIDER);
    }

    @Test
    void itemSimulationAndExecutionUseOneCallAtAnyPositiveIntScale() {
        for (int quantity : new int[] {1, 65_556, Integer.MAX_VALUE}) {
            FakeItemHandler source = new FakeItemHandler(2);
            source.stacks[1] = new ItemStack(Items.IRON_INGOT, quantity);
            FakeItemHandler target = new FakeItemHandler(1);
            ResourcePort input = new ItemResourcePort(source, PROVIDER);
            ResourcePort output = new ItemResourcePort(target, PROVIDER);
            TransferWorkBudget b = budget();
            assertEquals(ResourceTypes.ITEM, input.typeId());
            assertEquals(ResourcePort.ExtractionScope.VIEW, input.extractionScope());
            assertEquals(2, input.sourceViews(b));
            assertEquals(1, output.targetViews(b));
            assertTrue(input.peek(0, b).isEmpty());
            ResourceAmount candidate = input.peek(1, b).orElseThrow();
            assertEquals(quantity, candidate.quantity());
            assertEquals(quantity, input.extract(1, candidate.variant(), quantity, true, b));
            assertEquals(quantity, output.insert(0, candidate.variant(), quantity, true, b));
            assertEquals(quantity, source.stacks[1].getCount());
            assertTrue(target.stacks[0].isEmpty());
            assertEquals(quantity, input.extract(1, candidate.variant(), quantity, false, b));
            assertEquals(quantity, output.insert(0, candidate.variant(), quantity, false, b));
            assertTrue(source.stacks[1].isEmpty());
            assertEquals(quantity, target.stacks[0].getCount());
            assertEquals(8, b.calls());
            assertEquals(source.calls + target.calls, b.calls());
        }
    }

    @Test
    void nativeFluidTankAndEnergyStoragePreserveSimulationAndMoveMaximumInt() {
        FluidTank tank = new FluidTank(Integer.MAX_VALUE);
        EnergyStorage storage = new EnergyStorage(Integer.MAX_VALUE);
        ResourcePort[] ports = {new FluidResourcePort(tank, PROVIDER), new EnergyResourcePort(storage)};
        ResourceVariant[] variants = {water(), EnergyVariant.INSTANCE};
        for (int i = 0; i < ports.length; i++) {
            ResourcePort port = ports[i];
            TransferWorkBudget b = budget();
            assertEquals(1, port.sourceViews(b));
            assertEquals(1, port.targetViews(b));
            assertEquals(ResourcePort.ExtractionScope.HANDLER, port.extractionScope());
            assertEquals(variants[i].key().typeId(), port.typeId());
            assertTrue(port.peek(0, b).isEmpty());
            assertEquals(Integer.MAX_VALUE, port.insert(0, variants[i], Integer.MAX_VALUE, true, b));
            assertTrue(port.peek(0, b).isEmpty());
            assertEquals(Integer.MAX_VALUE, port.insert(0, variants[i], Integer.MAX_VALUE, false, b));
            assertEquals(Integer.MAX_VALUE, port.peek(0, b).orElseThrow().quantity());
            assertEquals(Integer.MAX_VALUE, port.extract(0, variants[i], Integer.MAX_VALUE, true, b));
            assertEquals(Integer.MAX_VALUE, port.peek(0, b).orElseThrow().quantity());
            assertEquals(Integer.MAX_VALUE, port.extract(0, variants[i], Integer.MAX_VALUE, false, b));
            assertTrue(port.peek(0, b).isEmpty());
            assertEquals(i == 0 ? 10 : 9, b.calls());
        }
    }

    @Test
    void fluidViewsAreCandidatesWhileExtractionSpansTheHandler() {
        NativePortFixtures.Fluid handler = new NativePortFixtures.Fluid(300, 700);
        ResourcePort port = new FluidResourcePort(handler, PROVIDER);
        TransferWorkBudget b = budget();
        assertEquals(2, port.sourceViews(b));
        assertEquals(1, port.targetViews(b));
        assertEquals(ResourcePort.ExtractionScope.HANDLER, port.extractionScope());
        assertEquals(300, port.peek(0, b).orElseThrow().quantity());
        assertEquals(700, port.peek(1, b).orElseThrow().quantity());
        assertEquals(1000, port.extract(0, water(), 1000, true, b));
        assertEquals(1000, port.extract(1, water(), 1000, true, b));
        assertEquals(300, handler.tanks[0].getFluidAmount());
        assertEquals(700, handler.tanks[1].getFluidAmount());
        assertEquals(800, port.extract(1, water(), 800, false, b));
        assertEquals(0, handler.tanks[0].getFluidAmount());
        assertEquals(200, handler.tanks[1].getFluidAmount());
        assertEquals(6, b.calls());
        assertEquals(handler.calls, b.calls());
    }

    @Test
    void itemRemainderIsValidatedAndConvertedToAcceptedQuantity() {
        NativePortFixtures.Item handler = new NativePortFixtures.Item();
        ResourcePort port = new ItemResourcePort(handler, PROVIDER);
        TransferWorkBudget b = budget();
        port.sourceViews(b);
        port.targetViews(b);
        handler.remainder = new ItemStack(Items.IRON_INGOT, 40);
        assertEquals(60, port.insert(0, iron(), 100, true, b));
        handler.remainder = new ItemStack(Items.GOLD_INGOT, 40);
        assertThrows(IllegalArgumentException.class, () -> port.insert(0, iron(), 100, false, b));
        handler.remainder = new ItemStack(Items.IRON_INGOT, 101);
        assertThrows(IllegalArgumentException.class, () -> port.insert(0, iron(), 100, true, b));
        handler.extracted = new ItemStack(Items.GOLD_INGOT, 1);
        assertThrows(IllegalArgumentException.class, () -> port.extract(0, iron(), 100, false, b));
        handler.extracted = new ItemStack(Items.IRON_INGOT, 101);
        assertThrows(IllegalArgumentException.class, () -> port.extract(0, iron(), 100, true, b));
        handler.extracted = new ItemStack(Items.IRON_INGOT, 1);
        handler.extracted.set(DataComponents.CUSTOM_NAME, Component.literal("Wrong components"));
        assertThrows(IllegalArgumentException.class, () -> port.extract(0, iron(), 100, true, b));
        assertEquals(handler.calls, b.calls());
    }

    @Test
    void invalidFluidAndEnergyResultsThrowWithoutCompensation() {
        NativePortFixtures.Fluid fluid = new NativePortFixtures.Fluid(100);
        ResourcePort fluidPort = new FluidResourcePort(fluid, PROVIDER);
        NativePortFixtures.Energy energy = new NativePortFixtures.Energy();
        ResourcePort energyPort = new EnergyResourcePort(energy);
        TransferWorkBudget b = budget();
        fluidPort.sourceViews(b);
        fluidPort.targetViews(b);
        energyPort.sourceViews(b);
        energyPort.targetViews(b);
        fluid.overrideFill = true;
        fluid.overrideDrain = true;
        for (int invalid : new int[] {-1, 101}) {
            fluid.filled = invalid;
            energy.accepted = invalid;
            energy.extracted = invalid;
            assertThrows(IllegalArgumentException.class, () -> fluidPort.insert(0, water(), 100, false, b));
            assertThrows(
                    IllegalArgumentException.class, () -> energyPort.insert(0, EnergyVariant.INSTANCE, 100, false, b));
            assertThrows(
                    IllegalArgumentException.class, () -> energyPort.extract(0, EnergyVariant.INSTANCE, 100, true, b));
        }
        fluid.drained = new FluidStack(Fluids.WATER, 101);
        assertThrows(IllegalArgumentException.class, () -> fluidPort.extract(0, water(), 100, false, b));
        fluid.drained = new FluidStack(Fluids.LAVA, 1);
        assertThrows(IllegalArgumentException.class, () -> fluidPort.extract(0, water(), 100, true, b));
        energy.stored = -1;
        assertThrows(IllegalArgumentException.class, () -> energyPort.peek(0, b));
        assertEquals(fluid.calls + energy.calls, b.calls());
        assertEquals(10, b.calls());
    }

    @Test
    void viewPreparationIsExplicitAndInvalidRequestsMakeNoNativeCalls() {
        NativePortFixtures.Item item = new NativePortFixtures.Item();
        NativePortFixtures.Fluid fluid = new NativePortFixtures.Fluid(1);
        ResourcePort[] ports = {
            new ItemResourcePort(item, PROVIDER),
            new FluidResourcePort(fluid, PROVIDER),
            new EnergyResourcePort(new EnergyStorage(100))
        };
        ResourceVariant[] variants = {iron(), water(), EnergyVariant.INSTANCE};
        for (int i = 0; i < ports.length; i++) {
            ResourcePort port = ports[i];
            ResourceVariant variant = variants[i];
            TransferWorkBudget b = budget();
            if (i < 2) assertThrows(IllegalArgumentException.class, () -> port.peek(0, b));
            port.sourceViews(b);
            port.targetViews(b);
            long calls = b.calls();
            assertThrows(IllegalArgumentException.class, () -> port.peek(-1, b));
            assertThrows(IllegalArgumentException.class, () -> port.peek(1, b));
            assertThrows(IllegalArgumentException.class, () -> port.extract(1, variant, 1, true, b));
            assertThrows(IllegalArgumentException.class, () -> port.insert(1, variant, 1, false, b));
            assertThrows(IllegalArgumentException.class, () -> port.extract(0, variant, 0, false, b));
            assertThrows(IllegalArgumentException.class, () -> port.insert(0, variant, -1, true, b));
            ResourceVariant wrong = i == 0 ? water() : iron();
            assertThrows(IllegalArgumentException.class, () -> port.insert(0, wrong, 1, true, b));
            assertThrows(IllegalArgumentException.class, () -> port.extract(0, wrong, 1, false, b));
            assertEquals(calls, b.calls());
        }
        ResourcePort itemPort = new ItemResourcePort(item, PROVIDER);
        ResourcePort fluidPort = new FluidResourcePort(fluid, PROVIDER);
        TransferWorkBudget b = budget();
        itemPort.sourceViews(b);
        fluidPort.sourceViews(b);
        item.views = 0;
        fluid.views = 0;
        assertEquals(0, itemPort.sourceViews(b));
        assertEquals(0, fluidPort.sourceViews(b));
        assertThrows(IllegalArgumentException.class, () -> itemPort.peek(0, b));
        assertThrows(IllegalArgumentException.class, () -> fluidPort.peek(0, b));
        item.views = -1;
        fluid.views = -1;
        assertThrows(IllegalArgumentException.class, () -> itemPort.sourceViews(b));
        assertThrows(IllegalArgumentException.class, () -> fluidPort.sourceViews(b));
        assertEquals(6, b.calls());
    }

    @Test
    void throwingCallsAreCountedAndTimedIncludingCountQueries() {
        NativePortFixtures.Item item = new NativePortFixtures.Item();
        NativePortFixtures.Fluid fluid = new NativePortFixtures.Fluid(1);
        NativePortFixtures.Energy energy = new NativePortFixtures.Energy();
        ResourcePort[] ports = {
            new ItemResourcePort(item, PROVIDER), new FluidResourcePort(fluid, PROVIDER), new EnergyResourcePort(energy)
        };
        ResourceVariant[] variants = {iron(), water(), EnergyVariant.INSTANCE};
        AtomicLong clock = new AtomicLong();
        TransferWorkBudget b = new TransferWorkBudget(1, 1000, 5, () -> clock.getAndAdd(10));
        for (ResourcePort port : ports) {
            port.sourceViews(b);
            port.targetViews(b);
        }
        item.throwCall = true;
        fluid.throwCall = true;
        energy.throwCall = true;
        long before = b.calls();
        for (int i = 0; i < ports.length; i++) {
            ResourcePort port = ports[i];
            ResourceVariant variant = variants[i];
            assertThrows(IllegalStateException.class, () -> port.peek(0, b));
            assertThrows(IllegalStateException.class, () -> port.extract(0, variant, 1, false, b));
            assertThrows(IllegalStateException.class, () -> port.insert(0, variant, 1, true, b));
        }
        assertThrows(IllegalStateException.class, () -> ports[0].sourceViews(b));
        assertThrows(IllegalStateException.class, () -> ports[1].sourceViews(b));
        assertEquals(before + 11, b.calls());
        assertEquals(b.calls(), b.slowCalls());
        assertFalse(b.canStart());
    }

    @Test
    void nativeStackAliasesCannotMutateCapturedIdentityOrLaterRequests() {
        NativePortFixtures.Item item = new NativePortFixtures.Item();
        item.setStackInSlot(0, new ItemStack(Items.IRON_INGOT, 20));
        NativePortFixtures.Fluid fluid = new NativePortFixtures.Fluid(20);
        ResourcePort[] ports = {new ItemResourcePort(item, PROVIDER), new FluidResourcePort(fluid, PROVIDER)};
        TransferWorkBudget b = budget();
        for (ResourcePort port : ports) {
            port.sourceViews(b);
            port.targetViews(b);
        }
        ResourceVariant capturedItem = ports[0].peek(0, b).orElseThrow().variant();
        ResourceVariant capturedFluid = ports[1].peek(0, b).orElseThrow().variant();
        item.getStackInSlot(0).set(DataComponents.CUSTOM_NAME, Component.literal("Modified"));
        fluid.tanks[0].getFluid().set(DataComponents.CUSTOM_NAME, Component.literal("Modified"));
        assertEquals(iron().key(), capturedItem.key());
        assertEquals(water().key(), capturedFluid.key());
        item.mutateInput = true;
        fluid.mutateInput = true;
        fluid.overrideFill = true;
        fluid.filled = 5;
        assertEquals(20, ports[0].insert(0, capturedItem, 20, true, b));
        assertEquals(5, ports[1].insert(0, capturedFluid, 20, true, b));
        fluid.lastRequest.set(DataComponents.CUSTOM_NAME, Component.literal("Retained input changed"));
        assertEquals(iron().key(), capturedItem.key());
        assertEquals(water().key(), capturedFluid.key());
        assertEquals(20, ((FluidVariant) capturedFluid).stack(20).getAmount());
    }
}
