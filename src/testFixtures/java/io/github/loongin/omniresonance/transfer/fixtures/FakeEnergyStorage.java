// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer.fixtures;

import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * A deterministic, single-thread-owned energy fixture. Simulations never mutate stored energy.
 * This test utility is not part of the production mod or its public API.
 */
public final class FakeEnergyStorage implements IEnergyStorage {
    private final int capacity;
    private int energy;

    /** Creates a fixture, rejecting negative capacity or initial energy outside its capacity. */
    public FakeEnergyStorage(int capacity, int initialEnergy) {
        if (capacity < 0 || initialEnergy < 0 || initialEnergy > capacity) {
            throw new IllegalArgumentException("Initial energy must be within non-negative capacity");
        }
        this.capacity = capacity;
        this.energy = initialEnergy;
    }

    @Override
    public int receiveEnergy(int toReceive, boolean simulate) {
        int received = Math.min(Math.max(0, toReceive), capacity - energy);
        if (!simulate) {
            energy += received;
        }
        return received;
    }

    @Override
    public int extractEnergy(int toExtract, boolean simulate) {
        int extracted = Math.min(Math.max(0, toExtract), energy);
        if (!simulate) {
            energy -= extracted;
        }
        return extracted;
    }

    @Override
    public int getEnergyStored() {
        return energy;
    }

    @Override
    public int getMaxEnergyStored() {
        return capacity;
    }

    @Override
    public boolean canExtract() {
        return true;
    }

    @Override
    public boolean canReceive() {
        return true;
    }
}
