// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.network;

import io.github.loongin.omniresonance.persistence.OwnerSavedData;
import io.github.loongin.omniresonance.persistence.SavedNetworkRepository;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Explicit network creation on its constructing server thread, with no implicit login or query creation.
 *
 * <p>The caller owns repository/directory lifecycle and supplies a trusted connection owner's UUID and validated
 * configuration quota. IDs come from the caller's non-mutating server-thread supplier. This service performs no simulation
 * or saving; expected validation failures precede authoritative mutation and throw unchecked exceptions.
 */
public final class NetworkCreationService {
    /** Stable expected admission failures, independent of log or exception wording. */
    public enum Reason {
        INVALID_NAME,
        NAME_CONFLICT,
        QUOTA_REACHED
    }

    /** Pure typed validation failure raised before authoritative creation; safe to inspect on any thread. */
    public static final class Rejected extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final Reason reason;

        public Rejected(Reason reason) {
            super(reason.name());
            this.reason = Objects.requireNonNull(reason, "reason");
        }

        /** Returns the immutable rejection category without mutation or simulation. */
        public Reason reason() {
            return reason;
        }
    }

    /** Internal boundary marker: network registration succeeded but its commit tail did not complete. */
    static final class UncertainCommit extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private UncertainCommit(RuntimeException failure) {
            super("Network creation failed after authoritative network registration", failure);
        }
    }

    private final Thread owningThread = Thread.currentThread();
    private final SavedNetworkRepository repository;
    private final NetworkDirectory directory;
    private final Supplier<UUID> idSource;

    /** Retains caller-owned server-thread collaborators without I/O/mutation; missing inputs are rejected. */
    public NetworkCreationService(
            SavedNetworkRepository repository, NetworkDirectory directory, Supplier<UUID> idSource) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.idSource = Objects.requireNonNull(idSource, "idSource");
    }

    /**
     * Creates an owned network and, when necessary, its default pointer, then updates the derived directory.
     *
     * <p>Runs only on the owning server thread. Inputs remain caller-owned; the result is immutable. Invalid
     * names, quotas, duplicates, and hard-limit excess throw {@link IllegalArgumentException}; unreadable owner
     * state or inconsistent startup collections throw {@link IllegalStateException}; exhausted ordering throws
     * {@link ArithmeticException}. These
     * failures leave authoritative records/default/index unchanged. Unexpected failures after the first
     * authoritative network registration retain their cause in an internal stage marker: the caller must
     * stop that intent rather than assume rollback or retry it. No simulation or synchronous save occurs.
     */
    public NetworkMetadata create(UUID owner, String name, int networksPerOwner) {
        requireOwningThread();
        Objects.requireNonNull(owner, "owner");
        ManagedName managedName;
        try {
            managedName = new ManagedName(name);
        } catch (IllegalArgumentException failure) {
            throw new Rejected(Reason.INVALID_NAME);
        }
        if (networksPerOwner < -1 || networksPerOwner > 1024) {
            throw new IllegalArgumentException("Invalid network quota");
        }
        directory.requireCreationAllowed(owner);
        int ownedCount = directory.ownedCount(owner);
        Optional<NetworkMetadata> firstOwned = directory.firstOwned(owner);
        if (ownedCount >= NetworkDirectory.MAXIMUM_NETWORKS_PER_OWNER
                || (networksPerOwner >= 0 && ownedCount >= networksPerOwner)) {
            throw new Rejected(Reason.QUOTA_REACHED);
        }
        if (directory.containsName(owner, managedName)) {
            throw new Rejected(Reason.NAME_CONFLICT);
        }
        Optional<OwnerSavedData> ownerData = repository.findOwner(owner);
        UUID id = Objects.requireNonNull(idSource.get(), "generated network id");
        if (directory.find(id).isPresent()) {
            throw new IllegalArgumentException("Network identity already exists");
        }
        long creationOrder = directory.nextCreationOrder(owner);
        NetworkMetadata metadata = new NetworkMetadata(id, owner, managedName, creationOrder, Set.of());
        UUID defaultId = firstOwned.map(NetworkMetadata::id).orElse(id);
        repository.createNetwork(metadata);
        try {
            if (ownerData.isEmpty()) {
                repository.createOwner(owner, defaultId);
            } else if (ownerData.orElseThrow().defaultNetworkId().isEmpty()) {
                ownerData.orElseThrow().setDefaultNetwork(defaultId);
            }
            directory.add(metadata);
        } catch (SavedNetworkRepository.RegistrationFailure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new UncertainCommit(failure);
        }
        return metadata;
    }

    /**
     * Returns only an existing pointer to a verified network owned by this owner, without creating/repairing data.
     * Missing owner/pointer/network returns empty; unreadable owner state or wrong-thread use fails closed.
     * The returned UUID is immutable; no simulation, dirty marking, or persistent modification occurs.
     */
    public Optional<UUID> preferredNetwork(UUID owner) {
        requireOwningThread();
        return repository
                .findOwner(owner)
                .flatMap(OwnerSavedData::defaultNetworkId)
                .filter(id -> directory
                        .find(id)
                        .filter(metadata -> metadata.ownerId().equals(owner))
                        .isPresent());
    }

    private void requireOwningThread() {
        if (Thread.currentThread() != owningThread) {
            throw new IllegalStateException("Network creation accessed outside its owning server thread");
        }
    }
}
