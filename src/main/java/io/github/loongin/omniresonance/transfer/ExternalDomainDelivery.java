// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import io.github.loongin.omniresonance.filter.FilterMode;
import io.github.loongin.omniresonance.filter.ResourceFilterCache;
import io.github.loongin.omniresonance.storage.DomainLedger;
import java.util.Objects;
import java.util.function.LongSupplier;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;

/** Server-thread delivery transaction for one published input lease. Borrows the runtime-owned filter cache;
 * never activates storage, advances compilation, retains candidates, or schedules retries. Publication retirement
 * owns its lifetime. The runtime supplies fresh authority, settings and the already activated ledger. */
abstract class ExternalDomainDelivery implements ExternalDomainInput {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(ExternalDomainDelivery.class);
    private final ResourceFilterCache filters;
    private final @Nullable ResourceFilterCache.Key filter;
    private final FilterMode mode;
    private final LongSupplier clock;
    private boolean failed;

    ExternalDomainDelivery(
            ResourceFilterCache filters,
            @Nullable ResourceFilterCache.Key filter,
            FilterMode mode,
            LongSupplier clock) {
        this.filters = filters;
        this.filter = filter;
        this.mode = mode;
        this.clock = clock;
    }

    protected abstract void requireServerThread();

    protected abstract DomainLedger activatedLedger();

    protected abstract long variantLimit();

    protected abstract long matchingBudgetNanos();

    protected abstract void moved(ResourceVariant variant, long accepted);

    protected final boolean failed() {
        return failed;
    }

    @Override
    public final long insert(Direction side, ResourceVariant variant, long maximum, boolean simulate) {
        requireServerThread();
        if (maximum <= 0 || failed || !available(side, variant.key().typeId())) return 0;
        Object token = filter == null ? null : filters.token(filter);
        if (!matches(variant)) return 0;
        if (!available(side, variant.key().typeId()) || !sameFilter(token)) return 0;
        DomainLedger ledger = activatedLedger();
        long limit = variantLimit();
        long accepted = Math.min(maximum, ledger.insertCapacity(variant.key(), limit));
        if (simulate || accepted == 0) return accepted;
        try (var deposit = ledger.reserveDeposit(variant.key(), accepted, limit).orElse(null)) {
            if (deposit == null || !available(side, variant.key().typeId()) || !sameFilter(token)) return 0;
            deposit.commit(accepted);
        } catch (RuntimeException failure) {
            failed = true;
            ledger.invalidate();
            LOGGER.error("External domain insertion failed; outcome must not be inferred or retried", failure);
            throw failure;
        }
        moved(variant, accepted);
        return accepted;
    }

    private boolean sameFilter(@Nullable Object token) {
        return filter == null || Objects.equals(token, filters.token(filter));
    }

    private boolean matches(ResourceVariant variant) {
        if (filter == null) return true;
        var compiled = filters.view(filter).compiled();
        if (compiled == null) return false;
        Boolean allowed = compiled.fastDecision(variant.key().typeId(), mode);
        if (allowed != null) return allowed;
        long start = clock.getAsLong();
        long duration = matchingBudgetNanos();
        var evaluation = compiled.evaluate(compiled.prepareCandidate(variant), mode);
        int remaining = 4096;
        while (!evaluation.done() && remaining > 0 && clock.getAsLong() - start < duration) {
            remaining -= evaluation.step(Math.min(256, remaining));
        }
        return evaluation.done() && clock.getAsLong() - start < duration && evaluation.allowed();
    }
}
