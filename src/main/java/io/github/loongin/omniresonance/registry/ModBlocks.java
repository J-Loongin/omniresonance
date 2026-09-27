// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.registry;

import io.github.loongin.omniresonance.bootstrap.OmniResonanceMod;
import io.github.loongin.omniresonance.material.ResonatingAmethystBlock;
import io.github.loongin.omniresonance.node.ResonanceTransferNodeBlock;
import io.github.loongin.omniresonance.node.ResonanceTransferPanelBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Owns completed block registrations without creating implicit BlockItems. */
public final class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(OmniResonanceMod.MOD_ID);
    public static final DeferredBlock<ResonatingAmethystBlock> RESONATING_AMETHYST = BLOCKS.registerBlock(
            "resonating_amethyst",
            ResonatingAmethystBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.AMETHYST_BLOCK)
                    .noLootTable()
                    .pushReaction(PushReaction.BLOCK));
    public static final DeferredBlock<ResonanceTransferNodeBlock> RESONANCE_TRANSFER_NODE = BLOCKS.registerBlock(
            "resonance_transfer_node",
            ResonanceTransferNodeBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.QUARTZ_BLOCK));
    public static final DeferredBlock<ResonanceTransferPanelBlock> RESONANCE_TRANSFER_PANEL = BLOCKS.registerBlock(
            "resonance_transfer_panel",
            ResonanceTransferPanelBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.QUARTZ_BLOCK)
                    .isSuffocating((state, level, pos) -> false)
                    .isViewBlocking((state, level, pos) -> false)
                    .isRedstoneConductor((state, level, pos) -> false));

    private static final java.util.List<java.util.function.Supplier<? extends net.minecraft.world.level.block.Block>>
            OPTIONAL_NODES = new java.util.ArrayList<>();
    /** FML construction-time optional content contribution, before block entity types are resolved. */
    public static void addNodeBlock(
            java.util.function.Supplier<? extends net.minecraft.world.level.block.Block> block) {
        OPTIONAL_NODES.add(java.util.Objects.requireNonNull(block));
    }

    public static net.minecraft.world.level.block.Block[] nodeBlocks() {
        var blocks = new java.util.ArrayList<net.minecraft.world.level.block.Block>();
        blocks.add(RESONANCE_TRANSFER_NODE.get());
        blocks.add(RESONANCE_TRANSFER_PANEL.get());
        for (var supplier : OPTIONAL_NODES) blocks.add(supplier.get());
        return blocks.toArray(net.minecraft.world.level.block.Block[]::new);
    }

    private ModBlocks() {}

    /** Installs the owned block registry without accessing a live world. */
    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
    }
}
