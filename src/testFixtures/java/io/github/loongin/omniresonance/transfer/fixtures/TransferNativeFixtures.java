// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer.fixtures;

import com.mojang.serialization.DynamicOps;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;

/** Stateful native handlers with deterministic execute-only partial acceptance for conservation tests. */
public final class TransferNativeFixtures {
    private TransferNativeFixtures() {}

    /** Delegates real component decoding, failing at a selected reconstruction before native invocation. */
    public static final class SerializationProvider implements HolderLookup.Provider {
        private final HolderLookup.Provider delegate;
        public int contexts;
        public int failAt = Integer.MAX_VALUE;
        public boolean persistent;

        public SerializationProvider(HolderLookup.Provider delegate) {
            this.delegate = delegate;
        }

        @Override
        public Stream<ResourceKey<? extends Registry<?>>> listRegistries() {
            return delegate.listRegistries();
        }

        @Override
        public <T> Optional<HolderLookup.RegistryLookup<T>> lookup(ResourceKey<? extends Registry<? extends T>> key) {
            return delegate.lookup(key);
        }

        @Override
        public <T> RegistryOps<T> createSerializationContext(DynamicOps<T> ops) {
            contexts++;
            if (contexts == failAt || persistent && contexts > failAt)
                throw new IllegalArgumentException("Request component reconstruction failed");
            return delegate.createSerializationContext(ops);
        }
    }

    public static final class Energy extends EnergyStorage {
        public int actualReceiveLimit = Integer.MAX_VALUE;
        public int actualExtractLimit = Integer.MAX_VALUE;
        public int extractionCalls, insertionCalls;

        public Energy(int amount) {
            super(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, amount);
        }

        @Override
        public int receiveEnergy(int amount, boolean simulate) {
            if (!simulate) insertionCalls++;
            return super.receiveEnergy(simulate ? amount : Math.min(amount, actualReceiveLimit), simulate);
        }

        @Override
        public int extractEnergy(int amount, boolean simulate) {
            if (!simulate) extractionCalls++;
            return super.extractEnergy(simulate ? amount : Math.min(amount, actualExtractLimit), simulate);
        }
    }

    public static final class Fluid extends FluidTank {
        public int actualReceiveLimit = Integer.MAX_VALUE;
        public int actualExtractLimit = Integer.MAX_VALUE;
        public int extractionCalls, insertionCalls;

        public Fluid(FluidStack initial) {
            super(Integer.MAX_VALUE);
            setFluid(initial);
        }

        @Override
        public int fill(FluidStack amount, FluidAction action) {
            if (action.execute()) insertionCalls++;
            return super.fill(
                    action.simulate()
                            ? amount
                            : amount.copyWithAmount(Math.min(amount.getAmount(), actualReceiveLimit)),
                    action);
        }

        @Override
        public FluidStack drain(FluidStack amount, FluidAction action) {
            if (action.execute()) extractionCalls++;
            return super.drain(
                    action.simulate()
                            ? amount
                            : amount.copyWithAmount(Math.min(amount.getAmount(), actualExtractLimit)),
                    action);
        }
    }
}
