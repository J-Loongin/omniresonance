// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import io.github.loongin.omniresonance.config.ServerSettings;
import io.github.loongin.omniresonance.recovery.RecoveryBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * Server-thread-only synchronous resource commits. No resource or simulation promise survives a call.
 * Greedy commits use one selected source/target view; ordinary ports use at most ten native calls, while carrier settlement uses the declared dynamic bound. Exact preparation uses
 * the caller's current tick budget, then admits the entire modification and worst-case return segment.
 * Known leftovers are disposed before returning; unknown external modifications are never retried.
 * This internal engine neither discovers capabilities nor owns scheduling allowances or receive windows.
 */
public final class ResourceTransferEngine {
    public static final int MAXIMUM_GREEDY_CALLS = 10;

    /**
     * Borrowed server-thread endpoint. Port and physical identity are stable for this handle's lifetime.
     * valid() is a nonmutating, non-discovering constant-cost check of the captured endpoint and permission
     * revisions; callers must invalidate stale filters/permissions through this check before committing.
     */
    public interface Handle {
        ResourcePort port();

        Object physicalIdentity();

        boolean valid();

        /** Returns the same owned port and identity for known remainders. Native ports keep normal validity;
         * internal ledgers can restore withdrawn ownership after authorization for new work has ended. */
        default Handle remainderTarget() {
            return this;
        }
    }

    public enum Failure {
        NONE,
        REFUSED,
        INVALID_ENDPOINT,
        RECOVERY_FULL,
        INCONSISTENT,
        EXCEPTION,
        UNKNOWN_MUTATION,
        WAITING_BUDGET
    }

    public enum Stage {
        NONE,
        SOURCE_EXTRACT,
        TARGET_INSERT,
        SOURCE_RETURN
    }

    /**
     * Immutable known evidence. extracted excludes unknown extraction results; moved/returned contain only
     * validated acceptances. For known outcomes extracted = moved + returned + buffered + stored. Unknown
     * requested quantity is uncertain, never an invented loss or compensation. completeBatch is true only
     * for an exact commit that fully extracted and inserted its rounded request. Caller debits output by
     * moved and must stop the current transaction on UNKNOWN_MUTATION. Proven pre-invocation request failures
     * are EXCEPTION with Stage.NONE and retain all known offered resources for bounded cleanup. No authority
     * is modified by accessors.
     */
    public record Result(
            long extracted,
            long moved,
            long returned,
            long buffered,
            long stored,
            Failure failure,
            boolean completeBatch,
            Stage unknownStage,
            int unknownRequested,
            @Nullable RuntimeException cause) {
        /**
         * Known extracted minus known returned; this is not authoritative net removal after an unknown
         * extraction or return. Unknown target insertion must not fabricate a receiving-window debit.
         */
        public long removed() {
            return extracted - returned;
        }
    }

    /**
     * Server-thread-owned discovery hints, safe to retain across ticks. Holds no quantities, reservations,
     * snapshots, or promises. Suffix positions are hints, not permissions; commit rechecks current bounds,
     * candidates and simulations. The scheduler owns fair discovery/resume positions outside this engine.
     * Nonzero keepCount always counts the entire source regardless of the extraction suffix.
     */
    public static final class ExactCandidate {
        private final Handle source, target;
        private final ResourceVariant variant;
        private final int sourceStartView, targetStartView;
        private final @Nullable int[] sourceHints, targetHints;
        private final Set<Integer> sourceSelection;

        private ExactCandidate(
                Handle source,
                Handle target,
                ResourceVariant variant,
                int sourceStartView,
                int targetStartView,
                @Nullable int[] sourceHints,
                @Nullable int[] targetHints) {
            this.source = Objects.requireNonNull(source);
            this.target = Objects.requireNonNull(target);
            this.variant = Objects.requireNonNull(variant);
            if (sourceStartView < 0 || targetStartView < 0) throw new IllegalArgumentException("Negative view hint");
            this.sourceStartView = sourceStartView;
            this.targetStartView = targetStartView;
            sourceSelection = new HashSet<>();
            this.sourceHints = sourceHints == null ? null : uniqueHints(sourceHints, sourceSelection);
            this.targetHints = targetHints == null ? null : uniqueHints(targetHints, new HashSet<>());
        }

        private boolean selectsSource(int view) {
            return sourceHints == null ? view >= sourceStartView : sourceSelection.contains(view);
        }
    }

    /** Captures candidate endpoints without native access, simulation, reservation, or authority mutation. */
    public ExactCandidate prepareExact(Handle source, Handle target, ResourceVariant variant) {
        return prepareExact(source, target, variant, 0, 0);
    }

    /** Captures already discovered suffix positions; all execution promises are rebuilt in commitExact. */
    public ExactCandidate prepareExact(
            Handle source, Handle target, ResourceVariant variant, int sourceStartView, int targetStartView) {
        return new ExactCandidate(source, target, variant, sourceStartView, targetStartView, null, null);
    }

    /**
     * Captures detached, deduplicated explicit view hints from scheduler discovery. Total input hint count
     * must fit the remaining native-call budget before any copy/allocation; this is a work bound, not a
     * gameplay limit. Negative indices reject. Current view bounds and all promises are checked on commit.
     * Caller may retain the candidate across ticks but must recapture invalidated permissions in its handles.
     */
    public ExactCandidate prepareExact(
            Handle source,
            Handle target,
            ResourceVariant variant,
            int[] sourceViews,
            int[] targetViews,
            TransferWorkBudget budget) {
        if ((long) sourceViews.length + targetViews.length > budget.remainingCalls())
            throw new IllegalArgumentException("View hints exceed remaining preparation budget");
        return new ExactCandidate(source, target, variant, 0, 0, sourceViews, targetViews);
    }

    /**
     * One selected-view soft-budget unit. Caller supplies the already keep/filter/allowance-limited amount.
     * Empty allowance and exhausted budget short-circuit. Once admitted, known remainder cleanup may overrun
     * budget, bounded by the declared mutation-call metadata (ten calls for ordinary ports). Simulations do not reserve recovery space or modify authority.
     */
    public Result commitGreedy(
            Handle source,
            int sourceView,
            Handle target,
            int targetView,
            ResourceVariant variant,
            long amount,
            RecoveryBuffer recovery,
            ServerSettings.RecoveryLimits limits,
            TransferWorkBudget budget) {
        return commitGreedy(source, sourceView, target, targetView, variant, amount, recovery, limits, budget, false);
    }

    /** Finishes an already admitted bounded discovery/greedy unit; only package-owned terminal discovery uses this. */
    Result commitGreedy(
            Handle source,
            int sourceView,
            Handle target,
            int targetView,
            ResourceVariant variant,
            long amount,
            RecoveryBuffer recovery,
            ServerSettings.RecoveryLimits limits,
            TransferWorkBudget budget,
            boolean admitted) {
        if (amount <= 0) return empty(Failure.REFUSED);
        if (!admitted && !budget.canStart()) return empty(Failure.WAITING_BUDGET);
        int requested = (int) Math.min(Integer.MAX_VALUE, amount);
        try {
            if (!eligible(source, target, variant)) return empty(Failure.INVALID_ENDPOINT);
            if (!viewCurrent(source, sourceView, true, budget) || !viewCurrent(target, targetView, false, budget))
                return empty(Failure.INVALID_ENDPOINT);
            if (!source.valid()) return empty(Failure.INVALID_ENDPOINT);
            requested = checked(source.port().extract(sourceView, variant, requested, true, budget), requested);
            if (requested == 0) return empty(Failure.REFUSED);
            if (!target.valid()) return empty(Failure.INVALID_ENDPOINT);
            requested = checked(target.port().insert(targetView, variant, requested, true, budget), requested);
            if (requested == 0) return empty(Failure.REFUSED);
        } catch (RuntimeException failure) {
            return exception(failure);
        }
        return execute(
                source,
                target,
                variant,
                List.of(new Step(sourceView, requested)),
                List.of(new Step(targetView, requested)),
                requested,
                false,
                recovery,
                limits,
                budget);
    }

    /**
     * Rebuilds promises and nonzero keep verification synchronously in this tick; paused preparation is
     * discarded. Quantities are nonnegative long and batch is positive, including batch greater than rate.
     * Native aggregate requests are capped at int max before rounding, never truncated by a cast.
     * Plans allocate only after budgeted observations and contain at most one entry per touched view.
     * WAITING_BUDGET always means zero extraction; no recovery reservation exists during simulations.
     */
    public Result commitExact(
            ExactCandidate candidate,
            long sourceAllowance,
            long outputAllowance,
            long keepCount,
            long batchSize,
            RecoveryBuffer recovery,
            ServerSettings.RecoveryLimits limits,
            TransferWorkBudget budget) {
        if (sourceAllowance < 0 || outputAllowance < 0 || keepCount < 0 || batchSize <= 0)
            throw new IllegalArgumentException("Invalid exact quantities");
        long limit = Math.min(Integer.MAX_VALUE, Math.min(sourceAllowance, outputAllowance));
        int requested = (int) rounded(limit, batchSize);
        if (requested == 0) return empty(Failure.REFUSED);
        if (!budget.canStart()) return empty(Failure.WAITING_BUDGET);
        Handle source = candidate.source, target = candidate.target;
        ResourceVariant variant = candidate.variant;
        List<Step> inputs = new ArrayList<>(), outputs = new ArrayList<>();
        try {
            if (!eligible(source, target, variant)) return empty(Failure.INVALID_ENDPOINT);
            int sourceViews = source.port().sourceViews(budget);
            if (candidate.sourceHints == null && candidate.sourceStartView >= sourceViews)
                return empty(Failure.REFUSED);
            boolean handler = source.port().extractionScope() == ResourcePort.ExtractionScope.HANDLER;
            List<Step> observed = new ArrayList<>();
            if (keepCount > 0) {
                long stored = 0;
                for (int view = 0; view < sourceViews; view++) {
                    if (!budget.canStart()) return empty(Failure.WAITING_BUDGET);
                    if (!source.valid()) return empty(Failure.INVALID_ENDPOINT);
                    ResourceAmount item = source.port().peek(view, budget).orElse(null);
                    if (item == null || !item.variant().key().equals(variant.key())) continue;
                    stored = Math.addExact(stored, item.quantity());
                    if (candidate.selectsSource(view) && (!handler || observed.isEmpty()))
                        observed.add(new Step(view, (int) Math.min(Integer.MAX_VALUE, item.quantity())));
                }
                requested = (int) rounded(Math.min(requested, Math.max(0, stored - keepCount)), batchSize);
                if (requested == 0) return empty(Failure.REFUSED);
            }
            int supplied = 0;
            int end = keepCount > 0
                    ? observed.size()
                    : candidate.sourceHints == null ? sourceViews : candidate.sourceHints.length;
            int start = keepCount > 0 || candidate.sourceHints != null ? 0 : candidate.sourceStartView;
            for (int index = start; index < end && supplied < requested; index++) {
                if (!budget.canStart()) return empty(Failure.WAITING_BUDGET);
                if (!source.valid()) return empty(Failure.INVALID_ENDPOINT);
                int view;
                if (keepCount > 0) view = observed.get(index).view();
                else {
                    view = candidate.sourceHints == null ? index : candidate.sourceHints[index];
                    if (view >= sourceViews) return empty(Failure.INVALID_ENDPOINT);
                    ResourceAmount item = source.port().peek(view, budget).orElse(null);
                    if (item == null || !item.variant().key().equals(variant.key())) continue;
                }
                if (!budget.canStart()) return empty(Failure.WAITING_BUDGET);
                if (!source.valid()) return empty(Failure.INVALID_ENDPOINT);
                int request = requested - supplied;
                int extracted = checked(source.port().extract(view, variant, request, true, budget), request);
                if (extracted > 0) {
                    inputs.add(new Step(view, extracted));
                    supplied += extracted;
                }
                if (handler) break;
            }
            requested = (int) rounded(supplied, batchSize);
            if (requested == 0) return empty(Failure.REFUSED);
            if (!budget.canStart()) return empty(Failure.WAITING_BUDGET);
            if (!target.valid()) return empty(Failure.INVALID_ENDPOINT);
            int targetViews = target.port().targetViews(budget);
            int accepted = 0;
            int targetStart = candidate.targetHints == null ? candidate.targetStartView : 0;
            int targetEnd = candidate.targetHints == null ? targetViews : candidate.targetHints.length;
            for (int index = targetStart; index < targetEnd && accepted < requested; index++) {
                int view = candidate.targetHints == null ? index : candidate.targetHints[index];
                if (view >= targetViews) return empty(Failure.INVALID_ENDPOINT);
                if (!budget.canStart()) return empty(Failure.WAITING_BUDGET);
                if (!target.valid()) return empty(Failure.INVALID_ENDPOINT);
                int request = requested - accepted;
                int inserted = checked(target.port().insert(view, variant, request, true, budget), request);
                if (inserted > 0) {
                    outputs.add(new Step(view, inserted));
                    accepted += inserted;
                }
            }
            requested = (int) rounded(accepted, batchSize);
            if (requested == 0) return empty(Failure.REFUSED);
            trim(inputs, requested);
            trim(outputs, requested);
            if (!budget.canFit(maximumModificationCalls(source, inputs.size(), target, outputs.size())))
                return empty(Failure.WAITING_BUDGET);
        } catch (RuntimeException failure) {
            return exception(failure);
        }
        return execute(source, target, variant, inputs, outputs, requested, true, recovery, limits, budget);
    }

    /**
     * Pure conservative native call bound after simulations: source count/extract, target count/insert,
     * then source target-count/return for each actual planned source view. Constant metadata costs zero
     * in native FE/fluid ports, so those types can finish below this bound. Long arithmetic cannot overflow
     * for nonnegative int plan lengths. No resource quantities or stack-size loops occur in this formula.
     */
    public static long maximumModificationCalls(int sourceSteps, int targetSteps) {
        if (sourceSteps < 0 || targetSteps < 0) throw new IllegalArgumentException("Negative plan size");
        return 4L * sourceSteps + 2L * targetSteps;
    }

    /** Pure bound for one greedy unit, including queries/simulations and worst-case source return. Zero means a private ledger port. */
    public static long maximumGreedyCalls(int sourceMutationCalls, int targetMutationCalls) {
        if (sourceMutationCalls < 0 || targetMutationCalls < 0)
            throw new IllegalArgumentException("Negative native call bound");
        return (sourceMutationCalls == 0 ? 0 : 4L + 2L * sourceMutationCalls)
                + (targetMutationCalls == 0 ? 0 : 3L + targetMutationCalls);
    }

    private static int mutationCalls(ResourcePort port) {
        int calls = port.maximumMutationCalls();
        if (calls < 0 || (calls == 0) == port.usesNativeCalls())
            throw new IllegalArgumentException("Invalid native mutation bound");
        return calls;
    }

    private static long maximumModificationCalls(Handle source, int sourceSteps, Handle target, int targetSteps) {
        int sourceCalls = mutationCalls(source.port()), targetCalls = mutationCalls(target.port());
        return Math.addExact(
                sourceCalls == 0 ? 0 : Math.multiplyExact(2L + 2L * sourceCalls, sourceSteps),
                targetCalls == 0 ? 0 : Math.multiplyExact(1L + targetCalls, targetSteps));
    }

    private static Result execute(
            Handle source,
            Handle target,
            ResourceVariant variant,
            List<Step> inputs,
            List<Step> outputs,
            int requested,
            boolean exact,
            RecoveryBuffer recovery,
            ServerSettings.RecoveryLimits limits,
            TransferWorkBudget budget) {
        long segmentBound;
        try {
            segmentBound = maximumModificationCalls(source, inputs.size(), target, outputs.size());
        } catch (RuntimeException invalidBound) {
            return exception(invalidBound);
        }
        RecoveryBuffer.Reservation reservation = recovery.reserve(
                        variant.key(), requested, limits.maxVariantsPerNetwork(), limits.maxEncodedBytesPerNetwork())
                .orElse(null);
        if (reservation == null) return empty(Failure.RECOVERY_FULL);
        Commit state = new Commit();
        long segmentStartCalls = budget.calls();
        try (reservation) {
            try {
                try {
                    if (!target.port().reserveInsertion(variant, requested)) return empty(Failure.REFUSED);
                } catch (RuntimeException admissionFailure) {
                    return exception(admissionFailure);
                }
                for (Step step : inputs) {
                    if (!state.current(source, step.view(), true, budget) || !state.valid(target)) break;
                    if (exact
                            && state.extracted == 0
                            && !budget.canFit(Math.max(0, segmentBound - (budget.calls() - segmentStartCalls)))) {
                        state.failure = Failure.WAITING_BUDGET;
                        break;
                    }
                    int actual;
                    long beforeExtractCalls = budget.calls();
                    try {
                        actual = checked(
                                source.port().extract(step.view(), variant, step.amount(), false, budget),
                                step.amount());
                    } catch (RuntimeException failure) {
                        if (budget.calls() != beforeExtractCalls)
                            return state.unknown(reservation, Stage.SOURCE_EXTRACT, step.amount(), failure);
                        state.preInvocationFailure(failure);
                        break;
                    }
                    state.extracted += actual;
                    state.held += actual;
                    if (actual != step.amount()) {
                        state.failure = Failure.INCONSISTENT;
                        break;
                    }
                }
                if (state.held > 0 && (!exact || state.extracted == requested && state.failure == Failure.NONE)) {
                    for (Step step : outputs) {
                        if (state.held == 0 || !state.current(target, step.view(), false, budget)) break;
                        int offered = (int) Math.min(state.held, step.amount());
                        state.held -= offered;
                        int accepted;
                        long beforeInsertCalls = budget.calls();
                        try {
                            accepted = checked(
                                    target.port().insert(step.view(), variant, offered, false, budget), offered);
                        } catch (RuntimeException failure) {
                            if (budget.calls() != beforeInsertCalls)
                                return state.unknown(reservation, Stage.TARGET_INSERT, offered, failure);
                            state.held += offered;
                            state.preInvocationFailure(failure);
                            break;
                        }
                        state.moved += accepted;
                        state.held += offered - accepted;
                        if (accepted != offered) {
                            state.failure = Failure.INCONSISTENT;
                            break;
                        }
                    }
                }
                Handle returnSource = state.held == 0 ? source : source.remainderTarget();
                for (Step step : inputs) {
                    if (state.held == 0) break;
                    int returnView =
                            source.port().extractionScope() == ResourcePort.ExtractionScope.HANDLER ? 0 : step.view();
                    if (!state.current(returnSource, returnView, false, budget)) break;
                    int offered = (int) Math.min(state.held, step.amount());
                    state.held -= offered;
                    int accepted;
                    long beforeReturnCalls = budget.calls();
                    try {
                        accepted = checked(
                                returnSource.port().insert(returnView, variant, offered, false, budget), offered);
                    } catch (RuntimeException failure) {
                        if (budget.calls() != beforeReturnCalls)
                            return state.unknown(reservation, Stage.SOURCE_RETURN, offered, failure);
                        state.held += offered;
                        state.preInvocationFailure(failure);
                        break;
                    }
                    state.returned += accepted;
                    state.held += offered - accepted;
                }
                RecoveryBuffer.Placement placement = reservation.placeKnownRemainder(state.held);
                return state.result(
                        placement.buffered(),
                        placement.stored(),
                        exact && state.failure == Failure.NONE && state.moved == requested,
                        Stage.NONE,
                        0);
            } finally {
                target.port().releaseInsertion();
            }
        }
    }

    private static boolean eligible(Handle source, Handle target, ResourceVariant variant) {
        return source.valid()
                && target.valid()
                && source.port() != target.port()
                && !source.physicalIdentity().equals(target.physicalIdentity())
                && source.port().typeId().equals(variant.key().typeId())
                && target.port().typeId().equals(variant.key().typeId());
    }

    private static boolean viewCurrent(Handle handle, int view, boolean extracting, TransferWorkBudget budget) {
        if (!handle.valid() || view < 0) return false;
        int count =
                extracting ? handle.port().sourceViews(budget) : handle.port().targetViews(budget);
        return view < count && handle.valid();
    }

    private static int checked(int amount, int requested) {
        if (amount < 0 || amount > requested) throw new IllegalArgumentException("Invalid resource port amount");
        return amount;
    }

    private static long rounded(long amount, long batchSize) {
        return amount / batchSize * batchSize;
    }

    private static void trim(List<Step> steps, int amount) {
        int remaining = amount;
        int count = 0;
        for (int index = 0; index < steps.size() && remaining > 0; index++) {
            Step step = steps.get(index);
            int bounded = Math.min(step.amount(), remaining);
            if (bounded != step.amount()) steps.set(index, new Step(step.view(), bounded));
            remaining -= bounded;
            count++;
        }
        steps.subList(count, steps.size()).clear();
    }

    private static Result empty(Failure failure) {
        return new Result(0, 0, 0, 0, 0, failure, false, Stage.NONE, 0, null);
    }

    private static Result exception(RuntimeException cause) {
        return new Result(0, 0, 0, 0, 0, Failure.EXCEPTION, false, Stage.NONE, 0, cause);
    }

    private static int[] uniqueHints(int[] hints, Set<Integer> seen) {
        int[] unique = new int[hints.length];
        int count = 0;
        for (int view : hints) {
            if (view < 0) throw new IllegalArgumentException("Negative view hint");
            if (seen.add(view)) unique[count++] = view;
        }
        return Arrays.copyOf(unique, count);
    }

    private record Step(int view, int amount) {}

    /** Per-call evidence and known holdings; never published, cached, or retained after return. */
    private static final class Commit {
        long extracted, moved, returned, held;
        Failure failure = Failure.NONE;

        @Nullable
        RuntimeException cause;

        boolean valid(Handle handle) {
            try {
                if (handle.valid()) return true;
                failure = Failure.INVALID_ENDPOINT;
            } catch (RuntimeException exception) {
                failure = Failure.EXCEPTION;
                cause = exception;
            }
            return false;
        }

        boolean current(Handle handle, int view, boolean extracting, TransferWorkBudget budget) {
            try {
                if (viewCurrent(handle, view, extracting, budget)) return true;
                failure = Failure.INVALID_ENDPOINT;
            } catch (RuntimeException exception) {
                failure = Failure.EXCEPTION;
                cause = exception;
            }
            return false;
        }

        void preInvocationFailure(RuntimeException exception) {
            failure = Failure.EXCEPTION;
            cause = exception;
        }

        Result unknown(RecoveryBuffer.Reservation reservation, Stage stage, int requested, RuntimeException exception) {
            failure = Failure.UNKNOWN_MUTATION;
            cause = exception;
            RecoveryBuffer.Placement placement = reservation.placeKnownRemainder(held);
            return result(placement.buffered(), placement.stored(), false, stage, requested);
        }

        Result result(long buffered, long stored, boolean completeBatch, Stage stage, int requested) {
            return new Result(
                    extracted, moved, returned, buffered, stored, failure, completeBatch, stage, requested, cause);
        }
    }
}
