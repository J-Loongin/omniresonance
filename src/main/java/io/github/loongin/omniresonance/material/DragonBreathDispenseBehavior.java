// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.material;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.core.dispenser.DefaultDispenseItemBehavior;
import net.minecraft.core.dispenser.DispenseItemBehavior;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.DispenserBlock;

/**
 * Dragon-breath dispenser adapter with exact fallback and container-item semantics.
 *
 * <p>The adapter owns no world references. Ineligible positions delegate the captured native behavior exactly
 * once; eligible full/unavailable positions stay handled without consuming or dropping the breath.
 */
public final class DragonBreathDispenseBehavior implements DispenseItemBehavior {
    private final DispenseItemBehavior fallback;

    public DragonBreathDispenseBehavior(DispenseItemBehavior fallback) {
        this.fallback = Objects.requireNonNull(fallback, "fallback");
    }

    public static void register() {
        DispenseItemBehavior previous = DispenserBlock.DISPENSER_REGISTRY.get(Items.DRAGON_BREATH);
        if (!(previous instanceof DragonBreathDispenseBehavior)) {
            DispenserBlock.registerBehavior(Items.DRAGON_BREATH, new DragonBreathDispenseBehavior(previous));
        }
    }

    @Override
    public ItemStack dispense(BlockSource source, ItemStack stack) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(stack, "stack");
        if (!stack.is(Items.DRAGON_BREATH)) {
            return fallback.dispense(source, stack);
        }
        Direction facing = source.state().getValue(DispenserBlock.FACING);
        BlockPos target = source.pos().relative(facing);
        DragonBreathInfusionService.Result result = DragonBreathInfusionService.inject(source.level(), target);
        if (!result.successfullyInjected() && !result.handledWithoutConsumption()) {
            return fallback.dispense(source, stack);
        }
        if (!result.successfullyInjected()) {
            playFailure(source, facing);
            return stack;
        }

        stack.shrink(1);
        ItemStack bottle = new ItemStack(Items.GLASS_BOTTLE);
        ItemStack resultStack = stack;
        if (stack.isEmpty()) {
            resultStack = bottle;
        } else {
            ItemStack remainder = source.blockEntity().insertItem(bottle);
            if (!remainder.isEmpty()) {
                DefaultDispenseItemBehavior.spawnItem(
                        source.level(), remainder, 6, facing, DispenserBlock.getDispensePosition(source));
            }
        }
        playSuccess(source, facing, target);
        return resultStack;
    }

    private static void playSuccess(BlockSource source, Direction facing, BlockPos target) {
        source.level().levelEvent(1000, source.pos(), 0);
        source.level().levelEvent(2000, source.pos(), facing.get3DDataValue());
        DragonBreathPlayerInteraction.feedback(source.level(), target);
    }

    private static void playFailure(BlockSource source, Direction facing) {
        source.level().levelEvent(1001, source.pos(), 0);
        source.level().levelEvent(2000, source.pos(), facing.get3DDataValue());
    }
}
