// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer.fixtures;

import io.github.loongin.omniresonance.transfer.ResourceAmount;
import io.github.loongin.omniresonance.transfer.ResourcePort;
import io.github.loongin.omniresonance.transfer.ResourceTransferEngine;
import io.github.loongin.omniresonance.transfer.ResourceVariant;
import io.github.loongin.omniresonance.transfer.ResourceVariantKey;
import io.github.loongin.omniresonance.transfer.TransferWorkBudget;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/** Stateful synchronous port fixture. Every native operation counts; mutation faults occur after changing storage.
 * Opaque default keys exercise scheduler identity handling; component-filter tests supply valid native keys. */
public final class SchedulerResourcePort implements ResourcePort, ResourceTransferEngine.Handle {
    public final ResourceLocation type;
    public ResourceVariant variant;
    public java.util.function.IntFunction<ResourceVariant> variantAt = view -> variant;
    public boolean valid = true;
    public final long[] amounts;
    public final List<ResourceLocation> moves;
    public int discoveries, calls;
    public boolean throwInsert, throwExtract, throwReturn, rejectInsert;
    public long insertionLimit = Integer.MAX_VALUE;
    public long viewCapacity = Integer.MAX_VALUE;
    public Runnable onSimulate = () -> {};
    public Runnable onCall = () -> {};

    public SchedulerResourcePort(ResourceLocation type, int size, long amount, List<ResourceLocation> moves) {
        this.type = type;
        this.moves = moves;
        amounts = new long[size];
        if (size > 0) amounts[0] = amount;
        ResourceVariantKey key = new ResourceVariantKey(type, new byte[0]);
        variant = () -> key;
    }

    void count(TransferWorkBudget b) {
        calls++;
        b.beforeCall();
        onCall.run();
        b.afterCall();
    }

    public ResourceLocation typeId() {
        return type;
    }

    public ExtractionScope extractionScope() {
        return ExtractionScope.VIEW;
    }

    public int sourceViews(TransferWorkBudget b) {
        count(b);
        return amounts.length;
    }

    public int targetViews(TransferWorkBudget b) {
        count(b);
        return amounts.length;
    }

    public Optional<ResourceAmount> peek(int view, TransferWorkBudget b) {
        count(b);
        return amounts[view] == 0
                ? Optional.empty()
                : Optional.of(new ResourceAmount(variantAt.apply(view), amounts[view]));
    }

    public long extract(int view, ResourceVariant v, long amount, boolean simulate, TransferWorkBudget b) {
        count(b);
        long n = v.key().equals(variantAt.apply(view).key()) ? Math.min(amounts[view], amount) : 0;
        if (!simulate) {
            amounts[view] -= n;
            if (throwExtract) throw new IllegalStateException("unknown extraction");
        }
        return n;
    }

    public long insert(int view, ResourceVariant v, long amount, boolean simulate, TransferWorkBudget b) {
        if (amount <= 0) throw new IllegalArgumentException("Request must be positive");
        count(b);
        if (simulate) onSimulate.run();
        long n = Math.min(
                Math.min(amount, Math.max(0, viewCapacity - amounts[view])),
                simulate ? Long.MAX_VALUE : insertionLimit);
        if (!simulate) {
            if (rejectInsert) return 0;
            amounts[view] += n;
            moves.add(type);
            if (throwInsert || throwReturn) throw new IllegalStateException("unknown insertion");
        }
        return n;
    }

    public ResourcePort port() {
        return this;
    }

    public Object physicalIdentity() {
        return this;
    }

    public boolean valid() {
        return valid;
    }
}
