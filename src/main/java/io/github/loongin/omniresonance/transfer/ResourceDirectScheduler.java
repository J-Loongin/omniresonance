// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import io.github.loongin.omniresonance.config.ServerSettings;
import io.github.loongin.omniresonance.filter.ResourceFilterCompiler;
import io.github.loongin.omniresonance.network.WorkingFaces;
import io.github.loongin.omniresonance.recovery.RecoveryBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Server-thread-owned multi-resource scheduling. Only configuration events rebuild routes. Each outer fair
 * entry owns its resource cursors and passive windows; no extracted resource survives a synchronous engine call.
 * Endpoint and matching state is bounded by registered types and discarded on authority/version changes. */
public final class ResourceDirectScheduler {
    private sealed interface WorkKey permits Key, RecoveryKey, DomainKey {}

    private record DomainKey(UUID nodeId) implements WorkKey {}

    public record Key(UUID nodeId, UUID channelId) implements WorkKey {}

    private record RecoveryKey(UUID networkId) implements WorkKey {}

    public record Configuration(
            UUID networkId,
            UUID nodeId,
            UUID channelId,
            long revision,
            ResourceTransferPolicy policy,
            WorkingFaces workingFaces) {
        public Key key() {
            return new Key(nodeId, channelId);
        }
    }

    public enum Status {
        IDLE,
        RUNNING,
        WAITING_BUDGET,
        BLOCKED,
        FAILED
    }
    /** Immutable compilation publication. A null compiled value means preparation is pending, never permission. */
    public record FilterView(Object token, @Nullable ResourceFilterCompiler.Compiled compiled) {
        public FilterView {
            Objects.requireNonNull(token);
        }
    }
    /** Server-thread authority boundary. Metadata/token lookups must be constant-time and perform no world scan.
     * Only resolve discovers capabilities, counting all native work against budget. Returned ports/identities remain
     * stable. Registered types are immutable and stable for this environment lifetime. Preparation is pure,
     * bounded by workUnits, and returns the actual progress made; zero progress is deferred to a later tick. */
    public interface Environment {
        default @Nullable TransferTelemetry telemetry() {
            return null;
        }

        default @Nullable UUID domainNetwork(UUID nodeId) {
            return null;
        }

        default long advanceDomain(UUID nodeId, long tick, ServerSettings settings, TransferWorkBudget budget) {
            return Long.MAX_VALUE;
        }

        List<ResourceLocation> registeredTypes();

        boolean active(Configuration config);

        @Nullable
        ResourceTransferEngine.Handle resolve(
                Configuration config, ResourceLocation type, Direction face, TransferWorkBudget budget);

        RecoveryBuffer recovery(UUID networkId);

        /** Constant-time recovery eligibility; false must not inspect buffered entries or discover endpoints. */
        default boolean hasRecoveryWork(UUID networkId) {
            return false;
        }

        /** Executes at most one buffered key on this server thread; no external capability calls. */
        default void advanceRecovery(UUID networkId, ServerSettings settings) {}

        default int faceMask(Configuration config) {
            return config.workingFaces().effectiveMask(Direction.DOWN);
        }

        default FilterView filter(Configuration config) {
            return new FilterView(config.revision(), null);
        }

        default Object filterToken(Configuration config) {
            return filter(config).token();
        }

        default int advanceFilter(Configuration config, int workUnits) {
            return 0;
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(ResourceDirectScheduler.class);
    private final Environment environment;
    private final List<ResourceLocation> types;
    private final Map<ResourceLocation, Integer> typeIndices = new HashMap<>();
    private final ResourceTransferEngine engine = new ResourceTransferEngine();
    private final FairDueScheduler<WorkKey> due = new FairDueScheduler<>();

    /** Updates one deduplicated recovery job without changing node windows, cursors or quotas. */
    public void wakeRecovery(UUID networkId, long tick) {
        RecoveryKey key = new RecoveryKey(Objects.requireNonNull(networkId));
        if (environment.hasRecoveryWork(networkId)) due.scheduleLowPriority(key, networkId, tick);
        else due.remove(key);
    }

    /** Publishes one domain job into the same network ring and tick budget as direct and recovery work. */
    public void scheduleDomain(UUID nodeId, @Nullable UUID networkId, long tick) {
        DomainKey key = new DomainKey(nodeId);
        if (networkId == null || tick == Long.MAX_VALUE) due.remove(key);
        else due.schedule(key, networkId, tick);
    }

    /** Returns -1 once a direct input is not due or has completed its current window; otherwise its next turn.
     * A budget-paused open window remains ahead of a domain input sharing the physical source. */
    public long directInputTurn(Key key, ResourceLocation type, long tick) {
        State state = states.get(key);
        Integer index = typeIndices.get(type);
        if (state == null
                || index == null
                || !(state.config.policy() instanceof ResourceTransferPolicy.Input)
                || !state.config.policy().scope().includes(type)
                || state.blockedUntilTick > tick
                || !environment.active(state.config)) return -1;
        TypeState resource = state.types[index];
        ResourceTransferPolicy.Input policy = (ResourceTransferPolicy.Input) state.config.policy();
        if (resource.open && policy.rate(type) - resource.spent < batch(policy, type)) return -1;
        if (resource.minimumRunTick > tick || resource.nextRunTick > tick && !resource.open) return -1;
        return Math.max(tick, resource.nextRunTick);
    }

    private final Map<Key, State> states = new HashMap<>();
    private final Map<UUID, Set<Key>> byNetwork = new HashMap<>(), byNode = new HashMap<>();
    private final Map<RouteKey, Set<Key>> inputRoutes = new HashMap<>();
    private final Map<RouteKey, List<Group>> routes = new HashMap<>();
    private final Map<UUID, Set<RouteKey>> networkRoutes = new HashMap<>();

    public ResourceDirectScheduler(Environment environment) {
        this.environment = Objects.requireNonNull(environment);
        types = List.copyOf(environment.registeredTypes());
        for (int index = 0; index < types.size(); index++) typeIndices.put(types.get(index), index);
        if (types.size() > ResourceScope.MAXIMUM_RESOURCE_TYPE_IDS || new HashSet<>(types).size() != types.size())
            throw new IllegalArgumentException("Invalid registered types");
        for (var type : types) ResourceScope.validateResourceTypeId(type);
    }
    /** Publishes authority on the owning thread without native work. Edits preserve type quotas and deadlines;
     * removal, network moves and direction changes release the old state, matching M2 lifecycle semantics. */
    public void replaceNetwork(UUID networkId, List<Configuration> configs, long currentTick) {
        Set<Key> previous = byNetwork.getOrDefault(networkId, Set.of()), next = new HashSet<>();
        for (Configuration c : configs) {
            if (!c.networkId().equals(networkId)) throw new IllegalArgumentException("Cross-network configuration");
            Key key = c.key();
            next.add(key);
            State s = states.get(key);
            if (s != null
                    && (!s.config.networkId().equals(networkId)
                            || s.config.policy().direction() != c.policy().direction())) {
                remove(key, currentTick);
                s = null;
            }
            boolean faceOnly = s != null
                    && s.config.policy().equals(c.policy())
                    && !s.config.workingFaces().equals(c.workingFaces());
            if (s == null) {
                s = new State(c, types);
                states.put(key, s);
                byNode.computeIfAbsent(c.nodeId(), x -> new HashSet<>()).add(key);
            } else {
                s.config = c;
                for (TypeState t : s.types) {
                    t.resetPreparation();
                    t.nextRunTick = Math.max(faceOnly ? t.nextRunTick : currentTick, t.minimumRunTick);
                }
                if (!faceOnly) s.emptyChecks = 0;
            }
            if (c.policy() instanceof ResourceTransferPolicy.Input) {
                inputRoutes
                        .computeIfAbsent(new RouteKey(networkId, c.channelId()), x -> new HashSet<>())
                        .add(key);
                schedule(s, currentTick);
            }
        }
        for (Key key : previous)
            if (!next.contains(key)
                    && states.get(key) != null
                    && states.get(key).config.networkId().equals(networkId)) remove(key, currentTick);
        if (next.isEmpty()) byNetwork.remove(networkId);
        else byNetwork.put(networkId, next);
        Set<RouteKey> old = networkRoutes.remove(networkId);
        if (old != null) for (var key : old) routes.remove(key);
        Map<RouteKey, List<State>> outputs = new HashMap<>();
        for (Key key : next) {
            State s = states.get(key);
            if (s.config.policy() instanceof ResourceTransferPolicy.Output)
                outputs.computeIfAbsent(new RouteKey(networkId, s.config.channelId()), x -> new ArrayList<>())
                        .add(s);
        }
        Set<RouteKey> routeKeys = new HashSet<>();
        for (var entry : outputs.entrySet()) {
            List<State> sorted = entry.getValue();
            sorted.sort(
                    Comparator.<State>comparingInt(s -> ((ResourceTransferPolicy.Output) s.config.policy()).priority())
                            .reversed()
                            .thenComparing(s -> s.config.nodeId()));
            List<Group> groups = new ArrayList<>();
            Group g = null;
            int priority = 0;
            for (State s : sorted) {
                int p = ((ResourceTransferPolicy.Output) s.config.policy()).priority();
                if (g == null || p != priority) {
                    g = new Group(types.size());
                    groups.add(g);
                    priority = p;
                }
                g.outputs.add(s);
            }
            routes.put(entry.getKey(), groups);
            routeKeys.add(entry.getKey());
        }
        if (!routeKeys.isEmpty()) networkRoutes.put(networkId, routeKeys);
    }
    /** Event wakeup invalidates preparation and idle sleep, while retaining spent quota and minimum/fault deadlines. */
    public void wakeNode(UUID nodeId, long tick) {
        Set<Key> keys = byNode.get(nodeId);
        if (keys == null) return;
        for (Key key : keys) {
            State s = states.get(key);
            if (s.config.policy() instanceof ResourceTransferPolicy.Input) wake(s, tick);
            else {
                Set<Key> inputs = inputRoutes.get(new RouteKey(s.config.networkId(), s.config.channelId()));
                if (inputs != null) for (Key input : inputs) wake(states.get(input), tick);
            }
        }
    }

    private void wake(State s, long tick) {
        s.emptyChecks = 0;
        for (TypeState t : s.types) {
            t.resetPreparation();
            t.nextRunTick = Math.max(tick, t.minimumRunTick);
        }
        schedule(s, tick);
    }
    /** Advances one bounded preparation unit per outer fair turn; simulations never spend quota and engine calls
     * settle all known extracted amounts synchronously. Failures terminate the affected window and apply backoff. */
    public void tick(long tick, ServerSettings settings, TransferWorkBudget budget) {
        while (budget.canStart()) {
            WorkKey work = due.poll(tick, budget::canStart);
            if (work == null) break;
            var telemetry = environment.telemetry();
            UUID measuredNetwork = telemetry == null
                    ? null
                    : work instanceof DomainKey domain
                            ? environment.domainNetwork(domain.nodeId())
                            : work instanceof RecoveryKey recovery
                                    ? recovery.networkId()
                                    : states.containsKey((Key) work)
                                            ? states.get((Key) work).config.networkId()
                                            : null;
            long beforeCalls = telemetry == null ? 0 : budget.calls();
            long beforeNanos = telemetry == null ? 0 : budget.elapsedNanos();
            try {
                if (work instanceof DomainKey domain) {
                    UUID networkId = environment.domainNetwork(domain.nodeId());
                    if (networkId == null) continue;
                    if (!budget.canStart()) {
                        due.schedule(domain, networkId, tick);
                        break;
                    }
                    long nextTick = environment.advanceDomain(domain.nodeId(), tick, settings, budget);
                    scheduleDomain(domain.nodeId(), networkId, nextTick);
                    continue;
                }
                if (work instanceof RecoveryKey recovery) {
                    if (!budget.canStart()) {
                        due.scheduleLowPriority(recovery, recovery.networkId(), tick);
                        break;
                    }
                    if (environment.hasRecoveryWork(recovery.networkId())) {
                        environment.advanceRecovery(recovery.networkId(), settings);
                        if (environment.hasRecoveryWork(recovery.networkId())) {
                            due.scheduleLowPriority(recovery, recovery.networkId(), Math.addExact(tick, 1));
                        }
                    }
                    continue;
                }
                Key key = (Key) work;
                State s = states.get(key);
                if (s == null) continue;
                if (!budget.canStart()) {
                    due.schedule(key, s.config.networkId(), tick);
                    break;
                }
                TypeState t = selectType(s, tick);
                if (t == null) {
                    schedule(s, tick);
                    continue;
                }
                long slow = budget.slowCalls();
                try {
                    step(s, t, tick, settings, budget, slow);
                } catch (RuntimeException failure) {
                    finish(s, t, tick, settings, true, failure);
                }
                if (budget.slowCalls() > slow && t.status != Status.FAILED) finish(s, t, tick, settings, true);
                if (!budget.canStart() && t.nextRunTick <= tick) t.status = Status.WAITING_BUDGET;
                schedule(s, tick);
            } finally {
                if (telemetry != null && measuredNetwork != null)
                    telemetry.work(
                            measuredNetwork,
                            tick,
                            budget.calls() - beforeCalls,
                            Math.max(0, budget.elapsedNanos() - beforeNanos));
            }
        }
    }

    /** Metadata-only observer scan, O(network configurations × registered types); no probes, callbacks or queue changes. */
    public TransferTelemetry.QueueCounts queueCounts(UUID network, long tick, ServerSettings settings) {
        int dueCount = 0, backoff = 0;
        for (var key : byNetwork.getOrDefault(network, Set.of())) {
            State state = states.get(key);
            if (state == null || !(state.config.policy() instanceof ResourceTransferPolicy.Input)) continue;
            long next = Long.MAX_VALUE;
            for (var type : state.types)
                if (state.config.policy().scope().includes(type.type)) next = Math.min(next, type.nextRunTick);
            next = Math.max(next, state.blockedUntilTick);
            if (next <= tick) dueCount++;
            else if (state.failures > 0
                    || state.emptyChecks >= settings.scheduler().emptyChecksBeforeSleep()) backoff++;
        }
        return new TransferTelemetry.QueueCounts(dueCount, backoff);
    }

    /** Pure server-thread status lookup; does not simulate, discover endpoints or change cursors. */
    public Status status(UUID nodeId, UUID channelId) {
        State s = states.get(new Key(nodeId, channelId));
        if (s == null) return Status.IDLE;
        Status result = Status.IDLE;
        for (TypeState t : s.types)
            if (s.config.policy().scope().includes(t.type) && t.status.ordinal() > result.ordinal()) result = t.status;
        return result;
    }

    /** Releases server-thread-owned scheduling, routing, matching and endpoint references without native calls. */
    public void close() {
        due.clear();
        states.clear();
        byNetwork.clear();
        byNode.clear();
        inputRoutes.clear();
        routes.clear();
        networkRoutes.clear();
    }

    private TypeState selectType(State s, long tick) {
        if (s.blockedUntilTick > tick) return null;
        for (int offset = 0; offset < s.types.length; offset++) {
            int i = (s.typeCursor + offset) % s.types.length;
            TypeState t = s.types[i];
            if (s.config.policy().scope().includes(t.type) && t.nextRunTick <= tick) {
                s.typeCursor = (i + 1) % s.types.length;
                return t;
            }
        }
        return null;
    }

    private void schedule(State s, long tick) {
        long next = Long.MAX_VALUE;
        for (TypeState t : s.types)
            if (s.config.policy().scope().includes(t.type)) next = Math.min(next, t.nextRunTick);
        if (next == Long.MAX_VALUE) next = Math.addExact(tick, s.config.policy().intervalTicks());
        due.schedule(s.config.key(), s.config.networkId(), Math.max(next, s.blockedUntilTick));
    }

    private void step(State s, TypeState t, long tick, ServerSettings settings, TransferWorkBudget b, long slowBefore) {
        ResourceTransferPolicy.Input policy = (ResourceTransferPolicy.Input) s.config.policy();
        if (!environment.active(s.config)) {
            t.resetPreparation();
            t.status = Status.BLOCKED;
            t.nextRunTick = Math.addExact(tick, policy.intervalTicks());
            return;
        }
        t.status = Status.RUNNING;
        if (!t.open) {
            t.open = true;
            t.spent = 0;
            t.windowMoved = false;
        }
        if (t.spent >= policy.rate(t.type) || batch(policy, t.type) > policy.rate(t.type) - t.spent) {
            finish(s, t, tick, settings, false);
            return;
        }
        if (t.source != null && !t.source.valid()) t.resetPreparation();
        switch (t.stage) {
            case SOURCE_CAPABILITY -> {
                t.sourceFace = nextFace(environment.faceMask(s.config) & ~t.sourceFacesDone, t.faceCursor);
                if (t.sourceFace < 0) {
                    finish(s, t, tick, settings, false);
                    return;
                }
                t.source = environment.resolve(s.config, t.type, Direction.from3DDataValue(t.sourceFace), b);
                if (t.source == null) {
                    advanceSource(t);
                    return;
                }
                if (!t.source.port().typeId().equals(t.type))
                    throw new IllegalArgumentException("Wrong resource port type");
                t.stage = Stage.SOURCE_VIEWS;
            }
            case SOURCE_VIEWS -> {
                t.sourceViews = t.source.port().sourceViews(b);
                t.stage = Stage.SOURCE_CANDIDATE;
            }
            case SOURCE_CANDIDATE -> {
                if (t.sourceView >= t.sourceViews) {
                    advanceSource(t);
                    return;
                }
                var candidate = t.source.port().peek(t.sourceView, b);
                if (candidate.isEmpty()) {
                    t.sourceView++;
                    return;
                }
                t.variant = candidate.get().variant();
                if (exact(policy, t.type) && previouslyInsufficient(s, t, t.variant.key())) {
                    t.sourceView++;
                    return;
                }
                t.candidateAmount = Math.min(candidate.get().quantity(), policy.rate(t.type) - t.spent);
                t.sourceMatch = null;
                t.targetMatch = null;
                t.stage = Stage.SOURCE_FILTER;
            }
            case SOURCE_FILTER -> {
                t.sourceMatch = match(s, t.sourceMatch, t.variant, b);
                if (!matchingReady(t, t.sourceMatch, tick)) return;
                if (!t.sourceMatch.allowed) {
                    t.sourceView++;
                    t.stage = Stage.SOURCE_CANDIDATE;
                    return;
                }
                t.groupIndex = 0;
                t.groupOffset = 0;
                t.groupStart = -1;
                t.targetFacesTried = 0;
                if (exact(policy, t.type)) {
                    t.sourceHints.clear();
                    t.targetHints.clear();
                    t.hintLimit = (int) Math.max(1, (b.calls() + b.remainingCalls()) / 2);
                    t.discoveryView = t.sourceView;
                    t.discoveredAmount = 0;
                    t.stage = Stage.EXACT_SOURCE;
                } else t.stage = Stage.TARGET_SELECT;
            }
            case EXACT_SOURCE -> {
                if (!permitCurrent(s, t.sourceMatch)) {
                    t.clearInsufficient();
                    restartCandidate(t);
                    return;
                }
                if (t.discoveryView >= t.sourceViews || t.discoveredAmount >= batch(policy, t.type)) {
                    if (t.discoveredAmount < batch(policy, t.type)) {
                        t.rememberInsufficient(t.variant.key(), t.sourceMatch.token);
                        t.sourceView++;
                        t.stage = Stage.SOURCE_CANDIDATE;
                        return;
                    }
                    t.stage = Stage.TARGET_SELECT;
                    return;
                }
                if (t.sourceHints.size() >= t.hintLimit) {
                    defer(t, tick);
                    t.hintLimit = Math.max(t.hintLimit, (int) Math.max(1, (b.calls() + b.remainingCalls()) / 2));
                    return;
                }
                int view = t.discoveryView++;
                var observed = t.source.port().peek(view, b);
                if (observed.isPresent() && observed.get().variant().key().equals(t.variant.key())) {
                    t.sourceHints.add(view);
                    t.discoveredAmount =
                            Math.addExact(t.discoveredAmount, observed.get().quantity());
                }
            }
            case TARGET_SELECT -> selectTarget(s, t, tick);
            case TARGET_FILTER -> {
                if (!outputActive(t, tick)) {
                    t.stage = Stage.TARGET_SELECT;
                    return;
                }
                t.targetMatch = match(t.output, t.targetMatch, t.variant, b);
                if (!matchingReady(t, t.targetMatch, tick)) return;
                if (!t.targetMatch.allowed) {
                    t.groupOffset++;
                    t.targetFacesTried = 0;
                    t.stage = Stage.TARGET_SELECT;
                    return;
                }
                t.stage = Stage.TARGET_CAPABILITY;
            }
            case TARGET_CAPABILITY -> {
                if (!outputActive(t, tick)) {
                    t.stage = Stage.TARGET_SELECT;
                    return;
                }
                TypeState out = t.output.types[t.index];
                int face = nextFace(environment.faceMask(t.output.config) & ~t.targetFacesTried, out.faceCursor);
                if (face < 0) {
                    t.groupOffset++;
                    t.targetFacesTried = 0;
                    t.stage = Stage.TARGET_SELECT;
                    return;
                }
                t.targetFacesTried |= 1 << face;
                t.target = environment.resolve(t.output.config, t.type, Direction.from3DDataValue(face), b);
                if (t.target == null
                        || t.target.port() == t.source.port()
                        || t.target.physicalIdentity().equals(t.source.physicalIdentity())) return;
                if (!t.target.port().typeId().equals(t.type))
                    throw new IllegalArgumentException("Wrong resource port type");
                out.faceCursor = (face + 1) % 6;
                t.selectedGroup.cursors[t.index] = (t.selectedIndex + 1) % t.selectedGroup.outputs.size();
                t.stage = Stage.TARGET_VIEWS;
            }
            case TARGET_VIEWS -> {
                if (!targetCurrent(s, t, tick)) {
                    restartCandidate(t);
                    return;
                }
                t.targetViews = t.target.port().targetViews(b);
                t.targetView = 0;
                t.targetHints.clear();
                t.targetAmount = 0;
                t.stage = Stage.TARGET_SIMULATION;
            }
            case TARGET_SIMULATION -> {
                if (!targetCurrent(s, t, tick)) {
                    restartCandidate(t);
                    return;
                }
                if (t.targetView >= t.targetViews) {
                    t.stage = exact(policy, t.type) && t.targetAmount >= batch(policy, t.type)
                            ? Stage.COMMIT
                            : Stage.TARGET_CAPABILITY;
                    return;
                }
                if (exact(policy, t.type)) {
                    long desired = Math.min(
                            t.discoveredAmount,
                            Math.min(policy.rate(t.type) - t.spent, available(t.output, t.index, tick)));
                    desired = desired / batch(policy, t.type) * batch(policy, t.type);
                    if (desired == 0) {
                        t.stage = Stage.TARGET_CAPABILITY;
                        return;
                    }
                    if (t.targetAmount >= desired) {
                        t.stage = Stage.COMMIT;
                        return;
                    }
                    if (t.targetHints.size() >= t.hintLimit) {
                        if (t.targetAmount >= batch(policy, t.type)) {
                            t.stage = Stage.COMMIT;
                            return;
                        }
                        defer(t, tick);
                        t.hintLimit = Math.max(t.hintLimit, (int) Math.max(1, (b.calls() + b.remainingCalls()) / 2));
                        return;
                    }
                    long accepted = t.target.port().insert(t.targetView, t.variant, desired - t.targetAmount, true, b);
                    if (accepted > 0) {
                        t.targetHints.add(t.targetView);
                        t.targetAmount = Math.addExact(t.targetAmount, accepted);
                    }
                    t.targetView++;
                    if (t.targetAmount >= desired) t.stage = Stage.COMMIT;
                    return;
                }
                long amount = Math.min(t.candidateAmount, available(t.output, t.index, tick));
                t.preparedAmount = t.target.port().insert(t.targetView, t.variant, amount, true, b);
                if (t.preparedAmount == 0) {
                    t.targetView++;
                    return;
                }
                t.stage = Stage.COMMIT;
            }
            case COMMIT -> commit(s, t, tick, settings, b, slowBefore);
        }
    }

    private void selectTarget(State s, TypeState t, long tick) {
        List<Group> groups = routes.get(new RouteKey(s.config.networkId(), s.config.channelId()));
        if (groups == null || t.groupIndex >= groups.size()) {
            t.sourceView++;
            t.stage = Stage.SOURCE_CANDIDATE;
            return;
        }
        Group g = groups.get(t.groupIndex);
        if (t.groupStart < 0) t.groupStart = g.cursors[t.index];
        if (t.groupOffset >= g.outputs.size()) {
            t.groupIndex++;
            t.groupOffset = 0;
            t.groupStart = -1;
            return;
        }
        int index = (t.groupStart + t.groupOffset) % g.outputs.size();
        State out = g.outputs.get(index);
        if (states.get(out.config.key()) != out
                || out.config.nodeId().equals(s.config.nodeId())
                || !out.config.policy().scope().includes(t.type)
                || !environment.active(out.config)
                || available(out, t.index, tick) < batch((ResourceTransferPolicy.Input) s.config.policy(), t.type)) {
            t.groupOffset++;
            t.targetFacesTried = 0;
            return;
        }
        t.output = out;
        t.selectedGroup = g;
        t.selectedIndex = index;
        t.targetMatch = null;
        t.stage = Stage.TARGET_FILTER;
    }

    private void commit(
            State s, TypeState t, long tick, ServerSettings settings, TransferWorkBudget b, long slowBefore) {
        if (!targetCurrent(s, t, tick)) {
            restartCandidate(t);
            return;
        }
        ResourceTransferPolicy.Input p = (ResourceTransferPolicy.Input) s.config.policy();
        long limit = Math.min(t.preparedAmount, Math.min(p.rate(t.type) - t.spent, available(t.output, t.index, tick)));
        ResourceTransferEngine.Result result;
        if (exact(p, t.type)) {
            if (!b.canFit((long) t.sourceHints.size() + t.targetHints.size())) {
                defer(t, tick);
                return;
            }
            var candidate = engine.prepareExact(
                    new GuardedHandle(t.source, s, t.sourceMatch),
                    new GuardedHandle(t.target, t.output, t.targetMatch),
                    t.variant,
                    indices(t.sourceHints),
                    indices(t.targetHints),
                    b);
            result = engine.commitExact(
                    candidate,
                    p.rate(t.type) - t.spent,
                    available(t.output, t.index, tick),
                    p.keepCount(),
                    batch(p, t.type),
                    environment.recovery(s.config.networkId()),
                    settings.recoveryLimits(),
                    b);
        } else {
            if (p.keepCount() > 0) {
                int views = t.source.port().sourceViews(b);
                long total = 0;
                for (int view = 0; view < views; view++) {
                    if (!b.canStart()) {
                        deferRetention(s, t, tick);
                        return;
                    }
                    var observed = t.source.port().peek(view, b);
                    if (observed.isPresent() && observed.get().variant().key().equals(t.variant.key()))
                        total = Math.addExact(total, observed.get().quantity());
                }
                if (!b.canStart()) {
                    deferRetention(s, t, tick);
                    return;
                }
                limit = Math.min(limit, Math.max(0, total - p.keepCount()));
            }
            if (limit == 0) {
                t.sourceView++;
                restartCandidate(t);
                return;
            }
            result = engine.commitGreedy(
                    new GuardedHandle(t.source, s, t.sourceMatch),
                    t.sourceView,
                    new GuardedHandle(t.target, t.output, t.targetMatch),
                    t.targetView,
                    t.variant,
                    limit,
                    environment.recovery(s.config.networkId()),
                    settings.recoveryLimits(),
                    b);
        }
        if (result.failure() == ResourceTransferEngine.Failure.WAITING_BUDGET) {
            deferRetention(s, t, tick);
            return;
        }
        if (result.moved() > 0) {
            if (environment.telemetry() != null)
                environment.telemetry().moved(s.config.networkId(), t.type, tick, result.moved());
            var out = t.output.config.policy();
            t.output.types[t.index].window.received(tick, result.moved(), out.rate(t.type), out.intervalTicks());
            t.windowMoved = true;
        }
        if (result.unknownStage() == ResourceTransferEngine.Stage.TARGET_INSERT)
            t.output.types[t.index].window.quarantineUncertainTargetInsert(
                    tick, t.output.config.policy().intervalTicks());
        if (result.failure() == ResourceTransferEngine.Failure.UNKNOWN_MUTATION) {
            finish(
                    s,
                    t,
                    tick,
                    settings,
                    true,
                    result.cause(),
                    TransferIncident.reason(result.failure()),
                    result.unknownStage());
            return;
        }
        t.spent = Math.addExact(t.spent, result.removed());
        if (result.failure() != ResourceTransferEngine.Failure.NONE
                && result.failure() != ResourceTransferEngine.Failure.REFUSED) {
            finish(
                    s,
                    t,
                    tick,
                    settings,
                    true,
                    result.cause(),
                    TransferIncident.reason(result.failure()),
                    result.unknownStage());
            return;
        }
        if (t.spent >= p.rate(t.type)) {
            finish(s, t, tick, settings, b.slowCalls() > slowBefore);
            return;
        }
        if (result.removed() == 0) {
            if (exact(p, t.type)) {
                t.sourceView++;
                restartCandidate(t);
            } else {
                t.targetView++;
                t.stage = Stage.TARGET_SIMULATION;
            }
        } else restartCandidate(t);
    }

    private boolean previouslyInsufficient(State s, TypeState t, ResourceVariantKey key) {
        Object token = s.config.policy().filterPresetId() == null ? null : environment.filterToken(s.config);
        if (!Objects.equals(token, t.insufficientToken)) {
            t.clearInsufficient();
            t.insufficientToken = token;
        }
        return t.insufficientVariants.contains(key);
    }

    private static boolean exact(ResourceTransferPolicy.Input p, ResourceLocation type) {
        var override = p.resourcePolicyOverrides().get(type);
        return override != null && override.batchMode() == ResourceTransferPolicy.BatchMode.EXACT;
    }

    private static long batch(ResourceTransferPolicy.Input p, ResourceLocation type) {
        return exact(p, type) ? p.resourcePolicyOverrides().get(type).batchSize() : 1;
    }

    private static int[] indices(List<Integer> hints) {
        int[] result = new int[hints.size()];
        for (int i = 0; i < result.length; i++) result[i] = hints.get(i);
        return result;
    }

    private static void defer(TypeState t, long tick) {
        t.status = Status.WAITING_BUDGET;
        t.nextRunTick = Math.addExact(tick, 1);
    }

    private void deferRetention(State s, TypeState t, long tick) {
        defer(t, tick);
        if (((ResourceTransferPolicy.Input) s.config.policy()).keepCount() > 0
                && (environment.faceMask(s.config) & ~(1 << t.sourceFace)) != 0) {
            t.retentionSkipped = true;
            advanceSource(t);
        }
    }

    private Match match(State s, @Nullable Match previous, ResourceVariant variant, TransferWorkBudget b) {
        if (s.config.policy().filterPresetId() == null) return new Match(s.config, null, true);
        FilterView view = environment.filter(s.config);
        Match m = previous;
        if (m == null || m.config != s.config || !Objects.equals(m.token, view.token()))
            m = new Match(s.config, view.token(), false);
        if (m.done) return m;
        m.progress = 0;
        if (!b.canStart()) return m;
        if (view.compiled() == null) {
            m.progress = environment.advanceFilter(s.config, 256);
            if (m.progress < 0 || m.progress > 256)
                throw new IllegalStateException("Invalid filter preparation progress");
            return m;
        }
        Boolean fast = view.compiled()
                .fastDecision(variant.key().typeId(), s.config.policy().filterMode());
        if (fast != null) {
            m.done = true;
            m.allowed = fast;
            return m;
        }
        if (m.evaluation == null) {
            view.compiled().preparationCost(variant);
            if (!b.canStart()) return m;
            m.evaluation = view.compiled()
                    .evaluate(
                            view.compiled().prepareCandidate(variant),
                            s.config.policy().filterMode());
            m.progress = 1;
            if (!b.canStart()) return m;
        }
        m.progress = m.evaluation.step(256);
        if (m.evaluation.done()) {
            m.done = true;
            m.allowed = m.evaluation.allowed();
        }
        return m;
    }

    private static boolean matchingReady(TypeState t, Match m, long tick) {
        if (m.done) return true;
        if (m.progress == 0) {
            t.nextRunTick = Math.addExact(tick, 1);
            t.status = Status.WAITING_BUDGET;
        }
        return false;
    }

    private boolean outputActive(TypeState t, long tick) {
        return t.output != null
                && states.get(t.output.config.key()) == t.output
                && t.output.config.policy().scope().includes(t.type)
                && environment.active(t.output.config)
                && available(t.output, t.index, tick) > 0;
    }

    private boolean targetCurrent(State s, TypeState t, long tick) {
        return t.target != null
                && t.target.valid()
                && outputActive(t, tick)
                && permitCurrent(s, t.sourceMatch)
                && permitCurrent(t.output, t.targetMatch);
    }

    private boolean permitCurrent(State s, Match m) {
        return m != null
                && m.done
                && m.allowed
                && m.config == s.config
                && states.get(s.config.key()) == s
                && (m.token == null || Objects.equals(m.token, environment.filterToken(s.config)))
                && environment.active(s.config);
    }

    private static long available(State s, int index, long tick) {
        var p = s.config.policy();
        return s.types[index].window.available(tick, p.rate(s.types[index].type), p.intervalTicks());
    }

    private void finish(State s, TypeState t, long tick, ServerSettings settings, boolean failed) {
        finish(s, t, tick, settings, failed, null);
    }

    private void finish(
            State s,
            TypeState t,
            long tick,
            ServerSettings settings,
            boolean failed,
            @Nullable RuntimeException cause) {
        finish(
                s,
                t,
                tick,
                settings,
                failed,
                cause,
                cause == null ? TransferIncident.Reason.SLOW_CALL : TransferIncident.Reason.EXCEPTION,
                ResourceTransferEngine.Stage.NONE);
    }

    private void finish(
            State s,
            TypeState t,
            long tick,
            ServerSettings settings,
            boolean failed,
            @Nullable RuntimeException cause,
            TransferIncident.Reason reason,
            ResourceTransferEngine.Stage incidentStage) {
        var cfg = settings.scheduler();
        if (failed && tick >= s.nextDiagnosticTick) {
            if (reason == TransferIncident.Reason.SLOW_CALL)
                LOGGER.warn(
                        "Slow resource direct call; applying bounded retry backoff (node={}, channel={}, type={})",
                        s.config.nodeId(),
                        s.config.channelId(),
                        t.type);
            else {
                LOGGER.error(
                        "Resource direct configuration failed; applying bounded retry backoff (node={}, channel={}, type={}, reason={}, stage={})",
                        s.config.nodeId(),
                        s.config.channelId(),
                        t.type,
                        reason,
                        incidentStage,
                        cause);
            }
            s.nextDiagnosticTick = Math.addExact(tick, cfg.breakerBackoffTicks().getFirst());
        }
        long delay = s.config.policy().intervalTicks();
        if (failed) {
            s.failures++;
            t.status = Status.FAILED;
            if (environment.telemetry() != null)
                environment
                        .telemetry()
                        .failure(
                                s.config.networkId(),
                                tick,
                                "direct_transfer",
                                TransferIncident.of(
                                        s.config.nodeId(),
                                        t.output == null ? null : t.output.config.nodeId(),
                                        s.config.channelId(),
                                        t.type,
                                        reason,
                                        incidentStage));
            if (s.failures >= cfg.failureThreshold()) {
                int stage = Math.min(s.breakerStage, cfg.breakerBackoffTicks().size() - 1);
                delay = Math.max(delay, cfg.breakerBackoffTicks().get(stage));
                s.blockedUntilTick = Math.addExact(tick, delay);
                s.breakerStage = Math.min(stage + 1, cfg.breakerBackoffTicks().size() - 1);
            }
        } else {
            s.failures = 0;
            s.breakerStage = 0;
            s.blockedUntilTick = 0;
            t.status = t.retentionSkipped ? Status.WAITING_BUDGET : Status.IDLE;
            if (t.windowMoved) s.emptyChecks = 0;
            else if (!t.retentionSkipped && s.emptyChecks < Integer.MAX_VALUE) s.emptyChecks++;
            if (!t.retentionSkipped && s.emptyChecks >= cfg.emptyChecksBeforeSleep()) {
                int stage = Math.min(
                        s.emptyChecks - cfg.emptyChecksBeforeSleep(),
                        cfg.idleBackoffTicks().size() - 1);
                delay = Math.max(delay, cfg.idleBackoffTicks().get(stage));
            }
        }
        t.open = false;
        if (t.sourceFace >= 0) t.faceCursor = (t.sourceFace + 1) % 6;
        t.resetPreparation();
        t.minimumRunTick = Math.addExact(tick, s.config.policy().intervalTicks());
        t.nextRunTick = Math.addExact(tick, delay);
    }

    private static int nextFace(int mask, int start) {
        for (int offset = 0; offset < 6; offset++) {
            int face = (start + offset) % 6;
            if ((mask & (1 << face)) != 0) return face;
        }
        return -1;
    }

    private static void advanceSource(TypeState t) {
        t.clearInsufficient();
        t.sourceHints.clear();
        t.targetHints.clear();
        t.sourceFacesDone |= 1 << t.sourceFace;
        t.faceCursor = (t.sourceFace + 1) % 6;
        t.source = null;
        t.sourceView = 0;
        t.variant = null;
        t.sourceMatch = null;
        t.stage = Stage.SOURCE_CAPABILITY;
    }

    private static void restartCandidate(TypeState t) {
        t.sourceHints.clear();
        t.targetHints.clear();
        t.sourceMatch = null;
        t.targetMatch = null;
        t.stage = Stage.SOURCE_CANDIDATE;
    }

    private void remove(Key key, long tick) {
        State s = states.remove(key);
        due.remove(key);
        if (s == null) return;
        for (TypeState t : s.types) t.resetPreparation();
        RouteKey route = new RouteKey(s.config.networkId(), s.config.channelId());
        Set<Key> inputs = inputRoutes.get(route);
        if (inputs != null) {
            inputs.remove(key);
            if (inputs.isEmpty()) inputRoutes.remove(route);
        }
        if (s.config.policy() instanceof ResourceTransferPolicy.Output) {
            List<Group> groups = routes.get(route);
            if (groups != null) {
                for (Group g : groups) {
                    g.outputs.remove(s);
                    if (!g.outputs.isEmpty())
                        for (int i = 0; i < g.cursors.length; i++) g.cursors[i] %= g.outputs.size();
                }
                groups.removeIf(g -> g.outputs.isEmpty());
                if (groups.isEmpty()) routes.remove(route);
            }
            if (inputs != null) for (Key input : inputs) wake(states.get(input), tick);
        }
        Set<Key> keys = byNode.get(key.nodeId());
        keys.remove(key);
        if (keys.isEmpty()) byNode.remove(key.nodeId());
    }

    private record RouteKey(UUID networkId, UUID channelId) {}

    private static final class Group {
        final List<State> outputs = new ArrayList<>();
        final int[] cursors;

        Group(int types) {
            cursors = new int[types];
        }
    }

    private final class GuardedHandle implements ResourceTransferEngine.Handle {
        private final ResourceTransferEngine.Handle delegate;
        private final State state;
        private final Match permit;

        GuardedHandle(ResourceTransferEngine.Handle delegate, State state, Match permit) {
            this.delegate = delegate;
            this.state = state;
            this.permit = permit;
        }

        public ResourcePort port() {
            return delegate.port();
        }

        public Object physicalIdentity() {
            return delegate.physicalIdentity();
        }

        public boolean valid() {
            return delegate.valid() && permitCurrent(state, permit);
        }
    }

    private enum Stage {
        SOURCE_CAPABILITY,
        SOURCE_VIEWS,
        SOURCE_CANDIDATE,
        SOURCE_FILTER,
        EXACT_SOURCE,
        TARGET_SELECT,
        TARGET_FILTER,
        TARGET_CAPABILITY,
        TARGET_VIEWS,
        TARGET_SIMULATION,
        COMMIT
    }

    private static final class Match {
        final Configuration config;
        final Object token;
        boolean done, allowed;
        int progress;
        ResourceFilterCompiler.Evaluation evaluation;

        Match(Configuration config, Object token, boolean allowed) {
            this.config = config;
            this.token = token;
            this.done = allowed;
            this.allowed = allowed;
        }
    }

    private static final class State {
        Configuration config;
        final TypeState[] types;
        int typeCursor, failures, breakerStage, emptyChecks;
        long blockedUntilTick;
        long nextDiagnosticTick = Long.MIN_VALUE;

        State(Configuration config, List<ResourceLocation> registered) {
            this.config = config;
            types = new TypeState[registered.size()];
            for (int i = 0; i < types.length; i++) types[i] = new TypeState(registered.get(i), i);
        }
    }

    private static final class TypeState {
        final ResourceLocation type;
        final int index;
        final ReceiveWindow window = new ReceiveWindow();
        Stage stage = Stage.SOURCE_CAPABILITY;
        Status status = Status.IDLE;
        long spent, nextRunTick, minimumRunTick, candidateAmount, preparedAmount;
        int sourceView,
                sourceViews,
                targetView,
                targetViews,
                groupIndex,
                groupOffset,
                groupStart,
                selectedIndex,
                faceCursor,
                sourceFacesDone,
                targetFacesTried;
        int sourceFace = -1;
        // Index hints own no promises or extracted resources. Each list grows only after an admitted observed
        // position, up to half the largest tick-call capacity admitted during this candidate. Copies before
        // commit require current budget for their combined length; resets/variant or token changes clear both.
        final List<Integer> sourceHints = new ArrayList<>(), targetHints = new ArrayList<>();
        // Navigation-only exclusions after a complete insufficient suffix scan, never quantity promises.
        // FIFO entry capacity is at most the largest admitted hint limit of this source-face round; backing
        // storage has the same lifetime high-water bound. Eviction only permits redundant discovery, never a
        // transfer. Face/handle/configuration/round resets and filter-token changes release all retained keys.
        final LinkedHashSet<ResourceVariantKey> insufficientVariants = new LinkedHashSet<>();
        Object insufficientToken;
        int insufficientLimit;
        int discoveryView, hintLimit;
        long discoveredAmount, targetAmount;
        boolean open, windowMoved, retentionSkipped;
        ResourceTransferEngine.Handle source, target;
        ResourceVariant variant;
        State output;
        Group selectedGroup;
        Match sourceMatch, targetMatch;

        TypeState(ResourceLocation type, int index) {
            this.type = type;
            this.index = index;
        }

        void clearInsufficient() {
            insufficientVariants.clear();
            insufficientToken = null;
            insufficientLimit = 0;
        }

        void rememberInsufficient(ResourceVariantKey key, Object token) {
            if (!Objects.equals(insufficientToken, token)) {
                clearInsufficient();
                insufficientToken = token;
            }
            insufficientLimit = Math.max(insufficientLimit, hintLimit);
            if (insufficientVariants.contains(key)) return;
            if (insufficientVariants.size() >= insufficientLimit) insufficientVariants.removeFirst();
            insufficientVariants.add(key);
        }

        void resetPreparation() {
            clearInsufficient();
            sourceHints.clear();
            targetHints.clear();
            retentionSkipped = false;
            stage = Stage.SOURCE_CAPABILITY;
            sourceView = 0;
            sourceFacesDone = 0;
            sourceFace = -1;
            targetFacesTried = 0;
            source = null;
            target = null;
            variant = null;
            output = null;
            selectedGroup = null;
            sourceMatch = null;
            targetMatch = null;
        }
    }
}
