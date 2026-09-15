// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.persistence;

import io.github.loongin.omniresonance.bootstrap.OmniResonanceMod;
import io.github.loongin.omniresonance.storage.DomainLedger;
import io.github.loongin.omniresonance.storage.StorageBucketHash;
import io.github.loongin.omniresonance.transfer.ResourceVariantKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.common.IOUtilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Native bucket lifecycle tests using isolated temporary data directories, never player saves. */
@GameTestHolder(OmniResonanceMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DomainStorageGameTests {
    private static final UUID NETWORK = new UUID(25, 30);
    private static final ResourceVariantKey KEY =
            new ResourceVariantKey(ResourceLocation.parse("example:opaque"), new byte[] {1, -1});

    private DomainStorageGameTests() {}

    @GameTest(template = "bootstrap")
    public static void liveRuntimeDrainsRecoveryWithoutNodesAndStopsAtUnavailableDomain(GameTestHelper helper)
            throws IOException {
        Path directory = Files.createTempDirectory("omniresonance-m4-runtime-recovery-");
        try {
            DimensionDataStorage nativeStorage = storage(helper, directory);
            SavedNetworkRepository repository = new SavedNetworkRepository(nativeStorage, directory);
            repository.createNetwork(new io.github.loongin.omniresonance.network.NetworkMetadata(
                    NETWORK,
                    new UUID(25, 31),
                    new io.github.loongin.omniresonance.network.ManagedName("Domain"),
                    0,
                    java.util.Set.of()));
            var data = repository.findLoadedNetwork(NETWORK).orElseThrow();
            data.recovery().restore(java.util.Map.of(KEY, Long.MAX_VALUE));
            var settings = io.github.loongin.omniresonance.config.ServerSettings.defaults();
            try (var runtime = new io.github.loongin.omniresonance.transfer.ResourceDirectRuntime(
                    helper.getLevel().getServer(),
                    repository,
                    new io.github.loongin.omniresonance.node.NetworkNodeDirectory(java.util.List.of()),
                    settings,
                    () -> 0)) {
                runtime.tick(0, settings);
                helper.assertTrue(data.recovery().isEmpty(), "Recovery-only network was never scheduled");
                helper.assertTrue(
                        repository
                                        .domainStorage(NETWORK)
                                        .activate()
                                        .orElseThrow()
                                        .amount(KEY)
                                == Long.MAX_VALUE,
                        "Recovery runtime lost the exact opaque amount");
                helper.assertTrue(runtime.cachedEndpoints() == 0, "Recovery discovered native endpoints");
                try (var consumed = repository
                        .domainStorage(NETWORK)
                        .activate()
                        .orElseThrow()
                        .withdraw(KEY, Long.MAX_VALUE)
                        .orElseThrow()) {
                    // Settle the fixture's inventory before exercising empty-network disposal.
                }
                repository.removeNetwork(NETWORK);
                runtime.tick(1, settings);
                try (var stale = data.recovery().reserve(KEY, 1, 1, 1000).orElseThrow()) {
                    helper.assertTrue(
                            stale.placeKnownRemainder(1).buffered() == 1,
                            "Deleted recovery-only network retained its runtime domain callback");
                }
            }
            UUID badId = new UUID(25, 32);
            repository.createNetwork(new io.github.loongin.omniresonance.network.NetworkMetadata(
                    badId,
                    new UUID(25, 31),
                    new io.github.loongin.omniresonance.network.ManagedName("Missing"),
                    1,
                    java.util.Set.of()));
            var bad = repository.findLoadedNetwork(badId).orElseThrow();
            bad.markStorageBuckets(1L);
            bad.recovery().restore(java.util.Map.of(KEY, 9L));
            try (var runtime = new io.github.loongin.omniresonance.transfer.ResourceDirectRuntime(
                    helper.getLevel().getServer(),
                    repository,
                    new io.github.loongin.omniresonance.node.NetworkNodeDirectory(java.util.List.of()),
                    settings,
                    () -> 0)) {
                runtime.tick(1, settings);
                runtime.tick(2, settings);
                helper.assertTrue(bad.recovery().amount(KEY) == 9, "Unavailable domain consumed recovery contents");
                helper.assertTrue(
                        repository.domainStorage(badId).state() == DomainStorage.State.UNAVAILABLE,
                        "Missing bucket did not isolate storage");
            }
            helper.succeed();
        } finally {
            remove(directory);
        }
    }

    @GameTest(template = "bootstrap")
    public static void repositoryPersistsBitmapAndDeletesOnlyVerifiedEmptyDomain(GameTestHelper helper)
            throws IOException {
        Path directory = Files.createTempDirectory("omniresonance-m4-repository-");
        try {
            DimensionDataStorage nativeStorage = storage(helper, directory);
            SavedNetworkRepository repository = new SavedNetworkRepository(nativeStorage, directory);
            repository.createNetwork(new io.github.loongin.omniresonance.network.NetworkMetadata(
                    NETWORK,
                    new UUID(25, 31),
                    new io.github.loongin.omniresonance.network.ManagedName("Domain"),
                    0,
                    java.util.Set.of()));
            DomainStorage domain = repository.domainStorage(NETWORK);
            helper.assertTrue(domain.state() == DomainStorage.State.NOT_LOADED, "Repository eagerly activated domain");
            DomainLedger ledger = domain.activate().orElseThrow();
            try (DomainLedger.Deposit deposit =
                    ledger.reserveDeposit(KEY, 14, -1).orElseThrow()) {
                deposit.commit(14);
            }
            nativeStorage.save();
            IOUtilities.waitUntilIOWorkerComplete();
            DimensionDataStorage freshStorage = storage(helper, directory);
            SavedNetworkRepository fresh = new SavedNetworkRepository(freshStorage, directory);
            fresh.loadNetworks();
            DomainLedger loaded = fresh.domainStorage(NETWORK).activate().orElseThrow();
            helper.assertTrue(loaded.amount(KEY) == 14, "Repository did not reload persisted mask and bucket together");
            boolean nonemptyRejected = false;
            try {
                fresh.requireEmptyNetworkStorage(NETWORK);
            } catch (IllegalStateException expected) {
                nonemptyRejected = true;
            }
            helper.assertTrue(nonemptyRejected, "Nonempty ledger permitted network deletion");
            try (DomainLedger.Withdrawal pending = loaded.withdraw(KEY, 14).orElseThrow()) {
                boolean pendingRejected = false;
                try {
                    fresh.requireEmptyNetworkStorage(NETWORK);
                } catch (IllegalStateException expected) {
                    pendingRejected = true;
                }
                helper.assertTrue(pendingRejected, "Pending return permitted network deletion");
            }
            fresh.requireEmptyNetworkStorage(NETWORK);
            fresh.removeNetwork(NETWORK);
            freshStorage.save();
            IOUtilities.waitUntilIOWorkerComplete();
            try (var files = Files.list(directory)) {
                helper.assertTrue(
                        files.findAny().isEmpty(), "Deleted empty network was saved again or left bucket files");
            }
            helper.succeed();
        } finally {
            remove(directory);
        }
    }

    @GameTest(template = "bootstrap")
    public static void lazyBucketsSaveReloadAndRetainEmptyFiles(GameTestHelper helper) throws IOException {
        Path directory = Files.createTempDirectory("omniresonance-m4-domain-");
        try {
            AtomicLong mask = new AtomicLong();
            DimensionDataStorage nativeStorage = storage(helper, directory);
            DomainStorage domain = new DomainStorage(NETWORK, nativeStorage, directory, mask::get, mask::set);
            helper.assertTrue(domain.state() == DomainStorage.State.NOT_LOADED, "Construction activated buckets");
            nativeStorage.save();
            IOUtilities.waitUntilIOWorkerComplete();
            try (var paths = Files.list(directory)) {
                helper.assertTrue(paths.findAny().isEmpty(), "Inactive domain wrote data");
            }
            DomainLedger ledger = domain.activate().orElseThrow();
            helper.assertTrue(mask.get() == 0, "Empty activation created a bucket");
            try (DomainLedger.Deposit deposit =
                    ledger.reserveDeposit(KEY, Long.MAX_VALUE, -1).orElseThrow()) {
                deposit.commit(Long.MAX_VALUE);
            }
            int bucket = StorageBucketHash.bucket(KEY);
            helper.assertTrue(mask.get() == (1L << bucket), "Created bitmap did not match the resource bucket");
            nativeStorage.save();
            IOUtilities.waitUntilIOWorkerComplete();
            Path file = directory.resolve(ManagedSavedDataNames.networkBucket(NETWORK, bucket) + ".dat");
            helper.assertTrue(Files.isRegularFile(file), "Normal native save omitted the bucket");
            DimensionDataStorage reloadedStorage = storage(helper, directory);
            DomainStorage reloaded = new DomainStorage(NETWORK, reloadedStorage, directory, mask::get, mask::set);
            DomainLedger loaded = reloaded.activate().orElseThrow();
            helper.assertTrue(loaded.amount(KEY) == Long.MAX_VALUE, "Opaque long quantity did not survive reload");
            try (DomainLedger.Withdrawal consumed =
                    loaded.withdraw(KEY, Long.MAX_VALUE).orElseThrow()) {
                helper.assertTrue(loaded.hasReservations(), "In-flight withdrawal disappeared");
            }
            reloadedStorage.save();
            IOUtilities.waitUntilIOWorkerComplete();
            helper.assertTrue(
                    Files.isRegularFile(file) && mask.get() != 0, "Emptying removed historical bucket identity");
            DomainStorage empty =
                    new DomainStorage(NETWORK, storage(helper, directory), directory, mask::get, mask::set);
            helper.assertTrue(empty.activate().orElseThrow().variantCount() == 0, "Empty bucket could not reload");
            helper.succeed();
        } finally {
            remove(directory);
        }
    }

    @GameTest(template = "bootstrap")
    public static void missingCorruptAndUnmarkedBucketsFailClosed(GameTestHelper helper) throws IOException {
        Path directory = Files.createTempDirectory("omniresonance-m4-unavailable-");
        try {
            int bucket = StorageBucketHash.bucket(KEY);
            AtomicLong mask = new AtomicLong(1L << bucket);
            DimensionDataStorage nativeStorage = storage(helper, directory);
            DomainStorage missing = new DomainStorage(NETWORK, nativeStorage, directory, mask::get, mask::set);
            helper.assertTrue(missing.activate().isEmpty(), "Missing created bucket became empty storage");
            helper.assertTrue(missing.state() == DomainStorage.State.UNAVAILABLE, "Missing bucket was not isolated");
            Path file = directory.resolve(ManagedSavedDataNames.networkBucket(NETWORK, bucket) + ".dat");
            byte[] corrupt = {1, 2, 3, 4};
            Files.write(file, corrupt);
            DimensionDataStorage corruptStorage = storage(helper, directory);
            DomainStorage bad = new DomainStorage(NETWORK, corruptStorage, directory, mask::get, mask::set);
            helper.assertTrue(bad.activate().isEmpty(), "Corrupt bucket became available");
            corruptStorage.save();
            IOUtilities.waitUntilIOWorkerComplete();
            helper.assertTrue(
                    java.util.Arrays.equals(corrupt, Files.readAllBytes(file)), "Failure replaced corrupt bytes");
            mask.set(0);
            DomainStorage unmarked =
                    new DomainStorage(NETWORK, storage(helper, directory), directory, mask::get, mask::set);
            helper.assertTrue(unmarked.activate().isEmpty(), "Unmarked occupied bucket identity was overwritten");
            Files.delete(file);
            helper.assertTrue(missing.activate().isEmpty(), "Unavailable domain retried without restart");
            helper.succeed();
        } finally {
            remove(directory);
        }
    }

    @GameTest(template = "bootstrap")
    public static void badBucketPreventsHealthyPartialInventory(GameTestHelper helper) throws IOException {
        Path directory = Files.createTempDirectory("omniresonance-m4-partial-");
        try {
            int bucket = StorageBucketHash.bucket(KEY);
            int missingBucket = (bucket + 1) % 64;
            DimensionDataStorage nativeStorage = storage(helper, directory);
            StorageBucketData healthy = StorageBucketData.create(NETWORK, bucket);
            healthy.setAmount(KEY, 14);
            nativeStorage.set(ManagedSavedDataNames.networkBucket(NETWORK, bucket), healthy);
            nativeStorage.save();
            IOUtilities.waitUntilIOWorkerComplete();
            AtomicLong mask = new AtomicLong((1L << bucket) | (1L << missingBucket));
            DomainStorage domain =
                    new DomainStorage(NETWORK, storage(helper, directory), directory, mask::get, mask::set);
            helper.assertTrue(
                    domain.activate().isEmpty(), "Healthy partial inventory escaped a failed full activation");
            helper.assertTrue(domain.state() == DomainStorage.State.UNAVAILABLE, "Partial domain reported available");
            helper.succeed();
        } finally {
            remove(directory);
        }
    }

    @GameTest(template = "bootstrap")
    public static void lateOccupiedBucketInvalidatesPreviouslyReturnedLedger(GameTestHelper helper) throws IOException {
        Path directory = Files.createTempDirectory("omniresonance-m4-late-conflict-");
        try {
            AtomicLong mask = new AtomicLong();
            DomainStorage domain =
                    new DomainStorage(NETWORK, storage(helper, directory), directory, mask::get, mask::set);
            DomainLedger ledger = domain.activate().orElseThrow();
            Path file = directory.resolve(
                    ManagedSavedDataNames.networkBucket(NETWORK, StorageBucketHash.bucket(KEY)) + ".dat");
            byte[] evidence = {7, 8, 9};
            Files.write(file, evidence);
            boolean rejected = false;
            try {
                ledger.reserveDeposit(KEY, 1, -1);
            } catch (IllegalStateException expected) {
                rejected = true;
            }
            helper.assertTrue(rejected && mask.get() == 0, "Late occupied identity admitted a deposit");
            helper.assertTrue(domain.state() == DomainStorage.State.UNAVAILABLE, "Late conflict was not isolated");
            boolean staleRejected = false;
            try {
                ledger.insertCapacity(KEY, -1);
            } catch (IllegalStateException expected) {
                staleRejected = true;
            }
            helper.assertTrue(staleRejected, "Retained ledger bypassed unavailable state");
            helper.assertTrue(
                    java.util.Arrays.equals(evidence, Files.readAllBytes(file)), "Late conflict destroyed evidence");
            helper.succeed();
        } finally {
            remove(directory);
        }
    }

    private static DimensionDataStorage storage(GameTestHelper helper, Path directory) {
        return new DimensionDataStorage(
                directory.toFile(), DataFixers.getDataFixer(), helper.getLevel().registryAccess());
    }

    private static void remove(Path directory) throws IOException {
        IOUtilities.waitUntilIOWorkerComplete();
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
