// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.config.ServerSettings;
import io.github.loongin.omniresonance.filter.ComponentCondition;
import io.github.loongin.omniresonance.filter.FilterMode;
import io.github.loongin.omniresonance.filter.ResourceFilterCompiler;
import io.github.loongin.omniresonance.filter.ResourceFilterPreset;
import io.github.loongin.omniresonance.filter.ResourceFilterRule;
import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.network.WorkingFaces;
import io.github.loongin.omniresonance.persistence.StorageBucketData;
import io.github.loongin.omniresonance.recovery.RecoveryBuffer;
import io.github.loongin.omniresonance.storage.DomainLedger;
import io.github.loongin.omniresonance.transfer.fixtures.FakeItemHandler;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

final class DomainOutputSchedulerTest {
    @Test
    void slowPreparationCallsCountTowardTheConfigurationBreaker() {
        Env env = new Env();
        var scheduler = new DomainOutputScheduler(env);
        scheduler.replaceNetwork(NET, List.of(config(HIGH, 10, PRESET)), 0);
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong();
        long next = 0;
        for (int tick = 0; tick < 3; tick++) {
            env.deposit(100);
            var target = env.targets.get(HIGH);
            target.stacks[0] = ItemStack.EMPTY;
            target.slotQueries = 0;
            target.afterSlots = () -> {
                if (target.slotQueries == 1) clock.addAndGet(10);
            };
            var budget = new TransferWorkBudget(1000, 1000000, 5, clock::get);
            for (int step = 0; step < 1000; step++) {
                next = scheduler.step(HIGH, tick, ServerSettings.defaults(), budget);
                if (next > tick) break;
            }
        }
        assertTrue(next >= 202, "Slow preparation never advanced the breaker: " + next);
    }

    @Test
    void oneWindowUsesMultipleSlotsUpToItsConfiguredRate() {
        Env env = new Env();
        env.deposit(100);
        FakeItemHandler high = new FakeItemHandler(2);
        high.capacity = 64;
        env.targets.put(HIGH, high);
        var scheduler = new DomainOutputScheduler(env);
        scheduler.replaceNetwork(NET, List.of(config(HIGH, 10, PRESET)), 0);
        run(scheduler, 0, 1000);
        assertEquals(100, high.stacks[0].getCount() + high.stacks[1].getCount());
        assertEquals(0, env.ledger.amount(IRON.key()));
    }

    @Test
    void rejectedPublicationPreservesPreviousUsableOutput() {
        Env env = new Env();
        env.deposit(100);
        var scheduler = new DomainOutputScheduler(env);
        var config = config(HIGH, 10, PRESET);
        scheduler.replaceNetwork(NET, List.of(config), 0);
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> scheduler.replaceNetwork(NET, List.of(config, config), 0));
        for (int tick = 0; tick < 5; tick++) run(scheduler, tick, 1000);
        assertEquals(100, env.targets.get(HIGH).stacks[0].getCount());
    }

    private static final UUID NET = new UUID(80, 1),
            HIGH = new UUID(80, 2),
            LOW = new UUID(80, 3),
            PRESET = new UUID(80, 4);
    private static final HolderLookup.Provider REGISTRIES =
            HolderLookup.Provider.create(BuiltInRegistries.REGISTRY.stream().map(registry -> registry.asLookup()));
    private static final ItemVariant IRON = ItemVariant.from(new ItemStack(Items.IRON_INGOT), REGISTRIES);

    @Test
    void lowJobCannotTakeInventoryBeforeEligibleHigherOutput() {
        Env env = new Env();
        env.targets.get(HIGH).capacity = 60;
        env.deposit(100);
        var scheduler = new DomainOutputScheduler(env);
        scheduler.replaceNetwork(NET, List.of(config(LOW, 0, PRESET), config(HIGH, 10, PRESET)), 0);
        for (int tick = 0; tick < 5; tick++) run(scheduler, tick, 1000);
        assertEquals(60, env.targets.get(HIGH).stacks[0].getCount());
        assertEquals(40, env.targets.get(LOW).stacks[0].getCount());
        assertEquals(0, env.ledger.amount(IRON.key()));
        assertEquals(0, scheduler.cachedVariants(NET));
    }

    @Test
    void missingPresetAndEmptyBlacklistNeverActivateAnOutput() {
        Env env = new Env();
        env.deposit(100);
        var scheduler = new DomainOutputScheduler(env);
        scheduler.replaceNetwork(NET, List.of(config(HIGH, 10, null)), 0);
        for (int tick = 0; tick < 5; tick++) run(scheduler, tick, 1000);
        assertEquals(100, env.ledger.amount(IRON.key()));
        assertEquals(0, env.resolutions);
        env.compiled = ResourceFilterCompiler.compile(
                PRESET,
                new ResourceFilterCompiler.OwnerSnapshot(
                        NET, Map.of(PRESET, new ResourceFilterPreset(PRESET, new ManagedName("Empty"), 0, List.of()))),
                (a, b) -> ResourceFilterCompiler.TagSnapshot.missing(0));
        var policy = new ResourceTransferPolicy.Output(
                1, ResourceScope.all(), RedstoneCondition.IGNORE, PRESET, FilterMode.BLACKLIST, Map.of(), 0);
        scheduler.replaceNetwork(
                NET,
                List.of(new DomainOutputScheduler.Configuration(NET, HIGH, 1, policy, WorkingFaces.explicit(1))),
                5);
        for (int tick = 5; tick < 10; tick++) run(scheduler, tick, 1000);
        assertEquals(100, env.ledger.amount(IRON.key()));
        assertEquals(0, env.resolutions);
    }

    @Test
    void smallBudgetsCanReachLaterSlotsWithoutUsingOldRefusalsForLowerPriority() {
        Env env = new Env();
        env.deposit(100);
        FakeItemHandler high = new FakeItemHandler(2);
        high.capacity = 64;
        high.stacks[0] = new ItemStack(Items.GOLD_INGOT, 64);
        env.targets.put(HIGH, high);
        var scheduler = new DomainOutputScheduler(env);
        scheduler.replaceNetwork(NET, List.of(config(LOW, 0, PRESET), config(HIGH, 10, PRESET)), 0);
        for (int tick = 0; tick < 30; tick++) run(scheduler, tick, 1);
        assertTrue(high.stacks[1].getCount() > 0, "Budget continuation never reached the free later slot");
        assertTrue(
                env.targets.get(LOW).stacks[0].isEmpty(), "Lower output bypassed current higher-capacity verification");
    }

    private static DomainOutputScheduler.Configuration config(UUID node, int priority, UUID preset) {
        return new DomainOutputScheduler.Configuration(
                NET,
                node,
                0,
                new ResourceTransferPolicy.Output(
                        1,
                        ResourceScope.all(),
                        RedstoneCondition.IGNORE,
                        preset,
                        FilterMode.WHITELIST,
                        Map.of(ResourceTypes.ITEM, new ResourceTransferPolicy.OutputOverride(100)),
                        priority),
                WorkingFaces.explicit(1));
    }

    private static void run(DomainOutputScheduler scheduler, long tick, int calls) {
        var budget = new TransferWorkBudget(calls, Long.MAX_VALUE, Long.MAX_VALUE, () -> 0);
        for (int i = 0; i < 1000 && budget.canStart(); i++) {
            long low = scheduler.step(LOW, tick, ServerSettings.defaults(), budget);
            long high = scheduler.step(HIGH, tick, ServerSettings.defaults(), budget);
            if (low > tick && high > tick) break;
        }
    }

    private static final class Env implements DomainOutputScheduler.Environment {
        final DomainLedger ledger = new DomainLedger(NET, Map.of(), index -> StorageBucketData.create(NET, index));
        final RecoveryBuffer recovery = new RecoveryBuffer(() -> {});
        final Map<UUID, FakeItemHandler> targets = new HashMap<>();
        final Map<UUID, ResourceTransferEngine.Handle> handles = new HashMap<>();
        int resolutions;
        ResourceFilterCompiler.Compiled compiled;

        Env() {
            targets.put(HIGH, new FakeItemHandler(1));
            targets.put(LOW, new FakeItemHandler(1));
            var rule = new ResourceFilterRule.Match(
                    new UUID(81, 1),
                    ResourceTypes.ITEM,
                    ResourceFilterRule.Selector.glob("minecraft:*"),
                    ComponentCondition.idOnly());
            compiled = ResourceFilterCompiler.compile(
                    PRESET,
                    new ResourceFilterCompiler.OwnerSnapshot(
                            NET,
                            Map.of(
                                    PRESET,
                                    new ResourceFilterPreset(PRESET, new ManagedName("Items"), 0, List.of(rule)))),
                    (a, b) -> ResourceFilterCompiler.TagSnapshot.missing(0));
        }

        void deposit(long amount) {
            try (var reservation = ledger.reserveDeposit(IRON.key(), amount, -1).orElseThrow()) {
                reservation.commit(amount);
            }
        }

        public List<ResourceLocation> types() {
            return List.of(ResourceTypes.ITEM);
        }

        public boolean active(DomainOutputScheduler.Configuration c) {
            return true;
        }

        public int faces(DomainOutputScheduler.Configuration c) {
            return 1;
        }

        public DomainLedger ledger(UUID network) {
            return ledger;
        }

        public ResourceVariant decode(ResourceVariantKey key) {
            return ItemVariant.restore(key, REGISTRIES);
        }

        public RecoveryBuffer recovery(UUID network) {
            return recovery;
        }

        public ResourceDirectScheduler.FilterView filter(DomainOutputScheduler.Configuration c) {
            return new ResourceDirectScheduler.FilterView(compiled, compiled);
        }

        public int advanceFilter(DomainOutputScheduler.Configuration c, int units) {
            return 0;
        }

        public ResourceTransferEngine.Handle resolve(
                DomainOutputScheduler.Configuration c,
                ResourceLocation type,
                Direction face,
                TransferWorkBudget budget) {
            return handles.computeIfAbsent(c.nodeId(), id -> {
                resolutions++;
                budget.beforeCall();
                var port = new ItemResourcePort(targets.get(id), REGISTRIES);
                return new ResourceTransferEngine.Handle() {
                    public ResourcePort port() {
                        return port;
                    }

                    public Object physicalIdentity() {
                        return targets.get(id);
                    }

                    public boolean valid() {
                        return true;
                    }
                };
            });
        }
    }
}
