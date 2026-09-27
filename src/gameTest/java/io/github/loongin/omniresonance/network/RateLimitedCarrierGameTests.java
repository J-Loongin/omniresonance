// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.network;

import io.github.loongin.omniresonance.bootstrap.OmniResonanceMod;
import io.github.loongin.omniresonance.networking.TerminalStorageRequest;
import io.github.loongin.omniresonance.networking.TerminalStorageResponse;
import io.github.loongin.omniresonance.persistence.StorageBucketData;
import io.github.loongin.omniresonance.recovery.RecoveryBuffer;
import io.github.loongin.omniresonance.storage.DomainLedger;
import io.github.loongin.omniresonance.transfer.FluidVariant;
import io.github.loongin.omniresonance.transfer.TransferWorkBudget;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(OmniResonanceMod.MOD_ID)
@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid = OmniResonanceMod.MOD_ID)
public final class RateLimitedCarrierGameTests {
    private static final String MARKER = "omniresonance_test_carrier";

    private RateLimitedCarrierGameTests() {}

    @SubscribeEvent
    public static void capabilities(RegisterCapabilitiesEvent event) {
        event.registerItem(
                Capabilities.FluidHandler.ITEM,
                (stack, ignored) -> stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                                .copyTag()
                                .getBoolean(MARKER)
                        ? new Carrier(stack)
                        : null,
                Items.PAPER);
    }

    @GameTest(template = "bootstrap")
    public static void shiftCarrierFillAndDrainResumeSafelyUntilCapacityAcrossSmallBudgets(GameTestHelper h) {
        var player = new net.neoforged.neoforge.common.util.FakePlayer(
                h.getLevel(), new com.mojang.authlib.GameProfile(new UUID(171, 1), "CarrierBulk"));
        player.initInventoryMenu();
        var stack = new ItemStack(Items.PAPER);
        var tag = new net.minecraft.nbt.CompoundTag();
        tag.putBoolean(MARKER, true);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        player.inventoryMenu.setCarried(stack);
        var network = new UUID(171, 2);
        var view = new UUID(171, 3);
        var session = new UUID(171, 4);
        var ledger = new DomainLedger(network, Map.of(), i -> StorageBucketData.create(network, i));
        var water =
                FluidVariant.from(new FluidStack(Fluids.WATER, 1), h.getLevel().registryAccess());
        try (var deposit = ledger.reserveDeposit(water.key(), 10000, -1).orElseThrow()) {
            deposit.commit(10000);
        }
        var recovery = new RecoveryBuffer(() -> {});
        var replies = new ArrayList<TerminalStorageResponse>();
        var audit = new ArrayList<io.github.loongin.omniresonance.persistence.AuditEntry>();
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
                    new TerminalStorageRequest(
                            view,
                            session,
                            1,
                            1,
                            ledger.sequence(water.key()),
                            player.inventoryMenu.getStateId(),
                            -1,
                            0,
                            true));
            run(service, replies);
            h.assertTrue(
                    replies.getLast().status() == TerminalStorageResponse.Status.COMPLETE
                            && replies.getLast().moved() == 10000
                            && ledger.amount(water.key()) == 0
                            && amount(player.inventoryMenu.getCarried()) == 10000,
                    "Shift fill did not reach carrier capacity");
            service.request(
                    player,
                    new TerminalStorageRequest(
                            view, session, 1, 2, 0, replies.getLast().menuState(), -1, 1, true));
            run(service, replies);
            h.assertTrue(
                    replies.getLast().status() == TerminalStorageResponse.Status.COMPLETE
                            && replies.getLast().moved() == 10000
                            && ledger.amount(water.key()) == 10000
                            && amount(player.inventoryMenu.getCarried()) == 0
                            && player.inventoryMenu.getCarried().is(Items.PAPER),
                    "Shift drain lost the empty carrier or failed to empty it");
            h.assertTrue(audit.size() == 2, "Carrier progress duplicated its audit");
        }
        h.succeed();
    }

    private static void run(TerminalStorageService service, ArrayList<TerminalStorageResponse> replies) {
        for (int i = 0; i < 60; i++) {
            service.tick(new TransferWorkBudget(1, Long.MAX_VALUE, Long.MAX_VALUE, () -> 0));
            var status = replies.getLast().status();
            if (status != TerminalStorageResponse.Status.PROGRESS
                    && status != TerminalStorageResponse.Status.WAITING_BUDGET) return;
        }
    }

    private static int amount(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                .copyTag()
                .getInt("amount");
    }

    private static final class Carrier implements IFluidHandlerItem {
        final ItemStack stack;

        Carrier(ItemStack stack) {
            this.stack = stack;
        }

        public ItemStack getContainer() {
            return stack;
        }

        public int getTanks() {
            return 1;
        }

        public FluidStack getFluidInTank(int tank) {
            return amount(stack) == 0 ? FluidStack.EMPTY : new FluidStack(Fluids.WATER, amount(stack));
        }

        public int getTankCapacity(int tank) {
            return 10000;
        }

        public boolean isFluidValid(int tank, FluidStack resource) {
            return resource.is(Fluids.WATER);
        }

        private void set(int n) {
            var tag = stack.get(DataComponents.CUSTOM_DATA).copyTag();
            tag.putInt("amount", n);
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        }

        public int fill(FluidStack resource, FluidAction action) {
            if (!resource.is(Fluids.WATER)) return 0;
            int n = Math.min(1000, Math.min(resource.getAmount(), 10000 - amount(stack)));
            if (action.execute()) set(amount(stack) + n);
            return n;
        }

        public FluidStack drain(FluidStack resource, FluidAction action) {
            return resource.is(Fluids.WATER) ? drain(resource.getAmount(), action) : FluidStack.EMPTY;
        }

        public FluidStack drain(int maximum, FluidAction action) {
            int n = Math.min(1000, Math.min(maximum, amount(stack)));
            if (action.execute()) set(amount(stack) - n);
            return n == 0 ? FluidStack.EMPTY : new FluidStack(Fluids.WATER, n);
        }
    }
}
