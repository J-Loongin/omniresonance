// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import io.github.loongin.omniresonance.config.ServerSettings;
import io.github.loongin.omniresonance.network.WorkingFaces;
import io.github.loongin.omniresonance.recovery.RecoveryBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-thread-owned greedy preparation and fair scheduling. State is bounded by authoritative configurations:
 * one input cursor, passive window and breaker per key, one sorted route per channel, one node index per node.
 * Replacements alone rebuild routes. Ordinary ticks never copy or scan configuration maps. No extracted resources
 * survive a call. Retention scans and their commit run uninterrupted; unfinished totals are discarded immediately.
 */
public final class ItemDirectScheduler {
    public record Key(UUID nodeId, UUID channelId) {}

    public record Configuration(
            UUID networkId,
            UUID nodeId,
            UUID channelId,
            long revision,
            ItemTransferPolicy policy,
            WorkingFaces workingFaces) {
        public Configuration(UUID networkId, UUID nodeId, UUID channelId, long revision, ItemTransferPolicy policy) {
            this(networkId, nodeId, channelId, revision, policy, WorkingFaces.attachedFace());
        }

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
    /** Server-thread authority boundary; resolve alone may discover a capability and must count that call. */
    public interface Environment {
        boolean active(Configuration config);

        boolean allows(Configuration config, ItemVariant variant);

        @Nullable
        ItemTransferEngine.Handle resolve(Configuration config, TransferWorkBudget budget);

        default int faceMask(Configuration config) {
            return config.workingFaces().effectiveMask(Direction.DOWN);
        }

        default @Nullable ItemTransferEngine.Handle resolve(
                Configuration config, Direction face, TransferWorkBudget budget) {
            return resolve(config, budget);
        }

        RecoveryBuffer recovery(UUID networkId);
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(ItemDirectScheduler.class);
    private final Environment environment;
    private final java.util.function.Function<ItemStack, ItemVariant> variants;
    private final ItemTransferEngine engine = new ItemTransferEngine();
    private final FairDueScheduler<Key> due = new FairDueScheduler<>();
    private final Map<Key, State> states = new HashMap<>();
    private final Map<UUID, Set<Key>> byNetwork = new HashMap<>(), byNode = new HashMap<>();
    private final Map<RouteKey, Set<Key>> inputRoutes = new HashMap<>();
    private final Map<RouteKey, List<Group>> routes = new HashMap<>();
    private final Map<UUID, Set<RouteKey>> networkRoutes = new HashMap<>();

    public ItemDirectScheduler(Environment environment, HolderLookup.Provider provider) {
        this(environment, stack -> ItemVariant.from(stack, provider));
    }

    ItemDirectScheduler(Environment environment, java.util.function.Function<ItemStack, ItemVariant> variants) {
        this.environment = environment;
        this.variants = variants;
    }
    /** Publishes immutable authority after a completed topology change; keeps quotas and breaker deadlines by identity. */
    public void replaceNetwork(UUID networkId, List<Configuration> configs, long currentTick) {
        Set<Key> previous = byNetwork.getOrDefault(networkId, Set.of());
        Set<Key> next = new HashSet<>();
        for (Configuration c : configs) {
            if (!c.networkId().equals(networkId)) throw new IllegalArgumentException("Cross-network configuration");
            Key key = c.key();
            next.add(key);
            State s = states.get(key);
            if (s != null
                    && (!s.config.networkId().equals(networkId)
                            || s.config.policy().direction() != c.policy().direction())) {
                remove(key);
                s = null;
            }
            boolean faceOnlyEdit = s != null
                    && s.config.policy().equals(c.policy())
                    && !s.config.workingFaces().equals(c.workingFaces());
            long retainedRunTick = faceOnlyEdit ? s.nextRunTick : currentTick;
            if (s == null) {
                s = new State(c);
                states.put(key, s);
                byNode.computeIfAbsent(c.nodeId(), ignored -> new HashSet<>()).add(key);
            } else {
                s.config = c;
                s.resetPreparation();
                if (!faceOnlyEdit) s.emptyChecks = 0;
            }
            if (c.policy() instanceof ItemTransferPolicy.Input) {
                inputRoutes
                        .computeIfAbsent(new RouteKey(networkId, c.channelId()), ignored -> new HashSet<>())
                        .add(key);
                s.nextRunTick = Math.max(
                        Math.max(currentTick, retainedRunTick), Math.max(s.minimumRunTick, s.blockedUntilTick));
                due.schedule(key, networkId, s.nextRunTick);
            }
        }
        for (Key key : previous)
            if (!next.contains(key)
                    && states.get(key) != null
                    && states.get(key).config.networkId().equals(networkId)) remove(key);
        if (next.isEmpty()) byNetwork.remove(networkId);
        else byNetwork.put(networkId, next);
        Set<RouteKey> oldRoutes = networkRoutes.remove(networkId);
        if (oldRoutes != null) for (RouteKey key : oldRoutes) routes.remove(key);
        Map<RouteKey, List<State>> outputs = new HashMap<>();
        for (Key key : next) {
            State s = states.get(key);
            if (s.config.policy() instanceof ItemTransferPolicy.Output)
                outputs.computeIfAbsent(new RouteKey(networkId, s.config.channelId()), ignored -> new ArrayList<>())
                        .add(s);
        }
        Set<RouteKey> routeKeys = new HashSet<>();
        for (var entry : outputs.entrySet()) {
            List<State> sorted = entry.getValue();
            sorted.sort(Comparator.<State>comparingInt(s -> ((ItemTransferPolicy.Output) s.config.policy()).priority())
                    .reversed()
                    .thenComparing(s -> s.config.nodeId()));
            List<Group> groups = new ArrayList<>();
            Group group = null;
            int priority = 0;
            for (State s : sorted) {
                int p = ((ItemTransferPolicy.Output) s.config.policy()).priority();
                if (group == null || p != priority) {
                    group = new Group();
                    groups.add(group);
                    priority = p;
                }
                group.outputs.add(s);
            }
            routes.put(entry.getKey(), groups);
            routeKeys.add(entry.getKey());
        }
        if (!routeKeys.isEmpty()) networkRoutes.put(networkId, routeKeys);
    }
    /** Explicit events discard preparation and idle backoff but retain active quotas and unexpired fault backoff. */
    public void wakeNode(UUID nodeId, long currentTick) {
        Set<Key> keys = byNode.get(nodeId);
        if (keys == null) return;
        for (Key key : keys) {
            State s = states.get(key);
            if (s.config.policy() instanceof ItemTransferPolicy.Input) wake(s, currentTick);
            else {
                Set<Key> inputs = inputRoutes.get(new RouteKey(s.config.networkId(), s.config.channelId()));
                if (inputs != null) for (Key input : inputs) wake(states.get(input), currentTick);
            }
        }
    }

    private void wake(State s, long tick) {
        s.resetPreparation();
        s.emptyChecks = 0;
        s.nextRunTick = Math.max(tick, Math.max(s.minimumRunTick, s.blockedUntilTick));
        due.schedule(s.config.key(), s.config.networkId(), s.nextRunTick);
    }

    /** Advances admitted work on the owning server thread; all known extracted resources settle synchronously. */
    public void tick(long currentTick, ServerSettings settings, TransferWorkBudget budget) {
        while (budget.canStart()) {
            Key key = due.poll(currentTick, budget::canStart);
            if (key == null) break;
            State s = states.get(key);
            if (s == null) continue;
            if (!budget.canStart()) {
                due.schedule(key, s.config.networkId(), currentTick);
                break;
            }
            long slowBefore = budget.slowCalls();
            try {
                step(s, currentTick, settings, budget, slowBefore);
            } catch (RuntimeException failure) {
                finish(s, currentTick, settings, true, failure);
            }
            if (budget.slowCalls() > slowBefore && s.status != Status.FAILED) finish(s, currentTick, settings, true);
            if (s.nextRunTick <= currentTick && s.status != Status.FAILED && s.status != Status.BLOCKED) {
                if (!budget.canStart()) s.status = Status.WAITING_BUDGET;
                due.schedule(key, s.config.networkId(), s.retentionWaiting ? currentTick + 1 : currentTick);
            }
        }
    }

    /** Read-only status lookup; never starts work, refreshes a cache or changes a scheduling cursor. */
    public Status status(UUID nodeId, UUID channelId) {
        State s = states.get(new Key(nodeId, channelId));
        return s == null ? Status.IDLE : s.status;
    }

    /** Releases every transient configuration, routing, heap and endpoint reference on the server thread. */
    public void close() {
        due.clear();
        states.clear();
        byNetwork.clear();
        byNode.clear();
        routes.clear();
        networkRoutes.clear();
        inputRoutes.clear();
    }

    private void step(State s, long tick, ServerSettings settings, TransferWorkBudget b, long slowBefore) {
        ItemTransferPolicy.Input policy = (ItemTransferPolicy.Input) s.config.policy();
        if (s.blockedUntilTick > tick) {
            due.schedule(s.config.key(), s.config.networkId(), s.blockedUntilTick);
            return;
        }
        if (!environment.active(s.config)) {
            s.resetPreparation();
            s.status = Status.BLOCKED;
            s.nextRunTick = tick + policy.intervalTicks();
            due.schedule(s.config.key(), s.config.networkId(), s.nextRunTick);
            return;
        }
        s.status = Status.RUNNING;
        s.retentionWaiting = false;
        if (!s.open) {
            s.open = true;
            s.spent = 0;
            s.windowMoved = false;
            s.sourceSlot = 0;
        }
        if (s.spent >= policy.rate()) {
            finish(s, tick, settings, b.slowCalls() > slowBefore);
            return;
        }
        if (s.source != null && !s.source.valid()) s.resetPreparation();
        switch (s.stage) {
            case SOURCE_CAPABILITY -> {
                int mask = environment.faceMask(s.config) & ~s.sourceFacesDone;
                s.sourceFace = nextFace(mask, s.faceCursor);
                if (s.sourceFace < 0) {
                    finish(s, tick, settings, b.slowCalls() > slowBefore);
                    return;
                }
                s.source = environment.resolve(s.config, Direction.from3DDataValue(s.sourceFace), b);
                if (s.source == null) {
                    advanceSource(s);
                    return;
                }
                s.stage = Stage.SOURCE_SLOTS;
            }
            case SOURCE_SLOTS -> {
                s.sourceSlots = ItemHandlerCalls.slots(s.source.handler(), b);
                s.stage = Stage.SOURCE_CANDIDATE;
            }
            case SOURCE_CANDIDATE -> {
                if (s.sourceSlot >= s.sourceSlots) {
                    advanceSource(s);
                    return;
                }
                ItemStack stack = ItemHandlerCalls.peek(s.source.handler(), s.sourceSlot, b);
                if (stack.isEmpty()) {
                    s.sourceSlot++;
                    return;
                }
                if (s.identity == null || !ItemStack.isSameItemSameComponents(s.identity, stack)) {
                    s.variant = variants.apply(stack);
                    s.identity = s.variant.stack(1);
                }
                if (!environment.allows(s.config, s.variant)) {
                    s.sourceSlot++;
                    return;
                }
                s.candidateAmount = Math.min(stack.getCount(), policy.rate() - s.spent);
                s.groupIndex = 0;
                s.groupOffset = 0;
                s.groupStart = -1;
                s.targetFacesTried = 0;
                s.stage = Stage.TARGET_CAPABILITY;
            }
            case TARGET_CAPABILITY -> selectTarget(s, tick, b);
            case TARGET_SLOTS -> {
                if (!targetCurrent(s)) {
                    s.stage = Stage.TARGET_CAPABILITY;
                    return;
                }
                s.targetSlots = ItemHandlerCalls.slots(s.target.handler(), b);
                s.targetSlot = 0;
                s.stage = Stage.TARGET_SIMULATION;
            }
            case TARGET_SIMULATION -> {
                if (!targetCurrent(s)) {
                    s.stage = Stage.TARGET_CAPABILITY;
                    return;
                }
                if (s.targetSlot >= s.targetSlots) {
                    s.stage = Stage.TARGET_CAPABILITY;
                    return;
                }
                long available = available(s.output, tick);
                if (available == 0) {
                    s.stage = Stage.TARGET_CAPABILITY;
                    return;
                }
                int amount = (int) Math.min(s.candidateAmount, available);
                ItemStack request = s.variant.stack(amount);
                ItemStack remainder = ItemHandlerCalls.insert(s.target.handler(), s.targetSlot, request, true, b);
                if (!ItemHandlerCalls.validAmount(remainder, request, amount)) {
                    finish(s, tick, settings, true);
                    return;
                }
                s.preparedAmount = amount - remainder.getCount();
                if (s.preparedAmount == 0) {
                    s.targetSlot++;
                    return;
                }
                s.stage = Stage.COMMIT;
            }
            case COMMIT -> commit(s, tick, settings, b, policy, slowBefore);
            default -> throw new IllegalStateException("Unknown preparation stage");
        }
    }

    private void selectTarget(State s, long tick, TransferWorkBudget b) {
        List<Group> groups = routes.get(new RouteKey(s.config.networkId(), s.config.channelId()));
        if (groups == null || s.groupIndex >= groups.size()) {
            s.stage = Stage.SOURCE_CANDIDATE;
            s.sourceSlot++;
            return;
        }
        Group g = groups.get(s.groupIndex);
        if (s.groupStart < 0) s.groupStart = g.cursor;
        if (s.groupOffset >= g.outputs.size()) {
            s.groupIndex++;
            s.groupOffset = 0;
            s.groupStart = -1;
            return;
        }
        int index = (s.groupStart + s.groupOffset) % g.outputs.size();
        State output = g.outputs.get(index);
        if (output.config.nodeId().equals(s.config.nodeId())
                || !environment.active(output.config)
                || !environment.allows(output.config, s.variant)
                || available(output, tick) == 0) {
            s.groupOffset++;
            s.targetFacesTried = 0;
            return;
        }
        int face = nextFace(environment.faceMask(output.config) & ~s.targetFacesTried, output.faceCursor);
        if (face < 0) {
            s.groupOffset++;
            s.targetFacesTried = 0;
            return;
        }
        s.targetFacesTried |= 1 << face;
        ItemTransferEngine.Handle target = environment.resolve(output.config, Direction.from3DDataValue(face), b);
        if (target == null
                || (target.handler() == s.source.handler()
                        || target.physicalIdentity().equals(s.source.physicalIdentity()))) return;
        output.faceCursor = (face + 1) % 6;
        s.target = target;
        s.output = output;
        s.selectedGroup = g;
        s.selectedIndex = index;
        g.cursor = (index + 1) % g.outputs.size();
        s.stage = Stage.TARGET_SLOTS;
    }

    private void commit(
            State s,
            long tick,
            ServerSettings settings,
            TransferWorkBudget b,
            ItemTransferPolicy.Input policy,
            long slowBefore) {
        if (!targetCurrent(s)
                || !environment.allows(s.config, s.variant)
                || !environment.allows(s.output.config, s.variant)) {
            s.stage = Stage.SOURCE_CANDIDATE;
            return;
        }
        long limit = Math.min(s.preparedAmount, Math.min(policy.rate() - s.spent, available(s.output, tick)));
        if (policy.keepCount() > 0) {
            // There is no generic inventory revision. Never retain this total or yield to another input before commit.
            if (!b.canStart()) {
                deferRetention(s);
                return;
            }
            int slots = ItemHandlerCalls.slots(s.source.handler(), b);
            long total = 0;
            ItemStack identity = s.variant.stack(1);
            for (int slot = 0; slot < slots; slot++) {
                if (!b.canStart()) {
                    deferRetention(s);
                    return;
                }
                ItemStack stack = ItemHandlerCalls.peek(s.source.handler(), slot, b);
                if (ItemStack.isSameItemSameComponents(stack, identity)) total = Math.addExact(total, stack.getCount());
            }
            if (!b.canStart()) {
                deferRetention(s);
                return;
            }
            limit = Math.min(limit, Math.max(0, total - policy.keepCount()));
        }
        if (limit <= 0) {
            s.stage = Stage.SOURCE_CANDIDATE;
            s.sourceSlot++;
            return;
        }
        ItemTransferEngine.Result result = engine.commit(
                new GuardedHandle(s.source, s.config, s.variant),
                s.sourceSlot,
                new GuardedHandle(s.target, s.output.config, s.variant),
                s.targetSlot,
                s.variant,
                limit,
                environment.recovery(s.config.networkId()),
                settings.recoveryLimits(),
                b);
        s.spent = Math.addExact(s.spent, result.removed());
        if (result.moved() > 0) {
            ItemTransferPolicy.Output outputPolicy = (ItemTransferPolicy.Output) s.output.config.policy();
            s.output.window.received(tick, result.moved(), outputPolicy.rate(), outputPolicy.intervalTicks());
            s.windowMoved = true;
        }
        if (result.failure() != ItemTransferEngine.Failure.NONE
                && result.failure() != ItemTransferEngine.Failure.REFUSED) {
            finish(s, tick, settings, true, result.cause());
            return;
        }
        if (s.spent >= policy.rate()) {
            finish(s, tick, settings, b.slowCalls() > slowBefore);
            return;
        }
        if (result.removed() == 0) {
            s.targetSlot++;
            s.stage = Stage.TARGET_SIMULATION;
        } else {
            s.stage = Stage.SOURCE_CANDIDATE;
        }
    }

    private void deferRetention(State s) {
        s.retentionWaiting = true;
        s.status = Status.WAITING_BUDGET;
        // Even the last deferred face must end a multi-face round, so replenished earlier faces can run next window.
        // A lone face retains its prepared candidate and may commit when a later tick has enough scan budget.
        int otherFaces = environment.faceMask(s.config) & ~(1 << s.sourceFace);
        if (otherFaces != 0) {
            // Only skipped faces remain unverified at round end; a single face retries before it can finish.
            s.retentionSkipped = true;
            advanceSource(s);
        }
    }

    private boolean targetCurrent(State s) {
        return s.target != null && s.target.valid() && environment.active(s.output.config);
    }

    private static long available(State s, long tick) {
        ItemTransferPolicy.Output p = (ItemTransferPolicy.Output) s.config.policy();
        return s.window.available(tick, p.rate(), p.intervalTicks());
    }

    private void finish(State s, long tick, ServerSettings settings, boolean failed) {
        finish(s, tick, settings, failed, null);
    }

    private void finish(State s, long tick, ServerSettings settings, boolean failed, @Nullable RuntimeException cause) {
        ServerSettings.Scheduler cfg = settings.scheduler();
        long delay = s.config.policy().intervalTicks();
        if (failed) {
            s.failures++;
            s.status = Status.FAILED;
            if (tick >= s.nextDiagnosticTick) {
                LOGGER.error(
                        "Item direct configuration failed; applying bounded retry backoff (node={}, channel={})",
                        s.config.nodeId(),
                        s.config.channelId(),
                        cause);
                s.nextDiagnosticTick = tick + cfg.breakerBackoffTicks().getFirst();
            }
            if (s.failures >= cfg.failureThreshold()) {
                int stage = Math.min(s.breakerStage, cfg.breakerBackoffTicks().size() - 1);
                delay = Math.max(delay, cfg.breakerBackoffTicks().get(stage));
                s.blockedUntilTick = tick + delay;
                s.breakerStage = Math.min(stage + 1, cfg.breakerBackoffTicks().size() - 1);
            }
        } else {
            s.failures = 0;
            s.breakerStage = 0;
            s.blockedUntilTick = 0;
            boolean waitingRetention =
                    s.retentionSkipped && s.spent < s.config.policy().rate();
            s.status = waitingRetention ? Status.WAITING_BUDGET : Status.IDLE;
            if (s.windowMoved) s.emptyChecks = 0;
            else if (!waitingRetention && s.emptyChecks < Integer.MAX_VALUE) s.emptyChecks++;
            if (!waitingRetention && s.emptyChecks >= cfg.emptyChecksBeforeSleep()) {
                int stage = Math.min(
                        s.emptyChecks - cfg.emptyChecksBeforeSleep(),
                        cfg.idleBackoffTicks().size() - 1);
                delay = Math.max(delay, cfg.idleBackoffTicks().get(stage));
            }
        }
        s.open = false;
        if (s.sourceFace >= 0) s.faceCursor = (s.sourceFace + 1) % 6;
        s.resetPreparation();
        s.minimumRunTick = tick + s.config.policy().intervalTicks();
        s.nextRunTick = tick + delay;
        due.schedule(s.config.key(), s.config.networkId(), s.nextRunTick);
    }

    private static int nextFace(int mask, int start) {
        for (int offset = 0; offset < 6; offset++) {
            int face = (start + offset) % 6;
            if ((mask & (1 << face)) != 0) return face;
        }
        return -1;
    }

    private static void advanceSource(State s) {
        s.sourceFacesDone |= 1 << s.sourceFace;
        s.faceCursor = (s.sourceFace + 1) % 6;
        s.source = null;
        s.sourceSlot = 0;
        s.identity = null;
        s.variant = null;
        s.stage = Stage.SOURCE_CAPABILITY;
    }

    private void remove(Key key) {
        State s = states.remove(key);
        due.remove(key);
        if (s == null) return;
        RouteKey route = new RouteKey(s.config.networkId(), s.config.channelId());
        Set<Key> inputs = inputRoutes.get(route);
        if (inputs != null) {
            inputs.remove(key);
            if (inputs.isEmpty()) inputRoutes.remove(route);
        }
        Set<Key> keys = byNode.get(key.nodeId());
        keys.remove(key);
        if (keys.isEmpty()) byNode.remove(key.nodeId());
    }

    private record RouteKey(UUID networkId, UUID channelId) {}

    private static final class Group {
        final List<State> outputs = new ArrayList<>();
        int cursor;
    }

    private final class GuardedHandle implements ItemTransferEngine.Handle {
        private final ItemTransferEngine.Handle delegate;
        private final Configuration config;
        private final ItemVariant variant;

        GuardedHandle(ItemTransferEngine.Handle delegate, Configuration config, ItemVariant variant) {
            this.delegate = delegate;
            this.config = config;
            this.variant = variant;
        }

        public net.neoforged.neoforge.items.IItemHandler handler() {
            return delegate.handler();
        }

        public Object physicalIdentity() {
            return delegate.physicalIdentity();
        }

        public boolean valid() {
            return delegate.valid() && environment.active(config) && environment.allows(config, variant);
        }
    }

    private enum Stage {
        SOURCE_CAPABILITY,
        SOURCE_SLOTS,
        SOURCE_CANDIDATE,
        TARGET_CAPABILITY,
        TARGET_SLOTS,
        TARGET_SIMULATION,
        COMMIT
    }

    private static final class State {
        private Stage stage = Stage.SOURCE_CAPABILITY;
        Configuration config;
        final ReceiveWindow window = new ReceiveWindow();
        Status status = Status.IDLE;
        long nextDiagnosticTick = Long.MIN_VALUE;
        long spent, nextRunTick, minimumRunTick, blockedUntilTick, candidateAmount, preparedAmount;
        int failures,
                breakerStage,
                emptyChecks,
                sourceSlot,
                sourceSlots,
                targetSlot,
                targetSlots,
                groupIndex,
                groupOffset,
                groupStart,
                selectedIndex;
        int faceCursor, sourceFacesDone, targetFacesTried;
        int sourceFace = -1;
        boolean open, windowMoved, retentionWaiting, retentionSkipped;
        ItemTransferEngine.Handle source, target;
        ItemVariant variant;
        ItemStack identity;
        State output;
        Group selectedGroup;

        State(Configuration config) {
            this.config = config;
        }

        void resetPreparation() {
            stage = Stage.SOURCE_CAPABILITY;
            sourceSlot = 0;
            sourceFacesDone = 0;
            sourceFace = -1;
            targetFacesTried = 0;
            source = null;
            target = null;
            variant = null;
            identity = null;
            output = null;
            selectedGroup = null;
            retentionWaiting = false;
            retentionSkipped = false;
        }
    }
}
