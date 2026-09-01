// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.registry;

import io.github.loongin.omniresonance.bootstrap.OmniResonanceMod;
import io.github.loongin.omniresonance.material.ResonatingAmethystBlockEntity;
import io.github.loongin.omniresonance.node.ResonanceNodeBlockEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Owns block-entity types for completed internal world states. */
public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE, OmniResonanceMod.MOD_ID);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ResonatingAmethystBlockEntity>>
            RESONATING_AMETHYST = BLOCK_ENTITIES.register(
                    "resonating_amethyst",
                    () -> BlockEntityType.Builder.of(
                                    ResonatingAmethystBlockEntity::new, ModBlocks.RESONATING_AMETHYST.get())
                            .build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ResonanceNodeBlockEntity>>
            RESONANCE_TRANSFER_NODE = BLOCK_ENTITIES.register(
                    "resonance_transfer_node",
                    () -> BlockEntityType.Builder.of(
                                    ResonanceNodeBlockEntity::new,
                                    ModBlocks.RESONANCE_TRANSFER_NODE.get(),
                                    ModBlocks.RESONANCE_TRANSFER_PANEL.get())
                            .build(null));

    private ModBlockEntities() {}

    /** Installs the owned block-entity registry without constructing world instances. */
    public static void register(IEventBus modBus) {
        BLOCK_ENTITIES.register(modBus);
    }
}
