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
    public static void replayAndBusyClicksCannotExecuteLater(GameTestHelper helper) {
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
            var busy =
                    new TerminalStorageRequest(view, session, 1, 2, 0, player.inventoryMenu.getStateId(), -1, 0, false);
            service.request(player, first);
            service.request(player, busy);
            helper.assertTrue(
                    replies.getLast().status() == TerminalStorageResponse.Status.BUSY, "Second pending click admitted");
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
    public static void pendingWritesRevalidateReloadPermissionAndRealCursor(GameTestHelper helper) {
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
                    replies.getLast().status() == TerminalStorageResponse.Status.STALE && ledger.variantCount() == 0,
                    "In-place cursor modification escaped snapshot validation");
            service.request(
                    player,
                    new TerminalStorageRequest(
                            view, session, 1, 3, 0, player.inventoryMenu.getStateId(), -1, 0, false));
            allowed[0] = false;
            service.tick(budget());
            helper.assertTrue(
                    replies.getLast().status() == TerminalStorageResponse.Status.DENIED && ledger.variantCount() == 0,
                    "Permission or sync-readiness loss allowed a pending write");
            helper.assertTrue(audit.isEmpty(), "Rejected writes must not append an audit entry");
        }
        helper.succeed();
    }
}
