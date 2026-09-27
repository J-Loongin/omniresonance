// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.compat.ae2;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("omniresonance")
@PrefixGameTestTemplate(false)
public final class ChemicalMergeGameTests {
    private ChemicalMergeGameTests() {}

    @GameTest(template = "bootstrap")
    public static void chemicalCellAndDomainMergeIntoOneTwoHundredBucketEntry(GameTestHelper h) {
        if (net.neoforged.fml.ModList.get().isLoaded("appmek")) Present.verify(h);
        h.succeed();
    }

    private static final class Present {
        static void verify(GameTestHelper h) {
            var hydrogen = mekanism.api.MekanismAPI.CHEMICAL_REGISTRY
                    .getHolder(net.minecraft.resources.ResourceLocation.parse("mekanism:hydrogen"))
                    .orElseThrow();
            var variant = io.github.loongin.omniresonance.compat.mekanism.ChemicalVariant.from(
                    new mekanism.api.chemical.ChemicalStack(hydrogen, 1));
            var canonical = me.ramidzkh.mekae2.ae2.MekanismKey.of(variant.stack(1));
            var action = appeng.api.networking.security.IActionSource.empty();
            var modulate = appeng.api.config.Actionable.MODULATE;
            var cellStack =
                    new net.minecraft.world.item.ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                            net.minecraft.resources.ResourceLocation.parse("appmek:chemical_storage_cell_1k")));
            var cell = appeng.api.storage.StorageCells.getCellInventory(cellStack, null);
            h.assertTrue(
                    cell != null && cell.insert(canonical, 100000, modulate, action) == 100000,
                    "Native chemical cell rejected 100 B");
            var network = new java.util.UUID(217, 1);
            var ledger = new io.github.loongin.omniresonance.storage.DomainLedger(
                    network,
                    java.util.Map.of(),
                    i -> io.github.loongin.omniresonance.persistence.StorageBucketData.create(network, i));
            try (var deposit = ledger.reserveDeposit(variant.key(), 100000, -1).orElseThrow()) {
                deposit.commit(100000);
            }
            var storage = new Ae2DomainStorage(
                    new Ae2DomainAccess(() -> ledger, () -> -1),
                    () -> h.getLevel().registryAccess());
            var total = new appeng.api.stacks.KeyCounter();
            cell.getAvailableStacks(total);
            storage.getAvailableStacks(total);
            h.assertTrue(
                    total.size() == 1 && total.get(canonical) == 200000,
                    "100 B cell plus 100 B domain did not merge as 200 B");
            long revision = ledger.revision();
            h.assertTrue(
                    storage.extract(canonical, 50000, appeng.api.config.Actionable.SIMULATE, action) == 50000
                            && ledger.revision() == revision,
                    "Native key simulation mutated domain");
            h.assertTrue(
                    storage.extract(canonical, 50000, modulate, action) == 50000
                            && ledger.amount(variant.key()) == 50000,
                    "Native key did not extract shared domain inventory");
            var legacy = ResonanceKeys.from(variant.key());
            h.assertTrue(
                    storage.insert(legacy, 50000, modulate, action) == 50000,
                    "Legacy independent key could not access original domain identity");
            total.clear();
            cell.getAvailableStacks(total);
            storage.getAvailableStacks(total);
            h.assertTrue(
                    total.size() == 1 && total.get(canonical) == 200000,
                    "Legacy key published a duplicate inventory entry");
            h.assertTrue(
                    !appeng.api.behaviors.ContainerItemStrategies.isTypeSupported(ResonanceKeys.CHEMICAL_TYPE),
                    "Two chemical carrier strategies remain active");
            var player = new net.neoforged.neoforge.common.util.FakePlayer(
                    h.getLevel(), new com.mojang.authlib.GameProfile(new java.util.UUID(217, 2), "UnifiedChemical"));
            player.initInventoryMenu();
            player.inventoryMenu.setCarried(
                    new net.minecraft.world.item.ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                            net.minecraft.resources.ResourceLocation.parse("mekanism:basic_chemical_tank"))));
            var filling = appeng.api.behaviors.ContainerItemStrategies.findCarriedContextForKey(
                    canonical, player, player.inventoryMenu);
            h.assertTrue(
                    filling != null && filling.insert(canonical, 1000, modulate) == 1000,
                    "Native chemical carrier fill failed");
            var emptying =
                    appeng.api.behaviors.ContainerItemStrategies.findCarriedContext(null, player, player.inventoryMenu);
            var contents = emptying.getExtractableContent();
            h.assertTrue(
                    contents != null && contents.what().equals(canonical) && contents.amount() == 1000,
                    "Automatic emptying chose a second chemical identity");
            h.assertTrue(
                    storage.insert(contents.what(), contents.amount(), appeng.api.config.Actionable.SIMULATE, action)
                            == 1000,
                    "Automatic emptying cannot target the shared domain");
            cell.persist();
            var reloaded = appeng.api.storage.StorageCells.getCellInventory(cellStack.copy(), null);
            var saved = new appeng.api.stacks.KeyCounter();
            reloaded.getAvailableStacks(saved);
            h.assertTrue(saved.get(canonical) == 100000, "Native chemical cell persistence changed");
        }
    }
}
