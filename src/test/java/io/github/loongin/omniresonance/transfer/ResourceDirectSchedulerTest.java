// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.config.ServerSettings;
import io.github.loongin.omniresonance.filter.FilterMode;
import io.github.loongin.omniresonance.network.WorkingFaces;
import io.github.loongin.omniresonance.recovery.RecoveryBuffer;
import io.github.loongin.omniresonance.transfer.fixtures.SchedulerResourcePort;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

final class ResourceDirectSchedulerTest {
    static final UUID NETWORK = new UUID(0, 1), CHANNEL = new UUID(0, 2);
    static final List<ResourceLocation> TYPES = List.of(ResourceTypes.ITEM, ResourceTypes.FLUID, ResourceTypes.ENERGY);

    static TransferWorkBudget budget(int calls) {
        return new TransferWorkBudget(calls, Long.MAX_VALUE, Long.MAX_VALUE, () -> 0);
    }

    static ResourceTransferPolicy.Input input(int rate, int interval) {
        Map<ResourceLocation, ResourceTransferPolicy.InputOverride> overrides = new HashMap<>();
        for (var type : TYPES)
            overrides.put(
                    type, new ResourceTransferPolicy.InputOverride(rate, ResourceTransferPolicy.BatchMode.GREEDY, 1));
        return new ResourceTransferPolicy.Input(
                interval, ResourceScope.all(), RedstoneCondition.IGNORE, null, FilterMode.WHITELIST, overrides, 0);
    }

    static ResourceTransferPolicy.Output output(int rate, int interval) {
        Map<ResourceLocation, ResourceTransferPolicy.OutputOverride> overrides = new HashMap<>();
        for (var type : TYPES) overrides.put(type, new ResourceTransferPolicy.OutputOverride(rate));
        return new ResourceTransferPolicy.Output(
                interval, ResourceScope.all(), RedstoneCondition.IGNORE, null, FilterMode.WHITELIST, overrides, 0);
    }

    static ResourceDirectScheduler.Configuration config(int node, ResourceTransferPolicy policy) {
        return new ResourceDirectScheduler.Configuration(
                NETWORK, new UUID(0, node), CHANNEL, 0, policy, WorkingFaces.attachedFace());
    }

    static final class Env implements ResourceDirectScheduler.Environment {
        TransferTelemetry observer;

        public TransferTelemetry telemetry() {
            return observer;
        }

        int filterSteps;
        long filterVersion;
        boolean filterPending;
        io.github.loongin.omniresonance.filter.ResourceFilterCompiler.Compiled compiled;
        final Map<String, SchedulerResourcePort> faces = new HashMap<>();
        final List<Direction> discoveredFaces = new ArrayList<>();
        final Set<UUID> inactive = new HashSet<>();
        java.util.concurrent.atomic.AtomicLong filterClock;
        int filterReads;
        io.github.loongin.omniresonance.filter.ResourceFilterCompiler.Preparation preparation;
        List<ResourceLocation> types = TYPES;
        final Map<UUID, Map<ResourceLocation, SchedulerResourcePort>> ports = new HashMap<>();
        final List<ResourceLocation> moves = new ArrayList<>();
        final RecoveryBuffer recovery = new RecoveryBuffer(() -> {});
        int resolutions;

        public List<ResourceLocation> registeredTypes() {
            return types;
        }

        public ResourceDirectScheduler.FilterView filter(ResourceDirectScheduler.Configuration c) {
            filterReads++;
            if (filterClock != null) filterClock.incrementAndGet();
            return new ResourceDirectScheduler.FilterView(filterVersion, filterPending ? null : compiled);
        }

        public Object filterToken(ResourceDirectScheduler.Configuration c) {
            return filterVersion;
        }

        public int advanceFilter(ResourceDirectScheduler.Configuration c, int units) {
            filterSteps++;
            if (preparation == null) return 0;
            int progress = preparation.step(units);
            if (preparation.done()) {
                compiled = preparation.result();
                filterPending = false;
            }
            return progress;
        }

        public boolean active(ResourceDirectScheduler.Configuration c) {
            return !inactive.contains(c.nodeId());
        }

        public ResourceTransferEngine.Handle resolve(
                ResourceDirectScheduler.Configuration c, ResourceLocation type, Direction face, TransferWorkBudget b) {
            resolutions++;
            discoveredFaces.add(face);
            b.beforeCall();
            b.afterCall();
            SchedulerResourcePort p = faces.getOrDefault(
                    c.nodeId() + ":" + type + ":" + face,
                    ports.getOrDefault(c.nodeId(), Map.of()).get(type));
            if (p != null) p.discoveries++;
            return p;
        }

        public RecoveryBuffer recovery(UUID id) {
            return recovery;
        }

        SchedulerResourcePort put(int node, ResourceLocation type, int size, long amount) {
            SchedulerResourcePort p = new SchedulerResourcePort(type, size, amount, moves);
            ports.computeIfAbsent(new UUID(0, node), x -> new HashMap<>()).put(type, p);
            return p;
        }
    }

    @Test
    void realSchedulerDistinguishesSlowCallsFromUncertainTargetMutation() {
        for (boolean slow : new boolean[] {true, false}) {
            Env env = new Env();
            env.types = List.of(ResourceTypes.ITEM);
            env.observer = new TransferTelemetry(TYPES);
            var source = env.put(3, ResourceTypes.ITEM, 1, 10);
            var target = env.put(4, ResourceTypes.ITEM, 1, 0);
            target.throwInsert = !slow;
            var scheduler = new ResourceDirectScheduler(env);
            scheduler.replaceNetwork(NETWORK, List.of(config(3, input(5, 10)), config(4, output(10, 10))), 0);
            var clock = new java.util.concurrent.atomic.AtomicLong();
            var budget = slow ? new TransferWorkBudget(1000, Long.MAX_VALUE, 1, clock::getAndIncrement) : budget(1000);
            scheduler.tick(0, ServerSettings.defaults(), budget);
            var incident = env.observer.snapshot(NETWORK, 0).incident();
            assertEquals(new UUID(0, 3), incident.node().id());
            assertEquals(CHANNEL, incident.channelId());
            assertEquals(ResourceTypes.ITEM, incident.type());
            assertEquals(
                    slow ? TransferIncident.Reason.SLOW_CALL : TransferIncident.Reason.UNKNOWN_MUTATION,
                    incident.reason());
            if (slow) {
                assertEquals(10, source.amounts[0]);
                assertEquals(0, target.amounts[0]);
            } else {
                assertEquals(new UUID(0, 4), incident.peer().id());
                assertEquals(ResourceTransferEngine.Stage.TARGET_INSERT, incident.stage());
            }
        }
    }

    @Test
    void threeTypesRoundRobinAndIndependentQuota() {
        Env e = new Env();
        for (var type : TYPES) {
            e.put(3, type, 1, 100);
            e.put(4, type, 1, 0);
        }
        var scheduler = new ResourceDirectScheduler(e);
        scheduler.replaceNetwork(NETWORK, List.of(config(3, input(7, 10)), config(4, output(100, 10))), 0);
        scheduler.tick(0, ServerSettings.defaults(), budget(1000));
        assertEquals(TYPES, e.moves);
        for (var type : TYPES) assertEquals(7, e.ports.get(new UUID(0, 4)).get(type).amounts[0]);
        scheduler.tick(9, ServerSettings.defaults(), budget(1000));
        assertEquals(3, e.moves.size());
        scheduler.tick(10, ServerSettings.defaults(), budget(1000));
        assertEquals(6, e.moves.size());
    }

    @Test
    void sharedOutputQuotaAndUnknownTargetQuarantine() {
        Env e = new Env();
        e.types = List.of(ResourceTypes.ITEM);
        e.put(3, ResourceTypes.ITEM, 1, 100);
        e.put(5, ResourceTypes.ITEM, 1, 100);
        SchedulerResourcePort target = e.put(4, ResourceTypes.ITEM, 1, 0);
        target.throwInsert = true;
        var scheduler = new ResourceDirectScheduler(e);
        scheduler.replaceNetwork(
                NETWORK, List.of(config(3, input(5, 10)), config(5, input(5, 10)), config(4, output(10, 10))), 0);
        scheduler.tick(0, ServerSettings.defaults(), budget(1000));
        assertEquals(5, target.amounts[0]);
        target.throwInsert = false;
        scheduler.tick(10, ServerSettings.defaults(), budget(1000));
        assertEquals(15, target.amounts[0]);
    }

    @Test
    void emptySourceDoesNotDiscoverTargets() {
        Env e = new Env();
        e.types = List.of(ResourceTypes.ITEM);
        e.put(3, ResourceTypes.ITEM, 1, 0);
        SchedulerResourcePort target = e.put(4, ResourceTypes.ITEM, 1, 0);
        var scheduler = new ResourceDirectScheduler(e);
        scheduler.replaceNetwork(NETWORK, List.of(config(3, input(5, 10)), config(4, output(10, 10))), 0);
        scheduler.tick(0, ServerSettings.defaults(), budget(1000));
        assertEquals(0, target.discoveries);
        assertEquals(1, e.resolutions);
    }

    static ResourceTransferPolicy.Input exact(int rate, long batch, long keep) {
        return new ResourceTransferPolicy.Input(
                10,
                ResourceScope.customSet(Set.of(ResourceTypes.ITEM)),
                RedstoneCondition.IGNORE,
                null,
                FilterMode.WHITELIST,
                Map.of(
                        ResourceTypes.ITEM,
                        new ResourceTransferPolicy.InputOverride(rate, ResourceTransferPolicy.BatchMode.EXACT, batch)),
                keep);
    }

    @Test
    void exactGreaterThanQuotaDoesNoCapabilityWork() {
        Env e = new Env();
        e.types = List.of(ResourceTypes.ITEM);
        e.put(3, ResourceTypes.ITEM, 1, 100);
        e.put(4, ResourceTypes.ITEM, 1, 0);
        var scheduler = new ResourceDirectScheduler(e);
        scheduler.replaceNetwork(NETWORK, List.of(config(3, exact(5, 6, 0)), config(4, output(100, 10))), 0);
        scheduler.tick(0, ServerSettings.defaults(), budget(100));
        assertEquals(0, e.resolutions);
    }

    @Test
    void exactHintsCrossTicksThenRecheckAndCommitSameTick() {
        Env e = new Env();
        e.types = List.of(ResourceTypes.ITEM);
        SchedulerResourcePort source = e.put(3, ResourceTypes.ITEM, 100000, 10);
        source.amounts[99999] = 10;
        SchedulerResourcePort target = e.put(4, ResourceTypes.ITEM, 100000, 0);
        target.viewCapacity = 10;
        java.util.Arrays.fill(target.amounts, 1, 99999, 10);
        var scheduler = new ResourceDirectScheduler(e);
        scheduler.replaceNetwork(NETWORK, List.of(config(3, exact(20, 20, 0)), config(4, output(100, 10))), 0);
        for (int tick = 0; tick < 14000; tick++) scheduler.tick(tick, ServerSettings.defaults(), budget(16));
        assertEquals(20, source.amounts[0] + source.amounts[99999]);
        assertEquals(ResourceDirectScheduler.Status.WAITING_BUDGET, scheduler.status(new UUID(0, 3), CHANNEL));
        var finalBudget = budget(40);
        scheduler.tick(14000, ServerSettings.defaults(), finalBudget);
        assertEquals(16, finalBudget.calls());
        assertEquals(20, target.amounts[0] + target.amounts[99999]);
    }

    @Test
    void greedyKeepWaitsForWholeSourceVerification() {
        Env e = new Env();
        e.types = List.of(ResourceTypes.ITEM);
        SchedulerResourcePort source = e.put(3, ResourceTypes.ITEM, 100, 20);
        SchedulerResourcePort target = e.put(4, ResourceTypes.ITEM, 1, 0);
        var p = new ResourceTransferPolicy.Input(
                10,
                ResourceScope.customSet(Set.of(ResourceTypes.ITEM)),
                RedstoneCondition.IGNORE,
                null,
                FilterMode.WHITELIST,
                Map.of(
                        ResourceTypes.ITEM,
                        new ResourceTransferPolicy.InputOverride(20, ResourceTransferPolicy.BatchMode.GREEDY, 1)),
                5);
        var scheduler = new ResourceDirectScheduler(e);
        scheduler.replaceNetwork(NETWORK, List.of(config(3, p), config(4, output(100, 10))), 0);
        for (int tick = 0; tick < 10; tick++) scheduler.tick(tick, ServerSettings.defaults(), budget(8));
        assertEquals(0, target.amounts[0]);
        scheduler.tick(10, ServerSettings.defaults(), budget(200));
        assertEquals(15, target.amounts[0]);
        assertEquals(5, source.amounts[0]);
    }

    @Test
    void allIncludesFourthRegisteredTypeAndCustomSkipsMissingTypes() {
        Env e = new Env();
        var fourth = ResourceLocation.parse("test:fourth");
        e.types = List.of(ResourceTypes.ITEM, ResourceTypes.FLUID, ResourceTypes.ENERGY, fourth);
        for (var type : e.types) {
            e.put(3, type, 1, 9);
            e.put(4, type, 1, 0);
        }
        var scheduler = new ResourceDirectScheduler(e);
        scheduler.replaceNetwork(NETWORK, List.of(config(3, input(7, 10)), config(4, output(100, 10))), 0);
        scheduler.tick(0, ServerSettings.defaults(), budget(1000));
        assertEquals(4, e.moves.size());
        assertEquals(9, e.ports.get(new UUID(0, 4)).get(fourth).amounts[0]);
        e = new Env();
        e.types = List.of(ResourceTypes.ITEM);
        e.put(3, ResourceTypes.ITEM, 1, 9);
        SchedulerResourcePort target = e.put(4, ResourceTypes.ITEM, 1, 0);
        var p = new ResourceTransferPolicy.Input(
                10,
                ResourceScope.customSet(Set.of(ResourceTypes.FLUID)),
                RedstoneCondition.IGNORE,
                null,
                FilterMode.WHITELIST,
                Map.of(),
                0);
        scheduler = new ResourceDirectScheduler(e);
        scheduler.replaceNetwork(NETWORK, List.of(config(3, p), config(4, output(100, 10))), 0);
        scheduler.tick(0, ServerSettings.defaults(), budget(1000));
        assertEquals(0, e.resolutions);
        assertEquals(0, target.amounts[0]);
    }

    @Test
    void oneCallBudgetGreedyProgressDoesNotRegrantQuota() {
        Env e = new Env();
        e.types = List.of(ResourceTypes.ITEM);
        e.put(3, ResourceTypes.ITEM, 2, 100);
        SchedulerResourcePort target = e.put(4, ResourceTypes.ITEM, 1, 0);
        var scheduler = new ResourceDirectScheduler(e);
        var configs = List.of(config(3, input(7, 1000)), config(4, output(100, 1000)));
        scheduler.replaceNetwork(NETWORK, configs, 0);
        for (int tick = 0; tick < 100; tick++) {
            scheduler.tick(tick, ServerSettings.defaults(), budget(1));
            if (tick == 50) {
                scheduler.replaceNetwork(NETWORK, configs, tick);
                scheduler.wakeNode(new UUID(0, 3), tick);
            }
        }
        assertEquals(7, target.amounts[0]);
    }

    @Test
    void exactBudgetWaitDoesNotBlockOtherTypesNewWindows() {
        Env e = new Env();
        e.types = List.of(ResourceTypes.ITEM, ResourceTypes.ENERGY);
        SchedulerResourcePort item = e.put(3, ResourceTypes.ITEM, 2, 10);
        item.amounts[1] = 10;
        SchedulerResourcePort itemTarget = e.put(4, ResourceTypes.ITEM, 2, 0);
        itemTarget.viewCapacity = 10;
        e.put(3, ResourceTypes.ENERGY, 1, 1000);
        SchedulerResourcePort energyTarget = e.put(4, ResourceTypes.ENERGY, 1, 0);
        var p = new ResourceTransferPolicy.Input(
                1,
                ResourceScope.all(),
                RedstoneCondition.IGNORE,
                null,
                FilterMode.WHITELIST,
                Map.of(
                        ResourceTypes.ITEM,
                        new ResourceTransferPolicy.InputOverride(20, ResourceTransferPolicy.BatchMode.EXACT, 20),
                        ResourceTypes.ENERGY,
                        new ResourceTransferPolicy.InputOverride(1, ResourceTransferPolicy.BatchMode.GREEDY, 1)),
                0);
        var scheduler = new ResourceDirectScheduler(e);
        scheduler.replaceNetwork(NETWORK, List.of(config(3, p), config(4, output(1000, 1))), 0);
        for (int tick = 0; tick < 30; tick++) scheduler.tick(tick, ServerSettings.defaults(), budget(16));
        assertEquals(0, itemTarget.amounts[0] + itemTarget.amounts[1]);
        assertTrue(energyTarget.amounts[0] > 2);
    }

    @Test
    void unknownSourceAndReturnDoNotQuarantineUntouchedTarget() {
        for (boolean extract : new boolean[] {true, false}) {
            Env e = new Env();
            e.types = List.of(ResourceTypes.ITEM);
            SchedulerResourcePort first = e.put(3, ResourceTypes.ITEM, 1, 10);
            SchedulerResourcePort second = e.put(5, ResourceTypes.ITEM, 1, 10);
            SchedulerResourcePort target = e.put(4, ResourceTypes.ITEM, 1, 0);
            first.throwExtract = extract;
            first.throwReturn = !extract;
            if (!extract) target.insertionLimit = 2;
            var scheduler = new ResourceDirectScheduler(e);
            scheduler.replaceNetwork(
                    NETWORK, List.of(config(3, input(5, 10)), config(5, input(5, 10)), config(4, output(10, 10))), 0);
            scheduler.tick(0, ServerSettings.defaults(), budget(1000));
            assertTrue(second.amounts[0] < 10);
            assertTrue(target.amounts[0] >= 2);
        }
    }

    @Test
    void outputWindowBeginsOnReceptionAndDoesNotExtend() {
        Env e = new Env();
        e.types = List.of(ResourceTypes.ITEM);
        SchedulerResourcePort source = e.put(3, ResourceTypes.ITEM, 1, 0);
        SchedulerResourcePort target = e.put(4, ResourceTypes.ITEM, 1, 0);
        var scheduler = new ResourceDirectScheduler(e);
        scheduler.replaceNetwork(NETWORK, List.of(config(3, input(4, 1)), config(4, output(10, 10))), 0);
        scheduler.tick(0, ServerSettings.defaults(), budget(1000));
        source.amounts[0] = 100;
        scheduler.wakeNode(new UUID(0, 3), 4);
        scheduler.tick(4, ServerSettings.defaults(), budget(1000));
        scheduler.tick(8, ServerSettings.defaults(), budget(1000));
        scheduler.tick(13, ServerSettings.defaults(), budget(1000));
        assertEquals(10, target.amounts[0]);
        scheduler.tick(14, ServerSettings.defaults(), budget(1000));
        assertEquals(14, target.amounts[0]);
    }

    @Test
    void pendingFilterWithoutProgressDefersUnderFixedClock() {
        Env e = new Env();
        e.types = List.of(ResourceTypes.ENERGY);
        e.put(3, ResourceTypes.ENERGY, 1, 100);
        SchedulerResourcePort target = e.put(4, ResourceTypes.ENERGY, 1, 0);
        e.filterPending = true;
        var base = input(5, 10);
        var p = new ResourceTransferPolicy.Input(
                10,
                base.scope(),
                base.redstoneCondition(),
                new UUID(0, 99),
                base.filterMode(),
                base.resourcePolicyOverrides(),
                0);
        var scheduler = new ResourceDirectScheduler(e);
        scheduler.replaceNetwork(NETWORK, List.of(config(3, p), config(4, output(100, 10))), 0);
        scheduler.tick(0, ServerSettings.defaults(), budget(1000));
        assertEquals(1, e.filterSteps);
        assertEquals(0, target.discoveries);
        scheduler.tick(1, ServerSettings.defaults(), budget(1000));
        assertEquals(2, e.filterSteps);
    }

    @Test
    void exactTargetBelowBatchSkipsCapabilityDiscovery() {
        Env e = new Env();
        e.types = List.of(ResourceTypes.ITEM);
        e.put(3, ResourceTypes.ITEM, 1, 100);
        SchedulerResourcePort target = e.put(4, ResourceTypes.ITEM, 1, 0);
        var scheduler = new ResourceDirectScheduler(e);
        scheduler.replaceNetwork(NETWORK, List.of(config(3, exact(20, 20, 0)), config(4, output(10, 10))), 0);
        scheduler.tick(0, ServerSettings.defaults(), budget(1000));
        assertEquals(0, target.discoveries);
    }

    static io.github.loongin.omniresonance.filter.ResourceFilterCompiler.Compiled emptyCompiled() {
        var id = new UUID(0, 99);
        var preset = new io.github.loongin.omniresonance.filter.ResourceFilterPreset(
                id, new io.github.loongin.omniresonance.network.ManagedName("Empty"), 0, List.of());
        return io.github.loongin.omniresonance.filter.ResourceFilterCompiler.compile(
                id,
                new io.github.loongin.omniresonance.filter.ResourceFilterCompiler.OwnerSnapshot(
                        new UUID(0, 88), Map.of(id, preset)),
                (type, tag) -> io.github.loongin.omniresonance.filter.ResourceFilterCompiler.TagSnapshot.missing(0));
    }

    @Test
    void absentTypeBlacklistDoesNotDecodeGenericNativeKey() {
        Env e = new Env();
        e.types = List.of(ResourceTypes.ITEM);
        e.compiled = emptyCompiled();
        e.put(3, ResourceTypes.ITEM, 1, 100);
        SchedulerResourcePort target = e.put(4, ResourceTypes.ITEM, 1, 0);
        var base = input(5, 10);
        var p = new ResourceTransferPolicy.Input(
                10,
                base.scope(),
                base.redstoneCondition(),
                new UUID(0, 99),
                FilterMode.BLACKLIST,
                base.resourcePolicyOverrides(),
                0);
        var scheduler = new ResourceDirectScheduler(e);
        scheduler.replaceNetwork(NETWORK, List.of(config(3, p), config(4, output(100, 10))), 0);
        scheduler.tick(0, ServerSettings.defaults(), budget(1000));
        assertEquals(5, target.amounts[0]);
    }

    @Test
    void exactUsesAvailableWholeBatchWithoutFillingRateOrEveryTarget() {
        Env e = new Env();
        e.types = List.of(ResourceTypes.ITEM);
        SchedulerResourcePort source = e.put(3, ResourceTypes.ITEM, 1000, 100);
        SchedulerResourcePort target = e.put(4, ResourceTypes.ITEM, 2, 0);
        target.viewCapacity = 10;
        var scheduler = new ResourceDirectScheduler(e);
        scheduler.replaceNetwork(NETWORK, List.of(config(3, exact(1000, 20, 0)), config(4, output(1000, 10))), 0);
        var b = budget(40);
        scheduler.tick(0, ServerSettings.defaults(), b);
        assertEquals(20, target.amounts[0] + target.amounts[1]);
        assertTrue(b.calls() <= 40);
    }

    @Test
    void filterCompilationAndEvaluationResumeAndCapturedTokenInvalidates() {
        Env e = new Env();
        e.types = List.of(ResourceTypes.ENERGY);
        var id = new UUID(0, 99);
        var rule = new io.github.loongin.omniresonance.filter.ResourceFilterRule.Match(
                new UUID(0, 1),
                ResourceTypes.ENERGY,
                io.github.loongin.omniresonance.filter.ResourceFilterRule.Selector.wholeType(),
                io.github.loongin.omniresonance.filter.ComponentCondition.idOnly());
        var preset = new io.github.loongin.omniresonance.filter.ResourceFilterPreset(
                id, new io.github.loongin.omniresonance.network.ManagedName("Energy"), 0, List.of(rule));
        e.preparation = io.github.loongin.omniresonance.filter.ResourceFilterCompiler.prepare(
                id,
                new io.github.loongin.omniresonance.filter.ResourceFilterCompiler.OwnerSnapshot(id, Map.of(id, preset)),
                (type, tag) -> io.github.loongin.omniresonance.filter.ResourceFilterCompiler.TagSnapshot.missing(0));
        e.filterPending = true;
        SchedulerResourcePort source = e.put(3, ResourceTypes.ENERGY, 1, 100);
        SchedulerResourcePort target = e.put(4, ResourceTypes.ENERGY, 1, 0);
        target.onSimulate = () -> {
            e.filterVersion++;
            e.compiled = emptyCompiled();
        };
        var base = input(5, 10);
        var p = new ResourceTransferPolicy.Input(
                10,
                base.scope(),
                base.redstoneCondition(),
                id,
                FilterMode.WHITELIST,
                base.resourcePolicyOverrides(),
                0);
        var scheduler = new ResourceDirectScheduler(e);
        scheduler.replaceNetwork(NETWORK, List.of(config(3, p), config(4, output(100, 10))), 0);
        scheduler.tick(0, ServerSettings.defaults(), budget(1000));
        assertEquals(100, source.amounts[0]);
        assertEquals(0, target.amounts[0]);
        assertTrue(e.filterSteps > 0);
        assertEquals(1, target.discoveries);
    }

    @Test
    void exactRechecksStaleHintsWithoutUsingOldAmounts() {
        Env e = new Env();
        e.types = List.of(ResourceTypes.ITEM);
        SchedulerResourcePort source = e.put(3, ResourceTypes.ITEM, 2, 10);
        source.amounts[1] = 10;
        SchedulerResourcePort target = e.put(4, ResourceTypes.ITEM, 2, 0);
        target.viewCapacity = 10;
        var scheduler = new ResourceDirectScheduler(e);
        scheduler.replaceNetwork(NETWORK, List.of(config(3, exact(20, 20, 0)), config(4, output(100, 10))), 0);
        for (int tick = 0; tick < 4; tick++) scheduler.tick(tick, ServerSettings.defaults(), budget(16));
        source.amounts[1] = 0;
        scheduler.tick(4, ServerSettings.defaults(), budget(40));
        assertEquals(10, source.amounts[0]);
        assertEquals(0, target.amounts[0] + target.amounts[1]);
    }

    @Test
    void selectedFacesShareQuotaAndAuthorityFailureDoesNoDiscovery() {
        Env e = new Env();
        e.types = List.of(ResourceTypes.ITEM);
        SchedulerResourcePort source = e.put(3, ResourceTypes.ITEM, 1, 100);
        SchedulerResourcePort target = e.put(4, ResourceTypes.ITEM, 1, 0);
        var c = config(3, input(7, 10));
        c = new ResourceDirectScheduler.Configuration(
                c.networkId(),
                c.nodeId(),
                c.channelId(),
                c.revision(),
                c.policy(),
                WorkingFaces.explicit((1 << Direction.WEST.get3DDataValue()) | (1 << Direction.EAST.get3DDataValue())));
        var scheduler = new ResourceDirectScheduler(e);
        scheduler.replaceNetwork(NETWORK, List.of(c, config(4, output(100, 10))), 0);
        e.inactive.add(c.nodeId());
        scheduler.tick(0, ServerSettings.defaults(), budget(1000));
        assertEquals(0, e.resolutions);
        e.inactive.clear();
        scheduler.wakeNode(c.nodeId(), 1);
        scheduler.tick(1, ServerSettings.defaults(), budget(1000));
        assertEquals(7, target.amounts[0]);
        assertTrue(e.discoveredFaces.contains(Direction.WEST));
        assertTrue(!e.discoveredFaces.contains(Direction.UP));
        assertEquals(93, source.amounts[0]);
    }

    @Test
    void equalPriorityRoundRobinAndHigherPriorityFirst() {
        for (boolean prioritized : new boolean[] {false, true}) {
            Env e = new Env();
            e.types = List.of(ResourceTypes.ITEM);
            e.put(3, ResourceTypes.ITEM, 1, 100);
            SchedulerResourcePort a = e.put(4, ResourceTypes.ITEM, 1, 0), b = e.put(5, ResourceTypes.ITEM, 1, 0);
            var high = output(100, 1);
            if (prioritized)
                high = new ResourceTransferPolicy.Output(
                        high.intervalTicks(),
                        high.scope(),
                        high.redstoneCondition(),
                        null,
                        high.filterMode(),
                        high.resourcePolicyOverrides(),
                        10);
            var scheduler = new ResourceDirectScheduler(e);
            scheduler.replaceNetwork(
                    NETWORK, List.of(config(3, input(1, 1)), config(4, output(100, 1)), config(5, high)), 0);
            for (int tick = 0; tick < 4; tick++) scheduler.tick(tick, ServerSettings.defaults(), budget(1000));
            assertEquals(prioritized ? 0 : 2, a.amounts[0]);
            assertEquals(prioritized ? 4 : 2, b.amounts[0]);
        }
    }

    @Test
    @org.junit.jupiter.api.Tag("scale")
    void oneThousandActiveConfigurationsFinishFairlyUnderASharedCallBudget() {
        Env env = new Env();
        env.types = List.of(ResourceTypes.ITEM);
        var configs = new ArrayList<ResourceDirectScheduler.Configuration>();
        for (int pair = 0; pair < 500; pair++) {
            int source = 3 + pair * 2, target = source + 1;
            env.put(source, ResourceTypes.ITEM, 1, 1);
            env.put(target, ResourceTypes.ITEM, 1, 0);
            UUID channel = new UUID(99, pair);
            configs.add(new ResourceDirectScheduler.Configuration(
                    NETWORK, new UUID(0, source), channel, 0, input(1, 1000), WorkingFaces.attachedFace()));
            configs.add(new ResourceDirectScheduler.Configuration(
                    NETWORK, new UUID(0, target), channel, 0, output(1, 1000), WorkingFaces.attachedFace()));
        }
        var scheduler = new ResourceDirectScheduler(env);
        scheduler.replaceNetwork(NETWORK, configs, 0);
        long totalCalls = 0, maximumCalls = 0;
        int ticks = 0;
        for (; ticks < 1000 && env.moves.size() < 500; ticks++) {
            var work = budget(64);
            scheduler.tick(ticks, ServerSettings.defaults(), work);
            assertTrue(work.calls() <= 64 + ResourceTransferEngine.MAXIMUM_GREEDY_CALLS);
            maximumCalls = Math.max(maximumCalls, work.calls());
            totalCalls += work.calls();
        }
        assertEquals(500, env.moves.size(), "Every equal-priority channel must make progress");
        for (int pair = 0; pair < 500; pair++) {
            assertEquals(0, env.ports.get(new UUID(0, 3 + pair * 2)).get(ResourceTypes.ITEM).amounts[0]);
            assertEquals(1, env.ports.get(new UUID(0, 4 + pair * 2)).get(ResourceTypes.ITEM).amounts[0]);
        }
        assertTrue(env.recovery.isEmpty());
        org.slf4j.LoggerFactory.getLogger(ResourceDirectSchedulerTest.class)
                .info(
                        "Scale acceptance: activeConfigurations=1000, channels=500, ticks={}, totalCalls={}, maxCallsPerTick={}, budget=64",
                        ticks,
                        totalCalls,
                        maximumCalls);
    }

    @Test
    @org.junit.jupiter.api.Tag("scale")
    void tenThousandSleepingConfigurationsDoNoNativeWorkAndBoundActiveAdmission() {
        Env e = new Env();
        e.types = List.of(ResourceTypes.ITEM);
        var configs = new ArrayList<ResourceDirectScheduler.Configuration>();
        for (int node = 3; node < 10003; node++) configs.add(config(node, input(1, 1000)));
        var scheduler = new ResourceDirectScheduler(e);
        scheduler.replaceNetwork(NETWORK, configs, 0);
        scheduler.tick(0, ServerSettings.defaults(), budget(10000));
        assertEquals(10000, e.resolutions);
        scheduler.tick(1, ServerSettings.defaults(), budget(10000));
        int before = e.resolutions;
        scheduler.tick(2, ServerSettings.defaults(), budget(10000));
        assertEquals(before, e.resolutions);
        scheduler.wakeNode(new UUID(0, 3), 3);
        var b = budget(1);
        scheduler.tick(3, ServerSettings.defaults(), b);
        assertTrue(e.resolutions - before <= 1);
        assertTrue(b.calls() <= 1);
    }

    @Test
    void largeFilterCompilationAndMatchingResumeUnderCpuBudget() {
        Env e = new Env();
        e.types = List.of(ResourceTypes.ITEM);
        e.filterClock = new java.util.concurrent.atomic.AtomicLong();
        var id = new UUID(0, 99);
        var rules = new ArrayList<io.github.loongin.omniresonance.filter.ResourceFilterRule>();
        for (int i = 0; i < 1000; i++)
            rules.add(new io.github.loongin.omniresonance.filter.ResourceFilterRule.Match(
                    new UUID(1, i),
                    ResourceTypes.ITEM,
                    io.github.loongin.omniresonance.filter.ResourceFilterRule.Selector.exact(
                            ResourceLocation.parse("minecraft:stone")),
                    io.github.loongin.omniresonance.filter.ComponentCondition.idOnly()));
        rules.add(new io.github.loongin.omniresonance.filter.ResourceFilterRule.Match(
                new UUID(1, 1000),
                ResourceTypes.ITEM,
                io.github.loongin.omniresonance.filter.ResourceFilterRule.Selector.wholeType(),
                io.github.loongin.omniresonance.filter.ComponentCondition.idOnly()));
        var preset = new io.github.loongin.omniresonance.filter.ResourceFilterPreset(
                id, new io.github.loongin.omniresonance.network.ManagedName("Large"), 0, rules);
        e.preparation = io.github.loongin.omniresonance.filter.ResourceFilterCompiler.prepare(
                id,
                new io.github.loongin.omniresonance.filter.ResourceFilterCompiler.OwnerSnapshot(id, Map.of(id, preset)),
                (type, tag) -> io.github.loongin.omniresonance.filter.ResourceFilterCompiler.TagSnapshot.missing(0));
        e.filterPending = true;
        SchedulerResourcePort source = e.put(3, ResourceTypes.ITEM, 1, 100);
        SchedulerResourcePort target = e.put(4, ResourceTypes.ITEM, 1, 0);
        var identity = new net.minecraft.nbt.CompoundTag();
        identity.putString("id", "minecraft:iron_ingot");
        identity.put("components", new net.minecraft.nbt.CompoundTag());
        var key = new ResourceVariantKey(ResourceTypes.ITEM, CanonicalResourceNbt.encode(identity));
        source.variant = () -> key;
        var base = input(5, 1000);
        var p = new ResourceTransferPolicy.Input(
                1000,
                base.scope(),
                base.redstoneCondition(),
                id,
                FilterMode.WHITELIST,
                base.resourcePolicyOverrides(),
                0);
        var scheduler = new ResourceDirectScheduler(e);
        scheduler.replaceNetwork(NETWORK, List.of(config(3, p), config(4, output(100, 10))), 0);
        for (int tick = 0; tick < 100 && target.amounts[0] == 0; tick++)
            scheduler.tick(
                    tick,
                    ServerSettings.defaults(),
                    new TransferWorkBudget(1000, 2, Long.MAX_VALUE, e.filterClock::get));
        assertEquals(5, target.amounts[0]);
        assertTrue(e.filterSteps >= 4);
        assertTrue(e.filterReads > 10);
    }

    static ResourceDirectScheduler.Configuration withFaces(ResourceDirectScheduler.Configuration c, int mask) {
        return new ResourceDirectScheduler.Configuration(
                c.networkId(), c.nodeId(), c.channelId(), c.revision() + 1, c.policy(), WorkingFaces.explicit(mask));
    }

    @Test
    void everySourceContainerRetainsSixteenAndLastLargeFaceDoesNotStarveReplenishedFirst() {
        for (boolean large : new boolean[] {false, true}) {
            Env e = new Env();
            e.types = List.of(ResourceTypes.ITEM);
            SchedulerResourcePort west = new SchedulerResourcePort(ResourceTypes.ITEM, 1, 48, e.moves),
                    east = new SchedulerResourcePort(ResourceTypes.ITEM, large ? 50 : 1, 48, e.moves);
            e.faces.put(new UUID(0, 3) + ":" + ResourceTypes.ITEM + ":" + Direction.WEST, west);
            e.faces.put(new UUID(0, 3) + ":" + ResourceTypes.ITEM + ":" + Direction.EAST, east);
            SchedulerResourcePort target = e.put(4, ResourceTypes.ITEM, 1, 0);
            var base = input(64, 100);
            var p = new ResourceTransferPolicy.Input(
                    100,
                    base.scope(),
                    base.redstoneCondition(),
                    null,
                    base.filterMode(),
                    base.resourcePolicyOverrides(),
                    16);
            var scheduler = new ResourceDirectScheduler(e);
            scheduler.replaceNetwork(NETWORK, List.of(withFaces(config(3, p), 48), config(4, output(256, 100))), 0);
            for (int tick = 0; tick < 30; tick++)
                scheduler.tick(tick, ServerSettings.defaults(), budget(large ? 10 : 1000));
            assertEquals(large ? 32 : 64, target.amounts[0]);
            assertEquals(16, west.amounts[0]);
            assertEquals(large ? 48 : 16, east.amounts[0]);
            if (large) {
                west.amounts[0] = 112;
                for (int tick = 30; tick < 100; tick++) scheduler.tick(tick, ServerSettings.defaults(), budget(10));
                assertEquals(32, target.amounts[0]);
                for (int tick = 100; tick < 200; tick++) scheduler.tick(tick, ServerSettings.defaults(), budget(10));
                assertEquals(96, target.amounts[0]);
                assertEquals(48, east.amounts[0]);
            }
        }
    }

    @Test
    void migratedOutputLeavesNoStaleRouteBusyLoop() {
        Env e = new Env();
        e.types = List.of(ResourceTypes.ITEM);
        e.put(3, ResourceTypes.ITEM, 1, 100);
        SchedulerResourcePort target = e.put(4, ResourceTypes.ITEM, 1, 0);
        var scheduler = new ResourceDirectScheduler(e);
        var out = config(4, output(100, 10));
        scheduler.replaceNetwork(NETWORK, List.of(config(3, input(5, 10)), out), 0);
        var other = new UUID(0, 999);
        scheduler.replaceNetwork(
                other,
                List.of(new ResourceDirectScheduler.Configuration(
                        other, out.nodeId(), out.channelId(), 1, out.policy(), out.workingFaces())),
                0);
        var clock = new java.util.concurrent.atomic.AtomicLong();
        scheduler.tick(
                0,
                ServerSettings.defaults(),
                new TransferWorkBudget(1000, 1000, Long.MAX_VALUE, clock::getAndIncrement));
        assertEquals(ResourceDirectScheduler.Status.IDLE, scheduler.status(new UUID(0, 3), CHANNEL));
        assertEquals(0, target.discoveries);
    }

    @Test
    void uncertainWindowSurvivesFaceScopeAndPolicyEdits() {
        Env e = new Env();
        e.types = List.of(ResourceTypes.ITEM);
        e.put(3, ResourceTypes.ITEM, 1, 100);
        SchedulerResourcePort target = e.put(4, ResourceTypes.ITEM, 1, 0);
        target.throwInsert = true;
        var scheduler = new ResourceDirectScheduler(e);
        var source = config(3, input(5, 1));
        var out = config(4, output(10, 10));
        scheduler.replaceNetwork(NETWORK, List.of(source, out), 0);
        scheduler.tick(0, ServerSettings.defaults(), budget(1000));
        assertEquals(5, target.amounts[0]);
        target.throwInsert = false;
        var excluded = new ResourceTransferPolicy.Output(
                10,
                ResourceScope.customSet(Set.of(ResourceTypes.ENERGY)),
                RedstoneCondition.IGNORE,
                null,
                FilterMode.WHITELIST,
                Map.of(),
                0);
        scheduler.replaceNetwork(
                NETWORK,
                List.of(
                        source,
                        new ResourceDirectScheduler.Configuration(
                                NETWORK, out.nodeId(), CHANNEL, 1, excluded, out.workingFaces())),
                1);
        scheduler.replaceNetwork(NETWORK, List.of(source, withFaces(out, 1)), 2);
        scheduler.tick(2, ServerSettings.defaults(), budget(1000));
        assertEquals(5, target.amounts[0]);
        scheduler.tick(10, ServerSettings.defaults(), budget(1000));
        assertEquals(10, target.amounts[0]);
    }

    @Test
    void exactDiscoveryAdaptsWhenAnotherInputSpendsOutputQuota() {
        Env e = new Env();
        e.types = List.of(ResourceTypes.ITEM);
        for (int node : new int[] {3, 5, 6}) e.put(node, ResourceTypes.ITEM, 1, 100);
        SchedulerResourcePort target = e.put(4, ResourceTypes.ITEM, 3, 0);
        target.viewCapacity = 10;
        var scheduler = new ResourceDirectScheduler(e);
        scheduler.replaceNetwork(
                NETWORK,
                List.of(
                        config(3, exact(30, 10, 0)),
                        config(5, exact(20, 10, 0)),
                        config(6, exact(10, 10, 0)),
                        config(4, output(30, 10))),
                0);
        scheduler.tick(0, ServerSettings.defaults(), budget(1000));
        assertTrue(scheduler.status(new UUID(0, 3), CHANNEL) != ResourceDirectScheduler.Status.FAILED);
        assertEquals(30, target.amounts[0] + target.amounts[1] + target.amounts[2]);
    }

    @Test
    void deferredRetentionDoesNotReuseEarlierIdleSleep() {
        Env e = new Env();
        e.types = List.of(ResourceTypes.ITEM);
        SchedulerResourcePort west = new SchedulerResourcePort(ResourceTypes.ITEM, 50, 0, e.moves),
                east = new SchedulerResourcePort(ResourceTypes.ITEM, 50, 0, e.moves);
        e.faces.put(new UUID(0, 3) + ":" + ResourceTypes.ITEM + ":" + Direction.WEST, west);
        e.faces.put(new UUID(0, 3) + ":" + ResourceTypes.ITEM + ":" + Direction.EAST, east);
        SchedulerResourcePort target = e.put(4, ResourceTypes.ITEM, 1, 0);
        var base = input(64, 1);
        var p = new ResourceTransferPolicy.Input(
                1, base.scope(), base.redstoneCondition(), null, base.filterMode(), base.resourcePolicyOverrides(), 16);
        var scheduler = new ResourceDirectScheduler(e);
        scheduler.replaceNetwork(NETWORK, List.of(withFaces(config(3, p), 48), config(4, output(256, 1))), 0);
        var settings = new ServerSettings(
                32,
                32,
                32,
                32,
                32,
                new ServerSettings.Scheduler(1, 1000, 1, List.of(20), 3, List.of(20), 1),
                ServerSettings.FilterLimits.defaults(),
                ServerSettings.RecoveryLimits.defaults());
        scheduler.tick(0, settings, budget(1000));
        west.amounts[0] = 48;
        east.amounts[0] = 48;
        for (int tick = 20; tick < 30; tick++) scheduler.tick(tick, settings, budget(10));
        assertEquals(0, target.amounts[0]);
        for (int tick = 30; tick < 34; tick++) scheduler.tick(tick, settings, budget(1000));
        assertEquals(64, target.amounts[0]);
    }

    @Test
    void failureBackoffSurvivesEventWakeAndDirectionResetIsExplicit() {
        Env e = new Env();
        e.types = List.of(ResourceTypes.ITEM);
        SchedulerResourcePort source = e.put(3, ResourceTypes.ITEM, 1, 100);
        SchedulerResourcePort target = e.put(4, ResourceTypes.ITEM, 1, 0);
        source.throwExtract = true;
        var scheduler = new ResourceDirectScheduler(e);
        var configs = List.of(config(3, input(5, 1)), config(4, output(100, 1)));
        scheduler.replaceNetwork(NETWORK, configs, 0);
        for (int tick = 0; tick < 3; tick++) scheduler.tick(tick, ServerSettings.defaults(), budget(1000));
        int before = e.resolutions;
        scheduler.wakeNode(new UUID(0, 3), 3);
        scheduler.replaceNetwork(NETWORK, configs, 3);
        source.throwExtract = false;
        scheduler.tick(3, ServerSettings.defaults(), budget(1000));
        scheduler.tick(201, ServerSettings.defaults(), budget(1000));
        assertEquals(before, e.resolutions);
        scheduler.tick(202, ServerSettings.defaults(), budget(1000));
        assertEquals(5, target.amounts[0]);
        scheduler.replaceNetwork(NETWORK, List.of(config(3, output(5, 1)), config(4, output(100, 1))), 202);
        scheduler.replaceNetwork(NETWORK, configs, 202);
        scheduler.tick(202, ServerSettings.defaults(), budget(1000));
        assertEquals(10, target.amounts[0]);
        scheduler.close();
        before = e.resolutions;
        scheduler.wakeNode(new UUID(0, 3), 203);
        scheduler.tick(203, ServerSettings.defaults(), budget(1000));
        assertEquals(before, e.resolutions);
    }

    @Test
    void slowQuotaCompletingCommitsKeepConsecutiveFailureBackoff() {
        Env e = new Env();
        e.types = List.of(ResourceTypes.ITEM);
        e.put(3, ResourceTypes.ITEM, 1, 100);
        SchedulerResourcePort target = e.put(4, ResourceTypes.ITEM, 1, 0);
        var clock = new java.util.concurrent.atomic.AtomicLong();
        target.onCall = () -> {
            if (target.calls % 6 == 0) clock.addAndGet(10);
        };
        var scheduler = new ResourceDirectScheduler(e);
        scheduler.replaceNetwork(NETWORK, List.of(config(3, input(5, 1)), config(4, output(100, 1))), 0);
        for (int tick = 0; tick < 3; tick++)
            scheduler.tick(
                    tick, ServerSettings.defaults(), new TransferWorkBudget(1000, Long.MAX_VALUE, 5, clock::get));
        assertEquals(15, target.amounts[0]);
        int before = e.resolutions;
        scheduler.wakeNode(new UUID(0, 3), 3);
        scheduler.tick(3, ServerSettings.defaults(), budget(1000));
        assertEquals(before, e.resolutions);
    }

    @Test
    void thousandActiveConfigurationsReceiveOneOuterShareNotOneSharePerType() {
        Env e = new Env();
        var configs = new ArrayList<ResourceDirectScheduler.Configuration>();
        for (int node = 3; node < 1003; node++) {
            configs.add(config(node, input(1, 1000)));
            for (var type : TYPES) e.put(node, type, 1, 1);
        }
        var scheduler = new ResourceDirectScheduler(e);
        scheduler.replaceNetwork(NETWORK, configs, 0);
        var b = budget(1000);
        scheduler.tick(0, ServerSettings.defaults(), b);
        assertEquals(1000, b.calls());
        assertEquals(1000, e.resolutions);
        for (int node = 3; node < 1003; node++) {
            var ports = e.ports.get(new UUID(0, node));
            assertEquals(1, ports.get(ResourceTypes.ITEM).discoveries);
            assertEquals(0, ports.get(ResourceTypes.FLUID).discoveries);
            assertEquals(0, ports.get(ResourceTypes.ENERGY).discoveries);
        }
    }

    @ParameterizedTest
    @CsvSource({"10,1", "100,1", "1000,1", "10,3", "100,3", "1000,3"})
    void insufficientExactVariantsHaveLinearDiscoveryCost(int size, int distinct) {
        Env e = new Env();
        e.types = List.of(ResourceTypes.ITEM);
        SchedulerResourcePort source = e.put(3, ResourceTypes.ITEM, size, 0);
        java.util.Arrays.fill(source.amounts, 1);
        ResourceVariant[] variants = new ResourceVariant[distinct];
        for (int i = 0; i < distinct; i++) {
            var key = new ResourceVariantKey(ResourceTypes.ITEM, new byte[] {(byte) i});
            variants[i] = () -> key;
        }
        source.variantAt = view -> variants[view % distinct];
        SchedulerResourcePort target = e.put(4, ResourceTypes.ITEM, 1, 0);
        var scheduler = new ResourceDirectScheduler(e);
        scheduler.replaceNetwork(
                NETWORK, List.of(config(3, exact(size + 1, size + 1, 0)), config(4, output(size + 1, 10))), 0);
        var b = budget((size + 1) * (size + 2));
        scheduler.tick(0, ServerSettings.defaults(), b);
        assertEquals(0, target.discoveries);
        assertEquals(0, target.amounts[0]);
        for (long amount : source.amounts) assertEquals(1, amount);
        assertTrue(
                b.calls() <= (distinct + 1L) * size + 2,
                "size=" + size + ", variants=" + distinct + ", native calls=" + b.calls());
        assertEquals((distinct + 1L) * size - distinct * (distinct - 1L) / 2 + 2, b.calls());
        source.amounts[0] = size + 1;
        scheduler.tick(10, ServerSettings.defaults(), budget((size + 1) * (size + 2)));
        assertEquals(size + 1, target.amounts[0]);
    }

    @ParameterizedTest
    @ValueSource(strings = {"token", "token_during_scan", "wake", "configuration", "handle"})
    void insufficientVariantNavigationIsClearedOnInvalidation(String invalidation) {
        Env e = new Env();
        e.types = List.of(ResourceTypes.ITEM);
        e.compiled = emptyCompiled();
        SchedulerResourcePort source = e.put(3, ResourceTypes.ITEM, 10, 0);
        java.util.Arrays.fill(source.amounts, 1);
        SchedulerResourcePort target = e.put(4, ResourceTypes.ITEM, 1, 0);
        var original = exact(11, 11, 0);
        var policy = new ResourceTransferPolicy.Input(
                10,
                original.scope(),
                original.redstoneCondition(),
                new UUID(0, 99),
                FilterMode.BLACKLIST,
                original.resourcePolicyOverrides(),
                0);
        var config = config(3, policy);
        var scheduler = new ResourceDirectScheduler(e);
        scheduler.replaceNetwork(NETWORK, List.of(config, config(4, output(100, 10))), 0);
        scheduler.tick(0, ServerSettings.defaults(), budget(invalidation.equals("token_during_scan") ? 14 : 20));
        assertEquals(0, target.amounts[0]);
        source.amounts[invalidation.equals("token_during_scan") ? 2 : 8] = 11;
        switch (invalidation) {
            case "token", "token_during_scan" -> e.filterVersion++;
            case "wake" -> scheduler.wakeNode(config.nodeId(), 1);
            case "configuration" ->
                scheduler.replaceNetwork(
                        NETWORK,
                        List.of(
                                new ResourceDirectScheduler.Configuration(
                                        NETWORK, config.nodeId(), CHANNEL, 1, policy, config.workingFaces()),
                                config(4, output(100, 10))),
                        1);
            case "handle" -> {
                source.valid = false;
                e.put(3, ResourceTypes.ITEM, 1, 11);
            }
            default -> throw new IllegalArgumentException(invalidation);
        }
        scheduler.tick(1, ServerSettings.defaults(), budget(1000));
        assertEquals(11, target.amounts[0]);
    }

    @Test
    void insufficientVariantOnOneFaceDoesNotExcludeAnotherContainer() {
        Env e = new Env();
        e.types = List.of(ResourceTypes.ITEM);
        SchedulerResourcePort west = new SchedulerResourcePort(ResourceTypes.ITEM, 10, 0, e.moves);
        java.util.Arrays.fill(west.amounts, 1);
        SchedulerResourcePort east = new SchedulerResourcePort(ResourceTypes.ITEM, 1, 11, e.moves);
        e.faces.put(new UUID(0, 3) + ":" + ResourceTypes.ITEM + ":" + Direction.WEST, west);
        e.faces.put(new UUID(0, 3) + ":" + ResourceTypes.ITEM + ":" + Direction.EAST, east);
        SchedulerResourcePort target = e.put(4, ResourceTypes.ITEM, 1, 0);
        var scheduler = new ResourceDirectScheduler(e);
        scheduler.replaceNetwork(
                NETWORK, List.of(withFaces(config(3, exact(11, 11, 0)), 48), config(4, output(100, 10))), 0);
        scheduler.tick(0, ServerSettings.defaults(), budget(1000));
        assertEquals(11, target.amounts[0]);
    }

    @Test
    void boundedInsufficientVariantEvictionNeverAuthorizesAnIncompleteBatch() {
        Env e = new Env();
        e.types = List.of(ResourceTypes.ITEM);
        SchedulerResourcePort source = e.put(3, ResourceTypes.ITEM, 10, 0);
        java.util.Arrays.fill(source.amounts, 1);
        ResourceVariant[] variants = new ResourceVariant[10];
        for (int i = 0; i < 10; i++) {
            var key = new ResourceVariantKey(ResourceTypes.ITEM, new byte[] {(byte) i});
            variants[i] = () -> key;
        }
        source.variantAt = view -> variants[view % 10];
        SchedulerResourcePort target = e.put(4, ResourceTypes.ITEM, 1, 0);
        var original = exact(3, 3, 0);
        var policy = new ResourceTransferPolicy.Input(
                1000,
                original.scope(),
                original.redstoneCondition(),
                null,
                original.filterMode(),
                original.resourcePolicyOverrides(),
                0);
        var scheduler = new ResourceDirectScheduler(e);
        scheduler.replaceNetwork(NETWORK, List.of(config(3, policy), config(4, output(100, 10))), 0);
        for (int tick = 0; tick < 100; tick++) scheduler.tick(tick, ServerSettings.defaults(), budget(4));
        assertEquals(0, target.discoveries);
        assertEquals(0, target.amounts[0]);
        for (long amount : source.amounts) assertEquals(1, amount);
        source.amounts[0] = 3;
        scheduler.tick(2000, ServerSettings.defaults(), budget(1000));
        assertEquals(3, target.amounts[0]);
    }
}
