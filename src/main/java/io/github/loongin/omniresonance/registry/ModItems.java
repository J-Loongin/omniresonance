// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.registry;

import io.github.loongin.omniresonance.bootstrap.OmniResonanceMod;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Owns the explicitly completed material item registrations. */
public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(OmniResonanceMod.MOD_ID);
    public static final DeferredItem<Item> OMNI_DUST = ITEMS.registerSimpleItem("omni_dust");
    public static final DeferredItem<Item> RESONANCE_SUBSTRATE = ITEMS.registerSimpleItem("resonance_substrate");
    public static final DeferredItem<Item> RESONANCE_CORE = ITEMS.registerSimpleItem("resonance_core");
    public static final DeferredItem<net.minecraft.world.item.BlockItem> RESONANCE_TRANSFER_NODE =
            ITEMS.registerSimpleBlockItem(ModBlocks.RESONANCE_TRANSFER_NODE);
    public static final DeferredItem<net.minecraft.world.item.BlockItem> RESONANCE_TRANSFER_PANEL =
            ITEMS.registerSimpleBlockItem(ModBlocks.RESONANCE_TRANSFER_PANEL);

    private ModItems() {}

    /** Installs this owned deferred register on the mod bus without touching live game state. */
    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }
}
