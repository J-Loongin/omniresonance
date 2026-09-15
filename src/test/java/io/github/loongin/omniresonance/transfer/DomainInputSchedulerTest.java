// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.config.ServerSettings;
import io.github.loongin.omniresonance.filter.FilterMode;
import io.github.loongin.omniresonance.network.WorkingFaces;
import io.github.loongin.omniresonance.persistence.StorageBucketData;
import io.github.loongin.omniresonance.recovery.RecoveryBuffer;
import io.github.loongin.omniresonance.storage.DomainLedger;
import io.github.loongin.omniresonance.transfer.fixtures.FakeItemHandler;
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

final class DomainInputSchedulerTest {
    private static final UUID NETWORK = new UUID(70, 1), NODE = new UUID(70, 2);
    private static final HolderLookup.Provider REGISTRIES =
            HolderLookup.Provider.create(BuiltInRegistries.REGISTRY.stream().map(registry -> registry.asLookup()));
    private static final ItemVariant IRON = ItemVariant.from(new ItemStack(Items.IRON_INGOT), REGISTRIES);

    @Test
    void smallBudgetsContinueDiscoveryAndDoNotRefillAnOpenRateWindow() {
        Env env = new Env(64, 64);
        DomainInputScheduler scheduler = new DomainInputScheduler(env);
        scheduler.replace(config(64, 20, 0, 1), 0);
        for (int tick = 0; tick < 10; tick++) run(scheduler, tick, budget(1));
        assertEquals(64, env.ledger.amount(IRON.key()));
        assertEquals(64, env.items.stacks[0].getCount() + env.items.stacks[1].getCount());
    }

    @Test
    void unfinishedDirectSourceWorkBlocksExtractionUntilItsTurnCompletes() {
        Env env = new Env(64);
        env.directUntil = 5;
        DomainInputScheduler scheduler = new DomainInputScheduler(env);
        scheduler.replace(config(64, 1, 0, 1), 0);
        run(scheduler, 0, budget(100));
        assertEquals(64, env.items.stacks[0].getCount());
        env.directUntil = -1;
        run(scheduler, 5, budget(100));
        assertEquals(64, env.ledger.amount(IRON.key()));
    }

    @Test
    void insufficientExactVariantIsNotRescannedQuadraticallyAcrossItsSlots() {
        int[] amounts = new int[1000];
        java.util.Arrays.fill(amounts, 1);
        Env env = new Env(amounts);
        DomainInputScheduler scheduler = new DomainInputScheduler(env);
        scheduler.replace(config(2000, 1, 0, 1001), 0);
        var budget = budget(5000);
        run(scheduler, 0, budget);
        assertEquals(0, env.ledger.variantCount());
        assertTrue(budget.calls() < 3100, "Repeated same-variant full scans: " + budget.calls());
    }

    @Test
    void retainedVariantIsNotRecountedAtEverySlot() {
        int[] amounts = new int[1000];
        java.util.Arrays.fill(amounts, 1);
        Env env = new Env(amounts);
        var scheduler = new DomainInputScheduler(env);
        scheduler.replace(config(2000, 1, 1000, 1), 0);
        var budget = budget(5000);
        run(scheduler, 0, budget);
        assertEquals(0, env.ledger.variantCount());
        assertTrue(budget.calls() < 2100, "Repeated full retention counts: " + budget.calls());
    }

    @Test
    void retentionBudgetWaitAtLargeContainerDoesNotStarveAnotherSelectedFace() {
        int[] large = new int[1000];
        java.util.Arrays.fill(large, 20);
        Env env = new Env(large);
        FakeItemHandler small = new FakeItemHandler(1);
        small.stacks[0] = IRON.stack(20);
        env.alternate = new ItemResourcePort(small, REGISTRIES);
        env.mask = 3;
        var scheduler = new DomainInputScheduler(env);
        var original = config(64, 1, 16, 1);
        scheduler.replace(
                new DomainInputScheduler.Configuration(NETWORK, NODE, 0, original.policy(), WorkingFaces.explicit(3)),
                0);
        for (int tick = 0; tick < 20; tick++) run(scheduler, tick, budget(8));
        assertEquals(4, env.ledger.amount(IRON.key()));
        assertEquals(16, small.stacks[0].getCount());
        assertEquals(0, env.items.extractionCalls);
    }

    private static DomainInputScheduler.Configuration config(int rate, int interval, long keep, long batch) {
        var override = new ResourceTransferPolicy.InputOverride(
                rate,
                batch == 1 ? ResourceTransferPolicy.BatchMode.GREEDY : ResourceTransferPolicy.BatchMode.EXACT,
                batch);
        var policy = new ResourceTransferPolicy.Input(
                interval,
                ResourceScope.all(),
                RedstoneCondition.IGNORE,
                null,
                FilterMode.WHITELIST,
                Map.of(ResourceTypes.ITEM, override),
                keep);
        return new DomainInputScheduler.Configuration(NETWORK, NODE, 0, policy, WorkingFaces.explicit(1));
    }

    private static void run(DomainInputScheduler scheduler, long tick, TransferWorkBudget budget) {
        for (int step = 0; step < 10000 && budget.canStart(); step++) {
            if (scheduler.step(NODE, tick, ServerSettings.defaults(), budget) > tick) return;
        }
    }

    private static TransferWorkBudget budget(int calls) {
        return new TransferWorkBudget(calls, Long.MAX_VALUE, Long.MAX_VALUE, () -> 0);
    }

    private static final class Env implements DomainInputScheduler.Environment {
        final FakeItemHandler items;
        final ResourcePort port;
        final RecoveryBuffer recovery = new RecoveryBuffer(() -> {});
        final DomainLedger ledger =
                new DomainLedger(NETWORK, Map.of(), index -> StorageBucketData.create(NETWORK, index));
        long directUntil = -1;
        int mask = 1;
        ResourcePort alternate;

        Env(int... counts) {
            items = new FakeItemHandler(counts.length);
            for (int i = 0; i < counts.length; i++) items.stacks[i] = IRON.stack(counts[i]);
            port = new ItemResourcePort(items, REGISTRIES);
        }

        public List<ResourceLocation> types() {
            return List.of(ResourceTypes.ITEM);
        }

        public boolean active(DomainInputScheduler.Configuration c) {
            return true;
        }

        public int faces(DomainInputScheduler.Configuration c) {
            return mask;
        }

        public ResourceTransferEngine.Handle resolve(
                DomainInputScheduler.Configuration c,
                ResourceLocation type,
                Direction face,
                TransferWorkBudget budget) {
            budget.beforeCall();
            ResourcePort selected = face == Direction.UP && alternate != null ? alternate : port;
            return new ResourceTransferEngine.Handle() {
                public ResourcePort port() {
                    return selected;
                }

                public Object physicalIdentity() {
                    return items;
                }

                public boolean valid() {
                    return true;
                }
            };
        }

        public DomainLedger ledger(UUID network) {
            return ledger;
        }

        public RecoveryBuffer recovery(UUID network) {
            return recovery;
        }

        public long directFirst(
                DomainInputScheduler.Configuration c,
                ResourceLocation type,
                Direction face,
                long tick,
                TransferWorkBudget budget) {
            return directUntil;
        }

        public ResourceDirectScheduler.FilterView filter(DomainInputScheduler.Configuration c) {
            return new ResourceDirectScheduler.FilterView(0, null);
        }

        public int advanceFilter(DomainInputScheduler.Configuration c, int units) {
            return 0;
        }
    }
}
