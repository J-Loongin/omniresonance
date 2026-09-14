// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.filter.FilterMode;
import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.network.NetworkMetadata;
import io.github.loongin.omniresonance.network.TransferDirection;
import io.github.loongin.omniresonance.network.WorkingFaces;
import io.github.loongin.omniresonance.node.NodeForm;
import io.github.loongin.omniresonance.node.NodeMode;
import io.github.loongin.omniresonance.transfer.ItemTransferPolicy;
import io.github.loongin.omniresonance.transfer.RedstoneCondition;
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

class ItemPolicyDataTest {
    static final UUID NETWORK = new UUID(1, 1), OWNER = new UUID(2, 2), NODE = new UUID(3, 3), CHANNEL = new UUID(4, 4);

    static NetworkSavedData data() {
        NetworkSavedData data =
                NetworkSavedData.create(new NetworkMetadata(NETWORK, OWNER, new ManagedName("Network"), 0, Set.of()));
        data.createNode(
                NODE,
                new ManagedName("Node"),
                GlobalPos.of(Level.OVERWORLD, BlockPos.ZERO),
                NodeForm.BLOCK,
                Direction.DOWN);
        data.setNodeMode(NODE, 0, NodeMode.DIRECT, false);
        data.createTunnel(new UUID(5, 5), new ManagedName("Tunnel"), CHANNEL, new ManagedName("Channel"), 1024);
        data.setDirectBinding(NODE, 1, CHANNEL, TransferDirection.INPUT, false, 16);
        return data;
    }

    static ItemTransferPolicy.Input policy() {
        return new ItemTransferPolicy.Input(4, 32, RedstoneCondition.SIGNAL, new UUID(6, 6), FilterMode.BLACKLIST, 19);
    }

    @Test
    void writesResourceWorkingFaceSchemaEight() {
        assertEquals(8, data().save(new CompoundTag(), RegistryAccess.EMPTY).getInt("schema_version"));
    }

    @Test
    void explicitFacesRoundTripAndDirectionSavePreservesSelection() {
        NetworkSavedData data = data();
        WorkingFaces faces = WorkingFaces.explicit(48);
        var node = data.setDirectBinding(NODE, 2, CHANNEL, policy(), faces, false, 16);
        data.setDirectBinding(NODE, node.revision(), CHANNEL, TransferDirection.OUTPUT, true, 16);
        CompoundTag tag = data.save(new CompoundTag(), RegistryAccess.EMPTY);
        assertEquals(
                faces,
                NetworkSavedData.load(NETWORK, tag)
                        .findDirectBinding(NODE, CHANNEL)
                        .orElseThrow()
                        .workingFaces());
        node = data.findNode(NODE).orElseThrow();
        data.setDirectBinding(NODE, node.revision(), CHANNEL, policy(), WorkingFaces.explicit(0), true, 16);
        assertEquals(
                WorkingFaces.explicit(0),
                NetworkSavedData.load(NETWORK, data.save(new CompoundTag(), RegistryAccess.EMPTY))
                        .findDirectBinding(NODE, CHANNEL)
                        .orElseThrow()
                        .workingFaces());
    }

    @Test
    void v6MigrationKeepsAttachedFaceAndSourceTagUntouched() {
        CompoundTag tag = data().save(new CompoundTag(), RegistryAccess.EMPTY);
        tag.putInt("schema_version", 6);
        CompoundTag binding = tag.getList("direct_bindings", Tag.TAG_COMPOUND).getCompound(0);
        binding.remove("resource_policy");
        binding.put("item_policy", ItemPolicyNbt.encode(ItemTransferPolicy.defaults(TransferDirection.INPUT)));
        binding.remove("working_face_mask");
        binding.remove("working_face_attached");
        CompoundTag before = tag.copy();
        assertEquals(
                WorkingFaces.attachedFace(),
                NetworkSavedData.load(NETWORK, tag)
                        .findDirectBinding(NODE, CHANNEL)
                        .orElseThrow()
                        .workingFaces());
        assertEquals(before, tag);
    }

    @Test
    void malformedFacesAndPanelForgeryFailWithoutMutation() {
        CompoundTag tag = data().save(new CompoundTag(), RegistryAccess.EMPTY);
        CompoundTag binding = tag.getList("direct_bindings", Tag.TAG_COMPOUND).getCompound(0);
        binding.putInt("working_face_mask", 64);
        binding.putBoolean("working_face_attached", false);
        assertThrows(IllegalArgumentException.class, () -> NetworkSavedData.load(NETWORK, tag));
        assertThrows(
                IllegalArgumentException.class, () -> WorkingFaces.explicit(63).validate(NodeForm.PANEL));
        NetworkSavedData data = data();
        UUID panel = new UUID(20, 20);
        data.createNode(
                panel,
                new ManagedName("Panel"),
                GlobalPos.of(Level.OVERWORLD, new BlockPos(1, 0, 0)),
                NodeForm.PANEL,
                Direction.WEST);
        data.setNodeMode(panel, 0, NodeMode.DIRECT, false);
        data.setDirty(false);
        long revision = data.topologyRevision();
        assertThrows(
                IllegalArgumentException.class,
                () -> data.setDirectBinding(panel, 1, CHANNEL, policy(), WorkingFaces.explicit(48), false, 16));
        assertFalse(data.isDirty());
        assertEquals(revision, data.topologyRevision());
        assertTrue(data.findDirectBinding(panel, CHANNEL).isEmpty());
    }

    @Test
    void faceOnlyEditsReviseOnceAndPhysicalPanelConversionRejectsExistingExplicitFaces() {
        NetworkSavedData data = data();
        var policy = data.findDirectBinding(NODE, CHANNEL).orElseThrow().storedPolicy();
        long topology = data.topologyRevision();
        var updated = data.setDirectBinding(NODE, 2, CHANNEL, policy, WorkingFaces.explicit(48), false, 16);
        assertEquals(3, updated.revision());
        assertEquals(topology + 1, data.topologyRevision());
        data.setDirty(false);
        data.setDirectBinding(NODE, 3, CHANNEL, policy, WorkingFaces.explicit(48), false, 16);
        assertFalse(data.isDirty());
        assertThrows(
                IllegalArgumentException.class,
                () -> data.updateNodePhysicalSnapshot(NODE, updated.position(), NodeForm.PANEL, Direction.DOWN));
        assertEquals(NodeForm.BLOCK, data.findNode(NODE).orElseThrow().form());
        assertFalse(data.isDirty());
    }

    @Test
    void fullPolicyEqualityControlsNoOpAndLegacySavePreservesCommonFields() {
        NetworkSavedData data = data();
        data.setDirty(false);
        long before = data.topologyRevision();
        var node = data.setDirectBinding(NODE, 2, CHANNEL, policy(), false, 16);
        assertEquals(3, node.revision());
        assertEquals(before + 1, data.topologyRevision());
        assertTrue(data.isDirty());
        data.setDirty(false);
        data.setDirectBinding(NODE, 3, CHANNEL, TransferDirection.INPUT, false, 16);
        assertFalse(data.isDirty());
        assertEquals(
                io.github.loongin.omniresonance.transfer.ResourceTransferPolicy.legacy(policy()),
                data.directBindings(NODE).getFirst().policy());
        data.setDirectBinding(NODE, 3, CHANNEL, TransferDirection.OUTPUT, true, 16);
        assertEquals(
                io.github.loongin.omniresonance.transfer.ResourceTransferPolicy.legacy(policy())
                        .switchDirection(TransferDirection.OUTPUT),
                data.directBindings(NODE).getFirst().policy());
    }

    @Test
    void v6RoundTripRetainsPolicyAndRejectsMixedDirectionFields() {
        NetworkSavedData data = data();
        data.setDirectBinding(NODE, 2, CHANNEL, policy(), false, 16);
        CompoundTag saved = data.save(new CompoundTag(), RegistryAccess.EMPTY);
        saved.putInt("schema_version", 6);
        CompoundTag legacyBinding =
                saved.getList("direct_bindings", Tag.TAG_COMPOUND).getCompound(0);
        legacyBinding.remove("resource_policy");
        legacyBinding.remove("working_face_mask");
        legacyBinding.remove("working_face_attached");
        legacyBinding.put("item_policy", ItemPolicyNbt.encode(policy()));
        assertEquals(
                io.github.loongin.omniresonance.transfer.ResourceTransferPolicy.legacy(policy()),
                NetworkSavedData.load(NETWORK, saved)
                        .directBindings(NODE)
                        .getFirst()
                        .policy());
        CompoundTag binding = saved.getList("direct_bindings", Tag.TAG_COMPOUND).getCompound(0);
        binding.getCompound("item_policy").putInt("priority", 4);
        CompoundTag before = saved.copy();
        assertThrows(IllegalArgumentException.class, () -> NetworkSavedData.load(NETWORK, saved));
        assertEquals(before, saved);
    }

    @Test
    void v5BindingDefaultsMigrateWithoutAddingExplicitDefaultRateOverride() {
        NetworkSavedData data = data();
        CompoundTag tag = data.save(new CompoundTag(), RegistryAccess.EMPTY);
        tag.putInt("schema_version", 5);
        tag.remove("recovery");
        tag.getList("direct_bindings", Tag.TAG_COMPOUND).getCompound(0).remove("resource_policy");
        tag.getList("direct_bindings", Tag.TAG_COMPOUND).getCompound(0).remove("working_face_mask");
        tag.getList("direct_bindings", Tag.TAG_COMPOUND).getCompound(0).remove("working_face_attached");
        CompoundTag before = tag.copy();
        NetworkSavedData migrated = NetworkSavedData.load(NETWORK, tag);
        assertEquals(
                io.github.loongin.omniresonance.transfer.ResourceTransferPolicy.legacy(
                        ItemTransferPolicy.defaults(TransferDirection.INPUT)),
                migrated.directBindings(NODE).getFirst().policy());
        CompoundTag saved = migrated.save(new CompoundTag(), RegistryAccess.EMPTY);
        assertTrue(saved.getList("direct_bindings", Tag.TAG_COMPOUND)
                .getCompound(0)
                .contains("resource_policy", Tag.TAG_COMPOUND));
        assertFalse(saved.getList("direct_bindings", Tag.TAG_COMPOUND)
                        .getCompound(0)
                        .getCompound("resource_policy")
                        .getList("resource_policy_overrides", Tag.TAG_COMPOUND)
                        .size()
                > 0);
        assertEquals(before, tag);
        assertFalse(migrated.isDirty());
    }
}
