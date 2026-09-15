// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import io.github.loongin.omniresonance.bootstrap.OmniResonanceMod;
import io.github.loongin.omniresonance.config.ServerSettings;
import io.github.loongin.omniresonance.persistence.StorageBucketData;
import io.github.loongin.omniresonance.recovery.RecoveryBuffer;
import io.github.loongin.omniresonance.storage.DomainLedger;
import io.github.loongin.omniresonance.transfer.fixtures.FakeItemHandler;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(OmniResonanceMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DomainTransferGameTests {
    private DomainTransferGameTests() {}

    @GameTest(template = "bootstrap")
    public static void nativeOutputsHonorPriorityBeforeSharingDomainInventory(GameTestHelper helper) {
        BlockPos highPosition = helper.absolutePos(new BlockPos(1, 2, 1));
        BlockPos lowPosition = helper.absolutePos(new BlockPos(3, 2, 1));
        try {
            for (int highCapacity : new int[] {60, 100, 0}) {
                var highEntity = ResourceEndpointGameTests.place(helper, highPosition);
                var lowEntity = ResourceEndpointGameTests.place(helper, lowPosition);
                var highItems = new FakeItemHandler(1);
                var lowItems = new FakeItemHandler(1);
                highItems.capacity = highCapacity;
                highEntity.items = highItems;
                lowEntity.items = lowItems;
                var registry = helper.getLevel().registryAccess();
                var variant = ItemVariant.from(new ItemStack(Items.IRON_INGOT), registry);
                var budget = new TransferWorkBudget(100, Long.MAX_VALUE, Long.MAX_VALUE, () -> 0);
                budget.beforeCall();
                var high = handle(
                        new ItemResourcePort(
                                helper.getLevel()
                                        .getCapability(Capabilities.ItemHandler.BLOCK, highPosition, Direction.UP),
                                registry),
                        highPosition);
                budget.beforeCall();
                var low = handle(
                        new ItemResourcePort(
                                helper.getLevel()
                                        .getCapability(Capabilities.ItemHandler.BLOCK, lowPosition, Direction.UP),
                                registry),
                        lowPosition);
                UUID network = new UUID(57, highCapacity);
                var ledger = new DomainLedger(network, Map.of(), index -> StorageBucketData.create(network, index));
                try (var deposit = ledger.reserveDeposit(variant.key(), 100, -1).orElseThrow()) {
                    deposit.commit(100);
                }
                var recovery = new RecoveryBuffer(() -> {});
                var order = new DomainOutputOrder(
                        java.util.List.of(output(2, 0, low, ledger, recovery), output(1, 10, high, ledger, recovery)));
                var cursor = order.cursor(variant.key());
                for (int step = 0; step < 3 && ledger.amount(variant.key()) > 0; step++)
                    order.step(variant, cursor, budget);
                helper.assertTrue(
                        highItems.stacks[0].getCount() == highCapacity, "High priority received the wrong amount");
                helper.assertTrue(
                        lowItems.stacks[0].getCount() == 100 - highCapacity,
                        "Lower priority consumed contested inventory first");
                helper.assertTrue(
                        ledger.amount(variant.key()) == 0 && recovery.isEmpty() && !ledger.hasReservations(),
                        "Output allocation did not conserve inventory");
            }
            helper.succeed();
        } finally {
            helper.getLevel().removeBlock(highPosition, false);
            helper.getLevel().removeBlock(lowPosition, false);
        }
    }

    private static DomainOutputOrder.Output output(
            int id, int priority, ResourceTransferEngine.Handle target, DomainLedger ledger, RecoveryBuffer buffer) {
        var window = new DomainTransferWindow();
        var engine = new DomainTransferEngine();
        return new DomainOutputOrder.Output() {
            public UUID id() {
                return new UUID(0, id);
            }

            public int priority() {
                return priority;
            }

            public DomainOutputOrder.Attempt attempt(ResourceVariant variant, TransferWorkBudget budget) {
                long allowance = window.available(0, 100);
                if (allowance == 0 || ledger.amount(variant.key()) == 0)
                    return new DomainOutputOrder.Attempt(DomainOutputOrder.State.NO_CAPACITY, 0);
                var result = engine.withdrawGreedy(
                        ledger,
                        target,
                        0,
                        variant,
                        allowance,
                        () -> true,
                        buffer,
                        ServerSettings.RecoveryLimits.defaults(),
                        budget);
                if (result.moved() > 0) window.moved(0, result.moved(), 100);
                DomainOutputOrder.State state =
                        switch (result.failure()) {
                            case NONE ->
                                result.moved() > 0
                                        ? DomainOutputOrder.State.COMMITTED
                                        : DomainOutputOrder.State.NO_CAPACITY;
                            case REFUSED, INVALID_ENDPOINT -> DomainOutputOrder.State.NO_CAPACITY;
                            case WAITING_BUDGET -> DomainOutputOrder.State.WAITING_BUDGET;
                            default -> DomainOutputOrder.State.FAILED;
                        };
                return new DomainOutputOrder.Attempt(state, result.moved());
            }
        };
    }

    @GameTest(template = "bootstrap")
    public static void nativeCapabilityEndpointsRoundTripThroughDomain(GameTestHelper helper) {
        BlockPos sourcePosition = helper.absolutePos(new BlockPos(1, 2, 1));
        BlockPos targetPosition = helper.absolutePos(new BlockPos(3, 2, 1));
        try {
            var source = ResourceEndpointGameTests.place(helper, sourcePosition);
            var target = ResourceEndpointGameTests.place(helper, targetPosition);
            var sourceItems = new FakeItemHandler(1);
            var targetItems = new FakeItemHandler(1);
            sourceItems.stacks[0] = new ItemStack(Items.IRON_INGOT, 64);
            source.items = sourceItems;
            target.items = targetItems;
            var registry = helper.getLevel().registryAccess();
            var variant = ItemVariant.from(sourceItems.stacks[0], registry);
            var budget = new TransferWorkBudget(100, Long.MAX_VALUE, Long.MAX_VALUE, () -> 0);
            budget.beforeCall();
            var sourcePort = new ItemResourcePort(
                    helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, sourcePosition, Direction.UP),
                    registry);
            budget.beforeCall();
            var targetPort = new ItemResourcePort(
                    helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, targetPosition, Direction.UP),
                    registry);
            UUID network = new UUID(56, 1);
            var ledger = new DomainLedger(network, Map.of(), index -> StorageBucketData.create(network, index));
            var buffer = new RecoveryBuffer(() -> {});
            var engine = new DomainTransferEngine();
            var in = engine.depositGreedy(
                    handle(sourcePort, sourcePosition),
                    0,
                    ledger,
                    variant,
                    64,
                    -1,
                    () -> true,
                    buffer,
                    ServerSettings.RecoveryLimits.defaults(),
                    budget);
            helper.assertTrue(
                    in.moved() == 64 && ledger.amount(variant.key()) == 64 && sourceItems.stacks[0].isEmpty(),
                    "Native source did not commit into domain");
            var restored = ResourceAdapterDirectory.nativeDefaults()
                    .decode(
                            new ResourceVariantKey(
                                    variant.key().typeId(), variant.key().canonicalBytes()),
                            registry)
                    .orElseThrow();
            var out = engine.withdrawGreedy(
                    ledger,
                    handle(targetPort, targetPosition),
                    0,
                    restored,
                    64,
                    () -> true,
                    buffer,
                    ServerSettings.RecoveryLimits.defaults(),
                    budget);
            helper.assertTrue(
                    out.moved() == 64 && ledger.amount(variant.key()) == 0 && targetItems.stacks[0].getCount() == 64,
                    "Domain did not commit to native target");
            helper.assertTrue(
                    !ledger.hasReservations() && buffer.isEmpty(), "Completed domain transfer leaked ownership");
            helper.assertTrue(
                    source.itemCalls == 1 && target.itemCalls == 1, "Native capability provider was bypassed");
            helper.succeed();
        } finally {
            helper.getLevel().removeBlock(sourcePosition, false);
            helper.getLevel().removeBlock(targetPosition, false);
        }
    }

    private static ResourceTransferEngine.Handle handle(ResourcePort port, BlockPos position) {
        return new ResourceTransferEngine.Handle() {
            public ResourcePort port() {
                return port;
            }

            public Object physicalIdentity() {
                return position;
            }

            public boolean valid() {
                return true;
            }
        };
    }
}
