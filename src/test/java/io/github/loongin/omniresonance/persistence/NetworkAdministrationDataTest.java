// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.network.NetworkDirectory;
import io.github.loongin.omniresonance.network.NetworkMetadata;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class NetworkAdministrationDataTest {
    private static final UUID NETWORK = new UUID(800, 1);
    private static final UUID OWNER = new UUID(800, 2);
    private static final UUID ADMIN = new UUID(800, 3);

    @Test
    void preparedAdditionIsReadOnlyAndCommitsOnlyManagementState() {
        NetworkSavedData data = NetworkSavedData.create(metadata(Set.of()));
        data.setDirty(false);
        CompoundTag before = data.save(new CompoundTag(), RegistryAccess.EMPTY);
        NetworkDirectory directory = new NetworkDirectory(List.of(data.metadata()));
        NetworkSavedData.PreparedAdministratorChange prepared = data.prepareAdministratorChange(ADMIN, true, 0, 128);
        NetworkDirectory.PreparedMetadataReplacement index =
                directory.prepareMetadataReplacement(prepared.previous(), prepared.next());
        assertFalse(data.isDirty());
        assertEquals(before, data.save(new CompoundTag(), RegistryAccess.EMPTY));
        assertTrue(directory.accessibleTo(ADMIN).isEmpty());

        data.commitAdministratorChange(prepared);
        directory.commitMetadataReplacement(index);
        assertTrue(data.isDirty());
        assertEquals(1, data.managementRevision());
        assertEquals(0, data.topologyRevision());
        assertEquals(List.of(data.metadata()), directory.accessibleTo(ADMIN));
        assertEquals(data.metadata(), directory.ownedBy(OWNER).getFirst());
        assertEquals(List.of(ADMIN), data.pageAdministratorIds(null, false, 128));
        NetworkSavedData reloaded = NetworkSavedData.load(NETWORK, data.save(new CompoundTag(), RegistryAccess.EMPTY));
        assertEquals(Set.of(ADMIN), reloaded.metadata().administrators());
        assertEquals(1, reloaded.managementRevision());
    }

    @Test
    void removalPreservesAccessToAnotherNetworkAndWorksAboveLoweredQuota() {
        NetworkSavedData data = NetworkSavedData.create(metadata(Set.of(ADMIN)));
        NetworkMetadata other =
                new NetworkMetadata(new UUID(800, 9), OWNER, new ManagedName("Other"), 1, Set.of(ADMIN));
        NetworkDirectory directory = new NetworkDirectory(List.of(data.metadata(), other));
        var prepared = data.prepareAdministratorChange(ADMIN, false, 0, 0);
        var index = directory.prepareMetadataReplacement(prepared.previous(), prepared.next());
        data.commitAdministratorChange(prepared);
        directory.commitMetadataReplacement(index);
        assertEquals(List.of(other), directory.accessibleTo(ADMIN));
        assertEquals(0, data.administratorCount());
        assertEquals(2, directory.ownedCount(OWNER));
        assertTrue(directory.containsName(OWNER, new ManagedName("Network")));
    }

    @Test
    void invalidDuplicateAndStaleChangesNeverDirtyOrModifyData() {
        NetworkSavedData data = NetworkSavedData.create(metadata(Set.of(ADMIN)));
        data.setDirty(false);
        CompoundTag before = data.save(new CompoundTag(), RegistryAccess.EMPTY);
        assertThrows(IllegalArgumentException.class, () -> data.prepareAdministratorChange(OWNER, true, 0, 128));
        assertThrows(IllegalArgumentException.class, () -> data.prepareAdministratorChange(OWNER, false, 0, 128));
        assertThrows(IllegalArgumentException.class, () -> data.prepareAdministratorChange(ADMIN, true, 0, 128));
        assertThrows(
                IllegalArgumentException.class, () -> data.prepareAdministratorChange(new UUID(800, 4), false, 0, 128));
        assertThrows(
                IllegalArgumentException.class, () -> data.prepareAdministratorChange(new UUID(800, 4), true, 0, 0));
        assertThrows(
                IllegalArgumentException.class, () -> data.prepareAdministratorChange(new UUID(800, 4), true, 9, 128));
        assertFalse(data.isDirty());
        assertEquals(before, data.save(new CompoundTag(), RegistryAccess.EMPTY));
    }

    @Test
    void overflowAndPreparedValueReuseAreRejectedBeforeModification() {
        NetworkSavedData data = NetworkSavedData.create(metadata(Set.of()));
        var first = data.prepareAdministratorChange(ADMIN, true, 0, -1);
        NetworkSavedData foreign = NetworkSavedData.create(metadata(Set.of()));
        foreign.setDirty(false);
        assertThrows(IllegalArgumentException.class, () -> foreign.commitAdministratorChange(first));
        assertFalse(foreign.isDirty());
        data.commitAdministratorChange(first);
        data.setDirty(false);
        assertThrows(IllegalArgumentException.class, () -> data.commitAdministratorChange(first));
        assertFalse(data.isDirty());
        CompoundTag exhausted = data.save(new CompoundTag(), RegistryAccess.EMPTY);
        exhausted.putLong("management_revision", Long.MAX_VALUE);
        NetworkSavedData loaded = NetworkSavedData.load(NETWORK, exhausted);
        assertThrows(
                ArithmeticException.class, () -> loaded.prepareAdministratorChange(ADMIN, false, Long.MAX_VALUE, 0));
        assertEquals(Set.of(ADMIN), loaded.metadata().administrators());
        assertFalse(loaded.isDirty());
    }

    @Test
    void memberPagingUsesStableUuidOrderAndRejectsUnknownAnchors() {
        UUID second = new UUID(800, 4);
        UUID third = new UUID(800, 5);
        NetworkSavedData data = NetworkSavedData.create(metadata(Set.of(third, ADMIN, second)));
        assertEquals(List.of(ADMIN, second), data.pageAdministratorIds(null, false, 2));
        assertEquals(List.of(third), data.pageAdministratorIds(second, false, 2));
        assertEquals(List.of(ADMIN, second), data.pageAdministratorIds(third, true, 2));
        assertThrows(IllegalArgumentException.class, () -> data.pageAdministratorIds(OWNER, false, 2));
        assertThrows(IllegalArgumentException.class, () -> data.pageAdministratorIds(null, true, 2));
        assertThrows(IllegalArgumentException.class, () -> data.pageAdministratorIds(null, false, 0));
    }

    @Test
    void directoryRejectsStaleOrForeignPreparedReplacementWithoutChangingAccess() {
        NetworkMetadata original = metadata(Set.of());
        NetworkDirectory directory = new NetworkDirectory(List.of(original));
        var add = directory.prepareMetadataReplacement(original, metadata(Set.of(ADMIN)));
        var foreign = new NetworkDirectory(List.of(original));
        assertThrows(IllegalArgumentException.class, () -> foreign.commitMetadataReplacement(add));
        assertTrue(foreign.accessibleTo(ADMIN).isEmpty());
        directory.commitMetadataReplacement(add);
        assertThrows(IllegalArgumentException.class, () -> directory.commitMetadataReplacement(add));
        assertEquals(1, directory.accessibleTo(ADMIN).size());
        assertThrows(
                IllegalArgumentException.class,
                () -> directory.prepareMetadataReplacement(original, metadata(Set.of())));
        NetworkMetadata renamed = new NetworkMetadata(NETWORK, OWNER, new ManagedName("Changed"), 0, Set.of(ADMIN));
        assertThrows(
                IllegalArgumentException.class,
                () -> directory.prepareMetadataReplacement(metadata(Set.of(ADMIN)), renamed));
        assertEquals("Network", directory.find(NETWORK).orElseThrow().name().value());
    }

    @Test
    void legacyV4GainsOnlyManagementRevisionAndKeepsItsInputUnmodified() {
        NetworkSavedData original = NetworkSavedData.create(metadata(Set.of(ADMIN)));
        original.createTunnel(
                new UUID(801, 1), new ManagedName("Tunnel"), new UUID(801, 2), new ManagedName("Channel"), 128);
        CompoundTag legacy = original.save(new CompoundTag(), RegistryAccess.EMPTY);
        legacy.putInt("schema_version", 4);
        legacy.remove("bucket_created_mask");
        legacy.remove("management_revision");
        legacy.remove("recovery");
        CompoundTag before = legacy.copy();

        NetworkSavedData loaded = NetworkSavedData.load(NETWORK, legacy);
        CompoundTag saved = loaded.save(new CompoundTag(), RegistryAccess.EMPTY);
        assertEquals(before, legacy);
        assertFalse(loaded.isDirty());
        assertEquals(9, saved.getInt("schema_version"));
        assertEquals(0L, saved.getLong("management_revision"));
        for (String key : before.getAllKeys()) {
            if (!key.equals("schema_version")) {
                assertEquals(before.get(key), saved.get(key), key);
            }
        }
    }

    @Test
    void currentSchemaRejectsMissingWrongTypeAndNegativeManagementRevision() {
        CompoundTag current = NetworkSavedData.create(metadata(Set.of())).save(new CompoundTag(), RegistryAccess.EMPTY);
        current.putInt("schema_version", 5);
        current.remove("bucket_created_mask");
        current.remove("recovery");
        for (String value : List.of("missing", "wrong_type", "negative")) {
            CompoundTag malformed = current.copy();
            malformed.remove("management_revision");
            if (value.equals("wrong_type")) {
                malformed.putInt("management_revision", 0);
            } else if (value.equals("negative")) {
                malformed.putLong("management_revision", -1);
            }
            CompoundTag before = malformed.copy();
            assertThrows(IllegalArgumentException.class, () -> NetworkSavedData.load(NETWORK, malformed));
            assertEquals(before, malformed);
        }
    }

    private static NetworkMetadata metadata(Set<UUID> administrators) {
        return new NetworkMetadata(NETWORK, OWNER, new ManagedName("Network"), 0, administrators);
    }
}
