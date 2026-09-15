// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer.fixtures;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem;

/** Native item-capability decorator for shared settlement, exception and reentrant ownership tests. */
public final class FluidItemProbe implements IFluidHandlerItem {
    private final IFluidHandlerItem delegate;
    public boolean failContainer;
    public int containerCalls, mutations;
    public Runnable afterMutation = () -> {};

    public FluidItemProbe(IFluidHandlerItem delegate) {
        this.delegate = delegate;
    }

    public ItemStack getContainer() {
        containerCalls++;
        if (failContainer) throw new IllegalStateException("Injected container failure");
        return delegate.getContainer();
    }

    public int getTanks() {
        return delegate.getTanks();
    }

    public FluidStack getFluidInTank(int tank) {
        return delegate.getFluidInTank(tank);
    }

    public int getTankCapacity(int tank) {
        return delegate.getTankCapacity(tank);
    }

    public boolean isFluidValid(int tank, FluidStack stack) {
        return delegate.isFluidValid(tank, stack);
    }

    public int fill(FluidStack stack, FluidAction action) {
        int result = delegate.fill(stack, action);
        changed(action);
        return result;
    }

    public FluidStack drain(FluidStack stack, FluidAction action) {
        var result = delegate.drain(stack, action);
        changed(action);
        return result;
    }

    public FluidStack drain(int amount, FluidAction action) {
        var result = delegate.drain(amount, action);
        changed(action);
        return result;
    }

    private void changed(FluidAction action) {
        if (action.execute()) {
            mutations++;
            afterMutation.run();
        }
    }
}
