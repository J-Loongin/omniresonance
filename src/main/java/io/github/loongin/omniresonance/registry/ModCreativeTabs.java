// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.registry;

import io.github.loongin.omniresonance.bootstrap.OmniResonanceMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Owns the mod's stable creative-mode categories without mutating vanilla tabs. */
public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, OmniResonanceMod.MOD_ID);
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN = TABS.register(
            "main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.omniresonance.main"))
                    .icon(() -> new ItemStack(ModItems.RESONANCE_CORE.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(ModItems.OMNI_DUST.get());
                        output.accept(ModItems.RESONANCE_SUBSTRATE.get());
                        output.accept(ModItems.RESONANCE_CORE.get());
                        output.accept(ModItems.RESONANCE_TRANSFER_NODE.get());
                        output.accept(ModItems.RESONANCE_TRANSFER_PANEL.get());
                    })
                    .build());

    private ModCreativeTabs() {}

    /** Installs the dedicated tab on the mod bus; its suppliers remain lazy until registry application. */
    public static void register(IEventBus modBus) {
        TABS.register(modBus);
    }
}
