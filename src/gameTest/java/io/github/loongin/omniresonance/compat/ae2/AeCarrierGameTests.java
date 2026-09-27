// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.compat.ae2;

import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("omniresonance")
@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid = "omniresonance")
public final class AeCarrierGameTests {
    private static final String MARKER = "omniresonance_ae_carrier_test";

    private AeCarrierGameTests() {}

    @SubscribeEvent
    public static void capabilities(RegisterCapabilitiesEvent event) {
        event.registerItem(
                Capabilities.EnergyStorage.ITEM,
                (stack, ignored) -> stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                                .copyTag()
                                .getBoolean(MARKER)
                        ? new Battery(stack)
                        : null,
                Items.PAPER);
    }

    @GameTest(template = "bootstrap")
    public static void aeCarrierSimulationsArePureAndStackedBatteriesSettleOneAtATime(GameTestHelper h) {
        if (net.neoforged.fml.ModList.get().isLoaded("ae2")) Present.verify(h);
        h.succeed();
    }

    private static ItemStack battery(int count) {
        var stack = new ItemStack(Items.PAPER, count);
        var data = new net.minecraft.nbt.CompoundTag();
        data.putBoolean(MARKER, true);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        return stack;
    }

    private static final class Battery implements net.neoforged.neoforge.energy.IEnergyStorage {
        private final ItemStack stack;

        Battery(ItemStack stack) {
            this.stack = stack;
        }

        public int getEnergyStored() {
            return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                    .copyTag()
                    .getInt("energy");
        }

        private void stored(int amount) {
            var data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                    .copyTag();
            data.putInt("energy", amount);
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        }

        public int getMaxEnergyStored() {
            return 5000;
        }

        public boolean canExtract() {
            return true;
        }

        public boolean canReceive() {
            return true;
        }

        public int receiveEnergy(int maximum, boolean simulate) {
            int moved = Math.min(750, Math.min(maximum, 5000 - getEnergyStored()));
            if (!simulate) stored(getEnergyStored() + moved);
            return moved;
        }

        public int extractEnergy(int maximum, boolean simulate) {
            int moved = Math.min(750, Math.min(maximum, getEnergyStored()));
            if (!simulate) stored(getEnergyStored() - moved);
            return moved;
        }
    }

    private static final class Present {
        static void verify(GameTestHelper h) {
            h.assertTrue(
                    appeng.api.behaviors.ContainerItemStrategies.isTypeSupported(ResonanceKeys.ENERGY_TYPE),
                    "Missing FE carrier registration");
            h.assertTrue(
                    !appeng.api.behaviors.ContainerItemStrategies.isTypeSupported(ResonanceKeys.SOURCE_TYPE),
                    "Guessed Source item capability");
            var player = new net.neoforged.neoforge.common.util.FakePlayer(
                    h.getLevel(), new com.mojang.authlib.GameProfile(new java.util.UUID(215, 1), "AeCarrier"));
            player.initInventoryMenu();
            var original = battery(2);
            player.inventoryMenu.setCarried(original);
            var context = appeng.api.behaviors.ContainerItemStrategies.findCarriedContextForKey(
                    ResonanceKeys.ENERGY, player, player.inventoryMenu);
            h.assertTrue(context != null, "AE did not discover carried battery");
            var simulate = appeng.api.config.Actionable.SIMULATE;
            var modulate = appeng.api.config.Actionable.MODULATE;
            var before = original.copy();
            h.assertTrue(
                    context.insert(ResonanceKeys.ENERGY, Long.MAX_VALUE, simulate) == 750
                            && ItemStack.matches(original, before)
                            && player.inventoryMenu.getCarried() == original,
                    "FE simulation changed cursor or narrowed request");
            h.assertTrue(
                    context.insert(ResonanceKeys.ENERGY, Long.MAX_VALUE, modulate) == 750,
                    "Native partial receipt was not returned");
            h.assertTrue(
                    player.inventoryMenu.getCarried().getCount() == 1
                            && new Battery(player.inventoryMenu.getCarried()).getEnergyStored() == 0,
                    "Processing one stacked battery modified remaining batteries");
            int charged = 0;
            for (var stack : player.getInventory().items)
                if (!stack.isEmpty()) charged += new Battery(stack).getEnergyStored();
            h.assertTrue(charged == 750, "Processed battery was not placed in inventory");
            h.assertTrue(
                    context.insert(ResonanceKeys.ENERGY, Long.MAX_VALUE, modulate) == 750,
                    "Repeated AE context stopped after stack split");
            h.assertTrue(
                    context.getExtractableContent().what().equals(ResonanceKeys.ENERGY)
                            && context.getExtractableContent().amount() == 750,
                    "AE discovered wrong FE identity or amount");
            h.assertTrue(
                    context.extract(ResonanceKeys.ENERGY, 300, simulate) == 300
                            && new Battery(player.inventoryMenu.getCarried()).getEnergyStored() == 750,
                    "FE extraction simulation mutated battery");
            h.assertTrue(
                    context.extract(ResonanceKeys.ENERGY, 300, modulate) == 300
                            && new Battery(player.inventoryMenu.getCarried()).getEnergyStored() == 450,
                    "FE actual extraction did not settle carrier");
            var snapshot = player.inventoryMenu.getCarried().copy();
            h.assertTrue(
                    context.insert(ResonanceKeys.ENERGY, 0, modulate) == 0
                            && ItemStack.matches(snapshot, player.inventoryMenu.getCarried()),
                    "Zero transfer replaced carrier");
            player.inventoryMenu.setCarried(new ItemStack(Items.STONE));
            h.assertTrue(
                    context.insert(ResonanceKeys.ENERGY, 1000, modulate) == 0,
                    "Stale carrier context modified unrelated item");
        }
    }
}
