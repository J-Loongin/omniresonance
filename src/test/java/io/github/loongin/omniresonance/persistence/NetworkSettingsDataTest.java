// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.network.NetworkMetadata;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class NetworkSettingsDataTest {
    private static final UUID NETWORK = new UUID(820, 1);
    private static final UUID OWNER = new UUID(820, 2);
    private static final UUID ADMINISTRATOR = new UUID(820, 3);

    @Test
    void renameChangesOnlyNameAndManagementRevision() {
        NetworkSavedData data = NetworkSavedData.create(metadata("Alpha"));
        data.setDirty(false);
        long topologyRevision = data.topologyRevision();

        NetworkSavedData.PreparedRename prepared = data.prepareRename(new ManagedName("Beta"), 0);
        NetworkMetadata renamed = data.commitRename(prepared);

        assertEquals("Beta", renamed.name().value());
        assertEquals(NETWORK, renamed.id());
        assertEquals(OWNER, renamed.ownerId());
        assertEquals(Set.of(ADMINISTRATOR), renamed.administrators());
        assertEquals(9, renamed.creationOrder());
        assertEquals(1, data.managementRevision());
        assertEquals(topologyRevision, data.topologyRevision());
        assertTrue(data.isDirty());
        NetworkSavedData reloaded = NetworkSavedData.load(NETWORK, data.save(new CompoundTag(), RegistryAccess.EMPTY));
        assertEquals("Beta", reloaded.metadata().name().value());
        assertEquals(1, reloaded.managementRevision());
    }

    @Test
    void staleForeignAndReusedRenameNeverMutateTheShard() {
        NetworkSavedData data = NetworkSavedData.create(metadata("Alpha"));
        NetworkSavedData foreign = NetworkSavedData.create(metadata("Alpha"));
        NetworkSavedData.PreparedRename prepared = data.prepareRename(new ManagedName("Beta"), 0);

        foreign.setDirty(false);
        assertThrows(IllegalArgumentException.class, () -> foreign.commitRename(prepared));
        assertFalse(foreign.isDirty());
        assertEquals("Alpha", foreign.metadata().name().value());

        data.commitRename(prepared);
        data.setDirty(false);
        assertThrows(IllegalArgumentException.class, () -> data.commitRename(prepared));
        assertThrows(IllegalArgumentException.class, () -> data.prepareRename(new ManagedName("Gamma"), 0));
        assertFalse(data.isDirty());
        assertEquals("Beta", data.metadata().name().value());
    }

    private static NetworkMetadata metadata(String name) {
        return new NetworkMetadata(NETWORK, OWNER, new ManagedName(name), 9, Set.of(ADMINISTRATOR));
    }
}
