// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class FakeEnergyStorageTest {
    @Test
    void simulatedReceiveDoesNotChangeStoredEnergy() {
        FakeEnergyStorage storage = new FakeEnergyStorage(100, 20);
        assertEquals(80, storage.receiveEnergy(100, true));
        assertEquals(20, storage.getEnergyStored());
    }

    @Test
    void actualReceiveReturnsOnlyAcceptedEnergy() {
        FakeEnergyStorage storage = new FakeEnergyStorage(100, 80);
        assertEquals(20, storage.receiveEnergy(50, false));
        assertEquals(100, storage.getEnergyStored());
        assertEquals(0, storage.receiveEnergy(1, false));
    }

    @Test
    void simulatedExtractDoesNotChangeStoredEnergy() {
        FakeEnergyStorage storage = new FakeEnergyStorage(100, 20);
        assertEquals(20, storage.extractEnergy(100, true));
        assertEquals(20, storage.getEnergyStored());
    }

    @Test
    void actualExtractCannotRemoveMoreThanStored() {
        FakeEnergyStorage storage = new FakeEnergyStorage(100, 20);
        assertEquals(20, storage.extractEnergy(100, false));
        assertEquals(0, storage.getEnergyStored());
        assertEquals(0, storage.extractEnergy(1, false));
    }

    @Test
    void largeReceiveIsBoundedByCapacityWithoutOverflow() {
        FakeEnergyStorage storage = new FakeEnergyStorage(Integer.MAX_VALUE, 10);
        assertEquals(Integer.MAX_VALUE - 10, storage.receiveEnergy(Integer.MAX_VALUE, false));
        assertEquals(Integer.MAX_VALUE, storage.getEnergyStored());
    }

    @Test
    void nonPositiveRequestsLeaveEnergyUnchanged() {
        FakeEnergyStorage storage = new FakeEnergyStorage(100, 20);
        assertEquals(0, storage.receiveEnergy(-1, false));
        assertEquals(0, storage.receiveEnergy(0, false));
        assertEquals(0, storage.extractEnergy(-1, false));
        assertEquals(0, storage.extractEnergy(0, false));
        assertEquals(20, storage.getEnergyStored());
    }

    @Test
    void invalidInitialStateIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new FakeEnergyStorage(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new FakeEnergyStorage(100, -1));
        assertThrows(IllegalArgumentException.class, () -> new FakeEnergyStorage(100, 101));
    }

    @Test
    void zeroCapacityStorageNeverAcceptsOrExtracts() {
        FakeEnergyStorage storage = new FakeEnergyStorage(0, 0);
        assertEquals(0, storage.receiveEnergy(Integer.MAX_VALUE, false));
        assertEquals(0, storage.extractEnergy(Integer.MAX_VALUE, false));
        assertEquals(0, storage.getEnergyStored());
    }
}
