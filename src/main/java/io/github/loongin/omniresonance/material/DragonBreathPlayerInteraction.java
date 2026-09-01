// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.material;

import io.github.loongin.omniresonance.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Native right-click adapter; all authority and progress mutation remains in the shared server service. */
public final class DragonBreathPlayerInteraction {
    private DragonBreathPlayerInteraction() {}

    /** Handles only dragon breath after higher-priority protection listeners have accepted the interaction. */
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.isCanceled() || !event.getItemStack().is(Items.DRAGON_BREATH)) {
            return;
        }
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        if (level.isClientSide) {
            if (isVisibleCandidate(level, pos)) {
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.sidedSuccess(true));
            }
            return;
        }
        DragonBreathInfusionService.Result result = DragonBreathInfusionService.inject((ServerLevel) level, pos);
        if (result.successfullyInjected()) {
            consumePlayerInput(event);
            feedback((ServerLevel) level, pos);
        }
        if (result.successfullyInjected() || result.handledWithoutConsumption()) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.sidedSuccess(false));
        }
    }

    private static boolean isVisibleCandidate(Level level, BlockPos pos) {
        if (level.dimension() != Level.END) {
            return false;
        }
        var state = level.getBlockState(pos);
        return state.is(ModBlocks.RESONATING_AMETHYST.get())
                || state.is(Blocks.AMETHYST_BLOCK)
                        && level.getBlockState(pos.below()).is(Blocks.END_STONE);
    }

    private static void consumePlayerInput(PlayerInteractEvent.RightClickBlock event) {
        var player = event.getEntity();
        if (player.getAbilities().instabuild) {
            return;
        }
        ItemStack input = event.getItemStack();
        input.shrink(1);
        ItemStack bottle = new ItemStack(Items.GLASS_BOTTLE);
        if (input.isEmpty()) {
            player.setItemInHand(event.getHand(), bottle);
        } else if (!player.getInventory().add(bottle)) {
            player.drop(bottle, false);
        }
    }

    static void feedback(ServerLevel level, BlockPos pos) {
        level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 1.0F, 1.0F);
        level.sendParticles(
                ParticleTypes.DRAGON_BREATH,
                pos.getX() + 0.5,
                pos.getY() + 0.7,
                pos.getZ() + 0.5,
                6,
                0.25,
                0.25,
                0.25,
                0.01);
    }
}
