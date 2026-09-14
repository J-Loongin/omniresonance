// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer.fixtures;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.neoforged.neoforge.items.ItemStackHandler;

/** Deterministic native-boundary violations, counters, and multi-tank semantics for port tests. */
public final class NativePortFixtures {
    private NativePortFixtures() {}

    public static final class Item extends ItemStackHandler {
        public int calls;
        public int views = 1;
        public ItemStack extracted = ItemStack.EMPTY;
        public ItemStack remainder = ItemStack.EMPTY;
        public boolean throwCall;
        public boolean mutateInput;

        public Item() {
            super(1);
        }

        private void call() {
            calls++;
            if (throwCall) throw new IllegalStateException("Native item failure");
        }

        @Override
        public int getSlots() {
            call();
            return views;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            call();
            return super.getStackInSlot(slot);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            call();
            return extracted;
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            call();
            if (mutateInput) stack.setCount(1);
            return remainder;
        }
    }

    public static final class Energy extends EnergyStorage {
        public int calls;
        public int extracted;
        public int accepted;
        public int stored;
        public boolean throwCall;

        public Energy() {
            super(Integer.MAX_VALUE);
        }

        private void call() {
            calls++;
            if (throwCall) throw new IllegalStateException("Native energy failure");
        }

        @Override
        public int getEnergyStored() {
            call();
            return stored;
        }

        @Override
        public int extractEnergy(int amount, boolean simulate) {
            call();
            return extracted;
        }

        @Override
        public int receiveEnergy(int amount, boolean simulate) {
            call();
            return accepted;
        }

        @Override
        public int getMaxEnergyStored() {
            throw new AssertionError("Capacity is not an acceptance promise");
        }

        @Override
        public boolean canReceive() {
            throw new AssertionError("No extra query");
        }

        @Override
        public boolean canExtract() {
            throw new AssertionError("No extra query");
        }
    }

    public static final class Fluid implements IFluidHandler {
        public final FluidTank[] tanks;
        public int calls;
        public int views;
        public boolean throwCall;
        public boolean overrideDrain;
        public FluidStack drained = FluidStack.EMPTY;
        public boolean overrideFill;
        public int filled;
        public boolean mutateInput;
        public FluidStack lastRequest = FluidStack.EMPTY;

        public Fluid(int... quantities) {
            tanks = new FluidTank[quantities.length];
            views = quantities.length;
            for (int i = 0; i < quantities.length; i++) {
                tanks[i] = new FluidTank(Integer.MAX_VALUE);
                tanks[i].setFluid(new FluidStack(Fluids.WATER, quantities[i]));
            }
        }

        private void call() {
            calls++;
            if (throwCall) throw new IllegalStateException("Native fluid failure");
        }

        @Override
        public int getTanks() {
            call();
            return views;
        }

        @Override
        public FluidStack getFluidInTank(int tank) {
            call();
            return tanks[tank].getFluid();
        }

        @Override
        public int getTankCapacity(int tank) {
            throw new AssertionError("No extra query");
        }

        @Override
        public boolean isFluidValid(int tank, FluidStack resource) {
            throw new AssertionError("No extra query");
        }

        @Override
        public int fill(FluidStack resource, FluidAction action) {
            call();
            lastRequest = resource;
            if (mutateInput) resource.setAmount(1);
            if (overrideFill) return filled;
            return tanks[0].fill(resource, action);
        }

        @Override
        public FluidStack drain(FluidStack resource, FluidAction action) {
            call();
            lastRequest = resource;
            if (mutateInput) resource.setAmount(1);
            if (overrideDrain) return drained;
            int total = 0;
            for (FluidTank tank : tanks) {
                if (total == resource.getAmount()) break;
                total += tank.drain(resource.copyWithAmount(resource.getAmount() - total), action)
                        .getAmount();
            }
            return resource.copyWithAmount(total);
        }

        @Override
        public FluidStack drain(int amount, FluidAction action) {
            throw new AssertionError("Port must use identity-sensitive drain");
        }
    }
}
