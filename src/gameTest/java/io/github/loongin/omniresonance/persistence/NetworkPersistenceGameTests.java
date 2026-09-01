// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.persistence;

import io.github.loongin.omniresonance.bootstrap.OmniResonanceMod;
import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.network.NetworkCreationService;
import io.github.loongin.omniresonance.network.NetworkDirectory;
import io.github.loongin.omniresonance.network.NetworkMetadata;
import io.github.loongin.omniresonance.node.NodeForm;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.common.IOUtilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real SavedData tests isolated from all player and GameTest world saves. */
@GameTestHolder(OmniResonanceMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NetworkPersistenceGameTests {
    private static final UUID OWNER = new UUID(0, 100);

    private NetworkPersistenceGameTests() {}

    /** Verifies normal saves reload identity, ordering, default ownership, and only standard shard files. */
    @GameTest(template = "bootstrap")
    public static void normalSaveReloadsNetworksAndDefault(GameTestHelper helper) throws IOException {
        Path directory = Files.createTempDirectory("omniresonance-network-save-test-");
        try {
            DimensionDataStorage storage = storage(helper, directory);
            SavedNetworkRepository repository = new SavedNetworkRepository(storage, directory);
            NetworkDirectory index = new NetworkDirectory(repository.loadNetworks());
            AtomicLong sequence = new AtomicLong();
            NetworkCreationService service =
                    new NetworkCreationService(repository, index, () -> new UUID(0, sequence.incrementAndGet()));
            helper.assertTrue(service.preferredNetwork(OWNER).isEmpty(), "An empty owner must not have a default");
            storage.save();
            IOUtilities.waitUntilIOWorkerComplete();
            helper.assertTrue(fileNames(directory).isEmpty(), "Reading an empty owner created a shard");
            NetworkMetadata first = service.create(OWNER, "First", -1);
            NetworkMetadata second = service.create(OWNER, "Second", -1);
            repository
                    .findLoadedNetwork(first.id())
                    .orElseThrow()
                    .createNode(
                            new UUID(8, 1),
                            new ManagedName("First node"),
                            GlobalPos.of(Level.OVERWORLD, new BlockPos(3, 64, 4)),
                            NodeForm.PANEL,
                            Direction.NORTH);
            storage.save();
            IOUtilities.waitUntilIOWorkerComplete();
            List<String> expectedFiles = List.of(
                    ManagedSavedDataNames.network(first.id()) + ".dat",
                    ManagedSavedDataNames.network(second.id()) + ".dat",
                    ManagedSavedDataNames.owner(OWNER) + ".dat");
            helper.assertTrue(
                    fileNames(directory).equals(expectedFiles), "Unexpected index, bucket, or missing shard file");
            CompoundTag wrapper =
                    NbtIo.readCompressed(directory.resolve(expectedFiles.getFirst()), NbtAccounter.unlimitedHeap());
            helper.assertTrue(
                    wrapper.getCompound("data").getInt("schema_version") == 2, "Standard SavedData wrapper missing");
            helper.assertTrue(wrapper.contains("DataVersion"), "Standard data version missing");
            SavedNetworkRepository reloaded = new SavedNetworkRepository(storage(helper, directory), directory);
            NetworkDirectory reloadedIndex = new NetworkDirectory(reloaded.loadNetworks());
            NetworkCreationService reloadedService =
                    new NetworkCreationService(reloaded, reloadedIndex, () -> new UUID(0, 3));
            helper.assertTrue(
                    reloadedIndex.ownedBy(OWNER).equals(List.of(first, second)),
                    "Metadata ordering did not survive reload");
            helper.assertTrue(
                    reloaded.findLoadedNetwork(first.id()).orElseThrow().nodes().size() == 1,
                    "Node records did not survive reload");
            helper.assertTrue(
                    reloadedService.preferredNetwork(OWNER).orElseThrow().equals(first.id()),
                    "Default did not survive reload");
            helper.assertTrue(!reloaded.findOwner(OWNER).orElseThrow().isDirty(), "Reading owner dirtied it");
            NetworkMetadata third = reloadedService.create(OWNER, "Third", -1);
            helper.assertTrue(third.creationOrder() == 2, "Reloaded creation order was reset");
            helper.succeed();
        } finally {
            removeTestDirectory(directory);
        }
    }

    /** Verifies one unreadable shard stays untouched and cannot hide healthy networks or impersonate buckets. */
    @GameTest(template = "bootstrap")
    public static void unreadableNetworkIsExcludedAndNeverOverwritten(GameTestHelper helper) throws IOException {
        Path directory = Files.createTempDirectory("omniresonance-network-corruption-test-");
        try {
            DimensionDataStorage storage = storage(helper, directory);
            SavedNetworkRepository repository = new SavedNetworkRepository(storage, directory);
            NetworkDirectory index = new NetworkDirectory(List.of());
            AtomicLong sequence = new AtomicLong();
            NetworkCreationService service =
                    new NetworkCreationService(repository, index, () -> new UUID(0, sequence.incrementAndGet()));
            NetworkMetadata damaged = service.create(OWNER, "Damaged", -1);
            NetworkMetadata healthy = service.create(OWNER, "Healthy", -1);
            storage.save();
            IOUtilities.waitUntilIOWorkerComplete();
            byte[] invalid = {1, 2, 3, 4};
            Path brokenFile = directory.resolve(ManagedSavedDataNames.network(damaged.id()) + ".dat");
            Files.write(brokenFile, invalid);
            Path bucket = directory.resolve(ManagedSavedDataNames.network(damaged.id()) + "_bucket_00.dat");
            Files.write(bucket, invalid);
            Path unrelated = directory.resolve("another_mod.dat");
            Files.write(unrelated, invalid);
            Path temporary = directory.resolve(ManagedSavedDataNames.network(damaged.id()) + ".dat.tmp");
            Files.write(temporary, invalid);
            DimensionDataStorage reloadedStorage = storage(helper, directory);
            SavedNetworkRepository reloaded = new SavedNetworkRepository(reloadedStorage, directory);
            helper.assertTrue(
                    reloaded.loadNetworks().equals(List.of(healthy)), "Damaged or unrelated data became a network");
            NetworkDirectory reloadedIndex = new NetworkDirectory(reloaded.loadNetworks());
            NetworkCreationService reloadedService =
                    new NetworkCreationService(reloaded, reloadedIndex, () -> new UUID(0, 3));
            helper.assertTrue(
                    reloadedService.preferredNetwork(OWNER).isEmpty(),
                    "An unreadable default was presented as accessible");
            reloadedStorage.save();
            IOUtilities.waitUntilIOWorkerComplete();
            for (Path preserved : List.of(brokenFile, bucket, unrelated, temporary)) {
                helper.assertTrue(
                        Arrays.equals(Files.readAllBytes(preserved), invalid),
                        "Unreadable or unrelated bytes were modified");
            }
            helper.succeed();
        } finally {
            removeTestDirectory(directory);
        }
    }

    private static DimensionDataStorage storage(GameTestHelper helper, Path directory) {
        return new DimensionDataStorage(
                directory.toFile(), DataFixers.getDataFixer(), helper.getLevel().registryAccess());
    }

    private static List<String> fileNames(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private static void removeTestDirectory(Path directory) throws IOException {
        IOUtilities.waitUntilIOWorkerComplete();
        try (var files = Files.walk(directory)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
