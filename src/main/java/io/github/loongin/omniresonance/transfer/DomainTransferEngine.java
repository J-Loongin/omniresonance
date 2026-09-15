// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import io.github.loongin.omniresonance.config.ServerSettings;
import io.github.loongin.omniresonance.recovery.RecoveryBuffer;
import io.github.loongin.omniresonance.storage.DomainLedger;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Server-thread synchronous domain commits using the common resource engine's simulation, exact-batch and
 * failure semantics. Callers own filtering, source keep preflight for greedy work, allowances and scheduling.
 * An invocation borrows an activated original ledger and captures one variant; no port or reservation survives
 * the call. Ledger operations make no native calls and never increment the external-call budget.
 */
public final class DomainTransferEngine {
    private final ResourceTransferEngine engine = new ResourceTransferEngine();

    /** Deposits one selected source view after common simulations, reserving ledger capacity before extraction. */
    public ResourceTransferEngine.Result depositGreedy(
            ResourceTransferEngine.Handle source,
            int sourceView,
            DomainLedger ledger,
            ResourceVariant variant,
            long amount,
            long variantLimit,
            BooleanSupplier valid,
            RecoveryBuffer recovery,
            ServerSettings.RecoveryLimits limits,
            TransferWorkBudget budget) {
        try (LedgerPort target = new LedgerPort(ledger, variant, variantLimit, valid, true)) {
            return engine.commitGreedy(source, sourceView, target, 0, variant, amount, recovery, limits, budget);
        }
    }

    /** Revalidates the whole source and keep count in this tick, then commits only complete budgeted batches. */
    public ResourceTransferEngine.Result depositExact(
            ResourceTransferEngine.Handle source,
            int sourceStartView,
            DomainLedger ledger,
            ResourceVariant variant,
            long amount,
            long keepCount,
            long batchSize,
            long variantLimit,
            BooleanSupplier valid,
            RecoveryBuffer recovery,
            ServerSettings.RecoveryLimits limits,
            TransferWorkBudget budget) {
        try (LedgerPort target = new LedgerPort(ledger, variant, variantLimit, valid, true)) {
            return engine.commitExact(
                    engine.prepareExact(source, target, variant, sourceStartView, 0),
                    amount,
                    amount,
                    keepCount,
                    batchSize,
                    recovery,
                    limits,
                    budget);
        }
    }

    /** Removes only after target simulation; known returns use pinned original capacity, never new-key admission. */
    public ResourceTransferEngine.Result withdrawGreedy(
            DomainLedger ledger,
            ResourceTransferEngine.Handle target,
            int targetView,
            ResourceVariant variant,
            long amount,
            BooleanSupplier valid,
            RecoveryBuffer recovery,
            ServerSettings.RecoveryLimits limits,
            TransferWorkBudget budget) {
        try (LedgerPort source = new LedgerPort(ledger, variant, -1, valid, false)) {
            return engine.commitGreedy(source, 0, target, targetView, variant, amount, recovery, limits, budget);
        }
    }

    /** One-call ledger boundary. Every failure precedes quantity mutation; native invocation markers stay untouched. */
    private static final class LedgerPort implements ResourcePort, ResourceTransferEngine.Handle, AutoCloseable {
        private final DomainLedger ledger;
        private final ResourceVariant variant;
        private final long variantLimit;
        private final BooleanSupplier current;
        private final boolean depositMode;
        private @Nullable DomainLedger.Deposit deposit;
        private @Nullable DomainLedger.Withdrawal withdrawal;
        private boolean closed;

        private LedgerPort(
                DomainLedger ledger,
                ResourceVariant variant,
                long variantLimit,
                BooleanSupplier current,
                boolean depositMode) {
            this.ledger = Objects.requireNonNull(ledger);
            this.variant = Objects.requireNonNull(variant);
            this.current = Objects.requireNonNull(current);
            if (variantLimit < -1) throw new IllegalArgumentException("Invalid domain variant limit");
            this.variantLimit = variantLimit;
            this.depositMode = depositMode;
        }

        public ResourcePort port() {
            return this;
        }

        public boolean usesNativeCalls() {
            return false;
        }

        public Object physicalIdentity() {
            return ledger;
        }

        public boolean valid() {
            return !closed && ledger.isAvailable() && current.getAsBoolean();
        }

        public ResourceTransferEngine.Handle remainderTarget() {
            return new ResourceTransferEngine.Handle() {
                public ResourcePort port() {
                    return LedgerPort.this;
                }

                public Object physicalIdentity() {
                    return ledger;
                }

                public boolean valid() {
                    return !closed && ledger.isAvailable();
                }
            };
        }

        public ResourceLocation typeId() {
            return variant.key().typeId();
        }

        public ExtractionScope extractionScope() {
            return ExtractionScope.HANDLER;
        }

        public int sourceViews(TransferWorkBudget budget) {
            return 1;
        }

        public int targetViews(TransferWorkBudget budget) {
            return 1;
        }

        public Optional<ResourceAmount> peek(int view, TransferWorkBudget budget) {
            validate(view, variant, 1);
            long amount = ledger.amount(variant.key());
            return amount == 0 ? Optional.empty() : Optional.of(new ResourceAmount(variant, amount));
        }

        public int extract(
                int view, ResourceVariant requestedVariant, int amount, boolean simulate, TransferWorkBudget budget) {
            validate(view, requestedVariant, amount);
            if (depositMode || withdrawal != null) throw new IllegalStateException("Invalid domain extraction phase");
            int accepted = (int) Math.min(amount, ledger.amount(variant.key()));
            if (simulate || accepted == 0) return accepted;
            withdrawal = ledger.withdraw(variant.key(), accepted).orElseThrow();
            return accepted;
        }

        public int insert(
                int view, ResourceVariant requestedVariant, int amount, boolean simulate, TransferWorkBudget budget) {
            if (!depositMode && !simulate && withdrawal != null) {
                if (closed || !ledger.isAvailable()) throw new IllegalStateException("Unavailable original domain");
                validateRequest(view, requestedVariant, amount);
            } else validate(view, requestedVariant, amount);
            if (depositMode) {
                if (simulate) return (int) Math.min(amount, ledger.insertCapacity(variant.key(), variantLimit));
                if (deposit == null) throw new IllegalStateException("Missing domain insertion reservation");
                deposit.commit(amount);
                return amount;
            }
            if (simulate || withdrawal == null) throw new IllegalStateException("Invalid domain return phase");
            withdrawal.returnRemainder(amount);
            return amount;
        }

        public boolean reserveInsertion(ResourceVariant requestedVariant, int amount) {
            validate(0, requestedVariant, amount);
            if (!depositMode || deposit != null) throw new IllegalStateException("Invalid domain reservation phase");
            deposit = ledger.reserveDeposit(variant.key(), amount, variantLimit).orElse(null);
            return deposit != null;
        }

        public void releaseInsertion() {
            if (deposit != null) deposit.close();
        }

        private void validate(int view, ResourceVariant requestedVariant, int amount) {
            if (!valid()) throw new IllegalStateException("Stale domain resource access");
            validateRequest(view, requestedVariant, amount);
        }

        private void validateRequest(int view, ResourceVariant requestedVariant, int amount) {
            if (view != 0 || amount <= 0 || !variant.key().equals(requestedVariant.key()))
                throw new IllegalArgumentException("Invalid domain resource request");
        }

        public void close() {
            if (closed) return;
            releaseInsertion();
            if (withdrawal != null) withdrawal.close();
            closed = true;
        }
    }
}
