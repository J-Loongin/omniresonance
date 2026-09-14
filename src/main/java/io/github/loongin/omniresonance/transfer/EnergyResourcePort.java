// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.energy.IEnergyStorage;

/** Server-thread-owned FE boundary. One balance view; only the operation result promises acceptance. */
public final class EnergyResourcePort implements ResourcePort {
    private final IEnergyStorage handler;
    /** Borrows the native handler for the port lifetime, without accessing or modifying it. */
    public EnergyResourcePort(IEnergyStorage handler) {
        this.handler = Objects.requireNonNull(handler);
    }

    @Override
    public ResourceLocation typeId() {
        return ResourceTypes.ENERGY;
    }

    @Override
    public ExtractionScope extractionScope() {
        return ExtractionScope.HANDLER;
    }

    @Override
    public int sourceViews(TransferWorkBudget budget) {
        return 1;
    }

    @Override
    public int targetViews(TransferWorkBudget budget) {
        return 1;
    }

    @Override
    public Optional<ResourceAmount> peek(int sourceView, TransferWorkBudget budget) {
        validateView(sourceView);
        int stored;
        budget.beforeCall();
        try {
            stored = handler.getEnergyStored();
        } finally {
            budget.afterCall();
        }
        if (stored < 0) throw new IllegalArgumentException("Negative native energy balance");
        return stored == 0 ? Optional.empty() : Optional.of(new ResourceAmount(EnergyVariant.INSTANCE, stored));
    }

    @Override
    public int extract(
            int sourceView, ResourceVariant variant, int amount, boolean simulate, TransferWorkBudget budget) {
        validateView(sourceView);
        validateRequest(variant, amount);
        int extracted;
        budget.beforeCall();
        try {
            extracted = handler.extractEnergy(amount, simulate);
        } finally {
            budget.afterCall();
        }
        return validateResult(extracted, amount);
    }

    @Override
    public int insert(
            int targetView, ResourceVariant variant, int amount, boolean simulate, TransferWorkBudget budget) {
        validateView(targetView);
        validateRequest(variant, amount);
        int accepted;
        budget.beforeCall();
        try {
            accepted = handler.receiveEnergy(amount, simulate);
        } finally {
            budget.afterCall();
        }
        return validateResult(accepted, amount);
    }

    private static void validateView(int view) {
        if (view != 0) throw new IllegalArgumentException("Energy view must be zero");
    }

    private static void validateRequest(ResourceVariant variant, int amount) {
        if (variant != EnergyVariant.INSTANCE || amount <= 0)
            throw new IllegalArgumentException("Expected energy and positive amount");
    }

    private static int validateResult(int result, int amount) {
        if (result < 0 || result > amount) throw new IllegalArgumentException("Invalid native energy result");
        return result;
    }
}
