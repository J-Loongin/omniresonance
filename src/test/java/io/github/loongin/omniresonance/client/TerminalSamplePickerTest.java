// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.transfer.ResourceTypes;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.junit.jupiter.api.Test;

class TerminalSamplePickerTest {
    @Test
    void itemSelectionSendsOnlyTheSlotIntentAndCancelDoesNotSample() {
        var picker = new TerminalSamplePicker();
        var actions = new ArrayList<TerminalFilterView.Action>();
        var stack = new ItemStack(Items.DIAMOND, 16);
        picker.open();
        picker.select(12, stack, ResourceTypes.ITEM, actions::add);
        assertEquals(List.of(new TerminalFilterView.Action.Sample(ResourceTypes.ITEM, 12, 0)), actions);
        assertEquals(16, stack.getCount());
        assertFalse(picker.isOpen());
        picker.open();
        assertTrue(picker.close());
        picker.select(1, stack, ResourceTypes.ITEM, actions::add);
        assertEquals(1, actions.size());
    }

    @Test
    void fluidPreviewCopiesNonemptyTanksWithoutInvokingMutationMethods() {
        var water = new FluidStack(Fluids.WATER, 1500);
        var result = TerminalSamplePicker.preview(handler(3, water));
        assertEquals(2, result.size());
        assertEquals(1, result.getFirst().index());
        assertEquals(2, result.getLast().index());
        water.setAmount(1);
        assertEquals(1500, result.getFirst().fluid().getAmount());
        assertThrows(UnsupportedOperationException.class, () -> result.clear());
        assertThrows(IllegalArgumentException.class, () -> TerminalSamplePicker.preview(handler(129, water)));
        assertThrows(IllegalArgumentException.class, () -> TerminalSamplePicker.preview(handler(-1, water)));
    }

    @Test
    void singleNonemptyTankKeepsItsRealIndexAndMultipleTanksWaitForSelection() {
        var picker = new TerminalSamplePicker();
        var actions = new ArrayList<TerminalFilterView.Action>();
        var tank = new TerminalSamplePicker.Tank(7, new FluidStack(Fluids.WATER, 1000));
        picker.open();
        picker.offerTanks(
                List.of(tank, new TerminalSamplePicker.Tank(9, new FluidStack(Fluids.LAVA, 1000))),
                ResourceTypes.FLUID,
                actions::add);
        assertTrue(actions.isEmpty());
        assertTrue(picker.isOpen());
        assertTrue(picker.back());
        assertTrue(picker.isOpen());
        assertTrue(picker.back());
        assertFalse(picker.isOpen());
        picker.offerTanks(List.of(tank), ResourceTypes.FLUID, actions::add);
        assertTrue(actions.isEmpty());
        picker.open();
        picker.offerTanks(List.of(tank), ResourceTypes.FLUID, actions::add);
        assertEquals(new TerminalFilterView.Action.Sample(ResourceTypes.FLUID, 0, 7), actions.getFirst());
        assertFalse(picker.isOpen());
    }

    @Test
    void inventoryPresentationStartsWithBackpackThenHotbarWithoutDuplicateSlots() {
        var slots = new java.util.HashSet<Integer>();
        for (int index = 0; index < 41; index++) assertTrue(slots.add(TerminalSamplePicker.inventorySlot(index)));
        assertEquals(9, TerminalSamplePicker.inventorySlot(0));
        assertEquals(0, TerminalSamplePicker.inventorySlot(27));
        assertEquals(40, TerminalSamplePicker.inventorySlot(40));
    }

    @Test
    void equipmentPresentationRunsFromHelmetToBootsWithoutChangingInventoryIdentity() {
        assertEquals(39, TerminalSamplePicker.inventorySlot(36));
        assertEquals(38, TerminalSamplePicker.inventorySlot(37));
        assertEquals(37, TerminalSamplePicker.inventorySlot(38));
        assertEquals(36, TerminalSamplePicker.inventorySlot(39));
        assertEquals(40, TerminalSamplePicker.inventorySlot(40));
    }

    @Test
    void sampleGeometryAlignsEquipmentAndOffhandWithoutOverlappingAnySlot() {
        var body = new TerminalLayout.Rect(100, 50, 350, 120);
        var helmet = TerminalSampleLayout.slot(body, 36);
        for (int index = 37; index < 40; index++) {
            var equipment = TerminalSampleLayout.slot(body, index);
            assertEquals(helmet.x(), equipment.x());
            assertEquals(helmet.y() + (index - 36) * 22, equipment.y());
        }
        var hotbar = TerminalSampleLayout.slot(body, 27);
        var offhand = TerminalSampleLayout.slot(body, 40);
        assertEquals(hotbar.y(), offhand.y());
        assertEquals(hotbar.y(), TerminalSampleLayout.slot(body, 39).y());
        assertEquals(22, hotbar.x() - offhand.x());
        for (int i = 0; i < 41; i++) {
            var a = TerminalSampleLayout.slot(body, i);
            assertEquals(20, a.width());
            assertEquals(20, a.height());
            for (int j = i + 1; j < 41; j++) {
                var b = TerminalSampleLayout.slot(body, j);
                assertTrue(a.right() <= b.x() || b.right() <= a.x() || a.bottom() <= b.y() || b.bottom() <= a.y());
            }
        }
    }

    private static IFluidHandler handler(int count, FluidStack fluid) {
        return new IFluidHandler() {
            public int getTanks() {
                return count;
            }

            public FluidStack getFluidInTank(int tank) {
                return tank == 0 ? FluidStack.EMPTY : fluid;
            }

            public int getTankCapacity(int tank) {
                throw new AssertionError("No capacity query");
            }

            public boolean isFluidValid(int tank, FluidStack stack) {
                throw new AssertionError("No validity query");
            }

            public int fill(FluidStack stack, FluidAction action) {
                throw new AssertionError("No fill");
            }

            public FluidStack drain(FluidStack stack, FluidAction action) {
                throw new AssertionError("No drain");
            }

            public FluidStack drain(int amount, FluidAction action) {
                throw new AssertionError("No drain");
            }
        };
    }
}
