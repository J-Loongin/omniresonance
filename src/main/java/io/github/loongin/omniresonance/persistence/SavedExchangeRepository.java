// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.jetbrains.annotations.Nullable;

/**
 * Owner-server-thread repository for one standard exchange shard. The caller owns the matching overworld storage
 * and directory. Reads never create authority, flush files or simulate. Failed reads or uncertain registrations
 * permanently block this repository until its server-session lifecycle ends, preventing destructive recreation.
 * One loaded authority reference is retained per repository and shared with native storage; missing reads are not
 * cached here. Filesystem inspection and loading happen only on management access, never the transfer hot path.
 */
public final class SavedExchangeRepository {
    private final Thread owningThread = Thread.currentThread();
    private final DimensionDataStorage storage;
    private final Path file;
    private final ExchangeSavedData.Limits limits;
    private @Nullable ExchangeSavedData loaded;
    private @Nullable RuntimeException failure;

    public SavedExchangeRepository(DimensionDataStorage storage, Path dataDirectory, ExchangeSavedData.Limits limits) {
        this.storage = Objects.requireNonNull(storage);
        this.file = Objects.requireNonNull(dataDirectory).resolve(ExchangeSavedData.STORAGE_ID + ".dat");
        this.limits = Objects.requireNonNull(limits);
    }

    /**
     * Returns the shared loaded shard reference on the owner thread, without creating missing data or transferring ownership.
     * The shard itself remains mutable only under its server-thread contract. Unreadable, nonregular, wrong-type
     * or uncertain authority throws and permanently blocks this repository; it is never reinterpreted as empty.
     */
    public Optional<ExchangeSavedData> find() {
        requireAvailable();
        if (loaded != null) return Optional.of(loaded);
        try {
            BasicFileAttributes attributes = attributes();
            if (attributes != null && !attributes.isRegularFile())
                throw new IllegalStateException("Exchange shard is not a regular file");
            SavedData value = storage.get(factory(), ExchangeSavedData.STORAGE_ID);
            if (value == null) {
                if (attributes != null)
                    throw new IllegalStateException("Existing exchange authority could not be read");
                return Optional.empty();
            }
            if (!(value instanceof ExchangeSavedData exchange))
                throw new IllegalStateException("Exchange storage identity has another data type");
            loaded = exchange;
            return Optional.of(exchange);
        } catch (RuntimeException exception) {
            failure = exception;
            throw new IllegalStateException("Exchange authority is unavailable", exception);
        }
    }

    /**
     * Explicit first creation after proving both path and native cache absent. Registers dirty authority without
     * forcing I/O. The caller must authorize the triggering operation before calling. Existing data rejects;
     * a throwing registration has unknown outcome and blocks all further repository operations without retry.
     */
    public ExchangeSavedData create() {
        requireAvailable();
        if (find().isPresent()) throw new IllegalStateException("Exchange authority already exists");
        ExchangeSavedData candidate = ExchangeSavedData.create(limits);
        try {
            storage.set(ExchangeSavedData.STORAGE_ID, candidate);
            loaded = candidate;
            return candidate;
        } catch (RuntimeException exception) {
            failure = exception;
            throw new IllegalStateException("Exchange registration outcome is unknown", exception);
        }
    }

    private SavedData.Factory<SavedData> factory() {
        return new SavedData.Factory<>(
                () -> {
                    throw new IllegalStateException("Implicit exchange creation is forbidden");
                },
                (tag, registries) -> ExchangeSavedData.load(tag, limits));
    }

    private @Nullable BasicFileAttributes attributes() {
        try {
            return Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            return null;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not inspect exchange shard", exception);
        }
    }

    private void requireAvailable() {
        if (Thread.currentThread() != owningThread)
            throw new IllegalStateException("Exchange repository accessed outside owning server thread");
        if (failure != null) throw new IllegalStateException("Exchange repository remains unavailable", failure);
    }
}
