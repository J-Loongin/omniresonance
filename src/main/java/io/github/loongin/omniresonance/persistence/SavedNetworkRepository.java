// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.persistence;

import io.github.loongin.omniresonance.network.NetworkMetadata;
import io.github.loongin.omniresonance.node.NetworkNodeRecord;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-thread repository using only the caller-owned standard DimensionDataStorage save lifecycle.
 *
 * <p>The caller must provide the matching overworld data directory and construct/use this instance on its server
 * thread. Storage ownership stays with the caller. Identity reservations cover only loaded, failed, and created
 * managed shards for this repository's lifetime; they prevent failed reads or later file removal from permitting
 * replacement. No missing-query result is retained here. No method simulates, flushes, or creates directories.
 * Wrong-thread calls fail before state access; I/O/read failures throw {@link IllegalStateException}.
 */
public final class SavedNetworkRepository {
    /**
     * Internal cross-package signal that authoritative registration threw with an unknown result.
     * The cause is retained for server diagnostics; callers must stop the affected intent without
     * assuming rollback. This implementation detail is not a supported integration or transaction API.
     */
    @ApiStatus.Internal
    public static final class RegistrationFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private RegistrationFailure(String name, RuntimeException failure) {
            super("SavedData registration outcome is unknown for " + name, failure);
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(SavedNetworkRepository.class);
    private final Thread owningThread = Thread.currentThread();
    private final DimensionDataStorage storage;
    private final Path dataDirectory;
    private final Set<String> reservedNames = new HashSet<>();
    private final Map<UUID, NetworkSavedData> loadedNetworks = new HashMap<>();

    /** Immutable startup snapshot of one healthy network and its authoritative node records. */
    public record LoadedNetwork(NetworkMetadata metadata, List<NetworkNodeRecord> nodes) {
        public LoadedNetwork {
            metadata = Objects.requireNonNull(metadata, "metadata");
            nodes = List.copyOf(nodes);
        }
    }

    /** Retains the caller's matching storage/directory without I/O or mutation; missing inputs are rejected. */
    public SavedNetworkRepository(DimensionDataStorage storage, Path dataDirectory) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
    }

    /**
     * Loads strict ordinary network files through standard SavedData factories on the owning server thread.
     *
     * <p>Returns an immutable metadata snapshot, never buckets. Individual invalid files are logged and excluded;
     * directory enumeration failure throws instead of returning an empty directory. Nothing is dirtied or saved.
     * Cross-record conflicts are validated by the directory before any metadata is exposed to players.
     */
    public List<NetworkMetadata> loadNetworks() {
        return loadNetworkData().stream().map(LoadedNetwork::metadata).toList();
    }

    /**
     * Loads strict ordinary network files and retains their authoritative v2 SavedData instances.
     *
     * <p>Returns immutable metadata/node snapshots in stable network-ID order. Individual invalid files are
     * reserved, logged and excluded without replacement; directory failures throw. Repeated calls reuse the exact
     * storage-owned instances and perform no implicit creation, dirtying, save or world access.
     */
    public List<LoadedNetwork> loadNetworkData() {
        requireOwningThread();
        List<LoadedNetwork> result = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dataDirectory)) {
            for (Path file : files) {
                Optional<UUID> parsed = ManagedSavedDataNames.parseNetworkFileName(
                        file.getFileName().toString());
                if (parsed.isEmpty()) {
                    continue;
                }
                UUID id = parsed.orElseThrow();
                String name = ManagedSavedDataNames.network(id);
                try {
                    BasicFileAttributes attributes = attributes(file);
                    if (attributes == null || !attributes.isRegularFile()) {
                        continue;
                    }
                    reservedNames.add(name);
                    SavedData loaded = storage.get(networkFactory(id), name);
                    if (!(loaded instanceof NetworkSavedData network)
                            || !network.metadata().id().equals(id)) {
                        throw new IllegalStateException("Network shard could not be validated");
                    }
                    NetworkSavedData previous = loadedNetworks.putIfAbsent(id, network);
                    if (previous != null && previous != network) {
                        throw new IllegalStateException("Network authority instance changed during one lifecycle");
                    }
                    result.add(new LoadedNetwork(network.metadata(), network.nodes()));
                } catch (IllegalArgumentException | IllegalStateException exception) {
                    LOGGER.error("Excluded unreadable network shard {}: {}", id, exception.getMessage());
                }
            }
        } catch (IOException | java.nio.file.DirectoryIteratorException exception) {
            throw new IllegalStateException("Could not enumerate network data directory", exception);
        }
        result.sort(Comparator.comparing(entry -> entry.metadata().id()));
        return List.copyOf(result);
    }

    /** Returns only an already loaded/created authoritative shard without I/O or creation. */
    public Optional<NetworkSavedData> findLoadedNetwork(UUID id) {
        requireOwningThread();
        return Optional.ofNullable(loadedNetworks.get(Objects.requireNonNull(id, "id")));
    }

    /**
     * Reads an owner without creating a shard. Missing files/cache entries return empty; occupied nonregular
     * paths, prior failed loads, invalid records, and identity mismatches fail closed without replacement.
     * Returned SavedData remains server-thread/storage-owned; this operation does not dirty or save it.
     */
    public Optional<OwnerSavedData> findOwner(UUID id) {
        requireOwningThread();
        String name = ManagedSavedDataNames.owner(id);
        BasicFileAttributes attributes = attributes(dataDirectory.resolve(name + ".dat"));
        if (attributes != null && !attributes.isRegularFile()) {
            reservedNames.add(name);
            throw new IllegalStateException("Owner shard path is not a regular file");
        }
        boolean reserved = reservedNames.contains(name);
        if (attributes != null) {
            reservedNames.add(name);
        }
        SavedData loaded = storage.get(ownerFactory(id), name);
        if (loaded == null) {
            if (attributes != null || reserved) {
                throw new IllegalStateException("Owner shard could not be loaded");
            }
            return Optional.empty();
        }
        reservedNames.add(name);
        if (!(loaded instanceof OwnerSavedData owner) || !owner.ownerId().equals(id)) {
            throw new IllegalStateException("Owner shard identity could not be validated");
        }
        return Optional.of(owner);
    }

    /**
     * Registers a new dirty network without writing disk. Any existing path, loaded object, or reserved identity
     * rejects the call before mutation. The immutable metadata can be shared; the new shard belongs to storage.
     * Duplicate identity throws {@link IllegalArgumentException}; no replacement or simulation is attempted.
     * An exception from authoritative registration retains its cause in an internal unknown-result signal.
     */
    public void createNetwork(NetworkMetadata metadata) {
        requireOwningThread();
        Objects.requireNonNull(metadata, "metadata");
        String name = ManagedSavedDataNames.network(metadata.id());
        requireAbsent(name, networkFactory(metadata.id()));
        NetworkSavedData network = NetworkSavedData.create(metadata);
        register(name, network);
        reservedNames.add(name);
        loadedNetworks.put(metadata.id(), network);
    }

    /**
     * Registers a new dirty owner on the owning thread, never overwriting an occupied/reserved identity.
     *
     * <p>Nullable default is caller-validated. The returned mutable shard belongs to storage; no simulation or
     * save occurs. Duplicates throw {@link IllegalArgumentException} before mutation, and I/O failures fail closed.
     * Authoritative registration exceptions carry an internal unknown-result signal, never a rollback claim.
     */
    public OwnerSavedData createOwner(UUID id, @Nullable UUID defaultId) {
        requireOwningThread();
        String name = ManagedSavedDataNames.owner(id);
        requireAbsent(name, ownerFactory(id));
        OwnerSavedData owner = OwnerSavedData.create(id, defaultId);
        register(name, owner);
        reservedNames.add(name);
        return owner;
    }

    private void register(String name, SavedData data) {
        try {
            storage.set(name, data);
        } catch (RuntimeException failure) {
            throw new RegistrationFailure(name, failure);
        }
    }

    private void requireAbsent(String name, SavedData.Factory<SavedData> factory) {
        if (reservedNames.contains(name) || attributes(dataDirectory.resolve(name + ".dat")) != null) {
            throw new IllegalArgumentException("SavedData identity is already occupied");
        }
        // get, unlike computeIfAbsent, cannot construct a replacement; it also detects another repository's cache.
        if (storage.get(factory, name) != null) {
            reservedNames.add(name);
            throw new IllegalArgumentException("SavedData identity is already cached");
        }
    }

    private static SavedData.Factory<SavedData> networkFactory(UUID id) {
        return new SavedData.Factory<>(
                () -> {
                    throw new IllegalStateException("Implicit network creation is forbidden");
                },
                (tag, registries) -> NetworkSavedData.load(id, tag));
    }

    private static SavedData.Factory<SavedData> ownerFactory(UUID id) {
        return new SavedData.Factory<>(
                () -> {
                    throw new IllegalStateException("Implicit owner creation is forbidden");
                },
                (tag, registries) -> OwnerSavedData.load(id, tag));
    }

    private static @Nullable BasicFileAttributes attributes(Path file) {
        try {
            return Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException exception) {
            return null;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not inspect managed data path", exception);
        }
    }

    private void requireOwningThread() {
        if (Thread.currentThread() != owningThread) {
            throw new IllegalStateException("Network repository accessed outside its owning server thread");
        }
    }
}
