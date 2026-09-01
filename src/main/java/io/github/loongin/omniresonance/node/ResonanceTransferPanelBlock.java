// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.node;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Temporary 2/16-thick six-direction panel form. */
public final class ResonanceTransferPanelBlock extends AbstractResonanceNodeBlock {
    public static final MapCodec<ResonanceTransferPanelBlock> CODEC = simpleCodec(ResonanceTransferPanelBlock::new);

    public ResonanceTransferPanelBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends ResonanceTransferPanelBlock> codec() {
        return CODEC;
    }

    @Override
    public NodeForm form() {
        return NodeForm.PANEL;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return NodeGeometry.panelShape(state.getValue(FACING));
    }

    @Override
    protected VoxelShape getCollisionShape(
            BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return NodeGeometry.panelShape(state.getValue(FACING));
    }
}
