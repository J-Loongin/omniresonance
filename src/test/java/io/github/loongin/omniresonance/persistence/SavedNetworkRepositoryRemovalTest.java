// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.persistence;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.network.NetworkMetadata;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.common.IOUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SavedNetworkRepositoryRemovalTest {
    private static final UUID NETWORK = new UUID(822, 1);
    private static final UUID OWNER = new UUID(822, 2);

    @TempDir
    Path directory;

    @AfterEach
    void finishQueuedIo() {
        IOUtilities.waitUntilIOWorkerComplete();
    }

    @Test
    void queuedSaveThenRemovalCannotBeRecreatedByALaterSave() {
        DimensionDataStorage storage = storage();
        SavedNetworkRepository repository = new SavedNetworkRepository(storage, directory);
        repository.createNetwork(metadata(NETWORK));
        Path file = directory.resolve(ManagedSavedDataNames.network(NETWORK) + ".dat");

        storage.save();
        repository.removeNetwork(NETWORK);
        IOUtilities.waitUntilIOWorkerComplete();
        storage.save();
        IOUtilities.waitUntilIOWorkerComplete();

        assertFalse(Files.exists(file));
        assertTrue(repository.findLoadedNetwork(NETWORK).isEmpty());
        assertTrue(
                new SavedNetworkRepository(storage(), directory).loadNetworks().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> repository.createNetwork(metadata(NETWORK)));
    }

    @Test
    void removalOfNeverPersistedNetworkKeepsOtherIdentityAvailable() {
        SavedNetworkRepository repository = new SavedNetworkRepository(storage(), directory);
        repository.createNetwork(metadata(NETWORK));

        repository.removeNetwork(NETWORK);
        UUID replacement = new UUID(822, 3);
        repository.createNetwork(metadata(replacement));

        assertTrue(repository.findLoadedNetwork(NETWORK).isEmpty());
        assertTrue(repository.findLoadedNetwork(replacement).isPresent());
        assertThrows(IllegalArgumentException.class, () -> repository.removeNetwork(NETWORK));
    }

    private DimensionDataStorage storage() {
        return new DimensionDataStorage(directory.toFile(), DataFixers.getDataFixer(), RegistryAccess.EMPTY);
    }

    private static NetworkMetadata metadata(UUID id) {
        return new NetworkMetadata(id, OWNER, new ManagedName("Network"), 0, Set.of());
    }
}
