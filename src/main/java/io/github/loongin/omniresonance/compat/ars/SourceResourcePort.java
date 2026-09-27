// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.compat.ars;

import com.hollingsworth.arsnouveau.api.source.ISourceCap;
import io.github.loongin.omniresonance.transfer.ResourceAmount;
import io.github.loongin.omniresonance.transfer.ResourcePort;
import io.github.loongin.omniresonance.transfer.ResourceVariant;
import io.github.loongin.omniresonance.transfer.TransferWorkBudget;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/** Server-thread-owned Source boundary. One balance view; only the operation result promises acceptance. */
public final class SourceResourcePort implements ResourcePort {
    private final ISourceCap handler;
    /** Borrows the native handler for the port lifetime, without accessing or modifying it. */
    public SourceResourcePort(ISourceCap handler) {
        this.handler = Objects.requireNonNull(handler);
    }

    @Override
    public ResourceLocation typeId() {
        return SourceVariant.TYPE;
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
            stored = handler.getSource();
        } finally {
            budget.afterCall();
        }
        if (stored < 0) throw new IllegalArgumentException("Negative native Source balance");
        return stored == 0 ? Optional.empty() : Optional.of(new ResourceAmount(SourceVariant.INSTANCE, stored));
    }

    @Override
    public long extract(
            int sourceView, ResourceVariant variant, long maximum, boolean simulate, TransferWorkBudget budget) {
        int amount = ResourcePort.intRequest(maximum);
        validateView(sourceView);
        validateRequest(variant, amount);
        int extracted;
        budget.beforeCall();
        try {
            extracted = handler.extractSource(amount, simulate);
        } finally {
            budget.afterCall();
        }
        return validateResult(extracted, amount);
    }

    @Override
    public long insert(
            int targetView, ResourceVariant variant, long maximum, boolean simulate, TransferWorkBudget budget) {
        int amount = ResourcePort.intRequest(maximum);
        validateView(targetView);
        validateRequest(variant, amount);
        int accepted;
        budget.beforeCall();
        try {
            accepted = handler.receiveSource(amount, simulate);
        } finally {
            budget.afterCall();
        }
        return validateResult(accepted, amount);
    }

    private static void validateView(int view) {
        if (view != 0) throw new IllegalArgumentException("Source view must be zero");
    }

    private static void validateRequest(ResourceVariant variant, int amount) {
        if (variant != SourceVariant.INSTANCE || amount <= 0)
            throw new IllegalArgumentException("Expected Source and positive amount");
    }

    private static int validateResult(int result, int amount) {
        if (result < 0 || result > amount) throw new IllegalArgumentException("Invalid native Source result");
        return result;
    }
}
