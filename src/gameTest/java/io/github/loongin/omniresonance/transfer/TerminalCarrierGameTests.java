// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import io.github.loongin.omniresonance.bootstrap.OmniResonanceMod;
import io.github.loongin.omniresonance.config.ServerSettings;
import io.github.loongin.omniresonance.persistence.StorageBucketData;
import io.github.loongin.omniresonance.recovery.RecoveryBuffer;
import io.github.loongin.omniresonance.storage.DomainLedger;
import io.github.loongin.omniresonance.transfer.fixtures.FluidItemProbe;
import java.util.Map;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(OmniResonanceMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TerminalCarrierGameTests {
    private TerminalCarrierGameTests() {}

    private static final class Slot implements TerminalCarrierPort.Slot {
        private ItemStack value;

        Slot(ItemStack value) {
            this.value = value;
        }

        public ItemStack stack() {
            return value;
        }

        public void stack(ItemStack value) {
            this.value = value;
        }

        public boolean current() {
            return true;
        }
    }

    private static DomainLedger ledger() {
        var id = new UUID(90, 1);
        return new DomainLedger(id, Map.of(), i -> StorageBucketData.create(id, i));
    }

    private static TransferWorkBudget budget() {
        return new TransferWorkBudget(100, 1000000, 1000000, () -> 0);
    }

    @GameTest(template = "bootstrap")
    public static void bulkInventoryDepositResumesWithSmallBudgetAndMatchesCompleteComponents(GameTestHelper helper) {
        var player = new net.neoforged.neoforge.common.util.FakePlayer(
                helper.getLevel(), new com.mojang.authlib.GameProfile(new UUID(98, 2), "BulkDeposit"));
        var ledger = ledger();
        var iron = ItemVariant.from(
                new ItemStack(Items.IRON_INGOT), helper.getLevel().registryAccess());
        player.getInventory().setItem(1, new ItemStack(Items.IRON_INGOT, 64));
        player.getInventory().setItem(2, new ItemStack(Items.IRON_INGOT, 32));
        var named = new ItemStack(Items.IRON_INGOT, 64);
        named.set(
                net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                net.minecraft.network.chat.Component.literal("Keep"));
        player.getInventory().setItem(3, named);
        player.inventoryMenu.setCarried(new ItemStack(Items.DIAMOND, 2));
        var operation = new TerminalStorageOperation(1, 0, 0, true, iron.key());
        TerminalStorageOperation.Outcome result = null;
        int steps = 0;
        do {
            var work = new TransferWorkBudget(1, 1000000, 1000000, () -> 0);
            result = operation.step(
                    player, ledger, new RecoveryBuffer(() -> {}), ServerSettings.defaults(), () -> true, work);
            helper.assertTrue(work.calls() <= 6, "Bulk work exceeded one ordinary greedy unit after its soft budget");
            steps++;
        } while (result.status() == io.github.loongin.omniresonance.networking.TerminalStorageResponse.Status.PROGRESS
                && steps < 40);
        helper.assertTrue(
                steps > 1
                        && result.moved() == 96
                        && ledger.amount(iron.key()) == 96
                        && player.getInventory().getItem(1).isEmpty()
                        && player.getInventory().getItem(2).isEmpty()
                        && player.getInventory().getItem(3) == named
                        && named.getCount() == 64
                        && player.inventoryMenu.getCarried().is(Items.DIAMOND),
                "Bulk deposit lost quantity, changed cursor, or included another component variant");
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void shiftClickTakesOneStackToInventoryWithoutChangingCursor(GameTestHelper helper) {
        var player = new net.neoforged.neoforge.common.util.FakePlayer(
                helper.getLevel(), new com.mojang.authlib.GameProfile(new UUID(98, 1), "QuickTake"));
        var ledger = ledger();
        var iron = ItemVariant.from(
                new ItemStack(Items.IRON_INGOT), helper.getLevel().registryAccess());
        try (var deposit = ledger.reserveDeposit(iron.key(), 96, -1).orElseThrow()) {
            deposit.commit(96);
        }
        var recovery = new RecoveryBuffer(() -> {});
        var result = new TerminalStorageOperation(-1, ledger.sequence(iron.key()), 0, true)
                .step(player, ledger, recovery, ServerSettings.defaults(), () -> true, budget());
        helper.assertTrue(
                result.moved() == 64
                        && player.inventoryMenu.getCarried().isEmpty()
                        && player.getInventory().getItem(9).is(Items.IRON_INGOT)
                        && player.getInventory().getItem(9).getCount() == 64
                        && ledger.amount(iron.key()) == 32,
                "Shift extraction did not place one stack directly in player inventory");
        player.inventoryMenu.setCarried(new ItemStack(Items.DIAMOND, 3));
        result = new TerminalStorageOperation(-1, ledger.sequence(iron.key()), 0, true)
                .step(player, ledger, recovery, ServerSettings.defaults(), () -> true, budget());
        helper.assertTrue(
                result.moved() == 32
                        && player.inventoryMenu.getCarried().is(Items.DIAMOND)
                        && player.inventoryMenu.getCarried().getCount() == 3,
                "Shift extraction consumed the cursor instead");
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void automaticBucketReturnsToDomainWhenPermissionChangesBeforeFill(GameTestHelper helper) {
        var player = new net.neoforged.neoforge.common.util.FakePlayer(
                helper.getLevel(), new com.mojang.authlib.GameProfile(new UUID(97, 3), "BucketPermission"));
        var ledger = ledger();
        var water = FluidVariant.from(
                new FluidStack(Fluids.WATER, 1000), helper.getLevel().registryAccess());
        var bucket =
                ItemVariant.from(new ItemStack(Items.BUCKET), helper.getLevel().registryAccess());
        try (var deposit = ledger.reserveDeposit(water.key(), 1000, -1).orElseThrow()) {
            deposit.commit(1000);
        }
        try (var deposit = ledger.reserveDeposit(bucket.key(), 1, -1).orElseThrow()) {
            deposit.commit(1);
        }
        int[] checks = {0};
        var result = new TerminalStorageOperation(-1, ledger.sequence(water.key()), 0, false)
                .step(
                        player,
                        ledger,
                        new RecoveryBuffer(() -> {}),
                        ServerSettings.defaults(),
                        () -> ++checks[0] <= 2,
                        budget());
        helper.assertTrue(
                result.moved() == 0
                        && player.inventoryMenu.getCarried().isEmpty()
                        && ledger.amount(water.key()) == 1000
                        && ledger.amount(bucket.key()) == 1
                        && !ledger.hasReservations(),
                "Revocation between automatic bucket acquisition and filling stranded a bucket or changed fluid");
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void bucketLeaseRestoresFullDomainIdentityAndNeverOverwritesChangedInventory(GameTestHelper helper) {
        var player = new net.neoforged.neoforge.common.util.FakePlayer(
                helper.getLevel(), new com.mojang.authlib.GameProfile(new UUID(97, 2), "BucketReturn"));
        var ledger = ledger();
        var water = FluidVariant.from(
                new FluidStack(Fluids.WATER, 1000), helper.getLevel().registryAccess());
        try (var deposit = ledger.reserveDeposit(water.key(), 1000, -1).orElseThrow()) {
            deposit.commit(1000);
        }
        var named = new ItemStack(Items.BUCKET);
        named.set(
                net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                net.minecraft.network.chat.Component.literal("Domain"));
        var variant = ItemVariant.from(named, helper.getLevel().registryAccess());
        try (var deposit = ledger.reserveDeposit(variant.key(), 1, -1).orElseThrow()) {
            deposit.commit(1);
        }
        try (var lease = new TerminalBucketLease.Search().acquire(player, ledger, water, () -> true, budget())) {
            helper.assertTrue(lease != null && ledger.amount(variant.key()) == 0, "Named domain bucket was not found");
            lease.restore(budget());
        }
        helper.assertTrue(
                ledger.amount(variant.key()) == 1
                        && player.inventoryMenu.getCarried().isEmpty(),
                "Rejected fill lost the original complete bucket identity");
        player.getInventory().setItem(3, new ItemStack(Items.BUCKET, 2));
        try (var lease = new TerminalBucketLease.Search().acquire(player, ledger, water, () -> true, budget())) {
            player.getInventory()
                    .getItem(3)
                    .set(
                            net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                            net.minecraft.network.chat.Component.literal("Changed"));
            lease.restore(budget());
        }
        helper.assertTrue(
                player.getInventory().getItem(3).getHoverName().getString().equals("Changed")
                        && player.getInventory().getItem(3).getCount() == 1
                        && player.getInventory().getItem(0).is(Items.BUCKET),
                "Restoring a bucket overwrote an in-place change to the source remainder");
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void shiftAutomaticBucketsGoToInventoryAndFullInventoryDoesNotConsumeResources(
            GameTestHelper helper) {
        for (boolean playerBucket : new boolean[] {false, true}) {
            var player = new net.neoforged.neoforge.common.util.FakePlayer(
                    helper.getLevel(),
                    new com.mojang.authlib.GameProfile(new UUID(197, playerBucket ? 1 : 2), "ShiftBucket"));
            var ledger = ledger();
            var fluid = FluidVariant.from(
                    new FluidStack(Fluids.WATER, 1), helper.getLevel().registryAccess());
            var empty = ItemVariant.from(
                    new ItemStack(Items.BUCKET), helper.getLevel().registryAccess());
            try (var d = ledger.reserveDeposit(fluid.key(), 2000, -1).orElseThrow()) {
                d.commit(2000);
            }
            if (playerBucket) player.getInventory().setItem(4, new ItemStack(Items.BUCKET, 2));
            else
                try (var d = ledger.reserveDeposit(empty.key(), 2, -1).orElseThrow()) {
                    d.commit(2);
                }
            var result = new TerminalStorageOperation(-1, ledger.sequence(fluid.key()), 0, true)
                    .step(
                            player,
                            ledger,
                            new RecoveryBuffer(() -> {}),
                            ServerSettings.defaults(),
                            () -> true,
                            budget());
            helper.assertTrue(
                    result.moved() == 1000
                            && player.inventoryMenu.getCarried().isEmpty()
                            && player.getInventory().countItem(Items.WATER_BUCKET) == 1
                            && ledger.amount(fluid.key()) == 1000,
                    "Shift automatic bucket was not delivered directly to inventory");
            helper.assertTrue(
                    playerBucket ? player.getInventory().countItem(Items.BUCKET) == 1 : ledger.amount(empty.key()) == 1,
                    "Shift bucket consumed wrong number of empty buckets");
        }
        var player = new net.neoforged.neoforge.common.util.FakePlayer(
                helper.getLevel(), new com.mojang.authlib.GameProfile(new UUID(197, 3), "FullShiftBucket"));
        for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(Items.STONE, 64));
        player.getInventory().setItem(0, new ItemStack(Items.BUCKET, 2));
        var ledger = ledger();
        var fluid = FluidVariant.from(
                new FluidStack(Fluids.LAVA, 1), helper.getLevel().registryAccess());
        try (var d = ledger.reserveDeposit(fluid.key(), 2000, -1).orElseThrow()) {
            d.commit(2000);
        }
        var blocked = new TerminalStorageOperation(-1, ledger.sequence(fluid.key()), 0, true)
                .step(player, ledger, new RecoveryBuffer(() -> {}), ServerSettings.defaults(), () -> true, budget());
        helper.assertTrue(
                blocked.status() == io.github.loongin.omniresonance.networking.TerminalStorageResponse.Status.NO_SPACE
                        && ledger.amount(fluid.key()) == 2000
                        && player.getInventory().getItem(0).getCount() == 2
                        && player.inventoryMenu.getCarried().isEmpty(),
                "Full inventory consumed fluid or moved a bucket");
        player.getInventory().setItem(8, new ItemStack(Items.BUCKET));
        var filled = new TerminalStorageOperation(-1, ledger.sequence(fluid.key()), 0, true)
                .step(player, ledger, new RecoveryBuffer(() -> {}), ServerSettings.defaults(), () -> true, budget());
        helper.assertTrue(
                filled.moved() == 1000
                        && player.getInventory().getItem(8).is(Items.LAVA_BUCKET)
                        && player.getInventory().getItem(0).getCount() == 2
                        && player.inventoryMenu.getCarried().isEmpty(),
                "Shift did not reuse a single bucket slot when inventory was otherwise full");
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void emptyCursorFindsOneBucketInPlayerInventoryThenDomain(GameTestHelper helper) {
        var player = new net.neoforged.neoforge.common.util.FakePlayer(
                helper.getLevel(), new com.mojang.authlib.GameProfile(new UUID(97, 1), "AutoBucket"));
        var ledger = ledger();
        var water = FluidVariant.from(
                new FluidStack(Fluids.WATER, 1000), helper.getLevel().registryAccess());
        var lava = FluidVariant.from(
                new FluidStack(Fluids.LAVA, 1000), helper.getLevel().registryAccess());
        var bucket =
                ItemVariant.from(new ItemStack(Items.BUCKET), helper.getLevel().registryAccess());
        for (var variant : java.util.List.of(water, lava))
            try (var deposit = ledger.reserveDeposit(variant.key(), 2000, -1).orElseThrow()) {
                deposit.commit(2000);
            }
        try (var deposit = ledger.reserveDeposit(bucket.key(), 2, -1).orElseThrow()) {
            deposit.commit(2);
        }
        player.getInventory().setItem(4, new ItemStack(Items.BUCKET, 2));
        var recovery = new RecoveryBuffer(() -> {});
        var result = new TerminalStorageOperation(-1, ledger.sequence(lava.key()), 0, false)
                .step(player, ledger, recovery, ServerSettings.defaults(), () -> true, budget());
        helper.assertTrue(
                result.moved() == 1000
                        && player.inventoryMenu.getCarried().is(Items.LAVA_BUCKET)
                        && player.getInventory().getItem(4).getCount() == 1
                        && ledger.amount(bucket.key()) == 2,
                "Empty cursor did not prefer a player bucket and create a lava bucket");
        player.inventoryMenu.setCarried(ItemStack.EMPTY);
        player.getInventory().setItem(4, ItemStack.EMPTY);
        result = new TerminalStorageOperation(-1, ledger.sequence(water.key()), 0, false)
                .step(player, ledger, recovery, ServerSettings.defaults(), () -> true, budget());
        helper.assertTrue(
                result.moved() == 1000
                        && player.inventoryMenu.getCarried().is(Items.WATER_BUCKET)
                        && ledger.amount(bucket.key()) == 1
                        && ledger.amount(water.key()) == 1000,
                "Empty cursor did not consume exactly one domain bucket and one bucket of water");
        player.inventoryMenu.setCarried(ItemStack.EMPTY);
        try (var withdrawal = ledger.withdraw(water.key(), 999).orElseThrow()) {}
        result = new TerminalStorageOperation(-1, ledger.sequence(water.key()), 0, false)
                .step(player, ledger, recovery, ServerSettings.defaults(), () -> true, budget());
        helper.assertTrue(
                result.moved() == 0
                        && player.inventoryMenu.getCarried().isEmpty()
                        && ledger.amount(bucket.key()) == 1
                        && ledger.amount(water.key()) == 1,
                "Insufficient fluid consumed or moved an empty bucket");
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void terminalCarrierFinishesOneBoundedUnitWithOneCallBudget(GameTestHelper helper) {
        var player = new net.neoforged.neoforge.common.util.FakePlayer(
                helper.getLevel(), new com.mojang.authlib.GameProfile(new UUID(93, 2), "SmallBudget"));
        player.inventoryMenu.setCarried(new ItemStack(Items.WATER_BUCKET));
        var budget = new TransferWorkBudget(1, 1000000, 1000000, () -> 0);
        var result = new TerminalStorageOperation(-1, 0, 1, false)
                .step(player, ledger(), new RecoveryBuffer(() -> {}), ServerSettings.defaults(), () -> true, budget);
        helper.assertTrue(
                result.moved() == 1000 && budget.calls() <= 15 && budget.calls() > 1,
                "A one-call soft budget starved a bounded carrier unit or exceeded its call bound");
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void terminalClicksUseActualCursorAndAe2CarrierDirections(GameTestHelper helper) {
        var player = new net.neoforged.neoforge.common.util.FakePlayer(
                helper.getLevel(), new com.mojang.authlib.GameProfile(new UUID(93, 1), "Clicks"));
        var ledger = ledger();
        var recovery = new RecoveryBuffer(() -> {});
        var settings = ServerSettings.defaults();
        player.inventoryMenu.setCarried(new ItemStack(Items.IRON_INGOT, 33));
        var deposit = new TerminalStorageOperation(-1, 0, 0, false);
        var result = deposit.step(player, ledger, recovery, settings, () -> true, budget());
        var iron = ItemVariant.from(
                new ItemStack(Items.IRON_INGOT), helper.getLevel().registryAccess());
        helper.assertTrue(
                result.moved() == 33 && player.inventoryMenu.getCarried().isEmpty(), "Item deposit failed");
        var take = new TerminalStorageOperation(-1, ledger.sequence(iron.key()), 1, false);
        result = take.step(player, ledger, recovery, settings, () -> true, budget());
        helper.assertTrue(
                result.moved() == 17 && player.inventoryMenu.getCarried().getCount() == 17,
                "Right click did not take half of the actual available stack");
        player.inventoryMenu.setCarried(new ItemStack(Items.WATER_BUCKET));
        result = new TerminalStorageOperation(-1, 0, 1, false)
                .step(player, ledger, recovery, settings, () -> true, budget());
        var water = FluidVariant.from(
                new FluidStack(Fluids.WATER, 1000), helper.getLevel().registryAccess());
        helper.assertTrue(
                result.moved() == 1000
                        && ledger.amount(water.key()) == 1000
                        && player.inventoryMenu.getCarried().is(Items.BUCKET),
                "Blank right click did not empty carrier");
        result = new TerminalStorageOperation(-1, ledger.sequence(water.key()), 0, false)
                .step(player, ledger, recovery, settings, () -> true, budget());
        helper.assertTrue(
                result.moved() == 1000 && player.inventoryMenu.getCarried().is(Items.WATER_BUCKET),
                "Left click did not fill carrier");
        result = new TerminalStorageOperation(-1, 0, 1, false)
                .step(player, ledger, recovery, settings, () -> false, budget());
        helper.assertTrue(
                result.moved() == 0 && player.inventoryMenu.getCarried().is(Items.WATER_BUCKET),
                "Denied operation modified a carrier");
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void energyCarrierStateSurvivesReplacementAndReturnsActualQuantities(GameTestHelper helper) {
        var player = new net.neoforged.neoforge.common.util.FakePlayer(
                helper.getLevel(), new com.mojang.authlib.GameProfile(new UUID(92, 1), "Energy"));
        player.inventoryMenu.setCarried(new ItemStack(Items.DIAMOND_PICKAXE));
        var ledger = ledger();
        try (var put =
                ledger.reserveDeposit(EnergyVariant.INSTANCE.key(), 100000, -1).orElseThrow()) {
            put.commit(100000);
        }
        var owner = new PlayerCarrierOwner(player);
        var port = TerminalCarrierPort.energy(
                owner,
                owner.stack(),
                new io.github.loongin.omniresonance.transfer.fixtures.StackEnergyStorage(owner.stack(), 100000),
                () -> true);
        var engine = new DomainTransferEngine();
        var recovery = new RecoveryBuffer(() -> {});
        var limits = ServerSettings.defaults().recoveryLimits();
        var fill = engine.withdrawGreedy(
                ledger, port, 0, EnergyVariant.INSTANCE, 25000, () -> true, recovery, limits, budget());
        helper.assertTrue(
                fill.moved() == 25000
                        && new io.github.loongin.omniresonance.transfer.fixtures.StackEnergyStorage(
                                                player.inventoryMenu.getCarried(), 100000)
                                        .getEnergyStored()
                                == 25000,
                "Settling the charged item lost its FE components");
        var next = new PlayerCarrierOwner(player);
        var source = TerminalCarrierPort.energy(
                next,
                next.stack(),
                new io.github.loongin.omniresonance.transfer.fixtures.StackEnergyStorage(next.stack(), 100000),
                () -> true);
        var drained = engine.depositGreedy(
                source, 0, ledger, EnergyVariant.INSTANCE, 10000, -1, () -> true, recovery, limits, budget());
        helper.assertTrue(
                drained.moved() == 10000
                        && ledger.amount(EnergyVariant.INSTANCE.key()) == 85000
                        && new io.github.loongin.omniresonance.transfer.fixtures.StackEnergyStorage(
                                                player.inventoryMenu.getCarried(), 100000)
                                        .getEnergyStored()
                                == 15000,
                "FE carrier/domain quantities diverged");
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void reentrantCarrierReplacementIsNotOverwritten(GameTestHelper helper) {
        var slot = new Slot(new ItemStack(Items.WATER_BUCKET));
        var probe = new FluidItemProbe(slot.stack().getCapability(Capabilities.FluidHandler.ITEM));
        probe.afterMutation = () -> slot.stack(new ItemStack(Items.DIAMOND));
        var port = TerminalCarrierPort.fluid(
                slot, slot.stack(), probe, helper.getLevel().registryAccess(), () -> true);
        var variant = FluidVariant.from(
                new FluidStack(Fluids.WATER, 1000), helper.getLevel().registryAccess());
        var ledger = ledger();
        var result = new DomainTransferEngine()
                .depositGreedy(
                        port,
                        0,
                        ledger,
                        variant,
                        1000,
                        -1,
                        () -> true,
                        new RecoveryBuffer(() -> {}),
                        ServerSettings.defaults().recoveryLimits(),
                        budget());
        helper.assertTrue(
                result.failure() == ResourceTransferEngine.Failure.UNKNOWN_MUTATION
                        && slot.stack().is(Items.DIAMOND)
                        && ledger.amount(variant.key()) == 0
                        && probe.mutations == 1,
                "Foreign slot content was overwritten or unknown extraction credited");
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void stackedBucketsKeepUnprocessedItemsAndUseInventoryThenWorldOverflow(GameTestHelper helper) {
        for (boolean full : new boolean[] {false, true}) {
            var player = new net.neoforged.neoforge.common.util.FakePlayer(
                    helper.getLevel(), new com.mojang.authlib.GameProfile(new UUID(91, full ? 2 : 1), "Carrier"));
            var position = helper.absolutePos(net.minecraft.core.BlockPos.ZERO);
            player.setPos(position.getX() + 0.5, position.getY() + 2, position.getZ() + 0.5);
            if (full) for (int i = 0; i < 36; i++) player.getInventory().setItem(i, new ItemStack(Items.STONE, 64));
            player.inventoryMenu.setCarried(new ItemStack(Items.BUCKET, 2));
            var owner = new PlayerCarrierOwner(player);
            var carrier = TerminalCarrierPort.fluid(
                    owner,
                    owner.stack(),
                    owner.stack().getCapability(Capabilities.FluidHandler.ITEM),
                    helper.getLevel().registryAccess(),
                    () -> true);
            var variant = FluidVariant.from(
                    new FluidStack(Fluids.WATER, 1000), helper.getLevel().registryAccess());
            var ledger = ledger();
            try (var put = ledger.reserveDeposit(variant.key(), 1000, -1).orElseThrow()) {
                put.commit(1000);
            }
            var budget = budget();
            var result = new DomainTransferEngine()
                    .withdrawGreedy(
                            ledger,
                            carrier,
                            0,
                            variant,
                            1000,
                            () -> true,
                            new RecoveryBuffer(() -> {}),
                            ServerSettings.defaults().recoveryLimits(),
                            budget);
            helper.assertTrue(
                    result.moved() == 1000 && ledger.amount(variant.key()) == 0,
                    "Stacked bucket extraction changed conservation");
            helper.assertTrue(
                    player.inventoryMenu.getCarried().is(Items.BUCKET)
                            && player.inventoryMenu.getCarried().getCount() == 1,
                    "Unprocessed empty buckets were lost or filled together");
            int stored = 0;
            for (int i = 0; i < 36; i++)
                if (player.getInventory().getItem(i).is(Items.WATER_BUCKET))
                    stored += player.getInventory().getItem(i).getCount();
            helper.assertTrue(stored == (full ? 0 : 1), "Processed bucket was not placed into available inventory");
            if (full) {
                var drops = helper.getLevel()
                        .getEntitiesOfClass(
                                net.minecraft.world.entity.item.ItemEntity.class,
                                player.getBoundingBox().inflate(0.5),
                                item -> item.getItem().is(Items.WATER_BUCKET));
                helper.assertTrue(
                        drops.size() == 1 && drops.getFirst().getItem().getCount() == 1,
                        "Full inventory did not preserve the carrier as a normal player drop");
            }
            helper.assertTrue(
                    budget.calls() <= ResourceTransferEngine.maximumGreedyCalls(0, carrier.maximumMutationCalls()),
                    "Carrier world placement exceeded its declared native bound");
        }
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void playerItemSlotDepositsAndReceivesWithoutPredictingOrOverfilling(GameTestHelper helper) {
        var slot = new Slot(new ItemStack(Items.IRON_INGOT, 32));
        var variant = ItemVariant.from(slot.stack(), helper.getLevel().registryAccess());
        var endpoint = new TerminalItemSlotPort(slot, 64, helper.getLevel().registryAccess(), () -> true);
        var ledger = ledger();
        var recovery = new RecoveryBuffer(() -> {});
        var engine = new DomainTransferEngine();
        var deposit = engine.depositGreedy(
                endpoint,
                0,
                ledger,
                variant,
                32,
                -1,
                () -> true,
                recovery,
                ServerSettings.defaults().recoveryLimits(),
                budget());
        helper.assertTrue(
                deposit.moved() == 32 && slot.stack().isEmpty() && ledger.amount(variant.key()) == 32,
                "Player item deposit changed conservation");
        var target = new TerminalItemSlotPort(slot, 8, helper.getLevel().registryAccess(), () -> true);
        var take = engine.withdrawGreedy(
                ledger,
                target,
                0,
                variant,
                32,
                () -> true,
                recovery,
                ServerSettings.defaults().recoveryLimits(),
                budget());
        helper.assertTrue(
                take.moved() == 8 && slot.stack().getCount() == 8 && ledger.amount(variant.key()) == 24,
                "Player item target exceeded its slot capacity");
        slot.stack(new ItemStack(Items.DIAMOND));
        var blocked = engine.withdrawGreedy(
                ledger,
                new TerminalItemSlotPort(slot, 64, helper.getLevel().registryAccess(), () -> true),
                0,
                variant,
                1,
                () -> true,
                recovery,
                ServerSettings.defaults().recoveryLimits(),
                budget());
        helper.assertTrue(
                blocked.moved() == 0 && slot.stack().is(Items.DIAMOND) && ledger.amount(variant.key()) == 24,
                "Conflicting carried stack was overwritten");
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void nativeBucketSettlesNewContainerAndCanDepositThenWithdraw(GameTestHelper helper) {
        var slot = new Slot(new ItemStack(Items.WATER_BUCKET));
        var probe = new FluidItemProbe(slot.stack().getCapability(Capabilities.FluidHandler.ITEM));
        var carrier = TerminalCarrierPort.fluid(
                slot, slot.stack(), probe, helper.getLevel().registryAccess(), () -> true);
        var variant = FluidVariant.from(
                new FluidStack(Fluids.WATER, 1000), helper.getLevel().registryAccess());
        var ledger = ledger();
        var recovery = new RecoveryBuffer(() -> {});
        var limits = ServerSettings.defaults().recoveryLimits();
        var budget = budget();
        carrier.sourceViews(budget);
        helper.assertTrue(carrier.extract(0, variant, 1000, true, budget) == 1000, "Bucket simulation failed");
        helper.assertTrue(
                probe.containerCalls == 0 && slot.stack().is(Items.WATER_BUCKET), "Simulation settled the carrier");
        var deposited = new DomainTransferEngine()
                .depositGreedy(carrier, 0, ledger, variant, 1000, -1, () -> true, recovery, limits, budget());
        helper.assertTrue(
                deposited.moved() == 1000
                        && ledger.amount(variant.key()) == 1000
                        && slot.stack().is(Items.BUCKET),
                "Deposit did not settle empty bucket");
        var receiver = TerminalCarrierPort.fluid(
                slot,
                slot.stack(),
                slot.stack().getCapability(Capabilities.FluidHandler.ITEM),
                helper.getLevel().registryAccess(),
                () -> true);
        var withdrawn = new DomainTransferEngine()
                .withdrawGreedy(ledger, receiver, 0, variant, 1000, () -> true, recovery, limits, budget());
        helper.assertTrue(
                withdrawn.moved() == 1000
                        && ledger.amount(variant.key()) == 0
                        && slot.stack().is(Items.WATER_BUCKET),
                "Withdrawal did not settle filled bucket");
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void failedContainerReadNeverCreditsUnknownExtractionOrRetriesIt(GameTestHelper helper) {
        var slot = new Slot(new ItemStack(Items.WATER_BUCKET));
        var probe = new FluidItemProbe(slot.stack().getCapability(Capabilities.FluidHandler.ITEM));
        probe.failContainer = true;
        var carrier = TerminalCarrierPort.fluid(
                slot, slot.stack(), probe, helper.getLevel().registryAccess(), () -> true);
        var variant = FluidVariant.from(
                new FluidStack(Fluids.WATER, 1000), helper.getLevel().registryAccess());
        var ledger = ledger();
        var recovery = new RecoveryBuffer(() -> {});
        var result = new DomainTransferEngine()
                .depositGreedy(
                        carrier,
                        0,
                        ledger,
                        variant,
                        1000,
                        -1,
                        () -> true,
                        recovery,
                        ServerSettings.defaults().recoveryLimits(),
                        budget());
        helper.assertTrue(
                result.failure() == ResourceTransferEngine.Failure.UNKNOWN_MUTATION
                        && result.unknownStage() == ResourceTransferEngine.Stage.SOURCE_EXTRACT,
                "Carrier getter failure was treated as a known extraction");
        helper.assertTrue(
                probe.mutations == 1 && ledger.amount(variant.key()) == 0 && recovery.isEmpty(),
                "Unknown extraction was retried or fabricated into storage");
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void permissionLossReturnsKnownFluidToTheOriginalCarrier(GameTestHelper helper) {
        var slot = new Slot(new ItemStack(Items.WATER_BUCKET));
        boolean[] authorized = {true};
        var probe = new FluidItemProbe(slot.stack().getCapability(Capabilities.FluidHandler.ITEM));
        probe.afterMutation = () -> authorized[0] = false;
        var carrier = TerminalCarrierPort.fluid(
                slot, slot.stack(), probe, helper.getLevel().registryAccess(), () -> authorized[0]);
        var variant = FluidVariant.from(
                new FluidStack(Fluids.WATER, 1000), helper.getLevel().registryAccess());
        var ledger = ledger();
        var result = new DomainTransferEngine()
                .depositGreedy(
                        carrier,
                        0,
                        ledger,
                        variant,
                        1000,
                        -1,
                        () -> authorized[0],
                        new RecoveryBuffer(() -> {}),
                        ServerSettings.defaults().recoveryLimits(),
                        budget());
        helper.assertTrue(
                result.returned() == 1000
                        && ledger.amount(variant.key()) == 0
                        && slot.stack().is(Items.WATER_BUCKET),
                "Known return lost the original carrier after revocation");
        helper.succeed();
    }
}
