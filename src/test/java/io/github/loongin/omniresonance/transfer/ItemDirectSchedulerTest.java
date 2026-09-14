// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.config.ServerSettings;
import io.github.loongin.omniresonance.filter.FilterMode;
import io.github.loongin.omniresonance.recovery.RecoveryBuffer;
import io.github.loongin.omniresonance.transfer.fixtures.FakeItemHandler;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

final class ItemDirectSchedulerTest {
    private static final HolderLookup.Provider PROVIDER =
            HolderLookup.Provider.create(BuiltInRegistries.REGISTRY.stream().map(registry -> registry.asLookup()));
    private static final UUID NETWORK = new UUID(0, 1), CHANNEL = new UUID(0, 2);
    private final Map<UUID, FakeItemHandler> handlers = new HashMap<>();
    private final Set<UUID> disabled = new HashSet<>(), filtered = new HashSet<>();
    private final Map<UUID, Integer> discoveries = new HashMap<>();
    private final RecoveryBuffer recovery = new RecoveryBuffer(() -> {});
    private final List<ItemDirectScheduler.Configuration> configs = new ArrayList<>();
    private final java.util.concurrent.atomic.AtomicInteger encodings = new java.util.concurrent.atomic.AtomicInteger();
    private final ItemDirectScheduler runtime = new ItemDirectScheduler(
            new ItemDirectScheduler.Environment() {
                public boolean active(ItemDirectScheduler.Configuration c) {
                    return !disabled.contains(c.nodeId());
                }

                public boolean allows(ItemDirectScheduler.Configuration c, ItemVariant v) {
                    return !filtered.contains(c.nodeId());
                }

                public ItemTransferEngine.Handle resolve(ItemDirectScheduler.Configuration c, TransferWorkBudget b) {
                    b.beforeCall();
                    try {
                        discoveries.merge(c.nodeId(), 1, Integer::sum);
                        FakeItemHandler h = handlers.get(c.nodeId());
                        return new ItemTransferEngine.Handle() {
                            public FakeItemHandler handler() {
                                return h;
                            }

                            public boolean valid() {
                                return active(c);
                            }
                        };
                    } finally {
                        b.afterCall();
                    }
                }

                public RecoveryBuffer recovery(UUID n) {
                    return recovery;
                }
            },
            stack -> {
                encodings.incrementAndGet();
                return ItemVariant.from(stack, PROVIDER);
            });

    private ItemDirectScheduler.Configuration input(int id, int amount, int rate, long keep, int slots) {
        return add(
                id,
                amount,
                slots,
                new ItemTransferPolicy.Input(20, rate, RedstoneCondition.IGNORE, null, FilterMode.WHITELIST, keep));
    }

    private ItemDirectScheduler.Configuration output(int id, int rate, int priority) {
        return add(
                id,
                0,
                1,
                new ItemTransferPolicy.Output(
                        20, rate, RedstoneCondition.IGNORE, null, FilterMode.WHITELIST, priority));
    }

    private ItemDirectScheduler.Configuration add(int id, int amount, int slots, ItemTransferPolicy policy) {
        UUID node = new UUID(1, id);
        FakeItemHandler h = new FakeItemHandler(slots);
        if (amount > 0) h.stacks[0] = new ItemStack(Items.IRON_INGOT, amount);
        handlers.put(node, h);
        var c = new ItemDirectScheduler.Configuration(NETWORK, node, CHANNEL, 0, policy);
        configs.add(c);
        return c;
    }

    private void activate() {
        runtime.replaceNetwork(NETWORK, configs, 0);
    }

    private TransferWorkBudget tick(long tick, int calls) {
        TransferWorkBudget b = new TransferWorkBudget(calls, Long.MAX_VALUE, Long.MAX_VALUE, () -> 0);
        runtime.tick(tick, ServerSettings.defaults(), b);
        return b;
    }

    private int amount(ItemDirectScheduler.Configuration c) {
        return handlers.get(c.nodeId()).stacks[0].getCount();
    }

    @Test
    void emptySourcePerformsZeroTargetDiscovery() {
        input(1, 0, 64, 0, 1);
        var out = output(2, 64, 0);
        activate();
        tick(0, 100);
        assertEquals(0, discoveries.getOrDefault(out.nodeId(), 0));
        assertTrue(discoveries.size() > 0);
    }

    @Test
    void concurrentInputsRotateEqualPriorityPreparations() {
        input(1, 64, 64, 0, 1);
        input(2, 64, 64, 0, 1);
        var a = output(3, 128, 0);
        var b = output(4, 128, 0);
        activate();
        tick(0, 1000);
        assertEquals(64, amount(a));
        assertEquals(64, amount(b));
    }

    @Test
    void unknownFailureCarriesExceptionEvidence() {
        var in = input(1, 64, 64, 0, 1);
        output(2, 64, 0);
        handlers.get(in.nodeId()).throwExtract = true;
        activate();
        tick(0, 1000);
        assertEquals(ItemDirectScheduler.Status.FAILED, runtime.status(in.nodeId(), CHANNEL));
    }

    @Test
    void twoInputsShareOutputWindow() {
        var a = input(1, 32, 32, 0, 1);
        var b = input(2, 32, 32, 0, 1);
        var out = output(3, 64, 0);
        activate();
        tick(0, 1000);
        assertEquals(64, amount(out));
        assertEquals(0, amount(a));
        assertEquals(0, amount(b));
        handlers.get(a.nodeId()).stacks[0] = new ItemStack(Items.IRON_INGOT, 32);
        runtime.wakeNode(a.nodeId(), 1);
        tick(1, 1000);
        assertEquals(64, amount(out));
    }

    @Test
    void lowerPriorityRunsAfterHighPriorityRefusal() {
        input(1, 64, 64, 0, 1);
        var high = output(2, 64, 10);
        var low = output(3, 64, 0);
        handlers.get(high.nodeId()).refuseSimulation = true;
        activate();
        tick(0, 1000);
        assertEquals(0, amount(high));
        assertEquals(64, amount(low));
    }

    @Test
    void equalPriorityTargetsRotateAcrossCommits() {
        input(1, 128, 128, 0, 1);
        var a = output(2, 128, 0);
        var b = output(3, 128, 0);
        handlers.get(new UUID(1, 1)).extractionLimit = 64;
        activate();
        tick(0, 1000);
        assertEquals(64, amount(a));
        assertEquals(64, amount(b));
    }

    @Test
    void retiringOldNetworkDoesNotRemoveReplacedConfigurationIdentity() {
        input(1, 64, 64, 0, 1);
        var out = output(2, 64, 0);
        activate();
        UUID next = new UUID(0, 99);
        List<ItemDirectScheduler.Configuration> moved = new ArrayList<>();
        for (var c : configs)
            moved.add(new ItemDirectScheduler.Configuration(next, c.nodeId(), c.channelId(), c.revision(), c.policy()));
        runtime.replaceNetwork(next, moved, 0);
        runtime.replaceNetwork(NETWORK, List.of(), 0);
        tick(0, 1000);
        assertEquals(64, amount(out));
    }

    @Test
    void wakesAndPolicyRefreshCannotBypassSourceInterval() {
        var in = input(1, 128, 32, 0, 1);
        var out = output(2, 128, 0);
        activate();
        tick(0, 1000);
        assertEquals(32, amount(out));
        runtime.wakeNode(in.nodeId(), 1);
        tick(1, 1000);
        runtime.wakeNode(out.nodeId(), 2);
        runtime.replaceNetwork(NETWORK, configs, 2);
        tick(2, 1000);
        assertEquals(32, amount(out));
        tick(20, 1000);
        assertEquals(64, amount(out));
    }

    @Test
    void boundedCommitOverrunStopsAllNetworks() {
        var a = input(1, 64, 64, 0, 1);
        var b = input(2, 64, 64, 0, 1);
        var outA = output(3, 128, 0);
        var outB = output(4, 128, 0);
        UUID secondNetwork = new UUID(0, 88);
        runtime.replaceNetwork(NETWORK, List.of(a, outA), 0);
        runtime.replaceNetwork(
                secondNetwork,
                List.of(
                        new ItemDirectScheduler.Configuration(secondNetwork, b.nodeId(), CHANNEL, 0, b.policy()),
                        new ItemDirectScheduler.Configuration(secondNetwork, outB.nodeId(), CHANNEL, 0, outB.policy())),
                0);
        boolean committed = false;
        for (int tick = 0; tick < 30; tick++) {
            TransferWorkBudget budget = tick(tick, 1);
            int mutations = handlers.get(a.nodeId()).extractionCalls + handlers.get(b.nodeId()).extractionCalls;
            if (mutations > 0) {
                assertEquals(1, mutations);
                assertTrue(budget.calls() <= ItemTransferEngine.MAXIMUM_COMMIT_CALLS);
                committed = true;
                break;
            }
        }
        assertTrue(committed);
    }

    @Test
    void quotaCompletionAnchorsIntervalToActualCommitTick() {
        input(1, 128, 64, 0, 1);
        var out = output(2, 128, 0);
        activate();
        for (int i = 0; i <= 6; i++) tick(i, 1);
        assertEquals(64, amount(out));
        tick(7, 1);
        tick(26, 1000);
        assertEquals(128, amount(out));
    }

    @Test
    void minimumCallBudgetAdvancesDefaultAndPreservesSourceQuota() {
        var in = input(1, 128, 64, 0, 1);
        var out = output(2, 128, 0);
        handlers.get(in.nodeId()).extractionLimit = 16;
        activate();
        for (int i = 0; i < 25; i++) {
            var b = tick(i, 1);
            assertTrue(b.calls() <= ItemTransferEngine.MAXIMUM_COMMIT_CALLS);
        }
        assertEquals(64, amount(out));
        assertEquals(64, amount(in));
    }

    @Test
    void retentionNeverUsesCrossTickTotals() {
        var in = input(1, 80, 64, 32, 4);
        var out = output(2, 64, 0);
        activate();
        for (int i = 0; i < 10; i++) tick(i, 1);
        assertEquals(0, amount(out));
        assertEquals(ItemDirectScheduler.Status.WAITING_BUDGET, runtime.status(in.nodeId(), CHANNEL));
        handlers.get(in.nodeId()).stacks[0] = new ItemStack(Items.IRON_INGOT, 40);
        tick(10, 1000);
        assertEquals(8, amount(out));
        assertEquals(32, amount(in));
    }

    @Test
    void retentionSharedInventoryNeverReusesTotalAfterOtherExtraction() {
        var a = input(1, 80, 64, 32, 1);
        var b = input(2, 0, 64, 32, 1);
        handlers.put(b.nodeId(), handlers.get(a.nodeId()));
        var out = output(3, 128, 0);
        activate();
        tick(0, 1000);
        assertEquals(48, amount(out));
        assertEquals(32, amount(a));
    }

    @Test
    void slowQuotaCompletingCommitsRetainConsecutiveFailureHistory() {
        var source = input(1, 256, 64, 0, 1);
        output(2, 256, 0);
        java.util.concurrent.atomic.AtomicLong time = new java.util.concurrent.atomic.AtomicLong();
        handlers.get(source.nodeId()).afterExtract = () -> time.addAndGet(10);
        activate();
        for (long tick : new long[] {0, 20, 40, 60}) {
            runtime.tick(tick, ServerSettings.defaults(), new TransferWorkBudget(1000, Long.MAX_VALUE, 5, time::get));
        }
        assertEquals(3, handlers.get(source.nodeId()).extractionCalls);
        assertEquals(64, amount(source));
        runtime.tick(240, ServerSettings.defaults(), new TransferWorkBudget(1000, Long.MAX_VALUE, 5, time::get));
        assertEquals(4, handlers.get(source.nodeId()).extractionCalls);
    }

    @Test
    void failureBackoffSurvivesAuthorityToggle() {
        var in = input(1, 64, 64, 0, 1);
        output(2, 64, 0);
        handlers.get(in.nodeId()).throwExtract = true;
        activate();
        tick(0, 1000);
        tick(20, 1000);
        tick(40, 1000);
        int calls = handlers.get(in.nodeId()).extractionCalls;
        disabled.add(in.nodeId());
        runtime.replaceNetwork(NETWORK, configs, 41);
        disabled.clear();
        runtime.replaceNetwork(NETWORK, configs, 42);
        tick(100, 1000);
        assertEquals(calls, handlers.get(in.nodeId()).extractionCalls);
        tick(240, 1000);
        assertEquals(calls + 1, handlers.get(in.nodeId()).extractionCalls);
    }

    @Test
    void outputChangeWakesSleepingSources() {
        var in = add(
                1, 64, 1, new ItemTransferPolicy.Input(1, 64, RedstoneCondition.IGNORE, null, FilterMode.WHITELIST, 0));
        var out = output(2, 64, 0);
        disabled.add(out.nodeId());
        activate();
        for (int i = 0; i < 30; i++) tick(i, 1000);
        disabled.clear();
        runtime.wakeNode(out.nodeId(), 30);
        tick(30, 1000);
        assertEquals(64, amount(out));
    }

    @Test
    void resumedCommitRechecksPreset() {
        var in = input(1, 64, 64, 0, 1);
        var out = output(2, 64, 0);
        activate();
        for (int i = 0; i < 6; i++) tick(i, 1);
        filtered.add(in.nodeId());
        tick(6, 100);
        assertEquals(0, amount(out));
        assertEquals(64, amount(in));
    }

    @Test
    void knownReceiptSurvivesUnknownReturnAndLimitsOtherInput() {
        var first = input(1, 64, 64, 0, 1);
        var second = input(2, 64, 64, 0, 1);
        var output = output(3, 64, 0);
        handlers.get(first.nodeId()).throwInsert = true;
        handlers.get(output.nodeId()).actualInsertLimit = 60;
        activate();
        tick(0, 1000);
        assertEquals(64, amount(output));
        assertEquals(60, amount(second));
        assertEquals(1, handlers.get(first.nodeId()).insertionCalls);
        assertEquals(ItemDirectScheduler.Status.FAILED, runtime.status(first.nodeId(), CHANNEL));
        assertTrue(recovery.isEmpty());
    }

    @Test
    void recoveryChargesKnownNetSourceRemovalOnly() {
        var in = input(1, 128, 64, 0, 1);
        var out = output(2, 128, 0);
        handlers.get(out.nodeId()).actualInsertLimit = 60;
        handlers.get(in.nodeId()).refuseReturn = true;
        activate();
        tick(0, 1000);
        assertEquals(60, amount(out));
        assertEquals(64, amount(in));
        assertEquals(
                4,
                recovery.amount(ItemVariant.from(new ItemStack(Items.IRON_INGOT), PROVIDER)
                        .key()));
    }

    @Test
    void stableVariantIsEncodedOnceAcrossRepeatedNativeLimits() {
        var in = input(1, 256, 256, 0, 1);
        output(2, 256, 0);
        handlers.get(in.nodeId()).extractionLimit = 64;
        activate();
        tick(0, 1000);
        assertEquals(1, encodings.get());
    }

    @Test
    void oneHundredNativeSlotsCostTouchedSlots() {
        var in = input(1, 0, 6400, 0, 100);
        var out = output(2, 6400, 0);
        FakeItemHandler h = handlers.get(in.nodeId());
        for (int i = 0; i < 100; i++) h.stacks[i] = new ItemStack(Items.IRON_INGOT, 64);
        activate();
        var b = tick(0, 10000);
        assertEquals(6400, amount(out));
        assertEquals(100, h.extractionCalls);
        assertTrue(b.calls() < 2000);
    }
}
