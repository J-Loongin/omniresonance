// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.material;

import io.github.loongin.omniresonance.registry.ModBlockEntities;
import io.github.loongin.omniresonance.registry.ModBlocks;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-authoritative persistent state for one resonating-amethyst position.
 *
 * <p>Progress changes and scheduling belong to the owning server thread. Decode failures remain unavailable and
 * retain defensive copies of this component's three raw fields; they never become a default batch. This entity
 * exposes no client mutation, inventory operation, simulation or per-tick ticker.
 */
public final class ResonatingAmethystBlockEntity extends BlockEntity {
    private static final Logger LOGGER = LoggerFactory.getLogger(ResonatingAmethystBlockEntity.class);
    private static final int SCHEMA_VERSION = 1;
    private static final String SCHEMA_VERSION_KEY = "schema_version";
    private static final String PENDING_COUNT_KEY = "pending_count";
    private static final String SETTLE_AT_KEY = "settle_at_game_tick";
    private @Nullable ResonanceProgress progress;
    private CompoundTag invalidOwnedFields = new CompoundTag();
    private boolean decodedUnavailable;
    private boolean unavailableLogged;

    public ResonatingAmethystBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.RESONATING_AMETHYST.get(), pos, state);
    }

    /** Initializes an uninitialized valid entity once, marks it changed and never schedules implicitly. */
    public void initialize(ResonanceProgress progress) {
        if (this.progress != null || decodedUnavailable) {
            throw new IllegalStateException("Resonating amethyst is already initialized or unavailable");
        }
        this.progress = Objects.requireNonNull(progress, "progress");
        setChanged();
    }

    /** Replaces usable progress on the server thread; unavailable or uninitialized state rejects before mutation. */
    void update(ResonanceProgress progress) {
        if (this.progress == null || decodedUnavailable) {
            throw new IllegalStateException("Resonating amethyst is unavailable");
        }
        this.progress = Objects.requireNonNull(progress, "progress");
        setChanged();
    }

    /** Returns the immutable validated value without mutating or scheduling world state. */
    public Optional<ResonanceProgress> progress() {
        return Optional.ofNullable(progress);
    }

    /** Reports validated initialized state; malformed and fresh uninitialized entities are unavailable. */
    public boolean isUsable() {
        return progress != null && !decodedUnavailable;
    }

    /** Ensures one bounded scheduled block tick for usable state on the matching loaded server position. */
    public void ensureScheduled(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        if (!isUsable()
                || getLevel() != level
                || !level.getBlockState(worldPosition).is(ModBlocks.RESONATING_AMETHYST.get())
                || level.getBlockTicks().hasScheduledTick(worldPosition, ModBlocks.RESONATING_AMETHYST.get())) {
            return;
        }
        long remaining = progress.remainingTicks(level.getGameTime());
        int delay = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, remaining));
        level.scheduleTick(worldPosition, ModBlocks.RESONATING_AMETHYST.get(), delay);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel serverLevel) {
            if (decodedUnavailable) {
                if (!unavailableLogged) {
                    LOGGER.warn(
                            "Rejected resonating-amethyst state at {}; preserving unavailable fields", worldPosition);
                    unavailableLogged = true;
                }
                return;
            }
            ensureScheduled(serverLevel);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        progress = null;
        invalidOwnedFields = copyOwnedFields(tag);
        decodedUnavailable = true;
        unavailableLogged = false;
        if (tag.contains(SCHEMA_VERSION_KEY, Tag.TAG_INT)
                && tag.getInt(SCHEMA_VERSION_KEY) == SCHEMA_VERSION
                && tag.contains(PENDING_COUNT_KEY, Tag.TAG_INT)
                && tag.contains(SETTLE_AT_KEY, Tag.TAG_LONG)) {
            try {
                progress = new ResonanceProgress(tag.getInt(PENDING_COUNT_KEY), tag.getLong(SETTLE_AT_KEY));
                invalidOwnedFields = new CompoundTag();
                decodedUnavailable = false;
                return;
            } catch (IllegalArgumentException invalidValue) {
                // Preserve raw fields below; the bounded warning contains no NBT body.
            }
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (progress != null && !decodedUnavailable) {
            tag.putInt(SCHEMA_VERSION_KEY, SCHEMA_VERSION);
            tag.putInt(PENDING_COUNT_KEY, progress.pendingCount());
            tag.putLong(SETTLE_AT_KEY, progress.settleAtGameTick());
            return;
        }
        for (String key : new String[] {SCHEMA_VERSION_KEY, PENDING_COUNT_KEY, SETTLE_AT_KEY}) {
            Tag value = invalidOwnedFields.get(key);
            if (value != null) {
                tag.put(key, value.copy());
            }
        }
    }

    private static CompoundTag copyOwnedFields(CompoundTag source) {
        CompoundTag copy = new CompoundTag();
        for (String key : new String[] {SCHEMA_VERSION_KEY, PENDING_COUNT_KEY, SETTLE_AT_KEY}) {
            Tag value = source.get(key);
            if (value != null) {
                copy.put(key, value.copy());
            }
        }
        return copy;
    }
}
