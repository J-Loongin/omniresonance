// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NetworkPermissionsTest {
    private static final UUID OWNER = UUID.fromString("3a1e0983-7865-4f0d-9d1a-f7b2b7cc0124");
    private static final UUID ADMIN = UUID.fromString("c8bdb0a1-3560-43d1-b3e6-9fad2ef09c98");
    private static final UUID STRANGER = UUID.fromString("fa145ddd-2b97-43bb-a4fb-611cb86eece8");

    @Test
    void ownerCanManageNetworkAdministratorsAndDeletion() {
        assertTrue(NetworkPermissions.canManage(OWNER, OWNER, Set.of(ADMIN)));
        assertTrue(NetworkPermissions.canManageAdministrators(OWNER, OWNER));
        assertTrue(NetworkPermissions.canDeleteNetwork(OWNER, OWNER));
    }

    @Test
    void administratorCanManageButCannotManageAdministratorsOrDelete() {
        assertTrue(NetworkPermissions.canManage(ADMIN, OWNER, Set.of(ADMIN)));
        assertFalse(NetworkPermissions.canManageAdministrators(ADMIN, OWNER));
        assertFalse(NetworkPermissions.canDeleteNetwork(ADMIN, OWNER));
    }

    @Test
    void strangerHasNoManagementRole() {
        assertFalse(NetworkPermissions.canManage(STRANGER, OWNER, Set.of(ADMIN)));
        assertFalse(NetworkPermissions.canManageAdministrators(STRANGER, OWNER));
        assertFalse(NetworkPermissions.canDeleteNetwork(STRANGER, OWNER));
    }

    @Test
    void revokingAdministratorIsImmediatelyReflectedWithoutMutatingInput() {
        Set<UUID> administrators = new HashSet<>(Set.of(ADMIN));

        assertTrue(NetworkPermissions.canManage(ADMIN, OWNER, administrators));
        assertTrue(administrators.contains(ADMIN));
        administrators.remove(ADMIN);

        assertFalse(NetworkPermissions.canManage(ADMIN, OWNER, administrators));
        assertTrue(administrators.isEmpty());
    }

    @Test
    void rejectsNullRoleInputsImmediately() {
        assertThrows(NullPointerException.class, () -> NetworkPermissions.canManage(null, OWNER, Set.of()));
        assertThrows(NullPointerException.class, () -> NetworkPermissions.canManage(OWNER, null, Set.of()));
        assertThrows(NullPointerException.class, () -> NetworkPermissions.canManage(OWNER, OWNER, null));
        assertThrows(NullPointerException.class, () -> NetworkPermissions.canManageAdministrators(null, OWNER));
        assertThrows(NullPointerException.class, () -> NetworkPermissions.canManageAdministrators(OWNER, null));
        assertThrows(NullPointerException.class, () -> NetworkPermissions.canDeleteNetwork(null, OWNER));
        assertThrows(NullPointerException.class, () -> NetworkPermissions.canDeleteNetwork(OWNER, null));
    }
}
