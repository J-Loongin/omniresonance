// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.persistence;

import io.github.loongin.omniresonance.storage.DomainLedger;
import io.github.loongin.omniresonance.storage.StorageBucketHash;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owner-server-thread lifetime for one domain's lazy native SavedData activation. At most 64 bucket handles
 * and one ledger are retained; disposal follows the owning network/server. Unavailable state is sticky until
 * restart, not a retry queue. Construction and state queries do not load buckets or alter metadata.
 * All access is synchronous; no method simulates, authorizes, flushes, repairs or deletes storage files.
 */
public final class DomainStorage {
    public enum State {
        NOT_LOADED,
        AVAILABLE,
        UNAVAILABLE
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(DomainStorage.class);
    private final Thread owner = Thread.currentThread();
    private final UUID networkId;
    private final DimensionDataStorage storage;
    private final Path directory;
    private final LongSupplier createdMask;
    private final LongConsumer publishMask;
    private State state = State.NOT_LOADED;
    private @Nullable DomainLedger ledger;

    /**
     * Retains trusted native storage, its matching directory and server-owned metadata accessors. The mask
     * publisher must atomically store the new mask and mark the network shard dirty without external reentry.
     * Missing arguments reject immediately; no I/O, allocation of inventory or metadata mutation occurs.
     */
    public DomainStorage(
            UUID networkId,
            DimensionDataStorage storage,
            Path directory,
            LongSupplier createdMask,
            LongConsumer publishMask) {
        this.networkId = Objects.requireNonNull(networkId);
        this.storage = Objects.requireNonNull(storage);
        this.directory = Objects.requireNonNull(directory);
        this.createdMask = Objects.requireNonNull(createdMask);
        this.publishMask = Objects.requireNonNull(publishMask);
    }

    /** Returns runtime availability on the owning thread without implicitly activating storage. */
    public State state() {
        checkThread();
        return state;
    }

    /**
     * Executes a full one-time activation before resource access, never as part of simulation. All marked
     * buckets must load successfully; unmarked occupied paths/cache identities also fail closed. No healthy
     * subset is published on failure. Loaded native shards stay clean and original files are never replaced.
     * Returns empty with one server diagnostic on failure; subsequent calls do no scans until restart.
     */
    public Optional<DomainLedger> activate() {
        checkThread();
        if (state != State.NOT_LOADED) return state == State.AVAILABLE ? Optional.of(ledger) : Optional.empty();
        try {
            long mask = createdMask.getAsLong();
            Map<Integer, StorageBucketData> loaded = new HashMap<>();
            for (int index = 0; index < StorageBucketHash.COUNT; index++) {
                String name = ManagedSavedDataNames.networkBucket(networkId, index);
                BasicFileAttributes attributes = attributes(name);
                boolean marked = (mask & (1L << index)) != 0;
                if (attributes != null && (!marked || !attributes.isRegularFile())) {
                    throw new IllegalStateException("Unmarked or nonregular storage bucket path: " + name);
                }
                SavedData data = storage.get(factory(index), name);
                if (!marked) {
                    if (data != null) throw new IllegalStateException("Unmarked cached storage bucket: " + name);
                } else {
                    if (!(data instanceof StorageBucketData bucket)
                            || !networkId.equals(bucket.networkId())
                            || bucket.bucketIndex() != index) {
                        throw new IllegalStateException("Missing, unreadable or mismatched storage bucket: " + name);
                    }
                    loaded.put(index, bucket);
                }
            }
            ledger = new DomainLedger(networkId, loaded, this::createBucket);
            state = State.AVAILABLE;
            return Optional.of(ledger);
        } catch (RuntimeException failure) {
            fail(failure);
            return Optional.empty();
        }
    }

    private StorageBucketData createBucket(int index) {
        checkThread();
        if (state != State.AVAILABLE) throw new IllegalStateException("Domain storage is unavailable");
        try {
            long mask = createdMask.getAsLong();
            String name = ManagedSavedDataNames.networkBucket(networkId, index);
            if ((mask & (1L << index)) != 0 || attributes(name) != null || storage.get(factory(index), name) != null) {
                throw new IllegalStateException("New storage bucket identity is occupied: " + name);
            }
            StorageBucketData data = StorageBucketData.create(networkId, index);
            storage.set(name, data);
            publishMask.accept(mask | (1L << index));
            return data;
        } catch (RuntimeException failure) {
            fail(failure);
            throw failure;
        }
    }

    private SavedData.Factory<SavedData> factory(int index) {
        return new SavedData.Factory<>(
                () -> {
                    throw new IllegalStateException("Implicit storage bucket creation is forbidden");
                },
                (tag, registries) -> StorageBucketData.load(networkId, index, tag));
    }

    private @Nullable BasicFileAttributes attributes(String name) {
        try {
            return Files.readAttributes(
                    directory.resolve(name + ".dat"), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            return null;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot inspect storage bucket " + name, failure);
        }
    }

    private void fail(RuntimeException failure) {
        if (ledger != null) ledger.invalidate();
        ledger = null;
        state = State.UNAVAILABLE;
        LOGGER.error(
                "Resonance domain storage is unavailable for network {}; preserve files and restore from backup",
                networkId,
                failure);
    }

    /** Ends a verified-empty network lifecycle on the owner thread; retained ledger handles become unusable. */
    public void close() {
        checkThread();
        if (ledger != null) {
            if (ledger.variantCount() != 0 || ledger.hasReservations())
                throw new IllegalStateException("Cannot close occupied domain");
            ledger.invalidate();
        }
        ledger = null;
        state = State.UNAVAILABLE;
    }

    private void checkThread() {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Domain storage accessed off owner thread");
    }
}
