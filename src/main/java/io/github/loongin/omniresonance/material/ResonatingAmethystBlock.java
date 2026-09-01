// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.material;

import com.mojang.serialization.MapCodec;
import io.github.loongin.omniresonance.registry.ModBlockEntities;
import io.github.loongin.omniresonance.registry.ModItems;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Internal, non-obtainable resonance state driven only by scheduled block ticks.
 *
 * <p>The block reads its matching validated block entity on the server thread. It never scans positions, owns an
 * inventory, simulates resource work or installs a block-entity ticker. Failed identity/state checks produce no
 * drops and do not create replacement progress.
 */
public final class ResonatingAmethystBlock extends BaseEntityBlock {
    public static final MapCodec<ResonatingAmethystBlock> CODEC = simpleCodec(ResonatingAmethystBlock::new);

    public ResonatingAmethystBlock(BlockBehaviour.Properties properties) {
        super(Objects.requireNonNull(properties, "properties"));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ResonatingAmethystBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> blockEntityType) {
        return null;
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        settle(level, pos);
    }

    static void settle(ServerLevel level, BlockPos pos) {
        ResonatingAmethystBlockEntity entity =
                ModBlockEntities.RESONATING_AMETHYST.get().getBlockEntity(level, pos);
        if (entity == null || !entity.isUsable()) {
            return;
        }
        ResonanceProgress progress = entity.progress().orElseThrow();
        if (!progress.isDue(level.getGameTime())) {
            entity.ensureScheduled(level);
            return;
        }
        int count = progress.pendingCount();
        if (!level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL)) {
            return;
        }
        Block.popResource(level, pos, new ItemStack(ModItems.OMNI_DUST.get(), count));
    }
}
