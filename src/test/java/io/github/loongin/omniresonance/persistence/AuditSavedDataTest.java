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
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;

class AuditSavedDataTest {
    private static final UUID OWNER = new UUID(101, 1), NETWORK = new UUID(102, 1);

    @Test
    void networkAndOwnerAuditRoundTripAndMigrateWithoutDirtyingInputs() {
        var network =
                NetworkSavedData.create(new NetworkMetadata(NETWORK, OWNER, new ManagedName("Network"), 0, Set.of()));
        var owner = OwnerSavedData.create(OWNER, NETWORK);
        network.setDirty(false);
        owner.setDirty(false);
        network.appendAudit(AuditRingTest.entry(1), 0);
        owner.appendAudit(AuditRingTest.entry(1), 0);
        assertFalse(network.isDirty());
        assertFalse(owner.isDirty());
        network.appendAudit(AuditRingTest.entry(2), 10);
        owner.appendAudit(AuditRingTest.entry(3), 10);
        assertTrue(network.isDirty());
        assertTrue(owner.isDirty());
        var n = network.save(new CompoundTag(), RegistryAccess.EMPTY);
        var o = owner.save(new CompoundTag(), RegistryAccess.EMPTY);
        assertEquals(network.auditEntries(), NetworkSavedData.load(NETWORK, n).auditEntries());
        assertEquals(owner.auditEntries(), OwnerSavedData.load(OWNER, o).auditEntries());
        n.remove("audit_entries");
        n.putInt("schema_version", 9);
        o.remove("audit_entries");
        o.putInt("schema_version", 3);
        var originalN = n.copy();
        var originalO = o.copy();
        var loadedN = NetworkSavedData.load(NETWORK, n);
        var loadedO = OwnerSavedData.load(OWNER, o);
        assertTrue(loadedN.auditEntries().isEmpty());
        assertTrue(loadedO.auditEntries().isEmpty());
        assertFalse(loadedN.isDirty());
        assertFalse(loadedO.isDirty());
        assertEquals(originalN, n);
        assertEquals(originalO, o);
        assertEquals(10, loadedN.save(new CompoundTag(), RegistryAccess.EMPTY).getInt("schema_version"));
        assertEquals(4, loadedO.save(new CompoundTag(), RegistryAccess.EMPTY).getInt("schema_version"));
        n.putInt("schema_version", 10);
        n.put("audit_entries", StringTag.valueOf("invalid"));
        assertThrows(IllegalArgumentException.class, () -> NetworkSavedData.load(NETWORK, n));
    }
}
