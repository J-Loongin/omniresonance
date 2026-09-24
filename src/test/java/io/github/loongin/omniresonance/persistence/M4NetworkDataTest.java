// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.network.DomainNodeConfiguration;
import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.network.NetworkMetadata;
import io.github.loongin.omniresonance.network.TransferDirection;
import io.github.loongin.omniresonance.node.NodeForm;
import io.github.loongin.omniresonance.node.NodeMode;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

final class M4NetworkDataTest {
    private static final UUID NETWORK = new UUID(7, 1);
    private static final UUID NODE = new UUID(7, 2);

    @Test
    void completeSaveActivatesPendingConfigurationAndRejectsStaleOrForgedFaces() {
        NetworkSavedData data = data();
        var node = data.findNode(NODE).orElseThrow();
        var policy = data.domainConfiguration(NODE).orElseThrow().storedPolicy();
        data.setDirty(false);
        assertThrows(
                IllegalArgumentException.class,
                () -> data.saveDomainConfiguration(
                        NODE,
                        node.revision(),
                        policy,
                        io.github.loongin.omniresonance.network.WorkingFaces.explicit(1),
                        false));
        assertFalse(data.isDirty());
        assertFalse(data.domainConfiguration(NODE).orElseThrow().configured());
        var savedNode = data.saveDomainConfiguration(
                NODE,
                node.revision(),
                policy,
                io.github.loongin.omniresonance.network.WorkingFaces.attachedFace(),
                false);
        assertTrue(data.domainConfiguration(NODE).orElseThrow().configured());
        assertEquals(node.revision() + 1, savedNode.revision());
        data.setDirty(false);
        assertEquals(
                savedNode,
                data.saveDomainConfiguration(
                        NODE,
                        savedNode.revision(),
                        policy,
                        io.github.loongin.omniresonance.network.WorkingFaces.attachedFace(),
                        false));
        assertFalse(data.isDirty());
        assertThrows(
                IllegalStateException.class,
                () -> data.saveDomainConfiguration(
                        NODE,
                        node.revision(),
                        policy,
                        io.github.loongin.omniresonance.network.WorkingFaces.attachedFace(),
                        false));
    }

    @Test
    void directionOnlyRequestDoesNotActivatePendingOrDiscardCompletedCommonFields() {
        NetworkSavedData data = data();
        var node = data.findNode(NODE).orElseThrow();
        data.setDomainConfiguration(NODE, node.revision(), TransferDirection.INPUT, false);
        assertFalse(data.domainConfiguration(NODE).orElseThrow().configured());
        var input = new io.github.loongin.omniresonance.transfer.ResourceTransferPolicy.Input(
                17,
                io.github.loongin.omniresonance.transfer.ResourceScope.customSet(
                        Set.of(io.github.loongin.omniresonance.transfer.ResourceTypes.ITEM)),
                io.github.loongin.omniresonance.transfer.RedstoneCondition.SIGNAL,
                null,
                io.github.loongin.omniresonance.filter.FilterMode.BLACKLIST,
                java.util.Map.of(),
                9);
        var stored = new io.github.loongin.omniresonance.transfer.StoredResourcePolicy(input, java.util.Map.of());
        node = data.saveDomainConfiguration(
                NODE,
                node.revision(),
                stored,
                io.github.loongin.omniresonance.network.WorkingFaces.attachedFace(),
                false);
        long revision = node.revision();
        assertThrows(
                IllegalStateException.class,
                () -> data.setDomainConfiguration(NODE, revision, TransferDirection.OUTPUT, false));
        data.setDomainConfiguration(NODE, revision, TransferDirection.OUTPUT, true);
        var output = data.domainConfiguration(NODE).orElseThrow();
        assertTrue(output.configured());
        assertEquals(stored.switchDirection(TransferDirection.OUTPUT), output.storedPolicy());
        assertEquals(17, output.policy().intervalTicks());
    }

    @Test
    void versionEightDomainMigratesToPendingWithoutStartingTransfers() {
        NetworkSavedData data = data();
        CompoundTag legacy = data.save(new CompoundTag(), RegistryAccess.EMPTY);
        legacy.putInt("schema_version", 8);
        legacy.remove("audit_entries");
        legacy.remove("bucket_created_mask");
        CompoundTag domain =
                legacy.getList("domain_configurations", Tag.TAG_COMPOUND).getCompound(0);
        for (String key : Set.copyOf(domain.getAllKeys())) {
            if (!key.equals("node_id") && !key.equals("direction")) domain.remove(key);
        }
        CompoundTag before = legacy.copy();
        NetworkSavedData loaded = NetworkSavedData.load(NETWORK, legacy);
        DomainNodeConfiguration pending = loaded.domainConfiguration(NODE).orElseThrow();
        assertFalse(pending.configured());
        assertEquals(TransferDirection.INPUT, pending.direction());
        assertTrue(pending.workingFaces().attached());
        assertEquals(0, loaded.bucketCreatedMask());
        assertFalse(loaded.isDirty());
        assertEquals(before, legacy);
        CompoundTag upgraded = loaded.save(new CompoundTag(), RegistryAccess.EMPTY);
        assertEquals(10, upgraded.getInt("schema_version"));
        assertEquals(upgraded, NetworkSavedData.load(NETWORK, upgraded).save(new CompoundTag(), RegistryAccess.EMPTY));
    }

    @Test
    void bucketMaskRetainsAllSixtyFourBitsAndRejectsClearing() {
        NetworkSavedData data = data();
        data.setDirty(false);
        data.markStorageBuckets(0);
        assertFalse(data.isDirty());
        data.markStorageBuckets(Long.MIN_VALUE | 1);
        assertTrue(data.isDirty());
        assertEquals(Long.MIN_VALUE | 1, data.bucketCreatedMask());
        assertThrows(IllegalArgumentException.class, () -> data.markStorageBuckets(1));
        CompoundTag saved = data.save(new CompoundTag(), RegistryAccess.EMPTY);
        NetworkSavedData loaded = NetworkSavedData.load(NETWORK, saved);
        assertEquals(Long.MIN_VALUE | 1, loaded.bucketCreatedMask());
        assertFalse(loaded.isDirty());
        loaded.markStorageBuckets(-1L);
        assertEquals(-1L, loaded.bucketCreatedMask());
    }

    @Test
    void currentSchemaRequiresTypedMaskAndCompleteDomainFields() {
        CompoundTag saved = data().save(new CompoundTag(), RegistryAccess.EMPTY);
        saved.remove("bucket_created_mask");
        assertThrows(IllegalArgumentException.class, () -> NetworkSavedData.load(NETWORK, saved));
        saved.putInt("bucket_created_mask", 0);
        assertThrows(IllegalArgumentException.class, () -> NetworkSavedData.load(NETWORK, saved));
        saved.putLong("bucket_created_mask", 0);
        saved.getList("domain_configurations", Tag.TAG_COMPOUND).getCompound(0).remove("configured");
        assertThrows(IllegalArgumentException.class, () -> NetworkSavedData.load(NETWORK, saved));
    }

    private static NetworkSavedData data() {
        NetworkSavedData data = NetworkSavedData.create(
                new NetworkMetadata(NETWORK, new UUID(7, 0), new ManagedName("Domain"), 0, Set.of()));
        var node = data.createNode(
                NODE,
                new ManagedName("Node"),
                GlobalPos.of(Level.OVERWORLD, BlockPos.ZERO),
                NodeForm.PANEL,
                Direction.DOWN);
        node = data.setNodeMode(NODE, node.revision(), NodeMode.DOMAIN, false).orElseThrow();
        data.setDomainConfiguration(NODE, node.revision(), TransferDirection.INPUT, false);
        return data;
    }
}
