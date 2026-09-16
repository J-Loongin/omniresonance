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
import net.neoforged.neoforge.common.IOUtilities;
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
    private final io.github.loongin.omniresonance.transfer.ResourceAdapterDirectory adapters;
    private final Set<net.minecraft.resources.ResourceLocation> registeredTypes;
    private final Set<String> reservedNames = new HashSet<>();
    private final Map<UUID, NetworkSavedData> loadedNetworks = new HashMap<>();
    /** One lazy lifecycle per accessed loaded network; removed with its network or this server repository. */
    private final Map<UUID, DomainStorage> domains = new HashMap<>();

    /** Returns a lazy domain lifecycle on the server thread; this does not read bucket contents or simulate. */
    public DomainStorage domainStorage(UUID networkId) {
        requireOwningThread();
        NetworkSavedData network = loadedNetworks.get(Objects.requireNonNull(networkId));
        if (network == null) throw new IllegalArgumentException("Domain requires a loaded network");
        DomainStorage domain = domains.get(networkId);
        if (domain == null) {
            domain = new DomainStorage(
                    networkId, storage, dataDirectory, network::bucketCreatedMask, network::markStorageBuckets, () -> {
                        if (networkObserver != null) networkObserver.accept(networkId);
                    });
            domains.put(networkId, domain);
        }
        return domain;
    }

    private boolean unreadableNetworkShards;
    private @Nullable RuntimeListener runtimeListener;
    private @Nullable java.util.function.Consumer<UUID> networkObserver;

    /** Server-thread eligibility observer; only queues keys after commits, never reads partially published data. */
    public void onNetworkChanged(@Nullable java.util.function.Consumer<UUID> observer) {
        requireOwningThread();
        networkObserver = observer;
        for (NetworkSavedData data : loadedNetworks.values()) attachRuntimeListener(data);
    }

    private void notifyNetworkChanged(UUID id) {
        if (runtimeListener != null) runtimeListener.networkChanged(id);
        if (networkObserver != null) networkObserver.accept(id);
    }
    /** One server-session observer. Callbacks enqueue keys only and never read partially published authority. */
    public interface RuntimeListener {
        void networkChanged(UUID id);

        void ownerCreated(UUID id);

        default void recoveryChanged(UUID id) {}
    }
    /** Installs or releases the single runtime observer without disk I/O or SavedData mutation. */
    public void onRuntimeChanged(@Nullable RuntimeListener listener) {
        requireOwningThread();
        runtimeListener = listener;
        for (NetworkSavedData data : loadedNetworks.values()) attachRuntimeListener(data);
    }
    /** Startup-only detached ID snapshot; normal runtime work uses explicit changes and O(1) lookups. */
    public List<UUID> loadedNetworkIds() {
        requireOwningThread();
        return List.copyOf(loadedNetworks.keySet());
    }

    private void attachRuntimeListener(NetworkSavedData data) {
        data.onRecoveryChanged(
                runtimeListener == null
                        ? null
                        : () -> {
                            if (runtimeListener != null)
                                runtimeListener.recoveryChanged(data.metadata().id());
                        });
        data.onRuntimeChanged(
                runtimeListener == null && networkObserver == null
                        ? null
                        : () -> notifyNetworkChanged(data.metadata().id()));
    }

    /** Immutable startup snapshot of one healthy network and its authoritative node records. */
    public record LoadedNetwork(NetworkMetadata metadata, List<NetworkNodeRecord> nodes) {
        public LoadedNetwork {
            metadata = Objects.requireNonNull(metadata, "metadata");
            nodes = List.copyOf(nodes);
        }
    }

    /** Retains the caller's matching storage/directory without I/O or mutation; missing inputs are rejected. */
    public SavedNetworkRepository(DimensionDataStorage storage, Path dataDirectory) {
        this(
                storage,
                dataDirectory,
                io.github.loongin.omniresonance.transfer.ResourceAdapterDirectory.nativeDefaults());
    }

    public SavedNetworkRepository(
            DimensionDataStorage storage,
            Path dataDirectory,
            io.github.loongin.omniresonance.transfer.ResourceAdapterDirectory adapters) {
        this.adapters = Objects.requireNonNull(adapters);
        this.registeredTypes = Set.copyOf(adapters.types());
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

    /** Returns the shared frozen startup directory on the owner thread, without discovery, simulation or mutation. */
    public io.github.loongin.omniresonance.transfer.ResourceAdapterDirectory resourceAdapters() {
        requireOwningThread();
        return adapters;
    }

    /** Returns the immutable registered ID snapshot shared by decoding and edit validation; no authority is changed. */
    public Set<net.minecraft.resources.ResourceLocation> registeredResourceTypes() {
        requireOwningThread();
        return registeredTypes;
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
                    attachRuntimeListener(network);
                    result.add(new LoadedNetwork(network.metadata(), network.nodes()));
                } catch (IllegalArgumentException | IllegalStateException exception) {
                    unreadableNetworkShards = true;
                    LOGGER.error("Excluded unreadable network shard {}: {}", id, exception.getMessage());
                }
            }
        } catch (IOException | java.nio.file.DirectoryIteratorException exception) {
            throw new IllegalStateException("Could not enumerate network data directory", exception);
        }
        result.sort(Comparator.comparing(entry -> entry.metadata().id()));
        return List.copyOf(result);
    }

    /**
     * Returns whether any network shard failed validation during this repository lifecycle. This owning-thread,
     * read-only signal never clears after repeated enumeration or file removal and performs no I/O or simulation.
     */
    @ApiStatus.Internal
    public boolean hasUnreadableNetworkShards() {
        requireOwningThread();
        return unreadableNetworkShards;
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
        attachRuntimeListener(network);
        notifyNetworkChanged(metadata.id());
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
        if (runtimeListener != null) runtimeListener.ownerCreated(id);
        return owner;
    }

    /**
     * Detaches one exact loaded network from the native save cache and this repository, then queues deletion of
     * only its standard file behind prior native saves. The identity remains reserved for this repository's
     * lifetime. Missing networks reject before mutation; completion is an in-memory commit, not an I/O receipt.
     */
    public void removeNetwork(UUID id) {
        requireOwningThread();
        Objects.requireNonNull(id, "id");
        NetworkSavedData removed = loadedNetworks.get(id);
        if (removed == null) {
            throw new IllegalArgumentException("Network is not loaded");
        }
        String name = ManagedSavedDataNames.network(id);
        Path file = dataDirectory.resolve(name + ".dat");
        requireEmptyNetworkStorage(id);
        long bucketMask = removed.bucketCreatedMask();
        for (int bucket = 0; bucket < 64; bucket++) {
            if ((bucketMask & (1L << bucket)) != 0) storage.set(ManagedSavedDataNames.networkBucket(id, bucket), null);
        }
        storage.set(name, null);
        if (!loadedNetworks.remove(id, removed)) {
            throw new IllegalStateException("Network authority changed during removal");
        }
        removed.onRuntimeChanged(null);
        removed.onRecoveryChanged(null);
        DomainStorage domain = domains.remove(id);
        if (domain != null) domain.close();
        notifyNetworkChanged(id);
        IOUtilities.withIOWorker(() -> {
            for (int bucket = 0; bucket < 64; bucket++) {
                if ((bucketMask & (1L << bucket)) != 0) {
                    deleteNetworkFile(
                            dataDirectory.resolve(ManagedSavedDataNames.networkBucket(id, bucket) + ".dat"), id);
                }
            }
            deleteNetworkFile(file, id);
        });
    }

    /**
     * Verifies the main file kind and activates all declared domain buckets before proving inventory empty.
     * Missing/corrupt/unmarked storage and in-flight reservations fail closed. Reads and activation never
     * rewrite files; only a separately authorized removeNetwork call detaches and deletes verified-empty shards.
     */
    public void requireEmptyNetworkStorage(UUID id) {
        requireOwningThread();
        Objects.requireNonNull(id, "id");
        if (!loadedNetworks.containsKey(id)) {
            throw new IllegalArgumentException("Network is not loaded");
        }
        String mainName = ManagedSavedDataNames.network(id);
        BasicFileAttributes main = attributes(dataDirectory.resolve(mainName + ".dat"));
        if (main != null && !main.isRegularFile()) {
            throw new IllegalStateException("Network shard path is not a regular file");
        }
        var ledger = domainStorage(id)
                .activate()
                .orElseThrow(() -> new IllegalStateException("Unverifiable domain prevents deletion"));
        if (ledger.variantCount() != 0 || ledger.hasReservations()) {
            throw new IllegalStateException("Domain inventory or reservations prevent deletion");
        }
    }

    private static void deleteNetworkFile(Path file, UUID id) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException | RuntimeException failure) {
            LOGGER.error("Could not delete removed network shard {} at {}", id, file, failure);
        }
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

    private SavedData.Factory<SavedData> networkFactory(UUID id) {
        return new SavedData.Factory<>(
                () -> {
                    throw new IllegalStateException("Implicit network creation is forbidden");
                },
                (tag, registries) -> NetworkSavedData.load(id, tag, registeredTypes));
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
