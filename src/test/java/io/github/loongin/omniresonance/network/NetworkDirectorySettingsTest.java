// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NetworkDirectorySettingsTest {
    private static final UUID OWNER = new UUID(821, 1);
    private static final UUID ADMINISTRATOR = new UUID(821, 2);
    private static final NetworkMetadata ALPHA = metadata(new UUID(821, 3), "Alpha", 0);
    private static final NetworkMetadata BETA = metadata(new UUID(821, 4), "Beta", 1);

    @Test
    void renameReplacesOnlyTheOwnerScopedNameIndex() {
        NetworkDirectory directory = new NetworkDirectory(List.of(ALPHA, BETA));
        NetworkMetadata renamed = metadata(ALPHA.id(), "Gamma", 0);

        NetworkDirectory.PreparedRename prepared = directory.prepareRename(ALPHA, renamed);
        directory.commitRename(prepared);

        assertFalse(directory.containsName(OWNER, new ManagedName("Alpha")));
        assertTrue(directory.containsName(OWNER, new ManagedName("gamma")));
        assertEquals(List.of(renamed, BETA), directory.ownedBy(OWNER));
        assertEquals(List.of(renamed, BETA), directory.accessibleTo(ADMINISTRATOR));
        assertEquals(renamed, directory.find(ALPHA.id()).orElseThrow());
    }

    @Test
    void renameCollisionAndStalePreparedValueLeaveEveryIndexUnchanged() {
        NetworkDirectory directory = new NetworkDirectory(List.of(ALPHA, BETA));
        assertThrows(
                IllegalArgumentException.class, () -> directory.prepareRename(ALPHA, metadata(ALPHA.id(), "BETA", 0)));
        assertEquals(List.of(ALPHA, BETA), directory.ownedBy(OWNER));

        NetworkMetadata renamed = metadata(ALPHA.id(), "Gamma", 0);
        NetworkDirectory.PreparedRename prepared = directory.prepareRename(ALPHA, renamed);
        directory.commitRename(prepared);
        assertThrows(IllegalArgumentException.class, () -> directory.commitRename(prepared));
        assertEquals(List.of(renamed, BETA), directory.ownedBy(OWNER));
    }

    @Test
    void removalReleasesOnlyTheTargetIndexesAndAllowsItsDisplayNameAgain() {
        NetworkDirectory directory = new NetworkDirectory(List.of(ALPHA, BETA));

        NetworkDirectory.PreparedRemoval prepared = directory.prepareRemoval(ALPHA);
        directory.commitRemoval(prepared);

        assertTrue(directory.find(ALPHA.id()).isEmpty());
        assertEquals(List.of(BETA), directory.ownedBy(OWNER));
        assertEquals(List.of(BETA), directory.accessibleTo(ADMINISTRATOR));
        assertFalse(directory.containsName(OWNER, new ManagedName("Alpha")));
        NetworkMetadata replacement = metadata(new UUID(821, 5), "ALPHA", 2);
        directory.add(replacement);
        assertEquals(List.of(BETA, replacement), directory.ownedBy(OWNER));
    }

    @Test
    void foreignAndStaleRemovalCannotRemoveAReplacement() {
        NetworkDirectory directory = new NetworkDirectory(List.of(ALPHA));
        NetworkDirectory foreign = new NetworkDirectory(List.of(ALPHA));
        NetworkDirectory.PreparedRemoval prepared = directory.prepareRemoval(ALPHA);

        assertThrows(IllegalArgumentException.class, () -> foreign.commitRemoval(prepared));
        assertEquals(ALPHA, foreign.find(ALPHA.id()).orElseThrow());
        directory.commitRemoval(prepared);
        assertThrows(IllegalArgumentException.class, () -> directory.commitRemoval(prepared));
    }

    private static NetworkMetadata metadata(UUID id, String name, long order) {
        return new NetworkMetadata(id, OWNER, new ManagedName(name), order, Set.of(ADMINISTRATOR));
    }
}
