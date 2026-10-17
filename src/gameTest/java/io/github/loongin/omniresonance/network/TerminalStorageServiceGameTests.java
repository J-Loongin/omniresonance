// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.network;

import io.github.loongin.omniresonance.bootstrap.OmniResonanceMod;
import io.github.loongin.omniresonance.config.ServerSettings;
import io.github.loongin.omniresonance.networking.TerminalStorageRequest;
import io.github.loongin.omniresonance.networking.TerminalStorageResponse;
import io.github.loongin.omniresonance.persistence.StorageBucketData;
import io.github.loongin.omniresonance.recovery.RecoveryBuffer;
import io.github.loongin.omniresonance.storage.DomainLedger;
import io.github.loongin.omniresonance.transfer.ItemVariant;
import io.github.loongin.omniresonance.transfer.TransferWorkBudget;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(OmniResonanceMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TerminalStorageServiceGameTests {
    private TerminalStorageServiceGameTests() {}

    static ServerSettings writable() {
        var base = ServerSettings.defaults();
        return new ServerSettings(
                base.networksPerOwner(),
                base.tunnelsPerNetwork(),
                base.channelsPerTunnel(),
                base.channelBindingsPerDirectNode(),
                base.administratorsPerNetwork(),
                base.scheduler(),
                base.filterLimits(),
                base.recoveryLimits(),
                base.storageVariantLimitPerNetwork(),
                base.terminalSync(),
                ServerSettings.DirectStorageAccess.READ_WRITE);
    }

    private static TransferWorkBudget budget() {
        return new TransferWorkBudget(100, 1000000, 1000000, () -> 0);
    }

    @GameTest(template = "bootstrap")
    public static void legacyEffectOrderRecoversThroughTerminalAndNativeItemPort(GameTestHelper helper) {
        var player =
                new FakePlayer(helper.getLevel(), new com.mojang.authlib.GameProfile(new UUID(120, 1), "LegacyFood"));
        player.initInventoryMenu();
        UUID network = new UUID(120, 2), view = new UUID(120, 3), session = new UUID(120, 4);
        var fresh = ItemVariant.from(new ItemStack(Items.ENCHANTED_GOLDEN_APPLE), player.registryAccess());
        var legacy = io.github.loongin.omniresonance.transfer.fixtures.LegacyEffectKeys.reverse(fresh.key());
        var bucket = StorageBucketData.create(
                network, io.github.loongin.omniresonance.storage.StorageBucketHash.bucket(legacy));
        bucket.setAmount(legacy, 64);
        var ledger = new DomainLedger(
                network, Map.of(bucket.bucketIndex(), bucket), i -> StorageBucketData.create(network, i));
        var replies = new ArrayList<TerminalStorageResponse>();
        try (var service = new TerminalStorageService(
                TerminalStorageServiceGameTests::writable,
                (p, s, g, n) -> true,
                n -> ledger,
                n -> new RecoveryBuffer(() -> {}),
                (p, reply) -> replies.add(reply),
                (n, entry) -> {})) {
            service.open(player, view, session, 1, network);
            service.request(
                    player,
                    new TerminalStorageRequest(view, session, 1, 1, ledger.sequence(fresh.key()), 0, -1, 0, true));
            service.tick(budget());
            helper.assertTrue(
                    player.getInventory().countItem(Items.ENCHANTED_GOLDEN_APPLE) == 64
                            && ledger.amount(fresh.key()) == 0,
                    "Legacy apple could not be taken from the terminal without loss");
            service.request(player, new TerminalStorageRequest(view, session, 1, 2, 0, 0, 9, 0, true));
            service.tick(budget());
            helper.assertTrue(
                    ledger.amount(fresh.key()) == 64 && player.getInventory().isEmpty(),
                    "Recovered apple could not be deposited again");
            helper.assertTrue(
                    replies.getLast().status() == TerminalStorageResponse.Status.COMPLETE, "Legacy action failed");
        }
        var target = new net.neoforged.neoforge.items.ItemStackHandler(1);
        var port = new io.github.loongin.omniresonance.transfer.ItemResourcePort(target, player.registryAccess());
        var work = budget();
        port.targetViews(work);
        var restored = ItemVariant.restore(legacy, player.registryAccess());
        helper.assertTrue(
                port.insert(0, restored, 64, true, work) == 64
                        && target.getStackInSlot(0).isEmpty(),
                "Legacy simulation mutated inventory");
        helper.assertTrue(
                port.insert(0, restored, 64, false, work) == 64
                        && target.getStackInSlot(0).getCount() == 64,
                "Native item endpoint rejected restored legacy identity");
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void tinyBudgetsRotatePlayersInsteadOfDrainingOnePlayersQueueFirst(GameTestHelper helper) {
        var a = new FakePlayer(helper.getLevel(), new com.mojang.authlib.GameProfile(new UUID(113, 1), "FirstQueue"));
        var b = new FakePlayer(helper.getLevel(), new com.mojang.authlib.GameProfile(new UUID(113, 2), "SecondQueue"));
        UUID view = new UUID(113, 3), session = new UUID(113, 4), network = new UUID(113, 5);
        var ledger = new DomainLedger(network, Map.of(), i -> StorageBucketData.create(network, i));
        try (var service = new TerminalStorageService(
                TerminalStorageServiceGameTests::writable,
                (p, s, g, n) -> true,
                n -> ledger,
                n -> new RecoveryBuffer(() -> {}),
                (p, reply) -> {},
                (n, entry) -> {})) {
            for (var player : java.util.List.of(a, b)) {
                service.open(player, view, session, 1, network);
                for (int slot = 0; slot < 9; slot++) {
                    player.getInventory().setItem(slot, new ItemStack(Items.IRON_INGOT, 64));
                    service.request(
                            player, new TerminalStorageRequest(view, session, 1, slot + 1, 0, 0, slot, 0, true));
                }
            }
            service.tick(new TransferWorkBudget(1, 1000000, 1000000, () -> 0));
            service.tick(new TransferWorkBudget(1, 1000000, 1000000, () -> 0));
            helper.assertTrue(
                    a.getInventory().getItem(0).isEmpty()
                            && b.getInventory().getItem(0).isEmpty(),
                    "The first player's queue starved the second player");
            helper.assertTrue(!a.getInventory().getItem(1).isEmpty(), "The first player bypassed the shared budget");
        }
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void revocationAndCloseCancelAcceptedWorkWithoutReplayingAfterRestore(GameTestHelper helper) {
        var player =
                new FakePlayer(helper.getLevel(), new com.mojang.authlib.GameProfile(new UUID(114, 1), "CancelQueue"));
        UUID view = new UUID(114, 2), session = new UUID(114, 3), network = new UUID(114, 4);
        var ledger = new DomainLedger(network, Map.of(), i -> StorageBucketData.create(network, i));
        boolean[] allowed = {true};
        try (var service = new TerminalStorageService(
                TerminalStorageServiceGameTests::writable,
                (p, s, g, n) -> allowed[0],
                n -> ledger,
                n -> new RecoveryBuffer(() -> {}),
                (p, reply) -> {},
                (n, entry) -> {})) {
            service.open(player, view, session, 1, network);
            for (int slot = 0; slot < 2; slot++) {
                player.getInventory().setItem(slot, new ItemStack(Items.IRON_INGOT, 8));
                service.request(player, new TerminalStorageRequest(view, session, 1, slot + 1, 0, 0, slot, 0, true));
            }
            allowed[0] = false;
            service.request(player, new TerminalStorageRequest(view, session, 1, 3, 0, 0, 0, 0, true));
            allowed[0] = true;
            service.tick(budget());
            helper.assertTrue(ledger.variantCount() == 0, "Revoked queued work revived after permission returned");
            service.request(player, new TerminalStorageRequest(view, session, 1, 4, 0, 0, 0, 0, true));
            service.request(player, new TerminalStorageRequest(view, session, 1, 5, 0, 0, 1, 0, true));
            service.close(player.getUUID());
            service.tick(budget());
            helper.assertTrue(
                    ledger.variantCount() == 0 && player.getInventory().countItem(Items.IRON_INGOT) == 16,
                    "Closing executed queued work");
        }
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void queuedQuickMovesIgnoreUnrelatedMenuRevisionsAndDrainWithoutRoundTrips(GameTestHelper helper) {
        var player =
                new FakePlayer(helper.getLevel(), new com.mojang.authlib.GameProfile(new UUID(111, 1), "Pipeline"));
        player.initInventoryMenu();
        UUID view = new UUID(111, 2), session = new UUID(111, 3), network = new UUID(111, 4);
        var ledger = new DomainLedger(network, Map.of(), i -> StorageBucketData.create(network, i));
        var replies = new ArrayList<TerminalStorageResponse>();
        var iron = ItemVariant.from(new ItemStack(Items.IRON_INGOT), player.registryAccess());
        for (int slot = 0; slot < 9; slot++) player.getInventory().setItem(slot, new ItemStack(Items.IRON_INGOT, 64));
        try (var service = new TerminalStorageService(
                TerminalStorageServiceGameTests::writable,
                (p, s, g, n) -> true,
                n -> ledger,
                n -> new RecoveryBuffer(() -> {}),
                (p, reply) -> replies.add(reply),
                (n, entry) -> {})) {
            service.open(player, view, session, 1, network);
            int revision = player.inventoryMenu.getStateId();
            player.inventoryMenu.incrementStateId();
            for (int slot = 0; slot < 9; slot++)
                service.request(
                        player, new TerminalStorageRequest(view, session, 1, slot + 1, 0, revision, slot, 0, true));
            player.getInventory().setItem(35, new ItemStack(Items.DIAMOND));
            player.inventoryMenu.broadcastChanges();
            helper.assertTrue(replies.size() == 1, "Pipelined requests were rejected before execution");
            service.tick(new TransferWorkBudget(1000, 1000000, 1000000, () -> 0));
            helper.assertTrue(
                    ledger.amount(iron.key()) == 576 && replies.size() == 10,
                    "A nine-slot sweep needed per-click network round trips");
            for (int i = 1; i <= 9; i++)
                helper.assertTrue(
                        replies.get(i).sequence() == i
                                && replies.get(i).status() == TerminalStorageResponse.Status.COMPLETE,
                        "Pipeline result order changed");
            helper.assertTrue(player.getInventory().getItem(35).is(Items.DIAMOND), "Unrelated slot was modified");
        }
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void missingResourceIsANoopAndDoesNotCancelTheNextIntent(GameTestHelper helper) {
        var player =
                new FakePlayer(helper.getLevel(), new com.mojang.authlib.GameProfile(new UUID(112, 1), "MissingKey"));
        UUID view = new UUID(112, 2), session = new UUID(112, 3), network = new UUID(112, 4);
        var ledger = new DomainLedger(network, Map.of(), i -> StorageBucketData.create(network, i));
        var replies = new ArrayList<TerminalStorageResponse>();
        player.getInventory().setItem(0, new ItemStack(Items.IRON_INGOT, 8));
        try (var service = new TerminalStorageService(
                TerminalStorageServiceGameTests::writable,
                (p, s, g, n) -> true,
                n -> ledger,
                n -> new RecoveryBuffer(() -> {}),
                (p, reply) -> replies.add(reply),
                (n, entry) -> {})) {
            service.open(player, view, session, 1, network);
            service.request(player, new TerminalStorageRequest(view, session, 1, 1, 999, 0, -1, 0, true));
            service.request(player, new TerminalStorageRequest(view, session, 1, 2, 0, 0, 0, 0, true));
            service.tick(budget());
            helper.assertTrue(
                    replies.size() == 3
                            && replies.get(1).status() == TerminalStorageResponse.Status.COMPLETE
                            && replies.get(1).moved() == 0
                            && replies.get(2).moved() == 8,
                    "A missing identity interrupted an independent following request");
        }
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void doubleQuickMoveUsesThePreviousServerIdentityAfterThatSlotBecomesEmpty(GameTestHelper helper) {
        var player =
                new FakePlayer(helper.getLevel(), new com.mojang.authlib.GameProfile(new UUID(98, 3), "DoubleMove"));
        player.initInventoryMenu();
        UUID view = new UUID(98, 4), session = new UUID(98, 5), network = new UUID(98, 6);
        var ledger = new DomainLedger(network, Map.of(), i -> StorageBucketData.create(network, i));
        var replies = new ArrayList<TerminalStorageResponse>();
        var audit = new ArrayList<io.github.loongin.omniresonance.persistence.AuditEntry>();
        var recovery = new RecoveryBuffer(() -> {});
        var iron = ItemVariant.from(
                new ItemStack(Items.IRON_INGOT), helper.getLevel().registryAccess());
        player.getInventory().setItem(9, new ItemStack(Items.IRON_INGOT, 64));
        player.getInventory().setItem(10, new ItemStack(Items.IRON_INGOT, 32));
        player.getInventory().setItem(11, new ItemStack(Items.IRON_INGOT, 16));
        try (var service = new TerminalStorageService(
                TerminalStorageServiceGameTests::writable,
                (p, s, g, n) -> true,
                n -> ledger,
                n -> recovery,
                (p, response) -> replies.add(response),
                (n, entry) -> audit.add(entry))) {
            service.open(player, view, session, 1, network);
            service.request(
                    player,
                    new TerminalStorageRequest(view, session, 1, 1, 0, player.inventoryMenu.getStateId(), 9, 0, true));
            service.tick(budget());
            helper.assertTrue(
                    ledger.amount(iron.key()) == 64
                            && player.getInventory().getItem(9).isEmpty(),
                    "Single quick move did not move just its source stack");
            service.request(
                    player,
                    new TerminalStorageRequest(
                            view, session, 1, 2, 0, replies.getLast().menuState(), 9, 0, true, true));
            int steps = 0;
            do {
                service.tick(new TransferWorkBudget(1, 1000000, 1000000, () -> 0));
                steps++;
            } while (replies.getLast().status() == TerminalStorageResponse.Status.PROGRESS && steps < 40);
            helper.assertTrue(
                    replies.getLast().status() == TerminalStorageResponse.Status.COMPLETE
                            && replies.getLast().moved() == 48
                            && ledger.amount(iron.key()) == 112
                            && player.getInventory().getItem(10).isEmpty()
                            && player.getInventory().getItem(11).isEmpty()
                            && steps > 1,
                    "Bulk continuation rejected its own updates or forgot the emptied source identity");
            helper.assertTrue(
                    audit.size() == 2
                            && audit.get(0).summary().equals("confirmed_amount=64")
                            && audit.get(1).summary().equals("confirmed_amount=48"),
                    "Bulk progress duplicated or lost its audit");
            service.request(
                    player,
                    new TerminalStorageRequest(
                            view, session, 1, 3, 0, replies.getLast().menuState(), 9, 0, true));
            service.tick(budget());
            service.request(
                    player,
                    new TerminalStorageRequest(
                            view, session, 1, 4, 0, replies.getLast().menuState(), 9, 0, true, true));
            helper.assertTrue(
                    replies.getLast().status() == TerminalStorageResponse.Status.COMPLETE
                            && replies.getLast().moved() == 0
                            && ledger.amount(iron.key()) == 112,
                    "Double-clicking an already empty inventory slot produced a false transfer warning");
        }
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void nativeCursorBroadcastRevisionIsAcknowledgedForTheFollowingClick(GameTestHelper helper) {
        var player = new FakePlayer(
                helper.getLevel(), new com.mojang.authlib.GameProfile(new UUID(96, 1), "CursorRevision"));
        player.initInventoryMenu();
        UUID view = new UUID(96, 2), session = new UUID(96, 3), network = new UUID(96, 4);
        var ledger = new DomainLedger(network, Map.of(), i -> StorageBucketData.create(network, i));
        var iron = ItemVariant.from(
                new ItemStack(Items.IRON_INGOT), helper.getLevel().registryAccess());
        try (var deposit = ledger.reserveDeposit(iron.key(), 64, -1).orElseThrow()) {
            deposit.commit(64);
        }
        var replies = new ArrayList<TerminalStorageResponse>();
        var audit = new ArrayList<io.github.loongin.omniresonance.persistence.AuditEntry>();
        var buffer = new RecoveryBuffer(() -> {});
        try (var service = new TerminalStorageService(
                TerminalStorageServiceGameTests::writable,
                (p, s, g, n) -> true,
                n -> ledger,
                n -> buffer,
                (p, response) -> replies.add(response),
                (n, entry) -> audit.add(entry))) {
            service.open(player, view, session, 1, network);
            int previous = player.inventoryMenu.getStateId();
            service.request(
                    player,
                    new TerminalStorageRequest(
                            view, session, 1, 1, ledger.sequence(iron.key()), previous, -1, 0, false));
            service.tick(budget());
            var result = replies.getLast();
            helper.assertTrue(
                    result.moved() == 64
                            && result.menuState() == player.inventoryMenu.getStateId()
                            && result.menuState() != previous,
                    "Cursor-only broadcast did not return its native final revision");
            service.request(
                    player, new TerminalStorageRequest(view, session, 1, 2, 0, result.menuState(), -1, 0, false));
            service.tick(budget());
            helper.assertTrue(
                    replies.getLast().moved() == 64
                            && player.inventoryMenu.getCarried().isEmpty()
                            && ledger.amount(iron.key()) == 64,
                    "Following click was falsely rejected after cursor-only update");
        }
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void replayAndOverflowClicksCannotExecuteLater(GameTestHelper helper) {
        var player = new FakePlayer(helper.getLevel(), new com.mojang.authlib.GameProfile(new UUID(94, 1), "Replay"));
        UUID view = new UUID(94, 2), session = new UUID(94, 3), network = new UUID(94, 4);
        var ledger = new DomainLedger(network, Map.of(), i -> StorageBucketData.create(network, i));
        var replies = new ArrayList<TerminalStorageResponse>();
        var audit = new ArrayList<io.github.loongin.omniresonance.persistence.AuditEntry>();
        var buffer = new RecoveryBuffer(() -> {});
        try (var service = new TerminalStorageService(
                TerminalStorageServiceGameTests::writable,
                (p, s, g, n) -> true,
                n -> ledger,
                n -> buffer,
                (p, response) -> replies.add(response),
                (n, entry) -> audit.add(entry))) {
            service.open(player, view, session, 1, network);
            player.inventoryMenu.setCarried(new ItemStack(Items.IRON_INGOT, 10));
            var first =
                    new TerminalStorageRequest(view, session, 1, 1, 0, player.inventoryMenu.getStateId(), -1, 0, false);
            var busy = new TerminalStorageRequest(
                    view, session, 1, 65, 0, player.inventoryMenu.getStateId(), -1, 0, false);
            service.request(player, first);
            for (int i = 2; i <= 64; i++)
                service.request(
                        player,
                        new TerminalStorageRequest(
                                view, session, 1, i, 0, player.inventoryMenu.getStateId(), -1, 0, false));
            service.request(player, busy);
            helper.assertTrue(
                    replies.getLast().status() == TerminalStorageResponse.Status.BUSY, "Overflow click was admitted");
            service.tick(budget());
            player.inventoryMenu.setCarried(new ItemStack(Items.IRON_INGOT, 20));
            service.request(player, first);
            service.request(player, busy);
            service.tick(budget());
            var iron = ItemVariant.from(
                    new ItemStack(Items.IRON_INGOT), helper.getLevel().registryAccess());
            helper.assertTrue(
                    ledger.amount(iron.key()) == 10
                            && player.inventoryMenu.getCarried().getCount() == 20,
                    "Repeated or previously busy request executed after completion");
            service.close(player.getUUID());
            helper.assertTrue(player.inventoryMenu.getCarried().isEmpty(), "Closing left the terminal cursor stranded");
        }
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void queuedWritesRevalidatePermissionAndReadTheCursorWhenExecutionStarts(GameTestHelper helper) {
        var player = new FakePlayer(helper.getLevel(), new com.mojang.authlib.GameProfile(new UUID(95, 1), "Recheck"));
        UUID view = new UUID(95, 2), session = new UUID(95, 3), network = new UUID(95, 4);
        var ledger = new DomainLedger(network, Map.of(), i -> StorageBucketData.create(network, i));
        var settings = new AtomicReference<>(writable());
        boolean[] allowed = {true};
        var replies = new ArrayList<TerminalStorageResponse>();
        var audit = new ArrayList<io.github.loongin.omniresonance.persistence.AuditEntry>();
        var buffer = new RecoveryBuffer(() -> {});
        try (var service = new TerminalStorageService(
                settings::get,
                (p, s, g, n) -> allowed[0],
                n -> ledger,
                n -> buffer,
                (p, response) -> replies.add(response),
                (n, entry) -> audit.add(entry))) {
            service.open(player, view, session, 1, network);
            player.inventoryMenu.setCarried(new ItemStack(Items.IRON_INGOT, 10));
            service.request(
                    player,
                    new TerminalStorageRequest(
                            view, session, 1, 1, 0, player.inventoryMenu.getStateId(), -1, 0, false));
            settings.set(ServerSettings.defaults());
            service.tick(budget());
            helper.assertTrue(
                    replies.getLast().status() == TerminalStorageResponse.Status.DENIED && ledger.variantCount() == 0,
                    "Read-only reload allowed a pending write");
            settings.set(writable());
            service.request(
                    player,
                    new TerminalStorageRequest(
                            view, session, 1, 2, 0, player.inventoryMenu.getStateId(), -1, 0, false));
            player.inventoryMenu.getCarried().setCount(11);
            service.tick(budget());
            helper.assertTrue(
                    replies.getLast().status() == TerminalStorageResponse.Status.COMPLETE
                            && replies.getLast().moved() == 11,
                    "A not-yet-started operation rejected current authoritative cursor state");
            service.request(
                    player,
                    new TerminalStorageRequest(
                            view, session, 1, 3, 0, player.inventoryMenu.getStateId(), -1, 0, false));
            allowed[0] = false;
            service.tick(budget());
            helper.assertTrue(
                    replies.getLast().status() == TerminalStorageResponse.Status.DENIED && ledger.variantCount() == 1,
                    "Permission or sync-readiness loss allowed a pending write");
            helper.assertTrue(
                    audit.size() == 1 && audit.getFirst().summary().equals("confirmed_amount=11"),
                    "Only the confirmed cursor deposit may append an audit entry");
        }
        helper.succeed();
    }
}
