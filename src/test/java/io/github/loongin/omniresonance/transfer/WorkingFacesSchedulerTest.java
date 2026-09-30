// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import static io.github.loongin.omniresonance.transfer.fixtures.ItemSchedulerConfigurations.configuration;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.config.ServerSettings;
import io.github.loongin.omniresonance.filter.FilterMode;
import io.github.loongin.omniresonance.network.WorkingFaces;
import io.github.loongin.omniresonance.recovery.RecoveryBuffer;
import io.github.loongin.omniresonance.transfer.fixtures.FakeItemHandler;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

class WorkingFacesSchedulerTest {
    private static final HolderLookup.Provider PROVIDER =
            HolderLookup.Provider.create(BuiltInRegistries.REGISTRY.stream().map(r -> r.asLookup()));
    private static final UUID NETWORK = new UUID(0, 1), CHANNEL = new UUID(0, 2);
    private static final int WEST_EAST =
            (1 << Direction.WEST.get3DDataValue()) | (1 << Direction.EAST.get3DDataValue());
    private static final io.github.loongin.omniresonance.filter.ResourceFilterCompiler.Compiled ALLOW =
            io.github.loongin.omniresonance.filter.ResourceFilterCompiler.compile(
                    null,
                    new io.github.loongin.omniresonance.filter.ResourceFilterCompiler.OwnerSnapshot(NETWORK, Map.of()),
                    (type, tag) -> null);
    private final Map<Endpoint, FakeItemHandler> handlers = new HashMap<>();
    private final Map<Endpoint, Integer> discoveries = new HashMap<>();
    private final List<ResourceDirectScheduler.Configuration> configs = new ArrayList<>();
    private final RecoveryBuffer recovery = new RecoveryBuffer(() -> {});
    private final ResourceDirectScheduler scheduler =
            new ResourceDirectScheduler(new ResourceDirectScheduler.Environment() {
                public boolean active(ResourceDirectScheduler.Configuration c) {
                    return true;
                }

                public List<net.minecraft.resources.ResourceLocation> registeredTypes() {
                    return List.of(ResourceTypes.ITEM);
                }

                public ResourceDirectScheduler.FilterView filter(ResourceDirectScheduler.Configuration c) {
                    return new ResourceDirectScheduler.FilterView(c.revision(), ALLOW);
                }

                public ResourceTransferEngine.Handle resolve(
                        ResourceDirectScheduler.Configuration c,
                        net.minecraft.resources.ResourceLocation type,
                        Direction face,
                        TransferWorkBudget budget) {
                    budget.beforeCall();
                    try {
                        Endpoint key = new Endpoint(c.nodeId(), face);
                        discoveries.merge(key, 1, Integer::sum);
                        FakeItemHandler handler = handlers.get(key);
                        if (handler == null) return null;
                        return new ResourceTransferEngine.Handle() {
                            private final ResourcePort port = new ItemResourcePort(handler, PROVIDER);

                            public ResourcePort port() {
                                return port;
                            }

                            public Object physicalIdentity() {
                                return handler;
                            }

                            public boolean valid() {
                                return true;
                            }
                        };
                    } finally {
                        budget.afterCall();
                    }
                }

                public RecoveryBuffer recovery(UUID network) {
                    return recovery;
                }
            });

    private ResourceDirectScheduler.Configuration config(int id, boolean input, int rate, long keep, int mask) {
        ItemTransferPolicy policy = input
                ? new ItemTransferPolicy.Input(100, rate, RedstoneCondition.IGNORE, null, FilterMode.WHITELIST, keep)
                : new ItemTransferPolicy.Output(100, rate, RedstoneCondition.IGNORE, null, FilterMode.WHITELIST, 0);
        var c = configuration(NETWORK, new UUID(1, id), CHANNEL, 0, policy, WorkingFaces.explicit(mask));
        configs.add(c);
        return c;
    }

    private FakeItemHandler face(ResourceDirectScheduler.Configuration c, Direction face, int amount) {
        FakeItemHandler h = new FakeItemHandler(1);
        if (amount > 0) h.stacks[0] = new ItemStack(Items.IRON_INGOT, amount);
        handlers.put(new Endpoint(c.nodeId(), face), h);
        return h;
    }

    private void tick(long tick, int calls) {
        scheduler.tick(
                tick,
                ServerSettings.defaults(),
                new TransferWorkBudget(calls, Long.MAX_VALUE, Long.MAX_VALUE, () -> 0));
    }

    private int amount(FakeItemHandler h) {
        return h.stacks[0].getCount();
    }

    private void activate() {
        scheduler.replaceNetwork(NETWORK, configs, 0);
    }

    @Test
    void emptySelectionMakesNoCapabilityCalls() {
        config(1, true, 64, 0, 0);
        config(2, false, 64, 0, WEST_EAST);
        activate();
        tick(0, 1000);
        assertTrue(discoveries.isEmpty());
    }

    @Test
    void sourcesShareQuotaAndRotateAcrossWindowsWithoutTouchingUnselectedNeighbor() {
        var in = config(1, true, 64, 0, WEST_EAST);
        var out = config(2, false, 256, 0, WEST_EAST);
        var west = face(in, Direction.WEST, 128);
        var east = face(in, Direction.EAST, 128);
        var down = face(in, Direction.DOWN, 128);
        var target = face(out, Direction.WEST, 0);
        activate();
        tick(0, 1000);
        assertEquals(64, amount(target));
        assertEquals(192, amount(west) + amount(east));
        tick(100, 1000);
        assertEquals(64, amount(west));
        assertEquals(64, amount(east));
        assertEquals(128, amount(down));
        assertEquals(0, discoveries.getOrDefault(new Endpoint(in.nodeId(), Direction.DOWN), 0));
    }

    @Test
    void everySourceContainerRetainsSixteen() {
        var in = config(1, true, 128, 16, WEST_EAST);
        var out = config(2, false, 128, 0, WEST_EAST);
        var west = face(in, Direction.WEST, 48);
        var east = face(in, Direction.EAST, 48);
        var target = face(out, Direction.WEST, 0);
        activate();
        tick(0, 1000);
        assertEquals(16, amount(west));
        assertEquals(16, amount(east));
        assertEquals(64, amount(target));
    }

    @Test
    void multipleInputsAndOutputFacesShareOneReceiveWindow() {
        var a = config(1, true, 64, 0, WEST_EAST);
        var b = config(2, true, 64, 0, WEST_EAST);
        var out = config(3, false, 64, 0, WEST_EAST);
        face(a, Direction.WEST, 64);
        face(b, Direction.WEST, 64);
        var west = face(out, Direction.WEST, 0);
        var east = face(out, Direction.EAST, 0);
        west.capacity = 32;
        east.capacity = 32;
        activate();
        tick(0, 1000);
        assertEquals(64, amount(west) + amount(east));
        assertEquals(32, amount(west));
        assertEquals(32, amount(east));
    }

    @Test
    void tinyBudgetsResumeFacesAndEditsPreserveSpentInputQuota() {
        var in = config(1, true, 64, 0, WEST_EAST);
        var out = config(2, false, 256, 0, WEST_EAST);
        var west = face(in, Direction.WEST, 128);
        var east = face(in, Direction.EAST, 128);
        west.extractionLimit = 16;
        east.extractionLimit = 16;
        var target = face(out, Direction.WEST, 0);
        activate();
        long tick = 0;
        while (amount(target) == 0 && tick < 40) tick(tick++, 1);
        assertEquals(16, amount(target));
        configs.set(
                0,
                configuration(
                        NETWORK,
                        in.nodeId(),
                        CHANNEL,
                        1,
                        in.policy(),
                        WorkingFaces.explicit(1 << Direction.EAST.get3DDataValue())));
        scheduler.replaceNetwork(NETWORK, configs, tick);
        for (; tick < 80; tick++) tick(tick, 1);
        assertEquals(64, amount(target));
        assertEquals(112, amount(west));
        assertEquals(80, amount(east));
    }

    @Test
    void unavailableAndEmptyFacesDoNotStarveUsableFace() {
        var in = config(1, true, 64, 0, WEST_EAST | 1);
        var out = config(2, false, 64, 0, WEST_EAST);
        face(in, Direction.WEST, 0);
        var east = face(in, Direction.EAST, 64);
        var target = face(out, Direction.WEST, 0);
        activate();
        for (int tick = 0; tick < 60; tick++) tick(tick, 1);
        assertEquals(64, amount(target));
        assertEquals(0, amount(east));
    }

    @Test
    void oversizedRetentionFaceDoesNotStarveSmallContainer() {
        var in = config(1, true, 64, 16, WEST_EAST);
        var out = config(2, false, 64, 0, WEST_EAST);
        var large = new FakeItemHandler(50);
        large.stacks[0] = new ItemStack(Items.IRON_INGOT, 48);
        handlers.put(new Endpoint(in.nodeId(), Direction.WEST), large);
        var east = face(in, Direction.EAST, 48);
        var target = face(out, Direction.WEST, 0);
        activate();
        for (int tick = 0; tick < 30; tick++) tick(tick, 10);
        assertEquals(32, amount(target));
        assertEquals(16, amount(east));
        assertEquals(48, amount(large));
    }

    @Test
    void lastOversizedRetentionFaceEndsRoundAndRevisitsReplenishedFirstFace() {
        var in = config(1, true, 64, 16, WEST_EAST);
        var out = config(2, false, 256, 0, WEST_EAST);
        var west = face(in, Direction.WEST, 48);
        var large = new FakeItemHandler(50);
        large.stacks[0] = new ItemStack(Items.IRON_INGOT, 48);
        handlers.put(new Endpoint(in.nodeId(), Direction.EAST), large);
        var target = face(out, Direction.WEST, 0);
        activate();
        for (int tick = 0; tick < 30; tick++) tick(tick, 10);
        assertEquals(32, amount(target));
        assertEquals(16, amount(west));
        assertEquals(48, amount(large));
        // Ordinary inventory changes do not promise capability invalidation or an explicit scheduler wake.
        west.stacks[0] = new ItemStack(Items.IRON_INGOT, 112);
        for (int tick = 30; tick < 100; tick++) tick(tick, 10);
        assertEquals(32, amount(target), "Ending the round must preserve the configured input interval");
        for (int tick = 100; tick < 200; tick++) tick(tick, 10);
        assertEquals(96, amount(target), "The next window must revisit WEST and share only one rate of sixty-four");
        assertEquals(48, amount(west));
        assertEquals(48, amount(large), "An incomplete retention scan must never authorize extraction");
    }

    @Test
    void allDeferredFacesKeepWaitingBudgetStatusAndAvoidIdleSleep() {
        var in = config(1, true, 64, 16, WEST_EAST);
        var out = config(2, false, 256, 0, WEST_EAST);
        var policy = new ItemTransferPolicy.Input(1, 64, RedstoneCondition.IGNORE, null, FilterMode.WHITELIST, 16);
        configs.set(0, configuration(NETWORK, in.nodeId(), CHANNEL, 0, policy, WorkingFaces.explicit(WEST_EAST)));
        for (Direction face : new Direction[] {Direction.WEST, Direction.EAST}) {
            var large = new FakeItemHandler(50);
            large.stacks[0] = new ItemStack(Items.IRON_INGOT, 48);
            handlers.put(new Endpoint(in.nodeId(), face), large);
        }
        var target = face(out, Direction.WEST, 0);
        var settings = new ServerSettings(
                32,
                32,
                32,
                32,
                32,
                new ServerSettings.Scheduler(1, 1000, 1, List.of(20), 3, List.of(20), 1),
                ServerSettings.FilterLimits.defaults(),
                ServerSettings.RecoveryLimits.defaults());
        activate();
        for (int tick = 0; tick < 10; tick++) {
            scheduler.tick(tick, settings, new TransferWorkBudget(10, Long.MAX_VALUE, Long.MAX_VALUE, () -> 0));
            assertEquals(
                    ResourceDirectScheduler.Status.WAITING_BUDGET,
                    scheduler.status(in.nodeId(), CHANNEL),
                    "A deferred retention round must not become an idle inventory check");
        }
        assertEquals(0, amount(target));
        // Once verification fits, the ordinary next interval runs immediately instead of an idle-backoff delay.
        for (int tick = 10; tick < 13; tick++) {
            scheduler.tick(tick, settings, new TransferWorkBudget(1000, Long.MAX_VALUE, Long.MAX_VALUE, () -> 0));
        }
        assertEquals(64, amount(target));
        assertEquals(16, amount(handlers.get(new Endpoint(in.nodeId(), Direction.WEST))));
        assertEquals(16, amount(handlers.get(new Endpoint(in.nodeId(), Direction.EAST))));
    }

    @Test
    void completedSingleFaceRetentionVerificationRestoresIdleBackoff() {
        var in = config(1, true, 64, 16, 16);
        var out = config(2, false, 256, 0, WEST_EAST);
        var policy = new ItemTransferPolicy.Input(1, 64, RedstoneCondition.IGNORE, null, FilterMode.WHITELIST, 16);
        configs.set(0, configuration(NETWORK, in.nodeId(), CHANNEL, 0, policy, WorkingFaces.explicit(16)));
        var source = new FakeItemHandler(5);
        source.stacks[0] = new ItemStack(Items.IRON_INGOT, 16);
        handlers.put(new Endpoint(in.nodeId(), Direction.WEST), source);
        var target = face(out, Direction.WEST, 0);
        var settings = new ServerSettings(
                32,
                32,
                32,
                32,
                32,
                new ServerSettings.Scheduler(1, 1000, 1, List.of(20), 3, List.of(20), 1),
                ServerSettings.FilterLimits.defaults(),
                ServerSettings.RecoveryLimits.defaults());
        activate();
        scheduler.tick(0, settings, new TransferWorkBudget(10, Long.MAX_VALUE, Long.MAX_VALUE, () -> 0));
        assertEquals(ResourceDirectScheduler.Status.WAITING_BUDGET, scheduler.status(in.nodeId(), CHANNEL));
        scheduler.tick(1, settings, new TransferWorkBudget(10, Long.MAX_VALUE, Long.MAX_VALUE, () -> 0));
        scheduler.tick(2, settings, new TransferWorkBudget(10, Long.MAX_VALUE, Long.MAX_VALUE, () -> 0));
        assertEquals(
                ResourceDirectScheduler.Status.IDLE,
                scheduler.status(in.nodeId(), CHANNEL),
                "A completed retention scan must clear the prior temporary budget wait");
        assertEquals(16, amount(source));
        assertEquals(0, amount(target));
        int callsBeforeSleep = source.calls;
        for (int tick = 3; tick < 22; tick++) {
            scheduler.tick(tick, settings, new TransferWorkBudget(10, Long.MAX_VALUE, Long.MAX_VALUE, () -> 0));
        }
        assertEquals(callsBeforeSleep, source.calls, "A fully checked idle source must obey idle backoff");
        scheduler.tick(22, settings, new TransferWorkBudget(10, Long.MAX_VALUE, Long.MAX_VALUE, () -> 0));
        assertTrue(source.calls > callsBeforeSleep, "Idle backoff must expire after twenty ticks");
    }

    @Test
    void faceOnlyEditRetainsIdleBackoffDeadline() {
        var in = config(1, true, 64, 0, 16);
        var out = config(2, false, 64, 0, WEST_EAST);
        var policy = new ItemTransferPolicy.Input(1, 64, RedstoneCondition.IGNORE, null, FilterMode.WHITELIST, 0);
        configs.set(0, configuration(NETWORK, in.nodeId(), CHANNEL, 0, policy, WorkingFaces.explicit(16)));
        var target = face(out, Direction.WEST, 0);
        var settings = new ServerSettings(
                32,
                32,
                32,
                32,
                32,
                new ServerSettings.Scheduler(1, 1000, 1, List.of(20), 3, List.of(20), 1),
                ServerSettings.FilterLimits.defaults(),
                ServerSettings.RecoveryLimits.defaults());
        activate();
        scheduler.tick(0, settings, new TransferWorkBudget(1000, Long.MAX_VALUE, Long.MAX_VALUE, () -> 0));
        face(in, Direction.EAST, 64);
        configs.set(0, configuration(NETWORK, in.nodeId(), CHANNEL, 1, policy, WorkingFaces.explicit(32)));
        scheduler.replaceNetwork(NETWORK, configs, 1);
        scheduler.tick(1, settings, new TransferWorkBudget(1000, Long.MAX_VALUE, Long.MAX_VALUE, () -> 0));
        assertEquals(0, amount(target));
        scheduler.tick(20, settings, new TransferWorkBudget(1000, Long.MAX_VALUE, Long.MAX_VALUE, () -> 0));
        assertEquals(64, amount(target));
    }

    private record Endpoint(UUID node, Direction direction) {}
}
