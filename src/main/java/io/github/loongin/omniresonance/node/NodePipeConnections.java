// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.node;

import io.github.loongin.omniresonance.registry.ModBlockEntities;
import io.github.loongin.omniresonance.transfer.PipeConnections;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.neoforged.neoforge.items.ItemStackHandler;

/** Server-thread native node capabilities. Configured domain inputs receive through an authoritative lease;
 * other configurations expose empty connection markers. No handler stores or exposes inventory for extraction.
 * Simulations are pure; actual acceptance follows the lease result and failures propagate without guessing. */
public final class NodePipeConnections {
    private static final ItemStackHandler ITEMS = new ItemStackHandler(0);
    private static final FluidTank FLUIDS = new FluidTank(0);
    private static final EnergyStorage ENERGY = new EnergyStorage(0);

    private NodePipeConnections() {}

    /** Registers empty connection markers or receive-only leases. Discovery never reads or changes domain inventory. */
    public static void register(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
                Capabilities.ItemHandler.BLOCK,
                ModBlockEntities.RESONANCE_TRANSFER_NODE.get(),
                (entity, side) -> !entity.pipeConnection(side, PipeConnections.Type.ITEM)
                        ? null
                        : entity.externalInput() == null
                                ? ITEMS
                                : new ItemsInput(entity.externalInput(), side, entity));
        event.registerBlockEntity(
                Capabilities.FluidHandler.BLOCK,
                ModBlockEntities.RESONANCE_TRANSFER_NODE.get(),
                (entity, side) -> !entity.pipeConnection(side, PipeConnections.Type.FLUID)
                        ? null
                        : entity.externalInput() == null
                                ? FLUIDS
                                : new FluidInput(entity.externalInput(), side, entity));
        event.registerBlockEntity(
                Capabilities.EnergyStorage.BLOCK,
                ModBlockEntities.RESONANCE_TRANSFER_NODE.get(),
                (entity, side) -> !entity.pipeConnection(side, PipeConnections.Type.ENERGY)
                        ? null
                        : entity.externalInput() == null ? ENERGY : new EnergyInput(entity.externalInput(), side));
    }

    private record ItemsInput(
            io.github.loongin.omniresonance.transfer.ExternalDomainInput input,
            net.minecraft.core.Direction side,
            ResonanceNodeBlockEntity entity)
            implements net.neoforged.neoforge.items.IItemHandler {
        public int getSlots() {
            return 1;
        }

        public net.minecraft.world.item.ItemStack getStackInSlot(int slot) {
            return net.minecraft.world.item.ItemStack.EMPTY;
        }

        public int getSlotLimit(int slot) {
            return 64;
        }

        public boolean isItemValid(int slot, net.minecraft.world.item.ItemStack stack) {
            return slot == 0
                    && !stack.isEmpty()
                    && input.available(side, io.github.loongin.omniresonance.transfer.ResourceTypes.ITEM);
        }

        public net.minecraft.world.item.ItemStack extractItem(int slot, int amount, boolean simulate) {
            return net.minecraft.world.item.ItemStack.EMPTY;
        }

        public net.minecraft.world.item.ItemStack insertItem(
                int slot, net.minecraft.world.item.ItemStack stack, boolean simulate) {
            if (!isItemValid(slot, stack)) return stack;
            io.github.loongin.omniresonance.transfer.ItemVariant variant;
            try {
                variant = io.github.loongin.omniresonance.transfer.ItemVariant.from(
                        stack, entity.getLevel().registryAccess());
            } catch (IllegalArgumentException unsupported) {
                return stack;
            }
            long accepted = input.insert(side, variant, stack.getCount(), simulate);
            return accepted == stack.getCount()
                    ? net.minecraft.world.item.ItemStack.EMPTY
                    : stack.copyWithCount(stack.getCount() - (int) accepted);
        }
    }

    private record FluidInput(
            io.github.loongin.omniresonance.transfer.ExternalDomainInput input,
            net.minecraft.core.Direction side,
            ResonanceNodeBlockEntity entity)
            implements net.neoforged.neoforge.fluids.capability.IFluidHandler {
        public int getTanks() {
            return 1;
        }

        public net.neoforged.neoforge.fluids.FluidStack getFluidInTank(int tank) {
            return net.neoforged.neoforge.fluids.FluidStack.EMPTY;
        }

        public int getTankCapacity(int tank) {
            return Integer.MAX_VALUE;
        }

        public boolean isFluidValid(int tank, net.neoforged.neoforge.fluids.FluidStack stack) {
            return tank == 0
                    && !stack.isEmpty()
                    && input.available(side, io.github.loongin.omniresonance.transfer.ResourceTypes.FLUID);
        }

        public int fill(net.neoforged.neoforge.fluids.FluidStack stack, FluidAction action) {
            if (!isFluidValid(0, stack)) return 0;
            io.github.loongin.omniresonance.transfer.FluidVariant variant;
            try {
                variant = io.github.loongin.omniresonance.transfer.FluidVariant.from(
                        stack, entity.getLevel().registryAccess());
            } catch (IllegalArgumentException unsupported) {
                return 0;
            }
            return (int) input.insert(side, variant, stack.getAmount(), action.simulate());
        }

        public net.neoforged.neoforge.fluids.FluidStack drain(int max, FluidAction action) {
            return net.neoforged.neoforge.fluids.FluidStack.EMPTY;
        }

        public net.neoforged.neoforge.fluids.FluidStack drain(
                net.neoforged.neoforge.fluids.FluidStack stack, FluidAction action) {
            return net.neoforged.neoforge.fluids.FluidStack.EMPTY;
        }
    }

    private record EnergyInput(
            io.github.loongin.omniresonance.transfer.ExternalDomainInput input, net.minecraft.core.Direction side)
            implements net.neoforged.neoforge.energy.IEnergyStorage {
        public int receiveEnergy(int maximum, boolean simulate) {
            return maximum <= 0
                    ? 0
                    : (int) input.insert(
                            side, io.github.loongin.omniresonance.transfer.EnergyVariant.INSTANCE, maximum, simulate);
        }

        public int extractEnergy(int maximum, boolean simulate) {
            return 0;
        }

        public int getEnergyStored() {
            return 0;
        }

        public int getMaxEnergyStored() {
            return Integer.MAX_VALUE;
        }

        public boolean canExtract() {
            return false;
        }

        public boolean canReceive() {
            return input.available(side, io.github.loongin.omniresonance.transfer.ResourceTypes.ENERGY);
        }
    }
}
