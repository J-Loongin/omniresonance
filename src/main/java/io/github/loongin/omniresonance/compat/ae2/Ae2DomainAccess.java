// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.compat.ae2;

import io.github.loongin.omniresonance.storage.DomainLedger;
import io.github.loongin.omniresonance.transfer.ResourceVariantKey;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

/**
 * Server-thread direct ledger operations for an interface. The supplier must only return the already activated,
 * currently authorized ledger after checking physical identity, binding, ownership, grid activity and uniqueness;
 * it must never activate storage. Simulation performs pure reads only. Actual quantities use one ledger operation,
 * independent of stack/amount size. Unexpected mutation failure isolates the ledger, retains one internal failure
 * and propagates: returning a guessed accepted quantity could duplicate resources in the caller.
 */
public final class Ae2DomainAccess {
    private final Thread owner = Thread.currentThread();
    private final Supplier<@Nullable DomainLedger> authorized;
    private final LongSupplier variantLimit;
    private @Nullable RuntimeException failure;

    public Ae2DomainAccess(Supplier<@Nullable DomainLedger> authorized, LongSupplier variantLimit) {
        this.authorized = Objects.requireNonNull(authorized);
        this.variantLimit = Objects.requireNonNull(variantLimit);
    }

    public long insert(ResourceVariantKey key, long maximum, boolean simulate) {
        check(key, maximum);
        if (maximum == 0) return 0;
        var ledger = current();
        if (ledger == null) return 0;
        long limit = variantLimit.getAsLong();
        long accepted = Math.min(maximum, ledger.insertCapacity(key, limit));
        if (accepted == 0 || simulate) return accepted;
        if (current() != ledger) return 0;
        try (var deposit = ledger.reserveDeposit(key, accepted, limit).orElse(null)) {
            if (deposit == null || current() != ledger) return 0;
            deposit.commit(accepted);
            return accepted;
        } catch (RuntimeException problem) {
            ledger.invalidate();
            failure = problem;
            throw new IllegalStateException(
                    "Domain interface insertion failed; do not infer or retry the outcome", problem);
        }
    }

    public long extract(ResourceVariantKey key, long maximum, boolean simulate) {
        check(key, maximum);
        if (maximum == 0) return 0;
        var ledger = current();
        if (ledger == null) return 0;
        long accepted = Math.min(maximum, ledger.amount(key));
        if (accepted == 0 || simulate) return accepted;
        if (current() != ledger) return 0;
        try (var withdrawal = ledger.withdraw(key, accepted).orElse(null)) {
            return withdrawal == null ? 0 : accepted;
        } catch (RuntimeException problem) {
            ledger.invalidate();
            failure = problem;
            throw new IllegalStateException(
                    "Domain interface extraction failed; do not infer or retry the outcome", problem);
        }
    }
    /** Pure eligibility read for inventory enumeration; never advances preparation or loads a ledger. */
    public @Nullable DomainLedger current() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Interface access off owner thread");
        if (failure != null) return null;
        var ledger = authorized.get();
        return ledger != null && ledger.isAvailable() ? ledger : null;
    }

    public @Nullable RuntimeException failure() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Interface failure read off owner thread");
        return failure;
    }

    private void check(ResourceVariantKey key, long amount) {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Interface access off owner thread");
        Objects.requireNonNull(key);
        if (amount < 0) throw new IllegalArgumentException("Negative interface quantity");
    }
}
