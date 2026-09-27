// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.compat.souls;

import com.buuz135.industrialforegoingsouls.capabilities.ISoulHandler;
import io.github.loongin.omniresonance.transfer.ResourceAmount;
import io.github.loongin.omniresonance.transfer.ResourcePort;
import io.github.loongin.omniresonance.transfer.ResourceVariant;
import io.github.loongin.omniresonance.transfer.TransferWorkBudget;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/** Server-thread soul capability boundary. One counted native call per operation; int requests are bounded once.
 * Handler-wide fill/drain preserve native distribution. Simulations do not modify resources; failures propagate. */
public final class SoulResourcePort implements ResourcePort {
    private final ISoulHandler handler;
    private int views = -1;

    public SoulResourcePort(ISoulHandler handler) {
        this.handler = java.util.Objects.requireNonNull(handler);
    }

    public ResourceLocation typeId() {
        return SoulVariant.TYPE;
    }

    public ExtractionScope extractionScope() {
        return ExtractionScope.HANDLER;
    }

    public int sourceViews(TransferWorkBudget budget) {
        budget.beforeCall();
        try {
            views = handler.getSoulTanks();
        } finally {
            budget.afterCall();
        }
        if (views < 0) throw new IllegalArgumentException("Negative soul tank count");
        return views;
    }

    public int targetViews(TransferWorkBudget budget) {
        return 1;
    }

    public Optional<ResourceAmount> peek(int view, TransferWorkBudget budget) {
        if (view < 0 || view >= views) throw new IllegalArgumentException("Soul view outside prepared bounds");
        int amount;
        budget.beforeCall();
        try {
            amount = handler.getSoulInTank(view);
        } finally {
            budget.afterCall();
        }
        if (amount < 0) throw new IllegalArgumentException("Negative soul balance");
        return amount == 0 ? Optional.empty() : Optional.of(new ResourceAmount(SoulVariant.INSTANCE, amount));
    }

    public long extract(int view, ResourceVariant variant, long maximum, boolean simulate, TransferWorkBudget budget) {
        if (view < 0 || view >= views) throw new IllegalArgumentException("Soul view outside prepared bounds");
        return move(variant, maximum, simulate, budget, false);
    }

    public long insert(int view, ResourceVariant variant, long maximum, boolean simulate, TransferWorkBudget budget) {
        if (view != 0) throw new IllegalArgumentException("Soul target view must be zero");
        return move(variant, maximum, simulate, budget, true);
    }

    private long move(
            ResourceVariant variant, long maximum, boolean simulate, TransferWorkBudget budget, boolean insert) {
        int amount = ResourcePort.intRequest(maximum);
        if (amount <= 0 || variant != SoulVariant.INSTANCE)
            throw new IllegalArgumentException("Expected positive soul request");
        var action = simulate ? ISoulHandler.Action.SIMULATE : ISoulHandler.Action.EXECUTE;
        int moved;
        budget.beforeCall();
        try {
            moved = insert ? handler.fill(amount, action) : handler.drain(amount, action);
        } finally {
            budget.afterCall();
        }
        if (moved < 0 || moved > amount) throw new IllegalArgumentException("Invalid native soul transfer result");
        return moved;
    }
}
