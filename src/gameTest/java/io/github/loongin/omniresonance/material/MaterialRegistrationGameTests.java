// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.material;

import io.github.loongin.omniresonance.bootstrap.OmniResonanceMod;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real-registry contracts for the obtainable M1-A materials. */
@GameTestHolder(OmniResonanceMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MaterialRegistrationGameTests {
    private MaterialRegistrationGameTests() {}

    /** Proves all current materials exist under stable IDs without pre-registering node content. */
    @GameTest(template = "bootstrap")
    public static void materialsUseStableIdsAndOrdinaryStackLimits(GameTestHelper helper) {
        for (String path : List.of("omni_dust", "resonance_substrate", "resonance_core")) {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(OmniResonanceMod.MOD_ID, path);
            Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
            helper.assertTrue(item != null, "Missing registered material " + id);
            helper.assertTrue(item.getDefaultMaxStackSize() == 64, "Material stack size changed for " + id);
        }
        helper.succeed();
    }

    /** Proves current content is isolated in the mod's own ordered creative tab. */
    @GameTest(template = "bootstrap")
    public static void materialsUseDedicatedCreativeTab(GameTestHelper helper) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(OmniResonanceMod.MOD_ID, "main");
        CreativeModeTab tab =
                BuiltInRegistries.CREATIVE_MODE_TAB.getOptional(id).orElse(null);
        helper.assertTrue(tab != null, "Missing dedicated creative tab " + id);
        tab.buildContents(new CreativeModeTab.ItemDisplayParameters(
                helper.getLevel().enabledFeatures(), true, helper.getLevel().registryAccess()));
        helper.assertTrue(
                BuiltInRegistries.ITEM
                        .getKey(tab.getIconItem().getItem())
                        .equals(ResourceLocation.fromNamespaceAndPath(OmniResonanceMod.MOD_ID, "resonance_core")),
                "Creative tab icon is not the resonance core");
        List<ResourceLocation> actual = tab.getDisplayItems().stream()
                .map(ItemStack::getItem)
                .map(BuiltInRegistries.ITEM::getKey)
                .toList();
        List<ResourceLocation> expected = List.of(
                        "omni_dust",
                        "resonance_substrate",
                        "resonance_core",
                        "resonance_transfer_node",
                        "resonance_transfer_panel")
                .stream()
                .map(path -> ResourceLocation.fromNamespaceAndPath(OmniResonanceMod.MOD_ID, path))
                .toList();
        helper.assertTrue(actual.equals(expected), "Creative tab contents or ordering changed: " + actual);
        helper.succeed();
    }
}
