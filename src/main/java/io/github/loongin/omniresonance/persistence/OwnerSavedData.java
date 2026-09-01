// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.persistence;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

/**
 * Server-thread-owned v1 default-network pointer for one owner.
 *
 * <p>Create and load must run on the owning server thread, which then owns all state access. UUID values are
 * immutable and tags are never retained. No operation is a simulation. Wrong-thread access fails before reading
 * or changing state, including inherited dirty marking and file saving through the guarded dirty methods.
 */
public final class OwnerSavedData extends SavedData {
    private final Thread owningThread = Thread.currentThread();
    private final UUID ownerId;
    private @Nullable UUID defaultNetworkId;

    private OwnerSavedData(UUID ownerId, @Nullable UUID defaultNetworkId) {
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.defaultNetworkId = defaultNetworkId;
    }

    /**
     * Creates a dirty shard on its owning server thread; a null default means no preferred network.
     *
     * <p>UUIDs remain immutable, and no caller state, simulation, or I/O is involved. A missing owner throws
     * {@link NullPointerException}; the caller must validate any default network's ownership and existence.
     */
    public static OwnerSavedData create(UUID ownerId, @Nullable UUID defaultNetworkId) {
        OwnerSavedData data = new OwnerSavedData(ownerId, defaultNetworkId);
        data.setDirty();
        return data;
    }

    /**
     * Decodes a clean shard on its owning server thread without retaining or modifying the caller's tag.
     *
     * <p>No simulation or I/O occurs. Missing inputs throw {@link NullPointerException}; unsupported, malformed, or
     * identity-mismatched data throws {@link IllegalArgumentException} without inventing replacement state.
     */
    public static OwnerSavedData load(UUID expectedOwnerId, CompoundTag tag) {
        ManagedDataNbt.validateSchemaAndFields(tag, ManagedDataNbt.OWNER_SCHEMA_VERSION, ManagedDataNbt.OWNER_FIELDS);
        UUID ownerId = ManagedDataNbt.readIdentity(tag, "owner_id", expectedOwnerId);
        UUID defaultNetworkId =
                tag.contains("default_network_id") ? ManagedDataNbt.readUuid(tag, "default_network_id") : null;
        return new OwnerSavedData(ownerId, defaultNetworkId);
    }

    /** Returns the immutable owner ID without modification or simulation; rejects wrong-thread access. */
    public UUID ownerId() {
        requireOwningThread();
        return ownerId;
    }

    /** Returns the immutable optional pointer without modification or simulation; rejects wrong-thread access. */
    public Optional<UUID> defaultNetworkId() {
        requireOwningThread();
        return Optional.ofNullable(defaultNetworkId);
    }

    /**
     * Replaces or clears the pointer on the owning server thread and marks dirty only when its value changes.
     *
     * <p>No simulation or I/O occurs, and the immutable input may be shared. Wrong-thread access throws
     * {@link IllegalStateException} even for a no-op; callers must first validate network ownership and existence.
     */
    public void setDefaultNetwork(@Nullable UUID networkId) {
        requireOwningThread();
        if (!Objects.equals(defaultNetworkId, networkId)) {
            defaultNetworkId = networkId;
            setDirty();
        }
    }

    /**
     * Writes v1 fields and fresh UUID tags into caller-owned output on the owning server thread.
     *
     * <p>The optional field is removed when no default exists. Output is not retained; dirty state is unchanged,
     * and no simulation or disk I/O occurs. Wrong-thread access is rejected before mutation; null output throws
     * {@link NullPointerException}.
     */
    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        requireOwningThread();
        Objects.requireNonNull(tag, "tag");
        tag.putInt("schema_version", ManagedDataNbt.OWNER_SCHEMA_VERSION);
        tag.putUUID("owner_id", ownerId);
        if (defaultNetworkId == null) {
            tag.remove("default_network_id");
        } else {
            tag.putUUID("default_network_id", defaultNetworkId);
        }
        return tag;
    }

    /** Sets owned dirty state without simulation; rejects wrong-thread access before mutation. */
    @Override
    public void setDirty(boolean dirty) {
        requireOwningThread();
        super.setDirty(dirty);
    }

    /** Reads owned dirty state without mutation or simulation; rejects wrong-thread access. */
    @Override
    public boolean isDirty() {
        requireOwningThread();
        return super.isDirty();
    }

    private void requireOwningThread() {
        if (Thread.currentThread() != owningThread) {
            throw new IllegalStateException("Owner SavedData accessed outside its owning server thread");
        }
    }
}
