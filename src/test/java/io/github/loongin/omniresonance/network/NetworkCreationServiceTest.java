// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.network;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.node.NodeForm;
import io.github.loongin.omniresonance.persistence.ManagedSavedDataNames;
import io.github.loongin.omniresonance.persistence.NetworkSavedData;
import io.github.loongin.omniresonance.persistence.OwnerSavedData;
import io.github.loongin.omniresonance.persistence.SavedNetworkRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.common.IOUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NetworkCreationServiceTest {
    private static final UUID OWNER = new UUID(0, 100);
    private static final UUID OTHER = new UUID(0, 200);
    private static final UUID ADMIN = new UUID(0, 300);

    @TempDir
    Path temporaryDirectory;

    private DimensionDataStorage storage;
    private SavedNetworkRepository repository;
    private NetworkDirectory directory;
    private NetworkCreationService service;

    @BeforeEach
    void setUp() {
        storage =
                new DimensionDataStorage(temporaryDirectory.toFile(), DataFixers.getDataFixer(), RegistryAccess.EMPTY);
        repository = new SavedNetworkRepository(storage, temporaryDirectory);
        directory = new NetworkDirectory(List.of());
        AtomicLong ids = new AtomicLong();
        service = new NetworkCreationService(repository, directory, () -> new UUID(0, ids.incrementAndGet()));
    }

    @AfterEach
    void finishIo() {
        IOUtilities.waitUntilIOWorkerComplete();
    }

    @Test
    void queriesDoNotCreateShardsAndFirstExplicitNetworkBecomesDefault() throws IOException {
        assertTrue(service.preferredNetwork(OWNER).isEmpty());
        assertTrue(directory.accessibleTo(OWNER).isEmpty());
        assertTrue(repository.loadNetworks().isEmpty());
        storage.save();
        IOUtilities.waitUntilIOWorkerComplete();
        assertEquals(List.of(), fileNames());
        NetworkMetadata created = service.create(OWNER, " 主网络 ", 1);
        assertEquals("主网络", created.name().value());
        assertEquals(created.id(), service.preferredNetwork(OWNER).orElseThrow());
        assertEquals(0, created.creationOrder());
        assertThrows(IllegalArgumentException.class, () -> service.create(OWNER, "Second", 1));
        assertEquals(List.of(created), directory.ownedBy(OWNER));
        assertEquals(List.of(), fileNames());
    }

    @Test
    void laterCreationPreservesDefaultAndQuotaReductionRetainsExistingNetworks() {
        NetworkMetadata first = service.create(OWNER, "First", -1);
        NetworkMetadata second = service.create(OWNER, "Second", -1);
        assertEquals(1, second.creationOrder());
        assertEquals(first.id(), service.preferredNetwork(OWNER).orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> service.create(OWNER, "Third", 1));
        assertEquals(List.of(first, second), directory.ownedBy(OWNER));
        assertThrows(
                UnsupportedOperationException.class,
                () -> directory.ownedBy(OWNER).clear());
    }

    @Test
    void rejectsNamesAndQuotaBeforeCreatingOwnerOrNetwork() {
        for (int quota : new int[] {-2, 0, 1025}) {
            assertThrows(IllegalArgumentException.class, () -> service.create(OWNER, "Valid", quota));
        }
        assertThrows(IllegalArgumentException.class, () -> service.create(OWNER, "  ", 1));
        assertTrue(repository.findOwner(OWNER).isEmpty());
        assertTrue(directory.ownedBy(OWNER).isEmpty());
        service.create(OWNER, "Alpha", -1);
        assertThrows(IllegalArgumentException.class, () -> service.create(OWNER, "ALPHA", -1));
        service.create(OTHER, "alpha", -1);
        assertEquals(1, directory.ownedBy(OWNER).size());
        assertEquals(1, directory.ownedBy(OTHER).size());
    }

    @Test
    void indexesOnlyExplicitRolesAndOrdersByCreationThenUuid() {
        NetworkMetadata later = metadata(3, OWNER, "Later", 9, Set.of(ADMIN));
        NetworkMetadata earlier = metadata(2, OWNER, "Earlier", 1, Set.of());
        NetworkMetadata tie = metadata(1, OTHER, "Tie", 9, Set.of(ADMIN));
        NetworkDirectory loaded = new NetworkDirectory(List.of(later, earlier, tie));
        assertEquals(List.of(earlier, later), loaded.ownedBy(OWNER));
        assertEquals(List.of(tie, later), loaded.accessibleTo(ADMIN));
        assertEquals(List.of(), loaded.accessibleTo(new UUID(0, 999)));
        assertEquals(10, loaded.nextCreationOrder(OWNER));
        assertThrows(
                UnsupportedOperationException.class,
                () -> loaded.accessibleTo(ADMIN).clear());
        assertThrows(IllegalArgumentException.class, () -> loaded.add(metadata(3, OTHER, "New", 0, Set.of())));
        assertThrows(IllegalArgumentException.class, () -> loaded.add(metadata(4, OWNER, "LATER", 0, Set.of())));
        assertEquals(List.of(earlier, later), loaded.ownedBy(OWNER));
    }

    @Test
    void startupExcludesAllConflictingIdentitiesAndNamesButKeepsHealthyNetworks() {
        NetworkMetadata healthy = metadata(5, OTHER, "Healthy", 0, Set.of());
        NetworkMetadata duplicateId = metadata(3, OTHER, "Different", 0, Set.of());
        NetworkDirectory loaded = new NetworkDirectory(List.of(
                metadata(1, OWNER, "Name", 0, Set.of()),
                metadata(2, OWNER, "NAME", 1, Set.of()),
                metadata(3, OWNER, "Unique", 2, Set.of()),
                duplicateId,
                healthy));
        assertTrue(loaded.ownedBy(OWNER).isEmpty());
        assertEquals(List.of(healthy), loaded.ownedBy(OTHER));
    }

    @Test
    void excludedNameConflictsBlockOwnerCreationWithoutHidingItsHealthyNetwork() {
        NetworkMetadata first = metadata(10, OWNER, "Name", 0, Set.of());
        NetworkMetadata duplicate = metadata(11, OWNER, "NAME", 1, Set.of());
        NetworkMetadata healthy = metadata(12, OWNER, "Healthy", 2, Set.of());
        for (NetworkMetadata metadata : List.of(first, duplicate, healthy)) {
            repository.createNetwork(metadata);
        }
        NetworkDirectory loaded = new NetworkDirectory(List.of(first, duplicate, healthy));
        AtomicLong sequence = new AtomicLong(20);
        NetworkCreationService guarded =
                new NetworkCreationService(repository, loaded, () -> new UUID(0, sequence.incrementAndGet()));
        assertThrows(IllegalStateException.class, () -> guarded.create(OWNER, "name", -1));
        assertThrows(IllegalStateException.class, () -> guarded.create(OWNER, "Different", 2));
        assertEquals(List.of(healthy), loaded.accessibleTo(OWNER));
        assertTrue(repository.findOwner(OWNER).isEmpty());
        NetworkMetadata other = guarded.create(OTHER, "Name", 1);
        assertEquals(List.of(other), loaded.ownedBy(OTHER));
    }

    @Test
    void excludedDuplicateIdentityBlocksEveryAffectedOwnerButNotUnrelatedOwners() {
        NetworkDirectory loaded = new NetworkDirectory(
                List.of(metadata(10, OWNER, "Owner", 0, Set.of()), metadata(10, OTHER, "Other", 0, Set.of())));
        NetworkCreationService guarded = new NetworkCreationService(repository, loaded, () -> new UUID(0, 20));
        assertThrows(IllegalStateException.class, () -> guarded.create(OWNER, "New", -1));
        assertThrows(IllegalStateException.class, () -> guarded.create(OTHER, "New", -1));
        NetworkMetadata healthy = guarded.create(ADMIN, "New", 1);
        assertEquals(List.of(healthy), loaded.ownedBy(ADMIN));
    }

    @Test
    void uuidCollisionDoesNotCreateOwnerOrChangeExistingDefault() {
        NetworkMetadata existing = service.create(OWNER, "Existing", -1);
        NetworkCreationService collision = new NetworkCreationService(repository, directory, existing::id);
        assertThrows(IllegalArgumentException.class, () -> collision.create(OTHER, "Other", -1));
        assertThrows(IllegalArgumentException.class, () -> collision.create(OWNER, "Another", -1));
        assertTrue(repository.findOwner(OTHER).isEmpty());
        assertEquals(existing.id(), service.preferredNetwork(OWNER).orElseThrow());
        assertEquals(List.of(existing), directory.ownedBy(OWNER));
    }

    @Test
    void repositoryRejectsExistingCacheEntriesIncludingEntriesFromAnotherRepository() {
        NetworkMetadata existing = metadata(1, OWNER, "Existing", 0, Set.of());
        NetworkSavedData network = NetworkSavedData.create(existing);
        storage.set(ManagedSavedDataNames.network(existing.id()), network);
        OwnerSavedData owner = OwnerSavedData.create(OWNER, existing.id());
        storage.set(ManagedSavedDataNames.owner(OWNER), owner);
        assertThrows(IllegalArgumentException.class, () -> service.create(OTHER, "Collision", -1));
        assertTrue(repository.findOwner(OTHER).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> repository.createNetwork(existing));
        assertThrows(IllegalArgumentException.class, () -> repository.createOwner(OWNER, null));
        assertSame(owner, repository.findOwner(OWNER).orElseThrow());
    }

    @Test
    void repositoryRetainsCreatedAndReloadedAuthoritativeNetworkShards() {
        assertTrue(repository.findLoadedNetwork(new UUID(0, 1)).isEmpty());
        assertTrue(repository.loadNetworkData().isEmpty());
        NetworkMetadata metadata = metadata(1, OWNER, "Loaded", 0, Set.of());
        repository.createNetwork(metadata);
        NetworkSavedData created = repository.findLoadedNetwork(metadata.id()).orElseThrow();
        created.createNode(
                new UUID(9, 1),
                new ManagedName("Node"),
                GlobalPos.of(Level.OVERWORLD, new BlockPos(4, 64, 5)),
                NodeForm.PANEL,
                Direction.WEST);
        storage.save();
        IOUtilities.waitUntilIOWorkerComplete();

        SavedNetworkRepository reloaded = new SavedNetworkRepository(
                new DimensionDataStorage(temporaryDirectory.toFile(), DataFixers.getDataFixer(), RegistryAccess.EMPTY),
                temporaryDirectory);
        List<SavedNetworkRepository.LoadedNetwork> firstLoad = reloaded.loadNetworkData();
        NetworkSavedData authoritative =
                reloaded.findLoadedNetwork(metadata.id()).orElseThrow();
        assertEquals(
                List.of(metadata),
                firstLoad.stream()
                        .map(SavedNetworkRepository.LoadedNetwork::metadata)
                        .toList());
        assertEquals(authoritative.nodes(), firstLoad.getFirst().nodes());
        assertEquals(1, authoritative.nodes().size());
        assertEquals(firstLoad, reloaded.loadNetworkData());
        assertSame(authoritative, reloaded.findLoadedNetwork(metadata.id()).orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> firstLoad.clear());
        assertThrows(
                UnsupportedOperationException.class,
                () -> firstLoad.getFirst().nodes().clear());
    }

    @Test
    void brokenOwnerAndOccupiedNetworkPathsRejectWithoutPartialChanges() throws IOException {
        byte[] invalid = {1, 2, 3, 4};
        Path ownerPath = temporaryDirectory.resolve(ManagedSavedDataNames.owner(OWNER) + ".dat");
        Files.write(ownerPath, invalid);
        assertThrows(IllegalStateException.class, () -> service.preferredNetwork(OWNER));
        assertThrows(IllegalStateException.class, () -> service.create(OWNER, "Rejected", -1));
        assertTrue(directory.ownedBy(OWNER).isEmpty());
        Path collision = temporaryDirectory.resolve(ManagedSavedDataNames.network(new UUID(0, 1)) + ".dat");
        Files.createDirectory(collision);
        assertThrows(IllegalArgumentException.class, () -> service.create(OTHER, "Rejected", -1));
        assertTrue(repository.findOwner(OTHER).isEmpty());
        storage.save();
        IOUtilities.waitUntilIOWorkerComplete();
        assertArrayEquals(invalid, Files.readAllBytes(ownerPath));
        assertTrue(Files.isDirectory(collision));
    }

    @Test
    void ignoresSymlinkNetworksAndRejectsSymlinkOwnerWithoutFollowingThem() throws IOException {
        Path target = temporaryDirectory.resolve("target.dat");
        Files.write(target, new byte[] {1, 2, 3});
        Files.createSymbolicLink(
                temporaryDirectory.resolve(ManagedSavedDataNames.network(new UUID(0, 1)) + ".dat"), target);
        Files.createSymbolicLink(temporaryDirectory.resolve(ManagedSavedDataNames.owner(OWNER) + ".dat"), target);
        assertTrue(repository.loadNetworks().isEmpty());
        assertThrows(IllegalStateException.class, () -> repository.findOwner(OWNER));
        assertThrows(IllegalArgumentException.class, () -> service.create(OTHER, "Collision", -1));
        assertTrue(repository.findOwner(OTHER).isEmpty());
    }

    @Test
    void missingOwnerUsesEarliestOwnedNetworkButNeverRewritesNonemptyInvalidPointer() {
        NetworkMetadata earlier = metadata(10, OWNER, "Earlier", 4, Set.of());
        directory.add(earlier);
        repository.createNetwork(earlier);
        service.create(OWNER, "New", -1);
        assertEquals(earlier.id(), service.preferredNetwork(OWNER).orElseThrow());
        OwnerSavedData owner = repository.findOwner(OWNER).orElseThrow();
        owner.setDefaultNetwork(new UUID(0, 9999));
        service.create(OWNER, "Another", -1);
        assertTrue(service.preferredNetwork(OWNER).isEmpty());
        assertEquals(new UUID(0, 9999), owner.defaultNetworkId().orElseThrow());
        owner.setDefaultNetwork(service.create(OTHER, "Other", -1).id());
        assertTrue(service.preferredNetwork(OWNER).isEmpty());
    }

    @Test
    void largeOwnerCreationPreservesEarliestDefaultCandidate() {
        List<NetworkMetadata> networks = new ArrayList<>(10000);
        for (int index = 0; index < 10000; index++) {
            networks.add(metadata(index + 1, OWNER, "Network " + index, index, Set.of()));
        }
        repository.createNetwork(networks.getFirst());
        NetworkDirectory loaded = new NetworkDirectory(networks);
        NetworkCreationService largeOwner = new NetworkCreationService(repository, loaded, () -> new UUID(0, 20000));

        NetworkMetadata created = largeOwner.create(OWNER, "New", -1);

        assertEquals(10000, created.creationOrder());
        assertEquals(
                networks.getFirst().id(),
                repository.findOwner(OWNER).orElseThrow().defaultNetworkId().orElseThrow());
        assertEquals(10001, loaded.ownedCount(OWNER));
    }

    @Test
    void overflowRejectsBeforeRegisteringAnyNetworkOrOwner() {
        NetworkMetadata last = metadata(9, OWNER, "Last", Long.MAX_VALUE, Set.of());
        directory.add(last);
        assertThrows(ArithmeticException.class, () -> service.create(OWNER, "Overflow", -1));
        assertTrue(repository.findOwner(OWNER).isEmpty());
        assertEquals(List.of(last), directory.ownedBy(OWNER));
    }

    @Test
    void unlimitedGameplayQuotaStillEnforcesOwnerCollectionHardLimit() {
        List<NetworkMetadata> networks = new ArrayList<>(262144);
        for (int index = 0; index < 262144; index++) {
            networks.add(metadata(index + 1, OWNER, "Network " + index, index, Set.of()));
        }
        NetworkDirectory full = new NetworkDirectory(networks);
        NetworkCreationService unlimited = new NetworkCreationService(repository, full, () -> new UUID(0, 999999));
        assertThrows(IllegalArgumentException.class, () -> unlimited.create(OWNER, "Excess", -1));
        assertThrows(
                IllegalArgumentException.class, () -> full.add(metadata(999999, OWNER, "Excess", 262144, Set.of())));
        assertEquals(262144, full.ownedBy(OWNER).size());
        assertTrue(repository.findOwner(OWNER).isEmpty());
    }

    @Test
    void startupRejectsOversizedOwnerCollectionWithoutExposingTruncatedAccess() {
        List<NetworkMetadata> networks = new ArrayList<>(262146);
        for (int index = 0; index < 262145; index++) {
            networks.add(metadata(index + 1, OWNER, "Network " + index, index, Set.of()));
        }
        NetworkMetadata healthy = metadata(999999, OTHER, "Healthy", 0, Set.of());
        networks.add(healthy);
        NetworkDirectory loaded = new NetworkDirectory(networks);
        NetworkCreationService guarded = new NetworkCreationService(repository, loaded, () -> new UUID(0, 999998));
        assertThrows(IllegalStateException.class, () -> guarded.create(OWNER, "Excess", -1));
        assertTrue(loaded.ownedBy(OWNER).isEmpty());
        assertTrue(loaded.accessibleTo(OWNER).isEmpty());
        assertEquals(List.of(healthy), loaded.ownedBy(OTHER));
    }

    @Test
    void startupDirectoryFailureIsNotAnEmptyNetworkList() throws IOException {
        Path file = temporaryDirectory.resolve("not-a-directory");
        Files.write(file, new byte[] {1});
        SavedNetworkRepository invalid = new SavedNetworkRepository(storage, file);
        assertThrows(IllegalStateException.class, invalid::loadNetworks);
        SavedNetworkRepository absent = new SavedNetworkRepository(storage, temporaryDirectory.resolve("missing"));
        assertThrows(IllegalStateException.class, absent::loadNetworks);
    }

    @Test
    void rejectsCrossThreadAccessBeforeTouchingState() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            List<Runnable> operations = List.of(
                    () -> service.create(OWNER, "Wrong thread", -1),
                    () -> service.preferredNetwork(OWNER),
                    () -> repository.loadNetworks(),
                    () -> repository.loadNetworkData(),
                    () -> repository.findLoadedNetwork(new UUID(0, 1)),
                    () -> repository.findOwner(OWNER),
                    () -> repository.createOwner(OWNER, null),
                    () -> repository.createNetwork(metadata(1, OWNER, "Wrong thread", 0, Set.of())),
                    () -> directory.ownedBy(OWNER),
                    () -> directory.accessibleTo(OWNER),
                    () -> directory.find(OWNER),
                    () -> directory.containsName(OWNER, new ManagedName("Name")),
                    () -> directory.nextCreationOrder(OWNER),
                    () -> directory.add(metadata(1, OWNER, "Wrong thread", 0, Set.of())));
            for (Runnable operation : operations) {
                ExecutionException failure = assertThrows(
                        ExecutionException.class,
                        () -> executor.submit(operation).get());
                assertTrue(failure.getCause() instanceof IllegalStateException);
            }
        }
        assertFalse(repository.findOwner(OWNER).isPresent());
    }

    private List<String> fileNames() throws IOException {
        try (var files = Files.list(temporaryDirectory)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private static NetworkMetadata metadata(long id, UUID owner, String name, long order, Set<UUID> administrators) {
        return new NetworkMetadata(new UUID(0, id), owner, new ManagedName(name), order, administrators);
    }
}
