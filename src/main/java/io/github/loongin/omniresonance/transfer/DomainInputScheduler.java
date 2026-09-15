// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import io.github.loongin.omniresonance.config.ServerSettings;
import io.github.loongin.omniresonance.filter.ResourceFilterCompiler;
import io.github.loongin.omniresonance.network.WorkingFaces;
import io.github.loongin.omniresonance.recovery.RecoveryBuffer;
import io.github.loongin.omniresonance.storage.DomainLedger;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Server-thread domain input work units. The central scheduler owns dispatch/network fairness and supplies
 * its one tick budget. State is bounded by published input nodes and registered resource types; endpoint,
 * filter and source-round hints are released on replacement/removal/close. No extracted resources survive step.
 */
public final class DomainInputScheduler {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(DomainInputScheduler.class);

    public record Configuration(
            UUID networkId, UUID nodeId, long revision, ResourceTransferPolicy.Input policy, WorkingFaces faces) {
        public Configuration {
            Objects.requireNonNull(networkId);
            Objects.requireNonNull(nodeId);
            Objects.requireNonNull(policy);
            Objects.requireNonNull(faces);
            if (revision < 0) throw new IllegalArgumentException("Negative domain configuration revision");
        }
    }

    /** Server-thread authority and counted capability boundary. Direct-first returns -1 when clear, otherwise
     * the next safe review tick; it must not treat mere existence of a direct binding as unfinished work. */
    public interface Environment {
        List<ResourceLocation> types();

        boolean active(Configuration config);

        int faces(Configuration config);

        @Nullable
        ResourceTransferEngine.Handle resolve(
                Configuration config, ResourceLocation type, Direction face, TransferWorkBudget budget);

        @Nullable
        DomainLedger ledger(UUID networkId);

        RecoveryBuffer recovery(UUID networkId);

        long directFirst(
                Configuration config, ResourceLocation type, Direction face, long tick, TransferWorkBudget budget);

        ResourceDirectScheduler.FilterView filter(Configuration config);

        int advanceFilter(Configuration config, int workUnits);

        default Object filterToken(Configuration config) {
            return filter(config).token();
        }
    }

    private enum Stage {
        DISCOVER,
        COUNT,
        PEEK,
        MATCH,
        COMMIT
    }

    private final Thread owner = Thread.currentThread();
    private final Environment environment;
    private final List<ResourceLocation> types;
    private final Map<UUID, State> states = new HashMap<>();
    private final DomainTransferEngine engine = new DomainTransferEngine();

    public DomainInputScheduler(Environment environment) {
        this.environment = Objects.requireNonNull(environment);
        types = List.copyOf(environment.types());
        if (types.size() > ResourceScope.MAXIMUM_RESOURCE_TYPE_IDS
                || new java.util.HashSet<>(types).size() != types.size())
            throw new IllegalArgumentException("Invalid domain resource directory");
        for (var type : types) ResourceScope.validateResourceTypeId(type);
    }

    /** Publishes immutable authority without native work; existing clocks survive ordinary edits and wakeups. */
    public void replace(Configuration config, long tick) {
        checkThread();
        State state = states.get(config.nodeId());
        if (state == null || !state.config.networkId().equals(config.networkId())) {
            state = new State(config, types);
            states.put(config.nodeId(), state);
        } else state.config = config;
        wake(config.nodeId(), tick);
    }

    public void remove(UUID nodeId) {
        checkThread();
        states.remove(nodeId);
    }

    public boolean contains(UUID nodeId) {
        checkThread();
        return states.containsKey(nodeId);
    }

    public @Nullable Configuration configuration(UUID nodeId) {
        checkThread();
        State state = states.get(nodeId);
        return state == null ? null : state.config;
    }

    /** Returns last-dispatched state without native access, storage activation or scheduler mutation. */
    public io.github.loongin.omniresonance.networking.NodeDomainStatus status(UUID nodeId, long tick) {
        checkThread();
        State state = states.get(nodeId);
        if (state == null) return io.github.loongin.omniresonance.networking.NodeDomainStatus.IDLE;
        if (state.failures > 0) return io.github.loongin.omniresonance.networking.NodeDomainStatus.FAILED;
        if (state.blocked) return io.github.loongin.omniresonance.networking.NodeDomainStatus.BLOCKED;
        boolean running = false;
        for (TypeState t : state.types) {
            if (!state.config.policy().scope().includes(t.type)) continue;
            if (t.nextTick > tick && t.minimumTick <= tick)
                return io.github.loongin.omniresonance.networking.NodeDomainStatus.WAITING_BUDGET;
            running |= t.variant != null;
        }
        return running
                ? io.github.loongin.omniresonance.networking.NodeDomainStatus.RUNNING
                : io.github.loongin.omniresonance.networking.NodeDomainStatus.IDLE;
    }

    public void close() {
        checkThread();
        states.clear();
    }

    public long wake(UUID nodeId, long tick) {
        checkThread();
        State state = states.get(nodeId);
        if (state == null) return Long.MAX_VALUE;
        for (TypeState type : state.types) {
            type.reset();
            type.minimumTick = type.window.nextRunTick();
            type.nextTick = Math.max(tick, type.minimumTick);
        }
        return due(state);
    }

    /** Performs one discovery/query/filter/commit unit and returns the next dispatch tick, without owning a queue. */
    public long step(UUID nodeId, long tick, ServerSettings settings, TransferWorkBudget budget) {
        checkThread();
        State state = states.get(nodeId);
        if (state == null) return Long.MAX_VALUE;
        if (state.blockedUntilTick > tick) return state.blockedUntilTick;
        TypeState type = select(state, tick);
        if (type == null) return due(state);
        if (!budget.canStart()) {
            type.nextTick = Math.addExact(tick, 1);
            return due(state);
        }
        long slowBefore = budget.slowCalls();
        try {
            work(state, type, tick, settings, budget, slowBefore);
            if (budget.slowCalls() > slowBefore) {
                LOGGER.warn("Slow domain input capability call (node={}, type={})", nodeId, type.type);
                if (type.window.nextRunTick() <= tick) finish(state, type, tick, settings, true);
            }
        } catch (RuntimeException failure) {
            LOGGER.error(
                    "Domain input failed; applying bounded retry backoff (node={}, type={})",
                    nodeId,
                    type.type,
                    failure);
            finish(state, type, tick, settings, true);
        }
        return due(state);
    }

    private void work(
            State state, TypeState t, long tick, ServerSettings settings, TransferWorkBudget b, long slowBefore) {
        Configuration c = state.config;
        if (!environment.active(c)) {
            state.blocked = true;
            t.reset();
            t.nextTick = Math.max(
                    t.window.nextRunTick(), Math.addExact(tick, c.policy().intervalTicks()));
            return;
        }
        state.blocked = false;
        long allowance = t.window.available(tick, c.policy().rate(t.type));
        var override = c.policy().resourcePolicyOverrides().get(t.type);
        long batch = override != null && override.batchMode() == ResourceTransferPolicy.BatchMode.EXACT
                ? override.batchSize()
                : 1;
        if (allowance < batch) {
            finish(state, t, tick, settings, b.slowCalls() > slowBefore);
            return;
        }
        if (t.source != null && !t.source.valid()) t.reset();
        switch (t.stage) {
            case DISCOVER -> {
                int mask = environment.faces(c) & ~t.facesDone;
                t.face = nextFace(mask, t.faceCursor);
                if (t.face < 0) {
                    finish(state, t, tick, settings, b.slowCalls() > slowBefore);
                    return;
                }
                long direct = environment.directFirst(c, t.type, Direction.from3DDataValue(t.face), tick, b);
                if (direct >= 0) {
                    t.nextTick = Math.max(tick, direct);
                    return;
                }
                t.source = environment.resolve(c, t.type, Direction.from3DDataValue(t.face), b);
                if (t.source == null) {
                    nextFace(t);
                    return;
                }
                t.stage = Stage.COUNT;
            }
            case COUNT -> {
                t.views = t.source.port().sourceViews(b);
                if (t.views == 0) {
                    nextFace(t);
                    return;
                }
                t.cursors[t.face] %= t.views;
                t.scanned = 0;
                t.rejectedLimit = (int) Math.min(Integer.MAX_VALUE, Math.max(1, (b.calls() + b.remainingCalls()) / 2));
                t.stage = Stage.PEEK;
            }
            case PEEK -> {
                ResourceAmount amount =
                        t.source.port().peek(t.cursors[t.face], b).orElse(null);
                if (amount == null) {
                    nextView(t);
                    return;
                }
                Object token = environment.filterToken(c);
                if (!Objects.equals(token, t.memoToken)) {
                    t.rejected.clear();
                    t.memoToken = token;
                }
                if (t.rejected.contains(amount.variant().key())) {
                    nextView(t);
                    return;
                }
                t.variant = amount.variant();
                t.quantity = amount.quantity();
                t.token = token;
                t.evaluation = null;
                t.stage = Stage.MATCH;
            }
            case MATCH -> {
                if (c.policy().filterPresetId() == null) {
                    t.stage = Stage.COMMIT;
                    return;
                }
                var view = environment.filter(c);
                if (!Objects.equals(view.token(), t.token)) {
                    t.token = view.token();
                    t.evaluation = null;
                }
                if (view.compiled() == null) {
                    int progress = environment.advanceFilter(c, 256);
                    if (progress < 0 || progress > 256)
                        throw new IllegalStateException("Invalid domain filter preparation progress");
                    if (progress == 0) t.nextTick = Math.addExact(tick, 1);
                    return;
                }
                Boolean fast = view.compiled().fastDecision(t.type, c.policy().filterMode());
                if (fast != null) {
                    if (fast) t.stage = Stage.COMMIT;
                    else nextView(t);
                    return;
                }
                if (t.evaluation == null) {
                    view.compiled().preparationCost(t.variant);
                    if (!b.canStart()) {
                        t.nextTick = Math.addExact(tick, 1);
                        return;
                    }
                    t.evaluation = view.compiled()
                            .evaluate(
                                    view.compiled().prepareCandidate(t.variant),
                                    c.policy().filterMode());
                }
                if (!b.canStart()) {
                    t.nextTick = Math.addExact(tick, 1);
                    return;
                }
                t.evaluation.step(256);
                if (t.evaluation.done()) {
                    if (t.evaluation.allowed()) t.stage = Stage.COMMIT;
                    else nextView(t);
                }
            }
            case COMMIT -> {
                long direct = environment.directFirst(c, t.type, Direction.from3DDataValue(t.face), tick, b);
                if (direct >= 0) {
                    t.nextTick = Math.max(tick, direct);
                    return;
                }
                if (!Objects.equals(t.token, environment.filterToken(c))) {
                    t.stage = Stage.MATCH;
                    t.evaluation = null;
                    return;
                }
                DomainLedger ledger = environment.ledger(c.networkId());
                if (ledger == null) {
                    finish(state, t, tick, settings, b.slowCalls() > slowBefore);
                    return;
                }
                Guarded source = new Guarded(c, t.source, t.token);
                java.util.function.BooleanSupplier valid = source;
                ResourceTransferEngine.Result result = batch > 1 || c.policy().keepCount() > 0
                        ? engine.depositExact(
                                source,
                                0,
                                ledger,
                                t.variant,
                                allowance,
                                c.policy().keepCount(),
                                batch,
                                settings.storageVariantLimitPerNetwork(),
                                valid,
                                environment.recovery(c.networkId()),
                                settings.recoveryLimits(),
                                b)
                        : engine.depositGreedy(
                                source,
                                t.cursors[t.face],
                                ledger,
                                t.variant,
                                Math.min(allowance, t.quantity),
                                settings.storageVariantLimitPerNetwork(),
                                valid,
                                environment.recovery(c.networkId()),
                                settings.recoveryLimits(),
                                b);
                if (result.removed() > 0) {
                    t.window.moved(tick, result.removed(), c.policy().rate(t.type));
                    t.moved = true;
                }
                if (result.failure() == ResourceTransferEngine.Failure.WAITING_BUDGET) {
                    if (c.policy().keepCount() > 0 && (environment.faces(c) & ~t.facesDone & ~(1 << t.face)) != 0) {
                        t.waitingRetention = true;
                        nextFace(t);
                        return;
                    }
                    t.nextTick = Math.addExact(tick, 1);
                    return;
                }
                if (result.failure() == ResourceTransferEngine.Failure.EXCEPTION
                        || result.failure() == ResourceTransferEngine.Failure.UNKNOWN_MUTATION
                        || result.failure() == ResourceTransferEngine.Failure.INCONSISTENT) {
                    LOGGER.error(
                            "Domain input commit failed (node={}, type={}, stage={}, uncertainRequest={})",
                            c.nodeId(),
                            t.type,
                            result.unknownStage(),
                            result.unknownRequested(),
                            result.cause());
                    finish(state, t, tick, settings, true);
                    return;
                }
                if ((batch > 1 || c.policy().keepCount() > 0)
                        && result.failure() == ResourceTransferEngine.Failure.REFUSED) {
                    if (t.rejected.size() >= t.rejectedLimit)
                        t.rejected.remove(t.rejected.iterator().next());
                    t.rejected.add(t.variant.key());
                }
                if (t.window.available(tick, c.policy().rate(t.type)) < batch)
                    finish(state, t, tick, settings, b.slowCalls() > slowBefore);
                else nextView(t);
            }
        }
    }

    private final class Guarded implements ResourceTransferEngine.Handle, java.util.function.BooleanSupplier {
        private final Configuration config;
        private final ResourceTransferEngine.Handle nativeHandle;
        private final Object token;

        Guarded(Configuration config, ResourceTransferEngine.Handle nativeHandle, Object token) {
            this.config = config;
            this.nativeHandle = nativeHandle;
            this.token = token;
        }

        public ResourcePort port() {
            return nativeHandle.port();
        }

        public Object physicalIdentity() {
            return nativeHandle.physicalIdentity();
        }

        public boolean valid() {
            return nativeHandle.valid()
                    && environment.active(config)
                    && Objects.equals(token, environment.filterToken(config));
        }

        public boolean getAsBoolean() {
            return valid();
        }
    }

    private void finish(State s, TypeState t, long tick, ServerSettings settings, boolean failure) {
        long delay = s.config.policy().intervalTicks();
        if (failure) {
            if (s.failures < Integer.MAX_VALUE) s.failures++;
            if (s.failures >= settings.scheduler().failureThreshold()) {
                var stages = settings.scheduler().breakerBackoffTicks();
                delay = Math.max(delay, stages.get(Math.min(s.breakerStage, stages.size() - 1)));
                s.breakerStage = Math.min(s.breakerStage + 1, stages.size() - 1);
                s.blockedUntilTick = Math.addExact(tick, delay);
            }
        } else {
            s.failures = 0;
            s.breakerStage = 0;
            s.blockedUntilTick = 0;
            if (t.moved) t.emptyChecks = 0;
            else if (!t.waitingRetention && t.emptyChecks < Integer.MAX_VALUE) t.emptyChecks++;
            if (!t.waitingRetention && t.emptyChecks >= settings.scheduler().emptyChecksBeforeSleep()) {
                var stages = settings.scheduler().idleBackoffTicks();
                delay = Math.max(
                        delay,
                        stages.get(Math.min(
                                t.emptyChecks - settings.scheduler().emptyChecksBeforeSleep(), stages.size() - 1)));
            }
        }
        t.window.finish(tick, s.config.policy().intervalTicks());
        t.faceCursor = t.face < 0 ? t.faceCursor : (t.face + 1) % 6;
        t.reset();
        t.moved = false;
        t.waitingRetention = false;
        t.nextTick = Math.addExact(tick, delay);
        t.minimumTick = t.nextTick;
    }

    private static void nextView(TypeState t) {
        t.cursors[t.face] = (t.cursors[t.face] + 1) % t.views;
        t.variant = null;
        t.evaluation = null;
        if (++t.scanned >= t.views) nextFace(t);
        else t.stage = Stage.PEEK;
    }

    private static void nextFace(TypeState t) {
        t.facesDone |= 1 << t.face;
        t.source = null;
        t.variant = null;
        t.evaluation = null;
        t.rejected.clear();
        t.stage = Stage.DISCOVER;
    }

    private static int nextFace(int mask, int start) {
        for (int i = 0; i < 6; i++) {
            int face = (start + i) % 6;
            if ((mask & 1 << face) != 0) return face;
        }
        return -1;
    }

    private TypeState select(State s, long tick) {
        for (int i = 0; i < s.types.length; i++) {
            int index = (s.typeCursor + i) % s.types.length;
            TypeState t = s.types[index];
            if (s.config.policy().scope().includes(t.type) && t.nextTick <= tick) {
                s.typeCursor = (index + 1) % s.types.length;
                return t;
            }
        }
        return null;
    }

    private static long due(State s) {
        long next = Long.MAX_VALUE;
        for (TypeState t : s.types) if (s.config.policy().scope().includes(t.type)) next = Math.min(next, t.nextTick);
        return Math.max(next, s.blockedUntilTick);
    }

    private void checkThread() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Domain input accessed off owner thread");
    }

    private static final class State {
        Configuration config;
        final TypeState[] types;
        int typeCursor, failures, breakerStage;
        long blockedUntilTick;
        boolean blocked;

        State(Configuration config, List<ResourceLocation> registered) {
            this.config = config;
            types = new TypeState[registered.size()];
            for (int i = 0; i < types.length; i++) types[i] = new TypeState(registered.get(i));
        }
    }

    private static final class TypeState {
        final ResourceLocation type;
        final DomainTransferWindow window = new DomainTransferWindow();
        final int[] cursors = new int[6];
        // Source-round negative discovery hints only, FIFO bounded by the observed tick call budget.
        final LinkedHashSet<ResourceVariantKey> rejected = new LinkedHashSet<>();
        int face = -1, faceCursor, facesDone, views, scanned, rejectedLimit = 1, emptyChecks;
        long nextTick, minimumTick, quantity;
        boolean moved, waitingRetention;
        Stage stage = Stage.DISCOVER;

        @Nullable
        ResourceTransferEngine.Handle source;

        @Nullable
        ResourceVariant variant;

        @Nullable
        ResourceFilterCompiler.Evaluation evaluation;

        @Nullable
        Object token, memoToken;

        TypeState(ResourceLocation type) {
            this.type = type;
        }

        void reset() {
            face = -1;
            facesDone = 0;
            source = null;
            variant = null;
            evaluation = null;
            rejected.clear();
            memoToken = null;
            stage = Stage.DISCOVER;
        }
    }
}
