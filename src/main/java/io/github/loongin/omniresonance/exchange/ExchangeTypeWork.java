// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.exchange;

import io.github.loongin.omniresonance.filter.ResourceFilterCompiler;
import io.github.loongin.omniresonance.storage.DomainLedger;
import io.github.loongin.omniresonance.transfer.DomainTransferWindow;
import io.github.loongin.omniresonance.transfer.ResourceTransferPolicy;
import io.github.loongin.omniresonance.transfer.ResourceVariant;
import io.github.loongin.omniresonance.transfer.ResourceVariantKey;
import io.github.loongin.omniresonance.transfer.TransferWorkBudget;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * One server-thread protocol/type work cursor. Keeps one candidate, one incremental filter evaluation and finite
 * inventory scan fences; no per-inventory cache or quantities are persisted. The caller retains its quota window
 * across publication replacements and schedules different types fairly. No world scan, capability call, player
 * online requirement or forced I/O occurs. Exceptions stop this cursor with retained evidence, never auto-retry.
 */
public final class ExchangeTypeWork implements AutoCloseable {
    public interface Environment {
        /** Checks exact current agreement revision, both owners and pause state; constant work without mutation. */
        boolean active(ExchangeAgreement agreement);
        /** Returns an already activated ledger for exactly the source identity, or null. No I/O or activation here. */
        @Nullable
        DomainLedger source(ExchangeAgreement agreement);
        /** Returns an already activated ledger for exactly the target identity, or null. No I/O or activation here. */
        @Nullable
        DomainLedger target(ExchangeAgreement agreement);
        /** Bounded pure decoding for registered types; null preserves opaque inventory without moving it. */
        @Nullable
        ResourceVariant decode(ResourceVariantKey key);
    }

    public enum Status {
        WAITING_BUDGET,
        WAITING_INTERVAL,
        BLOCKED,
        ROUND_FINISHED,
        FAILED
    }

    public record Progress(int used, long moved, Status status) {}

    private ExchangeTelemetry.Stage stage = ExchangeTelemetry.Stage.FILTER;

    ExchangeTelemetry.Stage failureStage() {
        return stage;
    }

    ResourceLocation resourceType() {
        return type;
    }

    private final Thread owner = Thread.currentThread();
    private final ExchangeAgreement agreement;
    private final long rate, batchSize;
    private final ResourceLocation type;
    private final DomainTransferWindow window;
    private final Environment environment;
    private final ExchangeFilterWork filter;
    private final boolean ownsFilter;
    private @Nullable Object filterToken;
    private @Nullable ResourceFilterCompiler.Compiled compiled;
    private @Nullable ResourceFilterCompiler.Evaluation evaluation;
    private @Nullable DomainLedger source, target;
    private @Nullable DomainLedger.Cursor candidate;
    private @Nullable ResourceVariant decoded;
    private @Nullable Boolean allowed;
    private @Nullable RuntimeException failure;
    private long after, ceiling, fence;
    private boolean round, wrapped, busy, closed;

    /** Publication-time setup; snapshots are detached off the hot path. Tags must be immutable bounded lookups. */
    public ExchangeTypeWork(
            ExchangeAgreement agreement,
            ResourceLocation type,
            DomainTransferWindow window,
            Environment environment,
            ResourceFilterCompiler.Tags tags) {
        this(agreement, type, window, environment, ExchangeFilterWork.prepared(agreement, tags), true);
    }

    /** Uses a publication-owned shared filter; closing this type does not close other types' filter preparation. */
    public ExchangeTypeWork(
            ExchangeAgreement agreement,
            ResourceLocation type,
            DomainTransferWindow window,
            Environment environment,
            ExchangeFilterWork filter) {
        this(agreement, type, window, environment, filter, false);
    }

    private ExchangeTypeWork(
            ExchangeAgreement agreement,
            ResourceLocation type,
            DomainTransferWindow window,
            Environment environment,
            ExchangeFilterWork filter,
            boolean ownsFilter) {
        this.agreement = Objects.requireNonNull(agreement);
        this.type = Objects.requireNonNull(type);
        var parameter = agreement.terms().parameter(type);
        rate = parameter.rate();
        batchSize = parameter.batchMode() == ResourceTransferPolicy.BatchMode.EXACT ? parameter.batchSize() : 1;
        this.window = Objects.requireNonNull(window);
        this.environment = Objects.requireNonNull(environment);
        this.filter = Objects.requireNonNull(filter);
        this.ownsFilter = ownsFilter;
    }

    /** Replaces derived tag evidence outside dispatch; restarts matching without restoring spent rate allowance. */
    public void invalidateTags(ResourceFilterCompiler.Tags tags) {
        checkThread();
        if (busy) throw new IllegalStateException("Cannot invalidate running exchange work");
        if (!ownsFilter) throw new IllegalStateException("Shared filter invalidation belongs to its publication");
        filter.replaceTags(tags);
        filterToken = null;
        compiled = null;
        resetScan();
    }

    /**
     * Runs at most workUnits microsteps under the shared soft CPU clock; native-call counts are untouched because
     * ledger exchange calls no capabilities. Decode/sample preparation each uses one admitted bounded operation,
     * then time is checked again. No reservation survives a return. Zero budget does not advance any cursor.
     */
    public Progress step(long tick, int workUnits, TransferWorkBudget budget, long variantLimit) {
        checkThread();
        Objects.requireNonNull(budget);
        if (tick < 0 || workUnits < 0 || variantLimit < -1)
            throw new IllegalArgumentException("Invalid exchange work budget");
        Math.addExact(tick, agreement.terms().intervalTicks());
        if (busy) throw new IllegalStateException("Reentrant exchange dispatch");
        if (failure != null) return new Progress(0, 0, Status.FAILED);
        int used = 0;
        long moved = 0;
        busy = true;
        try {
            while (used < workUnits && budget.canFit(0)) {
                stage = ExchangeTelemetry.Stage.VALIDATION;
                if (ExchangeFilterStatus.of(agreement.terms(), null).blocked()
                        || !environment.active(agreement)
                        || !agreement.terms().scope().includes(type)) return new Progress(used, moved, Status.BLOCKED);
                if (tick < window.nextRunTick()) return new Progress(used, moved, Status.WAITING_INTERVAL);
                used++;
                stage = ExchangeTelemetry.Stage.FILTER;
                var view = filter.view();
                if (filterToken != view.token()) {
                    filterToken = view.token();
                    resetScan();
                }
                compiled = view.compiled();
                if (compiled == null) {
                    filter.advance(1);
                    continue;
                }
                if (ExchangeFilterStatus.of(agreement.terms(), compiled).blocked())
                    return new Progress(used, moved, Status.BLOCKED);
                if (Boolean.FALSE.equals(
                        compiled.fastDecision(type, agreement.terms().filterMode()))) return finish(tick, used, moved);
                stage = ExchangeTelemetry.Stage.SOURCE_STORAGE;
                DomainLedger currentSource = environment.source(agreement);
                stage = ExchangeTelemetry.Stage.TARGET_STORAGE;
                DomainLedger currentTarget = environment.target(agreement);
                if (currentSource == null
                        || currentTarget == null
                        || !currentSource.isAvailable()
                        || !currentTarget.isAvailable()) return new Progress(used, moved, Status.BLOCKED);
                if (!currentSource.networkId().equals(agreement.consent().sourceNetwork())
                        || !currentTarget.networkId().equals(agreement.consent().targetNetwork()))
                    return new Progress(used, moved, Status.BLOCKED);
                if (source != currentSource || target != currentTarget) {
                    source = currentSource;
                    target = currentTarget;
                    resetScan();
                }
                if (window.available(tick, rate) == 0) return finish(tick, used, moved);
                if (!round) {
                    ceiling = source.sequenceCeiling();
                    fence = Math.min(after, ceiling);
                    wrapped = false;
                    round = true;
                    continue;
                }
                if (candidate == null) {
                    var next = source.nextWithin(after, wrapped ? fence : ceiling);
                    if (next.isEmpty()) {
                        if (!wrapped && fence > 0) {
                            wrapped = true;
                            after = 0;
                            continue;
                        }
                        return finish(tick, used, moved);
                    }
                    after = next.orElseThrow().sequence();
                    if (next.orElseThrow().key().typeId().equals(type)) candidate = next.orElseThrow();
                    continue;
                }
                if (decoded == null) {
                    Boolean fast = compiled.fastDecision(type, agreement.terms().filterMode());
                    if (Boolean.FALSE.equals(fast)) {
                        clearCandidate();
                        continue;
                    }
                    stage = ExchangeTelemetry.Stage.DECODE;
                    decoded = environment.decode(candidate.key());
                    if (decoded == null || !candidate.key().equals(decoded.key())) {
                        clearCandidate();
                        continue;
                    }
                    allowed = fast;
                    continue;
                }
                if (allowed == null) {
                    stage = ExchangeTelemetry.Stage.FILTER;
                    if (evaluation == null)
                        evaluation = compiled.evaluate(
                                compiled.prepareCandidate(decoded),
                                agreement.terms().filterMode());
                    else {
                        evaluation.step(1);
                        if (evaluation.done()) allowed = evaluation.allowed();
                    }
                    continue;
                }
                if (allowed) {
                    if (!budget.canFit(0)) return new Progress(used, moved, Status.WAITING_BUDGET);
                    stage = ExchangeTelemetry.Stage.VALIDATION;
                    if (!environment.active(agreement)) return new Progress(used, moved, Status.BLOCKED);
                    stage = ExchangeTelemetry.Stage.TRANSFER;
                    long transferred = source.transferTo(
                            target, candidate.key(), window.available(tick, rate), variantLimit, batchSize);
                    if (transferred > 0) {
                        window.moved(tick, transferred, rate);
                        moved = Math.addExact(moved, transferred);
                    }
                }
                clearCandidate();
            }
            return new Progress(used, moved, Status.WAITING_BUDGET);
        } catch (RuntimeException problem) {
            failure = problem;
            return new Progress(used, moved, Status.FAILED);
        } finally {
            busy = false;
        }
    }

    /** Retained internal failure evidence; not player text and never an instruction to retry the mutation. */
    public @Nullable RuntimeException failure() {
        checkThread();
        return failure;
    }

    private Progress finish(long tick, int used, long moved) {
        window.finish(tick, agreement.terms().intervalTicks());
        round = false;
        clearCandidate();
        return new Progress(used, moved, Status.ROUND_FINISHED);
    }

    private void clearCandidate() {
        candidate = null;
        decoded = null;
        evaluation = null;
        allowed = null;
    }

    private void resetScan() {
        after = 0;
        round = false;
        clearCandidate();
    }

    private void checkThread() {
        if (Thread.currentThread() != owner || closed)
            throw new IllegalStateException("Closed or off-thread exchange work");
    }

    @Override
    public void close() {
        checkThread();
        if (busy) throw new IllegalStateException("Cannot close running exchange work");
        closed = true;
        compiled = null;
        if (ownsFilter) filter.close();
        filterToken = null;
        source = null;
        target = null;
        clearCandidate();
    }
}
