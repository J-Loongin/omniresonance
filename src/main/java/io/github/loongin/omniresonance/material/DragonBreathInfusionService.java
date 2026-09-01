// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.material;

import io.github.loongin.omniresonance.registry.ModBlockEntities;
import io.github.loongin.omniresonance.registry.ModBlocks;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-thread authority for one dragon-breath infusion attempt.
 *
 * <p>This service validates dimension, block identity, support, bounded progress and exact deadlines before
 * mutation. It never accesses player/dispenser inventories, sends client data, simulates work or stores world
 * references. Callers consume an input only for {@link Result#successfullyInjected()}.
 */
public final class DragonBreathInfusionService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DragonBreathInfusionService.class);

    private DragonBreathInfusionService() {}

    /** Applies at most one validated progress transition on the supplied level's owning server thread. */
    public static Result inject(ServerLevel level, BlockPos pos) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(pos, "pos");
        requireServerThread(level);
        if (level.dimension() != Level.END) {
            return Result.WRONG_DIMENSION;
        }
        long gameTick = level.getGameTime();
        var state = level.getBlockState(pos);
        if (state.is(Blocks.AMETHYST_BLOCK)) {
            if (!level.getBlockState(pos.below()).is(Blocks.END_STONE)) {
                return Result.MISSING_END_STONE;
            }
            ResonanceProgress initial;
            try {
                initial = ResonanceProgress.start(gameTick);
            } catch (ArithmeticException overflow) {
                return Result.DEADLINE_OVERFLOW;
            }
            if (!level.setBlock(pos, ModBlocks.RESONATING_AMETHYST.get().defaultBlockState(), Block.UPDATE_ALL)) {
                return Result.UNAVAILABLE;
            }
            ResonatingAmethystBlockEntity entity =
                    ModBlockEntities.RESONATING_AMETHYST.get().getBlockEntity(level, pos);
            if (entity == null) {
                LOGGER.error("Resonating-amethyst block entity missing after conversion at {}", pos);
                return Result.UNAVAILABLE;
            }
            entity.initialize(initial);
            entity.ensureScheduled(level);
            return Result.SUCCESS_INITIAL;
        }
        if (!state.is(ModBlocks.RESONATING_AMETHYST.get())) {
            return Result.WRONG_BLOCK;
        }
        ResonatingAmethystBlockEntity entity =
                ModBlockEntities.RESONATING_AMETHYST.get().getBlockEntity(level, pos);
        if (entity == null || !entity.isUsable()) {
            return Result.UNAVAILABLE;
        }
        ResonanceProgress current = entity.progress().orElseThrow();
        if (current.isFull()) {
            entity.ensureScheduled(level);
            return Result.FULL;
        }
        ResonanceProgress updated;
        try {
            updated = current.inject(gameTick);
        } catch (ArithmeticException overflow) {
            return Result.DEADLINE_OVERFLOW;
        }
        entity.ensureScheduled(level);
        entity.update(updated);
        return Result.SUCCESS_EXISTING;
    }

    private static void requireServerThread(ServerLevel level) {
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("Dragon-breath infusion accessed outside the server thread");
        }
    }

    /** Fixed internal outcomes; clients never supply or decode these values. */
    public enum Result {
        SUCCESS_INITIAL,
        SUCCESS_EXISTING,
        FULL,
        WRONG_DIMENSION,
        WRONG_BLOCK,
        MISSING_END_STONE,
        UNAVAILABLE,
        DEADLINE_OVERFLOW;

        /** Reports an authoritative progress mutation; only these outcomes authorize inventory consumption. */
        public boolean successfullyInjected() {
            return this == SUCCESS_INITIAL || this == SUCCESS_EXISTING;
        }

        /** Reports an eligible target that must not fall through to vanilla item dropping. */
        public boolean handledWithoutConsumption() {
            return this == FULL || this == UNAVAILABLE || this == DEADLINE_OVERFLOW;
        }
    }
}
