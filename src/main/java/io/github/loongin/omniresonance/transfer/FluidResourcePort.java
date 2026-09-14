// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

/**
 * Server-thread-owned fluid boundary. Source views identify candidates, not independent drain promises.
 * Drain and fill operate on the entire handler; target view is always zero.
 */
public final class FluidResourcePort implements ResourcePort {
    private final IFluidHandler handler;
    private final HolderLookup.Provider provider;
    private int sourceViews = -1;

    /** Borrows the handler/provider for this port lifetime without native access or mutation. */
    public FluidResourcePort(IFluidHandler handler, HolderLookup.Provider provider) {
        this.handler = Objects.requireNonNull(handler);
        this.provider = Objects.requireNonNull(provider);
    }

    @Override
    public ResourceLocation typeId() {
        return ResourceTypes.FLUID;
    }

    @Override
    public ExtractionScope extractionScope() {
        return ExtractionScope.HANDLER;
    }

    @Override
    public int sourceViews(TransferWorkBudget budget) {
        budget.beforeCall();
        try {
            int count = handler.getTanks();
            if (count < 0) throw new IllegalArgumentException("Negative tank count");
            sourceViews = count;
            return count;
        } finally {
            budget.afterCall();
        }
    }

    @Override
    public int targetViews(TransferWorkBudget budget) {
        return 1;
    }

    @Override
    public Optional<ResourceAmount> peek(int sourceView, TransferWorkBudget budget) {
        validateView(sourceView, sourceViews);
        FluidStack stack;
        budget.beforeCall();
        try {
            stack = handler.getFluidInTank(sourceView).copy();
        } finally {
            budget.afterCall();
        }
        return stack.isEmpty()
                ? Optional.empty()
                : Optional.of(new ResourceAmount(FluidVariant.from(stack, provider), stack.getAmount()));
    }

    @Override
    public int extract(
            int sourceView, ResourceVariant variant, int amount, boolean simulate, TransferWorkBudget budget) {
        validateView(sourceView, sourceViews);
        FluidStack identity = request(variant, amount);
        FluidStack supplied = identity.copy();
        FluidStack extracted;
        budget.beforeCall();
        try {
            extracted = handler.drain(supplied, simulate ? FluidAction.SIMULATE : FluidAction.EXECUTE)
                    .copy();
        } finally {
            budget.afterCall();
        }
        if (extracted.isEmpty()) return 0;
        if (extracted.getAmount() > amount || !FluidStack.isSameFluidSameComponents(extracted, identity)) {
            throw new IllegalArgumentException("Invalid native fluid extraction");
        }
        return extracted.getAmount();
    }

    @Override
    public int insert(
            int targetView, ResourceVariant variant, int amount, boolean simulate, TransferWorkBudget budget) {
        validateView(targetView, 1);
        FluidStack supplied = request(variant, amount);
        int accepted;
        budget.beforeCall();
        try {
            accepted = handler.fill(supplied, simulate ? FluidAction.SIMULATE : FluidAction.EXECUTE);
        } finally {
            budget.afterCall();
        }
        if (accepted < 0 || accepted > amount) throw new IllegalArgumentException("Invalid native fluid acceptance");
        return accepted;
    }

    private static FluidStack request(ResourceVariant variant, int amount) {
        if (!(variant instanceof FluidVariant fluid)) throw new IllegalArgumentException("Expected fluid variant");
        return fluid.stack(amount);
    }

    private static void validateView(int view, int bound) {
        if (view < 0 || view >= bound) throw new IllegalArgumentException("Fluid view outside prepared bounds");
    }
}
