// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer.fixtures;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.energy.IEnergyStorage;

/** Stack-backed FE fixture; copies retain energy through standard Minecraft component serialization. */
public final class StackEnergyStorage implements IEnergyStorage {
    private final ItemStack stack;
    private final int capacity;

    public StackEnergyStorage(ItemStack stack, int capacity) {
        this.stack = stack;
        this.capacity = capacity;
    }

    public int getEnergyStored() {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                .copyTag()
                .getInt("test_energy");
    }

    public int getMaxEnergyStored() {
        return capacity;
    }

    public boolean canReceive() {
        return true;
    }

    public boolean canExtract() {
        return true;
    }

    public int receiveEnergy(int amount, boolean simulate) {
        int received = Math.min(Math.max(0, amount), capacity - getEnergyStored());
        if (!simulate && received > 0) write(getEnergyStored() + received);
        return received;
    }

    public int extractEnergy(int amount, boolean simulate) {
        int extracted = Math.min(Math.max(0, amount), getEnergyStored());
        if (!simulate && extracted > 0) write(getEnergyStored() - extracted);
        return extracted;
    }

    private void write(int amount) {
        var tag =
                stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        tag.putInt("test_energy", amount);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }
}
