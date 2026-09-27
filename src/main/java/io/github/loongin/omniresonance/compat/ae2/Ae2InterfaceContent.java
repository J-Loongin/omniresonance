// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.compat.ae2;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Loaded and registered exclusively when AE2 is present. No absent-mod placeholder content. */
public final class Ae2InterfaceContent {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks("omniresonance");
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems("omniresonance");
    public static final DeferredBlock<Ae2InterfaceBlock> BLOCK = BLOCKS.registerBlock(
            "ae_domain_interface", Ae2InterfaceBlock::new, BlockBehaviour.Properties.ofFullCopy(Blocks.QUARTZ_BLOCK));
    public static final DeferredItem<BlockItem> ITEM = ITEMS.registerSimpleBlockItem(BLOCK);

    private Ae2InterfaceContent() {}

    public static void register(IEventBus bus) {
        io.github.loongin.omniresonance.registry.ModBlocks.addNodeBlock(BLOCK);
        io.github.loongin.omniresonance.registry.ModCreativeTabs.addOptionalItem(ITEM);
        BLOCKS.register(bus);
        ITEMS.register(bus);
    }
}
