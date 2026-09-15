// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import io.github.loongin.omniresonance.config.ServerSettings;
import io.github.loongin.omniresonance.filter.FilterMode;
import io.github.loongin.omniresonance.filter.ResourceFilterCompiler;
import io.github.loongin.omniresonance.network.WorkingFaces;
import io.github.loongin.omniresonance.recovery.RecoveryBuffer;
import io.github.loongin.omniresonance.storage.DomainLedger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Server-thread domain output work units for the shared central queue. Per-node/type clocks and probe hints
 * survive budget pauses; hints never prove capacity. Per-variant matching and tie cursors belong to one network
 * publication and are removed when ledger ownership truly retires, or on publication replacement/close.
 */
public final class DomainOutputScheduler {
    public record Configuration(
            UUID networkId, UUID nodeId, long revision, ResourceTransferPolicy.Output policy, WorkingFaces faces) {
        public Configuration {
            Objects.requireNonNull(networkId);
            Objects.requireNonNull(nodeId);
            Objects.requireNonNull(policy);
            Objects.requireNonNull(faces);
            if (revision < 0) throw new IllegalArgumentException("Negative domain output revision");
        }
    }

    public interface Environment {
        List<ResourceLocation> types();

        boolean active(Configuration config);

        int faces(Configuration config);

        @Nullable
        ResourceTransferEngine.Handle resolve(
                Configuration config, ResourceLocation type, Direction face, TransferWorkBudget budget);

        @Nullable
        DomainLedger ledger(UUID networkId);

        @Nullable
        ResourceVariant decode(ResourceVariantKey key);

        RecoveryBuffer recovery(UUID networkId);

        ResourceDirectScheduler.FilterView filter(Configuration config);

        int advanceFilter(Configuration config, int workUnits);

        default Object filterToken(Configuration config) {
            return filter(config).token();
        }
    }

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(DomainOutputScheduler.class);
    private final Thread owner = Thread.currentThread();
    private final Environment environment;
    private final List<ResourceLocation> types;
    private final Map<ResourceLocation, Integer> typeIndices = new HashMap<>();
    private final Map<UUID, State> states = new HashMap<>();
    private final Map<UUID, Network> networks = new HashMap<>();
    private final DomainTransferEngine engine = new DomainTransferEngine();

    public DomainOutputScheduler(Environment environment) {
        this.environment = Objects.requireNonNull(environment);
        types = List.copyOf(environment.types());
        if (types.size() > ResourceScope.MAXIMUM_RESOURCE_TYPE_IDS || new HashSet<>(types).size() != types.size())
            throw new IllegalArgumentException("Invalid output type directory");
        for (int i = 0; i < types.size(); i++) {
            ResourceScope.validateResourceTypeId(types.get(i));
            typeIndices.put(types.get(i), i);
        }
    }

    /** Replaces one network publication without native calls. Clocks survive edits; derived variant caches do not. */
    public void replaceNetwork(UUID networkId, List<Configuration> configs, long tick) {
        checkThread();
        Objects.requireNonNull(networkId);
        if (configs.size() > 262144) throw new IllegalArgumentException("Too many domain outputs");
        Set<UUID> next = new HashSet<>();
        for (Configuration config : configs) {
            if (!networkId.equals(config.networkId()) || !next.add(config.nodeId()))
                throw new IllegalArgumentException("Invalid output publication");
        }
        Network old = networks.get(networkId);
        if (old != null && old.busy) throw new IllegalStateException("Cannot replace an active output publication");
        networks.remove(networkId);
        if (old != null) old.close();
        List<State> outputs = new ArrayList<>();
        for (Configuration config : configs) {

            State state = states.get(config.nodeId());
            if (state == null || !state.config.networkId().equals(networkId)) {
                state = new State(config);
                states.put(config.nodeId(), state);
            } else state.config = config;
            outputs.add(state);
            wake(config.nodeId(), tick);
        }
        if (old != null)
            for (State state : old.outputs)
                if (!next.contains(state.config.nodeId())) states.remove(state.config.nodeId(), state);
        if (!outputs.isEmpty()) networks.put(networkId, new Network(networkId, outputs));
    }

    public @Nullable Configuration configuration(UUID nodeId) {
        checkThread();
        State s = states.get(nodeId);
        return s == null ? null : s.config;
    }

    public int cachedVariants(UUID networkId) {
        checkThread();
        Network n = networks.get(networkId);
        return n == null ? 0 : n.variants.size();
    }

    /** Returns last-dispatched state without native access, storage activation or scheduler mutation. */
    public io.github.loongin.omniresonance.networking.NodeDomainStatus status(UUID nodeId, long tick) {
        checkThread();
        State state = states.get(nodeId);
        if (state == null) return io.github.loongin.omniresonance.networking.NodeDomainStatus.IDLE;
        if (state.failures > 0) return io.github.loongin.omniresonance.networking.NodeDomainStatus.FAILED;
        if (state.blocked) return io.github.loongin.omniresonance.networking.NodeDomainStatus.BLOCKED;
        boolean running = false;
        for (Type t : state.types) {
            if (!state.config.policy().scope().includes(t.type)) continue;
            if (t.nextTick > tick && t.minimumTick <= tick)
                return io.github.loongin.omniresonance.networking.NodeDomainStatus.WAITING_BUDGET;
            running |= t.pending != null;
        }
        return running
                ? io.github.loongin.omniresonance.networking.NodeDomainStatus.RUNNING
                : io.github.loongin.omniresonance.networking.NodeDomainStatus.IDLE;
    }

    public void close() {
        checkThread();
        for (Network n : networks.values()) n.close();
        networks.clear();
        states.clear();
    }

    public long wake(UUID nodeId, long tick) {
        checkThread();
        State s = states.get(nodeId);
        if (s == null) return Long.MAX_VALUE;
        for (Type t : s.types) {
            t.reset();
            t.minimumTick = t.window.nextRunTick();
            t.nextTick = Math.max(tick, t.minimumTick);
        }
        return due(s);
    }

    /** Executes one finite scan/decode/allocation unit using the caller's only tick budget. */
    public long step(UUID nodeId, long tick, ServerSettings settings, TransferWorkBudget budget) {
        checkThread();
        State s = states.get(nodeId);
        if (s == null) return Long.MAX_VALUE;
        Network n = networks.get(s.config.networkId());
        if (n == null) return Long.MAX_VALUE;
        if (n.busy) return Math.addExact(tick, 1);
        Type t = select(s, tick);
        if (t == null) return due(s);
        if (!budget.canStart()) {
            t.nextTick = Math.addExact(tick, 1);
            return due(s);
        }
        n.busy = true;
        n.tick = tick;
        n.settings = settings;
        try {
            if (!environment.active(s.config)) {
                s.blocked = true;
                t.reset();
                t.nextTick = Math.max(
                        t.minimumTick, Math.addExact(tick, s.config.policy().intervalTicks()));
                return due(s);
            }
            s.blocked = false;
            if (t.window.available(tick, s.config.policy().rate(t.type)) == 0) {
                finish(s, t, tick, settings, false);
                return due(s);
            }
            if (s.config.policy().filterPresetId() == null) {
                finish(s, t, tick, settings, false);
                return due(s);
            }
            var view = environment.filter(s.config);
            if (view.compiled() == null) {
                advanceFilter(s.config);
                t.nextTick = Math.addExact(tick, 1);
                return due(s);
            }
            if (!usable(s.config, view.compiled())
                    || Boolean.FALSE.equals(view.compiled()
                            .fastDecision(t.type, s.config.policy().filterMode()))) {
                finish(s, t, tick, settings, false);
                return due(s);
            }
            if (n.ledger == null) {
                n.ledger = environment.ledger(n.id);
                if (n.ledger == null) {
                    finish(s, t, tick, settings, false);
                    return due(s);
                }
                n.ledger.onRetired(n.variants::remove);
            }
            if (!n.ledger.isAvailable()) {
                t.nextTick = Math.addExact(tick, 1);
                return due(s);
            }
            if (t.pending != null && n.ledger.sequence(t.pending.variant.key()) != t.pending.sequence) t.pending = null;
            if (t.pending == null) {
                if (!t.round) {
                    t.round = true;
                    t.roundProgress = false;
                    t.start = t.sequence;
                    t.ceiling = n.ledger.sequenceCeiling();
                    t.wrapped = false;
                }
                DomainLedger.Cursor entry = n.ledger.nextAfter(t.sequence).orElse(null);
                if (entry == null) {
                    finish(s, t, tick, settings, false);
                    return due(s);
                }
                boolean wrap = entry.sequence() <= t.sequence;
                if (entry.sequence() > t.ceiling) {
                    entry = n.ledger.nextAfter(Long.MAX_VALUE).orElse(null);
                    wrap = true;
                }
                if (entry == null || entry.sequence() > t.ceiling || wrap && t.wrapped) {
                    endRound(s, t, tick, settings);
                    return due(s);
                }
                if (wrap) t.wrapped = true;
                if (t.wrapped && entry.sequence() > t.start) {
                    endRound(s, t, tick, settings);
                    return due(s);
                }
                t.sequence = entry.sequence();
                if (!entry.key().typeId().equals(t.type)) return due(s);
                Variant work = n.variants.get(entry.key());
                if (work == null) {
                    ResourceVariant decoded = environment.decode(entry.key());
                    if (decoded == null) return due(s);
                    work = new Variant(decoded, entry.sequence(), n.order.cursor(entry.key()));
                    n.variants.put(entry.key(), work);
                }
                t.pending = work;
                return due(s);
            }
            if (!budget.canStart()) {
                t.nextTick = Math.addExact(tick, 1);
                return due(s);
            }
            n.current = t.pending;
            var result = n.order.step(t.pending.variant, t.pending.cursor, budget);
            if (result.attempt().moved() > 0) t.roundProgress = true;
            if (result.attempt().state() == DomainOutputOrder.State.WAITING_BUDGET)
                t.nextTick = Math.max(t.minimumTick, Math.addExact(tick, 1));
            else t.pending = null;
        } catch (RuntimeException failure) {
            LOGGER.error("Domain output work failed (node={}, type={})", nodeId, t.type, failure);
            finish(s, t, tick, settings, true);
        } finally {
            n.current = null;
            n.busy = false;
        }
        return due(s);
    }

    private DomainOutputOrder.Attempt attempt(Network n, State s, ResourceVariant variant, TransferWorkBudget b) {
        Configuration c = s.config;
        Integer index = typeIndices.get(variant.key().typeId());
        if (index == null
                || !c.policy().scope().includes(variant.key().typeId())
                || !environment.active(c)
                || c.policy().filterPresetId() == null) return no();
        Type t = s.types[index];
        if (s.blockedUntilTick > n.tick
                || t.minimumTick > n.tick
                || t.window.available(n.tick, c.policy().rate(t.type)) == 0) return no();
        long slowBefore = b.slowCalls();
        try {
            var view = environment.filter(c);
            if (view.compiled() == null) {
                advanceFilter(c);
                return waitBudget();
            }
            if (!usable(c, view.compiled())) return no();
            Match match = n.current.matches.get(c.nodeId());
            if (match == null || !Objects.equals(match.token, view.token())) {
                match = new Match(view.token());
                n.current.matches.put(c.nodeId(), match);
            }
            if (!match.done) {
                Boolean fast = view.compiled().fastDecision(t.type, c.policy().filterMode());
                if (fast != null) {
                    match.done = true;
                    match.allowed = fast;
                } else {
                    if (match.evaluation == null) {
                        view.compiled().preparationCost(variant);
                        if (!b.canStart()) return waitBudget();
                        match.evaluation = view.compiled()
                                .evaluate(
                                        view.compiled().prepareCandidate(variant),
                                        c.policy().filterMode());
                    }
                    if (!b.canStart()) return waitBudget();
                    match.evaluation.step(256);
                    if (!match.evaluation.done()) return waitBudget();
                    match.done = true;
                    match.allowed = match.evaluation.allowed();
                    match.evaluation = null;
                }
            }
            if (!match.allowed) return no();
            Object token = match.token;
            for (int offset = 0; offset < 6; offset++) {
                int face = (t.faceCursor + offset) % 6;
                if ((environment.faces(c) & (1 << face)) == 0) continue;
                if (!b.canStart()) return waitBudget();
                var handle = environment.resolve(c, t.type, Direction.from3DDataValue(face), b);
                if (handle == null) continue;
                Probe p = t.probes[face];
                if (p == null || p.handle != handle || !handle.valid()) {
                    p = new Probe(handle);
                    t.probes[face] = p;
                }
                if (p.refresh) {
                    if (!b.canStart()) return waitBudget();
                    p.count = handle.port().targetViews(b);
                    p.refresh = false;
                    p.view = p.count == 0 ? 0 : p.view % p.count;
                    if (p.count == 0) continue;
                }
                if (p.count > 0) {
                    if (!b.canStart()) return waitBudget();
                    var attempted = commit(n, s, t, variant, token, p.handle, p.view, face, b, slowBefore);
                    if (attempted.state() != DomainOutputOrder.State.NO_CAPACITY) return attempted;
                    p.view = (p.view + 1) % p.count;
                }
                p.refresh = true;
                if (!b.canStart()) return waitBudget();
                int count = handle.port().targetViews(b);
                p.count = count;
                p.refresh = false;
                int start = count == 0 ? 0 : p.view % count;
                for (int slot = 0; slot < count; slot++) {
                    if (!b.canStart()) {
                        p.refresh = true;
                        return waitBudget();
                    }
                    int at = (int) (((long) start + slot) % count);
                    var attempted = commit(n, s, t, variant, token, handle, at, face, b, slowBefore);
                    if (attempted.state() != DomainOutputOrder.State.NO_CAPACITY) return attempted;
                    p.view = (at + 1) % count;
                }
                if (!b.canStart()) {
                    p.refresh = true;
                    return waitBudget();
                }
                if (handle.port().targetViews(b) != count) {
                    p.refresh = true;
                    return waitBudget();
                }
            }
            return no();
        } catch (RuntimeException failure) {
            LOGGER.error("Domain output candidate failed (node={}, type={})", c.nodeId(), t.type, failure);
            finish(s, t, n.tick, n.settings, true);
            return new DomainOutputOrder.Attempt(DomainOutputOrder.State.FAILED, 0);
        } finally {
            if (b.slowCalls() > slowBefore && t.failureTick != n.tick) {
                LOGGER.warn("Slow domain output preparation (node={}, type={})", c.nodeId(), t.type);
                finish(s, t, n.tick, n.settings, true);
            }
        }
    }

    private DomainOutputOrder.Attempt commit(
            Network n,
            State s,
            Type t,
            ResourceVariant variant,
            Object token,
            ResourceTransferEngine.Handle target,
            int view,
            int face,
            TransferWorkBudget budget,
            long slowBefore) {
        if (n.ledger.amount(variant.key()) == 0) return no();
        Configuration c = s.config;
        Guarded guarded = new Guarded(target, c, token);
        var result = engine.withdrawGreedy(
                n.ledger,
                guarded,
                view,
                variant,
                t.window.available(n.tick, c.policy().rate(t.type)),
                guarded,
                environment.recovery(n.id),
                n.settings.recoveryLimits(),
                budget);
        if (result.moved() > 0) {
            t.window.moved(n.tick, result.moved(), c.policy().rate(t.type));
            t.moved = true;
            t.faceCursor = (face + 1) % 6;
        }
        if (result.failure() == ResourceTransferEngine.Failure.WAITING_BUDGET) return waitBudget();
        if (result.failure() == ResourceTransferEngine.Failure.INVALID_ENDPOINT) {
            t.probes[face] = null;
            return environment.active(c) ? waitBudget() : no();
        }
        if (result.failure() != ResourceTransferEngine.Failure.NONE
                        && result.failure() != ResourceTransferEngine.Failure.REFUSED
                || budget.slowCalls() > slowBefore) {
            LOGGER.error(
                    "Domain output commit failed (node={}, type={}, stage={}, uncertainRequest={})",
                    c.nodeId(),
                    t.type,
                    result.unknownStage(),
                    result.unknownRequested(),
                    result.cause());
            finish(s, t, n.tick, n.settings, true);
            return new DomainOutputOrder.Attempt(DomainOutputOrder.State.FAILED, result.moved());
        }
        if (t.window.available(n.tick, c.policy().rate(t.type)) == 0) finish(s, t, n.tick, n.settings, false);
        return result.moved() > 0
                ? new DomainOutputOrder.Attempt(DomainOutputOrder.State.COMMITTED, result.moved())
                : no();
    }

    private final class Guarded implements ResourceTransferEngine.Handle, java.util.function.BooleanSupplier {
        private final ResourceTransferEngine.Handle target;
        private final Configuration config;
        private final Object token;

        Guarded(ResourceTransferEngine.Handle target, Configuration config, Object token) {
            this.target = target;
            this.config = config;
            this.token = token;
        }

        public ResourcePort port() {
            return target.port();
        }

        public Object physicalIdentity() {
            return target.physicalIdentity();
        }

        public boolean valid() {
            return target.valid()
                    && environment.active(config)
                    && Objects.equals(token, environment.filterToken(config));
        }

        public boolean getAsBoolean() {
            return valid();
        }
    }

    private void advanceFilter(Configuration c) {
        int progress = environment.advanceFilter(c, 256);
        if (progress < 0 || progress > 256) throw new IllegalStateException("Invalid filter preparation progress");
    }

    private static boolean usable(Configuration c, ResourceFilterCompiler.Compiled compiled) {
        return compiled.valid() && !(c.policy().filterMode() == FilterMode.BLACKLIST && compiled.ruleCount() == 0);
    }

    private static DomainOutputOrder.Attempt no() {
        return new DomainOutputOrder.Attempt(DomainOutputOrder.State.NO_CAPACITY, 0);
    }

    private static DomainOutputOrder.Attempt waitBudget() {
        return new DomainOutputOrder.Attempt(DomainOutputOrder.State.WAITING_BUDGET, 0);
    }

    private void endRound(State s, Type t, long tick, ServerSettings settings) {
        if (t.roundProgress && t.window.available(tick, s.config.policy().rate(t.type)) > 0) {
            t.round = false;
            t.roundProgress = false;
        } else finish(s, t, tick, settings, false);
    }

    private void finish(State s, Type t, long tick, ServerSettings settings, boolean failure) {
        long delay = s.config.policy().intervalTicks();
        if (failure) {
            t.failureTick = tick;
            if (s.failures < Integer.MAX_VALUE) s.failures++;
            if (s.failures >= settings.scheduler().failureThreshold()) {
                var stages = settings.scheduler().breakerBackoffTicks();
                delay = Math.max(delay, stages.get(Math.min(s.breakerStage, stages.size() - 1)));
                s.breakerStage = Math.min(s.breakerStage + 1, stages.size() - 1);
            }
            s.blockedUntilTick = Math.addExact(tick, delay);
        } else {
            s.failures = 0;
            s.breakerStage = 0;
            s.blockedUntilTick = 0;
            if (t.moved) t.emptyChecks = 0;
            else if (t.emptyChecks < Integer.MAX_VALUE) t.emptyChecks++;
            if (t.emptyChecks >= settings.scheduler().emptyChecksBeforeSleep()) {
                var stages = settings.scheduler().idleBackoffTicks();
                delay = Math.max(
                        delay,
                        stages.get(Math.min(
                                t.emptyChecks - settings.scheduler().emptyChecksBeforeSleep(), stages.size() - 1)));
            }
        }
        if (tick >= t.window.nextRunTick())
            t.window.finish(tick, s.config.policy().intervalTicks());
        t.reset();
        t.moved = false;
        t.minimumTick = Math.max(t.window.nextRunTick(), Math.addExact(tick, delay));
        t.nextTick = t.minimumTick;
    }

    private Type select(State s, long tick) {
        if (s.blockedUntilTick > tick) return null;
        for (int i = 0; i < s.types.length; i++) {
            int at = (s.typeCursor + i) % s.types.length;
            Type t = s.types[at];
            if (s.config.policy().scope().includes(t.type) && t.nextTick <= tick) {
                s.typeCursor = (at + 1) % s.types.length;
                return t;
            }
        }
        return null;
    }

    private static long due(State s) {
        long next = Long.MAX_VALUE;
        for (Type t : s.types) if (s.config.policy().scope().includes(t.type)) next = Math.min(next, t.nextTick);
        return Math.max(next, s.blockedUntilTick);
    }

    private void checkThread() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Domain output accessed off owner thread");
    }

    private final class State {
        Configuration config;
        final Type[] types;
        int typeCursor, failures, breakerStage;
        long blockedUntilTick;
        boolean blocked;

        State(Configuration c) {
            config = c;
            types = new Type[DomainOutputScheduler.this.types.size()];
            for (int i = 0; i < types.length; i++) types[i] = new Type(DomainOutputScheduler.this.types.get(i));
        }
    }

    private static final class Type {
        final ResourceLocation type;
        final DomainTransferWindow window = new DomainTransferWindow();
        final Probe[] probes = new Probe[6];
        long nextTick, minimumTick, sequence, start, ceiling;
        long failureTick = -1;
        int faceCursor, emptyChecks;
        boolean round, wrapped, moved, roundProgress;

        @Nullable
        Variant pending;

        Type(ResourceLocation type) {
            this.type = type;
        }

        void reset() {
            round = false;
            pending = null;
            java.util.Arrays.fill(probes, null);
        }
    }

    private static final class Probe {
        final ResourceTransferEngine.Handle handle;
        int count = -1, view;
        boolean refresh = true;

        Probe(ResourceTransferEngine.Handle handle) {
            this.handle = handle;
        }
    }

    private static final class Match {
        final Object token;
        boolean done, allowed;

        @Nullable
        ResourceFilterCompiler.Evaluation evaluation;

        Match(Object token) {
            this.token = token;
        }
    }

    private static final class Variant {
        final ResourceVariant variant;
        final long sequence;
        final DomainOutputOrder.Cursor cursor;
        final Map<UUID, Match> matches = new HashMap<>();

        Variant(ResourceVariant variant, long sequence, DomainOutputOrder.Cursor cursor) {
            this.variant = variant;
            this.sequence = sequence;
            this.cursor = cursor;
        }
    }

    private final class Network {
        final UUID id;
        final List<State> outputs;
        final DomainOutputOrder order;
        final Map<ResourceVariantKey, Variant> variants = new HashMap<>();

        @Nullable
        DomainLedger ledger;

        @Nullable
        Variant current;

        boolean busy;
        long tick;
        ServerSettings settings = ServerSettings.defaults();

        Network(UUID id, List<State> outputs) {
            this.id = id;
            this.outputs = List.copyOf(outputs);
            List<DomainOutputOrder.Output> candidates = new ArrayList<>();
            for (State s : outputs)
                candidates.add(new DomainOutputOrder.Output() {
                    public UUID id() {
                        return s.config.nodeId();
                    }

                    public int priority() {
                        return s.config.policy().priority();
                    }

                    public DomainOutputOrder.Attempt attempt(ResourceVariant variant, TransferWorkBudget budget) {
                        return DomainOutputScheduler.this.attempt(Network.this, s, variant, budget);
                    }
                });
            order = new DomainOutputOrder(candidates);
        }

        void close() {
            if (ledger != null) ledger.onRetired(null);
            variants.clear();
        }
    }
}
