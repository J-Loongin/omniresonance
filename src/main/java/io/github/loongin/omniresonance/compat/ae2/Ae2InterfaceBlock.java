// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.compat.ae2;

import com.mojang.serialization.MapCodec;
import io.github.loongin.omniresonance.node.AbstractResonanceNodeBlock;
import io.github.loongin.omniresonance.node.NodeForm;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/** Optional full-cube interface with a server-controlled conflict indicator and no per-block ticker. */
public final class Ae2InterfaceBlock extends AbstractResonanceNodeBlock {
    public static final MapCodec<Ae2InterfaceBlock> CODEC = simpleCodec(Ae2InterfaceBlock::new);
    public static final BooleanProperty CONFLICT = BooleanProperty.create("conflict");

    public Ae2InterfaceBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(CONFLICT, false));
    }

    @Override
    protected MapCodec<? extends Ae2InterfaceBlock> codec() {
        return CODEC;
    }

    @Override
    public NodeForm form() {
        return NodeForm.AE_INTERFACE;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(CONFLICT);
    }

    @Override
    protected net.minecraft.world.ItemInteractionResult useItemOn(
            net.minecraft.world.item.ItemStack stack,
            BlockState state,
            net.minecraft.world.level.Level level,
            net.minecraft.core.BlockPos pos,
            net.minecraft.world.entity.player.Player player,
            net.minecraft.world.InteractionHand hand,
            net.minecraft.world.phys.BlockHitResult hit) {
        if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)
            Ae2InterfaceRuntime.open(serverPlayer, pos);
        return net.minecraft.world.ItemInteractionResult.SUCCESS;
    }
}
