// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.node;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.state.BlockBehaviour;

/** Temporary full-cube physical node form. */
public final class ResonanceTransferNodeBlock extends AbstractResonanceNodeBlock {
    public static final MapCodec<ResonanceTransferNodeBlock> CODEC = simpleCodec(ResonanceTransferNodeBlock::new);

    public ResonanceTransferNodeBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends ResonanceTransferNodeBlock> codec() {
        return CODEC;
    }

    @Override
    public NodeForm form() {
        return NodeForm.BLOCK;
    }
}
