// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.persistence.SavedNetworkRepository;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.RegistryAccess;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.common.IOUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NetworkCreationRejectionTest {
    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void finishIo() {
        IOUtilities.waitUntilIOWorkerComplete();
    }

    @Test
    void typedRejectionsPreserveIdentityDefaultAndDirectory() {
        UUID owner = new UUID(1, 1);
        DimensionDataStorage storage =
                new DimensionDataStorage(temporaryDirectory.toFile(), DataFixers.getDataFixer(), RegistryAccess.EMPTY);
        SavedNetworkRepository repository = new SavedNetworkRepository(storage, temporaryDirectory);
        NetworkDirectory directory = new NetworkDirectory(List.of());
        AtomicLong ids = new AtomicLong();
        NetworkCreationService service =
                new NetworkCreationService(repository, directory, () -> new UUID(0, ids.incrementAndGet()));
        assertEquals(
                NetworkCreationService.Reason.INVALID_NAME,
                assertThrows(NetworkCreationService.Rejected.class, () -> service.create(owner, "  ", 2))
                        .reason());
        assertEquals(
                NetworkCreationService.Reason.QUOTA_REACHED,
                assertThrows(NetworkCreationService.Rejected.class, () -> service.create(owner, "First", 0))
                        .reason());
        assertTrue(repository.findOwner(owner).isEmpty());
        assertTrue(directory.ownedBy(owner).isEmpty());
        assertEquals(0, ids.get());
        NetworkMetadata first = service.create(owner, "First", 2);
        assertEquals(
                NetworkCreationService.Reason.NAME_CONFLICT,
                assertThrows(NetworkCreationService.Rejected.class, () -> service.create(owner, "FIRST", 2))
                        .reason());
        assertEquals(
                NetworkCreationService.Reason.QUOTA_REACHED,
                assertThrows(NetworkCreationService.Rejected.class, () -> service.create(owner, "Second", 0))
                        .reason());
        assertEquals(1, ids.get());
        assertEquals(List.of(first), directory.ownedBy(owner));
        assertEquals(first.id(), service.preferredNetwork(owner).orElseThrow());
    }
}
