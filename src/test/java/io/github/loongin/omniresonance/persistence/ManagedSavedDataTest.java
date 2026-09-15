// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.network.DirectNodeBinding;
import io.github.loongin.omniresonance.network.DomainNodeConfiguration;
import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.network.NetworkChannelRecord;
import io.github.loongin.omniresonance.network.NetworkMetadata;
import io.github.loongin.omniresonance.network.NetworkTunnelRecord;
import io.github.loongin.omniresonance.network.TransferDirection;
import io.github.loongin.omniresonance.node.NetworkNodeRecord;
import io.github.loongin.omniresonance.node.NodeForm;
import io.github.loongin.omniresonance.node.NodeMode;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedSavedDataTest {
    private static final UUID NETWORK = new UUID(1, 1);
    private static final UUID OTHER_NETWORK = new UUID(1, 2);
    private static final UUID OWNER = new UUID(2, 1);
    private static final UUID ADMIN = new UUID(3, 1);
    private static final UUID NODE_A = new UUID(4, 1);
    private static final UUID NODE_B = new UUID(4, 2);
    private static final UUID TUNNEL_A = new UUID(5, 1);
    private static final UUID TUNNEL_B = new UUID(5, 2);
    private static final UUID CHANNEL_A = new UUID(6, 1);
    private static final UUID CHANNEL_B = new UUID(6, 2);
    private static final ManagedName NAME = new ManagedName("主网络");
    private static final GlobalPos POS_A = GlobalPos.of(Level.OVERWORLD, new BlockPos(12, 64, -9));
    private static final GlobalPos POS_B = GlobalPos.of(Level.NETHER, new BlockPos(-8, 72, 31));

    @Test
    void metadataDefensivelyCopiesAdministrators() {
        Set<UUID> input = new HashSet<>(Set.of(ADMIN));
        NetworkMetadata metadata = new NetworkMetadata(NETWORK, OWNER, NAME, 0, input);
        input.clear();

        assertEquals(Set.of(ADMIN), metadata.administrators());
        assertThrows(
                UnsupportedOperationException.class,
                () -> metadata.administrators().clear());
    }

    @Test
    void metadataRejectsNegativeCreationOrderAndOwnerAsAdministrator() {
        assertThrows(IllegalArgumentException.class, () -> new NetworkMetadata(NETWORK, OWNER, NAME, -1, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new NetworkMetadata(NETWORK, OWNER, NAME, 0, Set.of(OWNER)));
    }

    @Test
    void metadataRejectsMissingFieldsAndAdministrator() {
        assertThrows(NullPointerException.class, () -> new NetworkMetadata(null, OWNER, NAME, 0, Set.of()));
        assertThrows(NullPointerException.class, () -> new NetworkMetadata(NETWORK, null, NAME, 0, Set.of()));
        assertThrows(NullPointerException.class, () -> new NetworkMetadata(NETWORK, OWNER, null, 0, Set.of()));
        assertThrows(NullPointerException.class, () -> new NetworkMetadata(NETWORK, OWNER, NAME, 0, null));
        Set<UUID> administrators = new HashSet<>();
        administrators.add(null);
        assertThrows(NullPointerException.class, () -> new NetworkMetadata(NETWORK, OWNER, NAME, 0, administrators));
    }

    @Test
    void administratorLimitAcceptsBoundaryAndRejectsOverflow() {
        Set<UUID> administrators = new HashSet<>();
        for (int index = 0; index < 262144; index++) {
            administrators.add(new UUID(4, index));
        }
        NetworkMetadata metadata = new NetworkMetadata(NETWORK, OWNER, NAME, Long.MAX_VALUE, administrators);
        CompoundTag saved = NetworkSavedData.create(metadata).save(new CompoundTag(), RegistryAccess.EMPTY);

        assertEquals(262144, saved.getList("administrators", Tag.TAG_INT_ARRAY).size());
        assertEquals(metadata, NetworkSavedData.load(NETWORK, saved).metadata());
        administrators.add(new UUID(4, 262144));
        assertThrows(
                IllegalArgumentException.class, () -> new NetworkMetadata(NETWORK, OWNER, NAME, 0, administrators));
    }

    @Test
    void newNetworkIsDirtyAndSavesExactV6Fields() {
        NetworkSavedData data = NetworkSavedData.create(metadata());

        assertTrue(data.isDirty());
        assertEquals(currentNetworkTag(), data.save(new CompoundTag(), RegistryAccess.EMPTY));
        assertTrue(data.isDirty());
    }

    @Test
    void v3NetworkMigratesInMemoryWithoutInventingTopologyOrDirtying() {
        CompoundTag v3 = networkV3Tag();
        CompoundTag node = nodeTag(NODE_A, 1, "Direct", POS_A, "block", "down");
        node.putString("mode", "direct");
        v3.putLong("last_node_number", 1);
        ListTag nodes = new ListTag();
        nodes.add(node);
        v3.put("nodes", nodes);
        CompoundTag before = v3.copy();

        NetworkSavedData migrated = NetworkSavedData.load(NETWORK, v3);

        assertEquals(before, v3);
        assertFalse(migrated.isDirty());
        assertEquals(0, migrated.topologyRevision());
        assertTrue(migrated.tunnels().isEmpty());
        assertTrue(migrated.channels(TUNNEL_A).isEmpty());
        assertTrue(migrated.directBindings(NODE_A).isEmpty());
        assertTrue(migrated.domainConfiguration(NODE_A).isEmpty());
        assertEquals(NodeMode.DIRECT, migrated.findNode(NODE_A).orElseThrow().mode());
        CompoundTag saved = migrated.save(new CompoundTag(), RegistryAccess.EMPTY);
        assertEquals(9, saved.getInt("schema_version"));
        assertEquals(0, saved.getLong("management_revision"));
        assertEquals(0, saved.getLong("last_tunnel_number"));
        assertEquals(0, saved.getLong("topology_revision"));
        assertTrue(saved.getList("tunnels", Tag.TAG_COMPOUND).isEmpty());
        assertTrue(saved.getList("channels", Tag.TAG_COMPOUND).isEmpty());
        assertTrue(saved.getList("direct_bindings", Tag.TAG_COMPOUND).isEmpty());
        assertTrue(saved.getList("domain_configurations", Tag.TAG_COMPOUND).isEmpty());
    }

    @Test
    void completeV4TopologyMigratesToStableOwnedSnapshots() {
        CompoundTag input = topologyNetworkTag();
        input.putInt("schema_version", 4);
        input.remove("management_revision");
        CompoundTag before = input.copy();

        NetworkSavedData loaded = NetworkSavedData.load(NETWORK, input);

        assertEquals(before, input);
        assertFalse(loaded.isDirty());
        assertEquals(9, loaded.topologyRevision());
        assertEquals(
                List.of(
                        new NetworkTunnelRecord(TUNNEL_A, 1, new ManagedName("Primary"), 3, true, 2),
                        new NetworkTunnelRecord(TUNNEL_B, 2, new ManagedName("Secondary"), 4, false, 1)),
                loaded.tunnels());
        assertEquals(
                List.of(
                        new NetworkChannelRecord(CHANNEL_A, TUNNEL_A, 1, new ManagedName("Input"), 5),
                        new NetworkChannelRecord(CHANNEL_B, TUNNEL_A, 2, new ManagedName("Output"), 6)),
                loaded.channels(TUNNEL_A));
        assertEquals(
                List.of(new DirectNodeBinding(
                        NODE_A,
                        CHANNEL_A,
                        io.github.loongin.omniresonance.transfer.ItemTransferPolicy.defaults(TransferDirection.INPUT))),
                loaded.directBindings(NODE_A));
        assertEquals(
                Optional.of(new DomainNodeConfiguration(NODE_B, TransferDirection.OUTPUT)),
                loaded.domainConfiguration(NODE_B));
        CompoundTag currentExpected = before.copy();
        currentExpected.putInt("schema_version", 9);
        currentExpected.putLong("bucket_created_mask", 0);
        CompoundTag expectedDomain = currentExpected
                .getList("domain_configurations", Tag.TAG_COMPOUND)
                .getCompound(0);
        expectedDomain.put(
                "resource_policy",
                ResourcePolicyNbt.encode(new DomainNodeConfiguration(NODE_B, TransferDirection.OUTPUT).storedPolicy()));
        expectedDomain.putInt("working_face_mask", 0);
        expectedDomain.putBoolean("working_face_attached", true);
        expectedDomain.putBoolean("configured", false);
        currentExpected.put("recovery", new ListTag());
        currentExpected
                .getList("direct_bindings", Tag.TAG_COMPOUND)
                .getCompound(0)
                .putInt("working_face_mask", 0);
        currentExpected
                .getList("direct_bindings", Tag.TAG_COMPOUND)
                .getCompound(0)
                .putBoolean("working_face_attached", true);
        currentExpected
                .getList("direct_bindings", Tag.TAG_COMPOUND)
                .getCompound(0)
                .put(
                        "resource_policy",
                        ResourcePolicyNbt.encode(new io.github.loongin.omniresonance.transfer.StoredResourcePolicy(
                                io.github.loongin.omniresonance.transfer.ResourceTransferPolicy.legacy(
                                        io.github.loongin.omniresonance.transfer.ItemTransferPolicy.defaults(
                                                TransferDirection.INPUT)),
                                java.util.Map.of())));
        currentExpected.putLong("management_revision", 0);
        assertEquals(currentExpected, loaded.save(new CompoundTag(), RegistryAccess.EMPTY));
        assertThrows(UnsupportedOperationException.class, () -> loaded.tunnels().clear());
        assertThrows(
                UnsupportedOperationException.class,
                () -> loaded.channels(TUNNEL_A).clear());
        assertThrows(
                UnsupportedOperationException.class,
                () -> loaded.directBindings(NODE_A).clear());
    }

    @Test
    void v4RejectsDuplicateAndDanglingTopologyWithoutChangingInput() {
        CompoundTag emptyTunnel = topologyNetworkTag();
        emptyTunnel.getList("channels", Tag.TAG_COMPOUND).remove(2);
        assertRejectedV4(emptyTunnel, "tunnel without a channel");

        CompoundTag duplicateTunnel = topologyNetworkTag();
        CompoundTag duplicate =
                duplicateTunnel.getList("tunnels", Tag.TAG_COMPOUND).getCompound(1);
        duplicate.putString("name", "primary");
        assertRejectedV4(duplicateTunnel, "duplicate tunnel name");

        CompoundTag danglingChannel = topologyNetworkTag();
        danglingChannel.getList("channels", Tag.TAG_COMPOUND).getCompound(0).putUUID("tunnel_id", new UUID(999, 1));
        assertRejectedV4(danglingChannel, "dangling tunnel");

        CompoundTag danglingBinding = topologyNetworkTag();
        danglingBinding
                .getList("direct_bindings", Tag.TAG_COMPOUND)
                .getCompound(0)
                .putUUID("channel_id", new UUID(999, 2));
        assertRejectedV4(danglingBinding, "dangling channel");

        CompoundTag crossTunnelBindings = topologyNetworkTag();
        crossTunnelBindings
                .getList("direct_bindings", Tag.TAG_COMPOUND)
                .add(directionTag(NODE_A, "channel_id", new UUID(84, 3), "output"));
        assertRejectedV4(crossTunnelBindings, "one direct node bound across tunnels");

        CompoundTag wrongNodeMode = topologyNetworkTag();
        wrongNodeMode
                .getList("domain_configurations", Tag.TAG_COMPOUND)
                .getCompound(0)
                .putUUID("node_id", NODE_A);
        assertRejectedV4(wrongNodeMode, "domain configuration on direct node");

        CompoundTag invalidDirection = topologyNetworkTag();
        invalidDirection
                .getList("direct_bindings", Tag.TAG_COMPOUND)
                .getCompound(0)
                .putString("direction", "sideways");
        assertRejectedV4(invalidDirection, "invalid direction");
    }

    @Test
    void v4RejectsMissingUnexpectedAndOversizedTopologyEntries() {
        CompoundTag legal = topologyNetworkTag();
        for (String listKey : List.of("tunnels", "channels", "direct_bindings", "domain_configurations")) {
            CompoundTag entry = legal.getList(listKey, Tag.TAG_COMPOUND).getCompound(0);
            for (String field : entry.getAllKeys()) {
                CompoundTag input = topologyNetworkTag();
                input.getList(listKey, Tag.TAG_COMPOUND).getCompound(0).remove(field);
                assertRejectedV4(input, listKey + " missing " + field);
            }
            CompoundTag input = topologyNetworkTag();
            input.getList(listKey, Tag.TAG_COMPOUND).getCompound(0).putString("future", "field");
            assertRejectedV4(input, listKey + " unknown field");
        }

        CompoundTag oversized = networkTag();
        ListTag tunnels = new ListTag();
        CompoundTag malformed = new CompoundTag();
        for (int index = 0; index < 65536; index++) {
            tunnels.add(malformed);
        }
        oversized.put("tunnels", tunnels);
        assertRejectedV4(oversized, "tunnel hard limit before entry decode");
    }

    @Test
    void loadedNetworkIsCleanAndIndependentOfInputTag() {
        CompoundTag input = networkTag();
        CompoundTag before = input.copy();
        NetworkSavedData loaded = NetworkSavedData.load(NETWORK, input);

        assertEquals(metadata(), loaded.metadata());
        assertFalse(loaded.isDirty());
        assertEquals(before, input);
        input.putString("name", "changed");
        input.getList("administrators", Tag.TAG_INT_ARRAY).clear();
        input.getIntArray("network_id")[0] = 99;
        assertEquals(metadata(), loaded.metadata());
    }

    @Test
    void networkSerializationAllocatesIndependentMutableTags() {
        NetworkSavedData data = NetworkSavedData.create(metadata());
        CompoundTag first = data.save(new CompoundTag(), RegistryAccess.EMPTY);
        CompoundTag second = data.save(new CompoundTag(), RegistryAccess.EMPTY);
        assertEquals(currentNetworkTag(), first);
        first.getList("administrators", Tag.TAG_INT_ARRAY).getIntArray(0)[0] = 99;
        first.getIntArray("owner_id")[0] = 99;

        assertEquals(currentNetworkTag(), second);
        assertEquals(metadata(), data.metadata());
        assertEquals(currentNetworkTag(), data.save(new CompoundTag(), RegistryAccess.EMPTY));
    }

    @Test
    void networkAdministratorsHaveStableUuidOrder() {
        UUID first = new UUID(-1, 1);
        NetworkMetadata metadata = new NetworkMetadata(NETWORK, OWNER, NAME, 0, Set.of(ADMIN, first));
        ListTag expected = new ListTag();
        expected.add(NbtUtils.createUUID(first));
        expected.add(NbtUtils.createUUID(ADMIN));

        assertEquals(
                expected,
                NetworkSavedData.create(metadata)
                        .save(new CompoundTag(), RegistryAccess.EMPTY)
                        .getList("administrators", Tag.TAG_INT_ARRAY));
    }

    @Test
    void networkRejectsIdentityMismatch() {
        CompoundTag input = networkTag();
        assertThrows(IllegalArgumentException.class, () -> NetworkSavedData.load(OTHER_NETWORK, input));
    }

    @Test
    void networkRejectsEveryMissingRequiredField() {
        for (String key : networkTag().getAllKeys()) {
            CompoundTag input = networkTag();
            input.remove(key);
            assertThrows(IllegalArgumentException.class, () -> NetworkSavedData.load(NETWORK, input), key);
        }
    }

    @Test
    void networkRejectsWrongFieldTypesInsteadOfDefaulting() {
        for (String key : networkTag().getAllKeys()) {
            CompoundTag input = networkTag();
            input.put(key, key.equals("name") ? IntTag.valueOf(1) : StringTag.valueOf("1"));
            assertThrows(IllegalArgumentException.class, () -> NetworkSavedData.load(NETWORK, input), key);
        }
        CompoundTag numericOrder = networkTag();
        numericOrder.putInt("creation_order", 0);
        assertThrows(IllegalArgumentException.class, () -> NetworkSavedData.load(NETWORK, numericOrder));
    }

    @Test
    void networkRejectsUnknownFieldsAndUnsupportedSchema() {
        CompoundTag unknown = networkTag();
        unknown.putString("future_field", "preserve me");
        assertThrows(IllegalArgumentException.class, () -> NetworkSavedData.load(NETWORK, unknown));
        for (int schema : new int[] {-1, 0, 1, 2, 6}) {
            CompoundTag input = networkTag();
            input.putInt("schema_version", schema);
            CompoundTag before = input.copy();
            assertThrows(IllegalArgumentException.class, () -> NetworkSavedData.load(NETWORK, input));
            assertEquals(before, input);
        }
        CompoundTag wrongType = networkTag();
        wrongType.putLong("schema_version", 4);
        assertThrows(IllegalArgumentException.class, () -> NetworkSavedData.load(NETWORK, wrongType));
    }

    @Test
    void networkNodesRoundTripInStableNumberOrderAndOwnTheirTags() {
        NetworkSavedData data = NetworkSavedData.create(metadata());
        NetworkNodeRecord first =
                data.createNode(NODE_B, new ManagedName("Ore input"), POS_A, NodeForm.PANEL, Direction.WEST);
        NetworkNodeRecord second =
                data.createNode(NODE_A, new ManagedName("Fluid output"), POS_B, NodeForm.BLOCK, Direction.UP);

        CompoundTag saved = data.save(new CompoundTag(), RegistryAccess.EMPTY);
        ListTag nodes = saved.getList("nodes", Tag.TAG_COMPOUND);
        assertEquals(2, nodes.size());
        assertEquals(1, nodes.getCompound(0).getLong("node_number"));
        assertEquals(NODE_B, nodes.getCompound(0).getUUID("node_id"));
        assertEquals(2, nodes.getCompound(1).getLong("node_number"));
        assertEquals(NODE_A, nodes.getCompound(1).getUUID("node_id"));
        assertEquals(
                Set.of(
                        "node_id",
                        "node_number",
                        "name",
                        "dimension",
                        "x",
                        "y",
                        "z",
                        "form",
                        "facing",
                        "revision",
                        "enabled",
                        "chunk_loading_requested",
                        "mode"),
                nodes.getCompound(0).getAllKeys());
        assertEquals(0, nodes.getCompound(0).getLong("revision"));
        assertTrue(nodes.getCompound(0).getBoolean("enabled"));
        assertFalse(nodes.getCompound(0).getBoolean("chunk_loading_requested"));
        assertEquals("unconfigured", nodes.getCompound(0).getString("mode"));

        NetworkSavedData loaded = NetworkSavedData.load(NETWORK, saved);
        assertEquals(List.of(first, second), loaded.nodes());
        assertEquals(2, loaded.lastNodeNumber());
        assertEquals(Optional.of(first), loaded.findNode(NODE_B));
        assertFalse(loaded.isDirty());
        assertThrows(UnsupportedOperationException.class, () -> loaded.nodes().clear());

        nodes.getCompound(0).putString("name", "mutated");
        nodes.getCompound(0).putInt("x", 999);
        assertEquals(List.of(first, second), loaded.nodes());
        assertEquals("Ore input", loaded.findNode(NODE_B).orElseThrow().name().value());
    }

    @Test
    void networkNodeMutationsAllocateMonotonicNumbersAndDirtyOnlyOnChange() {
        NetworkSavedData data = NetworkSavedData.create(metadata());
        NetworkNodeRecord first = data.createNode(NODE_A, new ManagedName("A"), POS_A, NodeForm.BLOCK, Direction.DOWN);
        assertEquals(1, first.nodeNumber());
        data.setDirty(false);

        assertSame(
                first,
                data.updateNodePhysicalSnapshot(NODE_A, POS_A, NodeForm.BLOCK, Direction.DOWN)
                        .orElseThrow());
        assertFalse(data.isDirty());
        assertTrue(data.updateNodePhysicalSnapshot(NODE_A, POS_B, NodeForm.PANEL, Direction.UP)
                .isEmpty());
        assertTrue(data.removeNode(NODE_A, POS_B).isEmpty());
        assertFalse(data.isDirty());

        NetworkNodeRecord updated = data.updateNodePhysicalSnapshot(NODE_A, POS_A, NodeForm.PANEL, Direction.NORTH)
                .orElseThrow();
        assertEquals(NodeForm.PANEL, updated.form());
        assertEquals(Direction.NORTH, updated.facing());
        assertEquals(1, updated.revision());
        assertTrue(data.isDirty());
        data.setDirty(false);
        assertEquals(Optional.of(updated), data.removeNode(NODE_A, POS_A));
        assertTrue(data.isDirty());
        data.setDirty(false);

        NetworkNodeRecord second = data.createNode(NODE_B, new ManagedName("B"), POS_B, NodeForm.BLOCK, Direction.UP);
        assertEquals(2, second.nodeNumber());
        assertEquals(2, data.lastNodeNumber());
    }

    @Test
    void networkNodeManagementMutationsAreRevisionedAndDirtyOnlyOnChange() {
        NetworkSavedData data = NetworkSavedData.create(metadata());
        NetworkNodeRecord created =
                data.createNode(NODE_A, new ManagedName("Node A"), POS_A, NodeForm.BLOCK, Direction.DOWN);
        data.setDirty(false);

        NetworkNodeRecord renamed = data.renameNode(NODE_A, created.revision(), new ManagedName("Crusher input"))
                .orElseThrow();
        assertEquals(1, renamed.revision());
        assertEquals("Crusher input", renamed.name().value());
        assertTrue(data.isDirty());
        data.setDirty(false);
        assertSame(renamed, data.renameNode(NODE_A, 1, renamed.name()).orElseThrow());
        assertFalse(data.isDirty());
        NetworkNodeRecord recased =
                data.renameNode(NODE_A, 1, new ManagedName("CRUSHER INPUT")).orElseThrow();
        assertEquals(2, recased.revision());
        assertEquals("CRUSHER INPUT", recased.name().value());

        NetworkNodeRecord disabled = data.setNodeEnabled(NODE_A, 2, false).orElseThrow();
        assertEquals(3, disabled.revision());
        assertFalse(disabled.enabled());
        data.setDirty(false);
        assertThrows(IllegalStateException.class, () -> data.setNodeChunkLoadingRequested(NODE_A, 3, true));
        assertThrows(IllegalStateException.class, () -> data.setNodeMode(NODE_A, 3, NodeMode.DIRECT, false));
        assertEquals(disabled, data.findNode(NODE_A).orElseThrow());
        assertFalse(data.isDirty());

        NetworkNodeRecord enabled = data.setNodeEnabled(NODE_A, 3, true).orElseThrow();
        NetworkNodeRecord requested = data.setNodeChunkLoadingRequested(NODE_A, enabled.revision(), true)
                .orElseThrow();
        NetworkNodeRecord direct = data.setNodeMode(NODE_A, requested.revision(), NodeMode.DIRECT, false)
                .orElseThrow();
        assertEquals(6, direct.revision());
        assertTrue(direct.chunkLoadingRequested());
        assertEquals(NodeMode.DIRECT, direct.mode());
        data.setDirty(false);

        assertThrows(
                IllegalStateException.class, () -> data.setNodeMode(NODE_A, direct.revision(), NodeMode.DOMAIN, false));
        assertEquals(direct, data.findNode(NODE_A).orElseThrow());
        assertFalse(data.isDirty());
        NetworkNodeRecord domain = data.setNodeMode(NODE_A, direct.revision(), NodeMode.DOMAIN, true)
                .orElseThrow();
        assertEquals(7, domain.revision());
        assertEquals(NodeMode.DOMAIN, domain.mode());
    }

    @Test
    void networkNodeManagementFailuresLeaveEveryIndexAndDirtyStateUnchanged() {
        NetworkSavedData data = NetworkSavedData.create(metadata());
        NetworkNodeRecord first =
                data.createNode(NODE_A, new ManagedName("Node A"), POS_A, NodeForm.BLOCK, Direction.DOWN);
        data.createNode(NODE_B, new ManagedName("Node B"), POS_B, NodeForm.PANEL, Direction.UP);
        data.setDirty(false);

        assertTrue(data.renameNode(NODE_A, 9, new ManagedName("Changed")).isEmpty());
        assertTrue(data.setNodeEnabled(NODE_A, 9, false).isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () -> data.renameNode(NODE_A, first.revision(), new ManagedName("node b")));
        assertThrows(
                IllegalArgumentException.class,
                () -> data.setNodeMode(NODE_A, first.revision(), NodeMode.UNCONFIGURED, false));
        assertEquals(first, data.findNode(NODE_A).orElseThrow());
        assertFalse(data.isDirty());

        CompoundTag exhaustedTag = networkTag();
        exhaustedTag.putLong("last_node_number", 1);
        CompoundTag exhaustedNode = nodeTag(NODE_A, 1, "Node A", POS_A, "block", "down");
        exhaustedNode.putLong("revision", Long.MAX_VALUE);
        ListTag nodes = new ListTag();
        nodes.add(exhaustedNode);
        exhaustedTag.put("nodes", nodes);
        NetworkSavedData exhausted = NetworkSavedData.load(NETWORK, exhaustedTag);
        assertThrows(
                ArithmeticException.class,
                () -> exhausted.renameNode(NODE_A, Long.MAX_VALUE, new ManagedName("Changed")));
        assertEquals("Node A", exhausted.findNode(NODE_A).orElseThrow().name().value());
        assertFalse(exhausted.isDirty());
    }

    @Test
    void networkNodeMutationsRejectDuplicatesAndNumberOverflowBeforeMutation() {
        NetworkSavedData data = NetworkSavedData.create(metadata());
        data.createNode(NODE_A, new ManagedName("Node A"), POS_A, NodeForm.BLOCK, Direction.DOWN);
        data.setDirty(false);

        assertThrows(
                IllegalArgumentException.class,
                () -> data.createNode(NODE_A, new ManagedName("Other"), POS_B, NodeForm.PANEL, Direction.UP));
        assertThrows(
                IllegalArgumentException.class,
                () -> data.createNode(NODE_B, new ManagedName("node a"), POS_B, NodeForm.PANEL, Direction.UP));
        assertThrows(
                IllegalArgumentException.class,
                () -> data.createNode(NODE_B, new ManagedName("Other"), POS_A, NodeForm.PANEL, Direction.UP));
        assertEquals(1, data.nodes().size());
        assertEquals(1, data.lastNodeNumber());
        assertFalse(data.isDirty());

        CompoundTag exhaustedTag = networkTag();
        exhaustedTag.putLong("last_node_number", Long.MAX_VALUE);
        NetworkSavedData exhausted = NetworkSavedData.load(NETWORK, exhaustedTag);
        assertThrows(
                ArithmeticException.class,
                () -> exhausted.createNode(NODE_B, new ManagedName("B"), POS_B, NodeForm.BLOCK, Direction.UP));
        assertEquals(Long.MAX_VALUE, exhausted.lastNodeNumber());
        assertTrue(exhausted.nodes().isEmpty());
        assertFalse(exhausted.isDirty());
    }

    @Test
    void networkNodeMethodsRejectMissingInputsBeforeLookupOrMutation() {
        NetworkSavedData data = NetworkSavedData.create(metadata());
        ManagedName name = new ManagedName("Node");
        assertThrows(NullPointerException.class, () -> data.findNode(null));
        assertThrows(NullPointerException.class, () -> data.containsNodeName(null));
        assertThrows(
                NullPointerException.class, () -> data.createNode(null, name, POS_A, NodeForm.BLOCK, Direction.DOWN));
        assertThrows(
                NullPointerException.class, () -> data.createNode(NODE_A, null, POS_A, NodeForm.BLOCK, Direction.DOWN));
        assertThrows(
                NullPointerException.class, () -> data.createNode(NODE_A, name, null, NodeForm.BLOCK, Direction.DOWN));
        assertThrows(NullPointerException.class, () -> data.createNode(NODE_A, name, POS_A, null, Direction.DOWN));
        assertThrows(NullPointerException.class, () -> data.createNode(NODE_A, name, POS_A, NodeForm.BLOCK, null));
        assertThrows(
                NullPointerException.class,
                () -> data.updateNodePhysicalSnapshot(null, POS_A, NodeForm.BLOCK, Direction.DOWN));
        assertThrows(
                NullPointerException.class,
                () -> data.updateNodePhysicalSnapshot(NODE_A, null, NodeForm.BLOCK, Direction.DOWN));
        assertThrows(
                NullPointerException.class, () -> data.updateNodePhysicalSnapshot(NODE_A, POS_A, null, Direction.DOWN));
        assertThrows(
                NullPointerException.class, () -> data.updateNodePhysicalSnapshot(NODE_A, POS_A, NodeForm.BLOCK, null));
        assertThrows(NullPointerException.class, () -> data.removeNode(null, POS_A));
        assertThrows(NullPointerException.class, () -> data.removeNode(NODE_A, null));
        assertThrows(NullPointerException.class, () -> data.renameNode(null, 0, name));
        assertThrows(NullPointerException.class, () -> data.renameNode(NODE_A, 0, null));
        assertThrows(NullPointerException.class, () -> data.setNodeEnabled(null, 0, false));
        assertThrows(NullPointerException.class, () -> data.setNodeChunkLoadingRequested(null, 0, true));
        assertThrows(NullPointerException.class, () -> data.setNodeMode(null, 0, NodeMode.DIRECT, false));
        assertThrows(NullPointerException.class, () -> data.setNodeMode(NODE_A, 0, null, false));
        assertThrows(IllegalArgumentException.class, () -> data.renameNode(NODE_A, -1, name));
        assertTrue(data.nodes().isEmpty());
        assertEquals(0, data.lastNodeNumber());
    }

    @Test
    void networkRejectsMalformedNodeFieldsAndDuplicateManagedKeys() {
        CompoundTag legal = nodeTag(NODE_A, 1, "Node A", POS_A, "block", "down");
        for (String key : legal.getAllKeys()) {
            CompoundTag node = legal.copy();
            node.remove(key);
            assertRejectedNodeList(List.of(node), 1, "missing " + key);
        }
        for (String key : legal.getAllKeys()) {
            CompoundTag node = legal.copy();
            node.put(
                    key,
                    key.equals("name")
                                    || key.equals("dimension")
                                    || key.equals("form")
                                    || key.equals("facing")
                                    || key.equals("mode")
                            ? IntTag.valueOf(1)
                            : StringTag.valueOf("wrong"));
            assertRejectedNodeList(List.of(node), 1, "wrong type " + key);
        }

        assertRejectedNodeList(List.of(nodeTag(NODE_A, 0, "Node A", POS_A, "block", "down")), 1, "zero number");
        assertRejectedNodeList(List.of(nodeTag(NODE_A, 2, "Node A", POS_A, "block", "down")), 1, "number above last");
        assertRejectedNodeList(List.of(nodeTag(NODE_A, 1, "Node A", POS_A, "unknown", "down")), 1, "unknown form");
        assertRejectedNodeList(List.of(nodeTag(NODE_A, 1, "Node A", POS_A, "block", "sideways")), 1, "unknown facing");
        CompoundTag negativeRevision = legal.copy();
        negativeRevision.putLong("revision", -1);
        assertRejectedNodeList(List.of(negativeRevision), 1, "negative revision");
        CompoundTag unknownMode = legal.copy();
        unknownMode.putString("mode", "future");
        assertRejectedNodeList(List.of(unknownMode), 1, "unknown mode");
        for (String key : List.of("enabled", "chunk_loading_requested")) {
            for (byte value : new byte[] {-1, 2}) {
                CompoundTag invalidBoolean = legal.copy();
                invalidBoolean.putByte(key, value);
                assertRejectedNodeList(List.of(invalidBoolean), 1, key + " raw value " + value);
            }
        }
        CompoundTag invalidDimension = legal.copy();
        invalidDimension.putString("dimension", "Bad:Dimension");
        assertRejectedNodeList(List.of(invalidDimension), 1, "invalid dimension");
        CompoundTag oversizedDimension = legal.copy();
        oversizedDimension.putString("dimension", "a:" + "x".repeat(255));
        assertRejectedNodeList(List.of(oversizedDimension), 1, "oversized dimension");

        CompoundTag second = nodeTag(NODE_B, 2, "Node B", POS_B, "panel", "north");
        CompoundTag duplicateId = second.copy();
        duplicateId.putUUID("node_id", NODE_A);
        assertRejectedNodeList(List.of(legal, duplicateId), 2, "duplicate id");
        CompoundTag duplicateNumber = second.copy();
        duplicateNumber.putLong("node_number", 1);
        assertRejectedNodeList(List.of(legal, duplicateNumber), 2, "duplicate number");
        CompoundTag duplicateName = second.copy();
        duplicateName.putString("name", "node a");
        assertRejectedNodeList(List.of(legal, duplicateName), 2, "duplicate name");
        CompoundTag duplicatePosition = second.copy();
        duplicatePosition.putString("dimension", "minecraft:overworld");
        duplicatePosition.putInt("x", 12);
        duplicatePosition.putInt("y", 64);
        duplicatePosition.putInt("z", -9);
        assertRejectedNodeList(List.of(legal, duplicatePosition), 2, "duplicate position");
    }

    @Test
    void networkNodeModesRoundTripFromLiteralTags() {
        assertModeLoaded("unconfigured", NodeMode.UNCONFIGURED);
        assertModeLoaded("direct", NodeMode.DIRECT);
        assertModeLoaded("domain", NodeMode.DOMAIN);
    }

    @Test
    void networkRejectsNodeListAboveHardLimitBeforeEntryDecode() {
        CompoundTag input = networkTag();
        ListTag nodes = new ListTag();
        CompoundTag malformed = new CompoundTag();
        for (int index = 0; index < 262145; index++) {
            nodes.add(malformed);
        }
        input.put("nodes", nodes);

        assertThrows(IllegalArgumentException.class, () -> NetworkSavedData.load(NETWORK, input));
    }

    @Test
    void networkRejectsInvalidNamesAndNegativeCreationOrder() {
        for (String name : List.of("", "  ", "bad\nname", "§name", "x".repeat(65), "\uD800")) {
            CompoundTag input = networkTag();
            input.putString("name", name);
            assertThrows(IllegalArgumentException.class, () -> NetworkSavedData.load(NETWORK, input));
        }
        CompoundTag negativeOrder = networkTag();
        negativeOrder.putLong("creation_order", -1);
        assertThrows(IllegalArgumentException.class, () -> NetworkSavedData.load(NETWORK, negativeOrder));
    }

    @Test
    void networkRejectsMalformedIdentityArrays() {
        for (String key : List.of("network_id", "owner_id")) {
            for (int length : new int[] {0, 3, 5}) {
                CompoundTag input = networkTag();
                input.putIntArray(key, new int[length]);
                assertThrows(IllegalArgumentException.class, () -> NetworkSavedData.load(NETWORK, input), key);
            }
        }
    }

    @Test
    void networkAllowsEmptyEndOrUuidLists() throws IOException {
        for (ListTag empty : List.of(new ListTag(), emptyListOfType(Tag.TAG_INT_ARRAY))) {
            CompoundTag input = networkTag();
            input.put("administrators", empty);
            NetworkMetadata loaded = NetworkSavedData.load(NETWORK, input).metadata();
            assertNotNull(loaded);
            assertEquals(Set.of(), loaded.administrators());
        }
    }

    @Test
    void networkRejectsWrongAdministratorElementTypesIncludingEmptyTypedLists() throws IOException {
        ListTag strings = new ListTag();
        strings.add(StringTag.valueOf(ADMIN.toString()));
        for (ListTag wrong : List.of(strings, emptyListOfType(Tag.TAG_STRING))) {
            CompoundTag input = networkTag();
            input.put("administrators", wrong);
            assertThrows(IllegalArgumentException.class, () -> NetworkSavedData.load(NETWORK, input));
        }
    }

    @Test
    void networkRejectsDuplicateAdministratorsAndOwnerMembership() {
        ListTag duplicates = new ListTag();
        duplicates.add(NbtUtils.createUUID(ADMIN));
        duplicates.add(NbtUtils.createUUID(ADMIN));
        ListTag owner = new ListTag();
        owner.add(NbtUtils.createUUID(OWNER));
        for (ListTag invalid : List.of(duplicates, owner)) {
            CompoundTag input = networkTag();
            input.put("administrators", invalid);
            assertThrows(IllegalArgumentException.class, () -> NetworkSavedData.load(NETWORK, input));
        }
    }

    @Test
    void networkRejectsMalformedAdministratorArrays() {
        for (int length : new int[] {0, 3, 5}) {
            CompoundTag input = networkTag();
            ListTag administrators = new ListTag();
            administrators.add(new IntArrayTag(new int[length]));
            input.put("administrators", administrators);
            assertThrows(IllegalArgumentException.class, () -> NetworkSavedData.load(NETWORK, input));
        }
    }

    @Test
    void networkRejectsAdministratorListAboveHardLimit() {
        CompoundTag input = networkTag();
        ListTag administrators = new ListTag();
        for (int index = 0; index < 262145; index++) {
            administrators.add(NbtUtils.createUUID(new UUID(4, index)));
        }
        input.put("administrators", administrators);

        assertThrows(IllegalArgumentException.class, () -> NetworkSavedData.load(NETWORK, input));
    }

    @Test
    void rejectedNetworkLoadDoesNotModifyInput() {
        CompoundTag input = networkTag();
        input.putInt("unknown", 42);
        CompoundTag before = input.copy();

        assertThrows(IllegalArgumentException.class, () -> NetworkSavedData.load(NETWORK, input));
        assertEquals(before, input);
    }

    @Test
    void newOwnerIsDirtyAndSavesExactV3Fields() {
        OwnerSavedData data = OwnerSavedData.create(OWNER, NETWORK);

        assertTrue(data.isDirty());
        assertEquals(currentOwnerTag(true), data.save(new CompoundTag(), RegistryAccess.EMPTY));
        assertTrue(data.isDirty());
    }

    @Test
    void loadedOwnerIsCleanAndIndependentOfInputTag() {
        CompoundTag input = ownerTag(true);
        CompoundTag before = input.copy();
        OwnerSavedData data = OwnerSavedData.load(OWNER, input);

        assertEquals(OWNER, data.ownerId());
        assertEquals(Optional.of(NETWORK), data.defaultNetworkId());
        assertFalse(data.isDirty());
        assertEquals(before, input);
        input.getIntArray("owner_id")[0] = 99;
        input.putUUID("default_network_id", OTHER_NETWORK);
        assertEquals(OWNER, data.ownerId());
        assertEquals(Optional.of(NETWORK), data.defaultNetworkId());
    }

    @Test
    void absentOwnerDefaultIsNotInvented() {
        OwnerSavedData loaded = OwnerSavedData.load(OWNER, ownerTag(false));
        OwnerSavedData created = OwnerSavedData.create(OWNER, null);

        assertEquals(Optional.empty(), loaded.defaultNetworkId());
        assertFalse(loaded.isDirty());
        assertTrue(created.isDirty());
        assertEquals(currentOwnerTag(false), created.save(new CompoundTag(), RegistryAccess.EMPTY));
    }

    @Test
    void ownerChangesDirtyOnlyWhenDefaultActuallyChanges() {
        OwnerSavedData data = OwnerSavedData.load(OWNER, ownerTag(true));
        data.setDefaultNetwork(NETWORK);
        assertFalse(data.isDirty());
        data.setDefaultNetwork(OTHER_NETWORK);
        assertTrue(data.isDirty());
        assertEquals(Optional.of(OTHER_NETWORK), data.defaultNetworkId());
        data.setDirty(false);
        data.setDefaultNetwork(null);
        assertTrue(data.isDirty());
        assertEquals(Optional.empty(), data.defaultNetworkId());
        data.setDirty(false);
        data.setDefaultNetwork(null);
        assertFalse(data.isDirty());
        data.setDefaultNetwork(NETWORK);
        assertTrue(data.isDirty());
        assertEquals(Optional.of(NETWORK), data.defaultNetworkId());
    }

    @Test
    void clearingDefaultRemovesStaleOptionalFieldFromReusedOutputTag() {
        OwnerSavedData data = OwnerSavedData.create(OWNER, null);
        assertEquals(currentOwnerTag(false), data.save(ownerTag(true), RegistryAccess.EMPTY));
    }

    @Test
    void ownerSerializationAllocatesIndependentMutableTags() {
        OwnerSavedData data = OwnerSavedData.create(OWNER, NETWORK);
        CompoundTag first = data.save(new CompoundTag(), RegistryAccess.EMPTY);
        CompoundTag second = data.save(new CompoundTag(), RegistryAccess.EMPTY);
        assertEquals(currentOwnerTag(true), first);
        first.getIntArray("owner_id")[0] = 99;
        first.getIntArray("default_network_id")[0] = 99;

        assertEquals(currentOwnerTag(true), second);
        assertEquals(OWNER, data.ownerId());
        assertEquals(Optional.of(NETWORK), data.defaultNetworkId());
    }

    @Test
    void ownerRejectsMissingRequiredFieldsAndIdentityMismatch() {
        for (String key : List.of("schema_version", "owner_id")) {
            CompoundTag input = ownerTag(true);
            input.remove(key);
            assertThrows(IllegalArgumentException.class, () -> OwnerSavedData.load(OWNER, input));
        }
        assertThrows(IllegalArgumentException.class, () -> OwnerSavedData.load(ADMIN, ownerTag(true)));
    }

    @Test
    void ownerRejectsWrongTypesMalformedUuidsAndUnknownFields() {
        for (String key : ownerTag(true).getAllKeys()) {
            CompoundTag input = ownerTag(true);
            input.putString(key, "1");
            assertThrows(IllegalArgumentException.class, () -> OwnerSavedData.load(OWNER, input));
        }
        for (String key : List.of("owner_id", "default_network_id")) {
            for (int length : new int[] {0, 3, 5}) {
                CompoundTag input = ownerTag(true);
                input.putIntArray(key, new int[length]);
                assertThrows(IllegalArgumentException.class, () -> OwnerSavedData.load(OWNER, input));
            }
        }
        CompoundTag unknown = ownerTag(true);
        unknown.putInt("unknown", 42);
        CompoundTag before = unknown.copy();
        assertThrows(IllegalArgumentException.class, () -> OwnerSavedData.load(OWNER, unknown));
        assertEquals(before, unknown);
    }

    @Test
    void ownerRejectsUnsupportedOrWronglyTypedSchema() {
        for (int schema : new int[] {-1, 0, 2}) {
            CompoundTag input = ownerTag(false);
            input.putInt("schema_version", schema);
            assertThrows(IllegalArgumentException.class, () -> OwnerSavedData.load(OWNER, input));
        }
        CompoundTag wrongType = ownerTag(true);
        wrongType.putLong("schema_version", 1);
        assertThrows(IllegalArgumentException.class, () -> OwnerSavedData.load(OWNER, wrongType));
    }

    @Test
    void savedDataRejectsMissingRequiredInputs() {
        assertThrows(NullPointerException.class, () -> NetworkSavedData.create(null));
        assertThrows(NullPointerException.class, () -> NetworkSavedData.load(null, networkTag()));
        assertThrows(NullPointerException.class, () -> NetworkSavedData.load(NETWORK, null));
        assertThrows(NullPointerException.class, () -> OwnerSavedData.create(null, null));
        assertThrows(NullPointerException.class, () -> OwnerSavedData.load(null, ownerTag(false)));
        assertThrows(NullPointerException.class, () -> OwnerSavedData.load(OWNER, null));
    }

    @Test
    void networkRejectsAllOffThreadStateAccessIncludingInheritedMethods(@TempDir Path directory) throws Exception {
        NetworkSavedData data = NetworkSavedData.create(metadata());
        data.setDirty(false);
        Path file = directory.resolve("network.dat");
        assertOffThreadRejected(List.of(
                data::metadata,
                data::nodes,
                data::lastNodeNumber,
                data::topologyRevision,
                data::lastTunnelNumber,
                data::tunnels,
                () -> data.channels(TUNNEL_A),
                () -> data.directBindings(NODE_A),
                () -> data.domainConfiguration(NODE_A),
                () -> data.findTunnel(TUNNEL_A),
                () -> data.findChannel(CHANNEL_A),
                () -> data.pageTunnels(null, false, 128),
                () -> data.pageChannels(TUNNEL_A, null, false, 128),
                () -> data.createTunnel(TUNNEL_A, new ManagedName("T"), CHANNEL_A, new ManagedName("Channel 1"), -1),
                () -> data.renameTunnel(TUNNEL_A, 0, new ManagedName("T")),
                () -> data.setTunnelEnabled(TUNNEL_A, 0, false),
                () -> data.createChannel(TUNNEL_A, 0, CHANNEL_A, new ManagedName("C"), -1),
                () -> data.renameChannel(CHANNEL_A, 0, new ManagedName("C")),
                () -> data.summarizeTunnelDeletion(TUNNEL_A),
                () -> data.summarizeChannelDeletion(CHANNEL_A),
                () -> data.deleteTunnel(TUNNEL_A, 0, 0),
                () -> data.deleteChannel(CHANNEL_A, 0, 0),
                () -> data.setDirectBinding(NODE_A, 0, CHANNEL_A, TransferDirection.INPUT, false, -1),
                () -> data.removeDirectBinding(NODE_A, 0, CHANNEL_A),
                () -> data.setDomainConfiguration(NODE_A, 0, TransferDirection.INPUT, false),
                () -> data.removeDomainConfiguration(NODE_A, 0),
                () -> data.findNode(NODE_A),
                () -> data.containsNodeName(new ManagedName("A")),
                () -> data.createNode(NODE_A, new ManagedName("A"), POS_A, NodeForm.BLOCK, Direction.DOWN),
                () -> data.updateNodePhysicalSnapshot(NODE_A, POS_A, NodeForm.PANEL, Direction.UP),
                () -> data.renameNode(NODE_A, 0, new ManagedName("B")),
                () -> data.setNodeEnabled(NODE_A, 0, false),
                () -> data.setNodeChunkLoadingRequested(NODE_A, 0, true),
                () -> data.setNodeMode(NODE_A, 0, NodeMode.DIRECT, false),
                () -> data.removeNode(NODE_A, POS_A),
                data::isDirty,
                data::setDirty,
                () -> data.setDirty(true),
                () -> data.save(new CompoundTag(), RegistryAccess.EMPTY),
                () -> data.save(file.toFile(), RegistryAccess.EMPTY)));
        assertFalse(data.isDirty());
        assertFalse(Files.exists(file));
    }

    @Test
    void ownerRejectsAllOffThreadStateAccessIncludingNoOpMutation(@TempDir Path directory) throws Exception {
        OwnerSavedData data = OwnerSavedData.load(OWNER, ownerTag(true));
        Path file = directory.resolve("owner.dat");
        assertOffThreadRejected(List.of(
                data::ownerId,
                data::defaultNetworkId,
                data::isDirty,
                data::setDirty,
                () -> data.setDirty(true),
                () -> data.setDefaultNetwork(NETWORK),
                () -> data.setDefaultNetwork(null),
                () -> data.save(new CompoundTag(), RegistryAccess.EMPTY),
                () -> data.save(file.toFile(), RegistryAccess.EMPTY)));
        assertFalse(data.isDirty());
        assertEquals(Optional.of(NETWORK), data.defaultNetworkId());
        assertFalse(Files.exists(file));
    }

    private static NetworkMetadata metadata() {
        return new NetworkMetadata(NETWORK, OWNER, NAME, 0, Set.of(ADMIN));
    }

    private static CompoundTag currentNetworkTag() {
        CompoundTag tag = networkTag();
        tag.putInt("schema_version", 9);
        tag.putLong("bucket_created_mask", 0);
        tag.put("recovery", new ListTag());
        return tag;
    }

    private static CompoundTag currentOwnerTag(boolean withDefault) {
        CompoundTag tag = ownerTag(withDefault);
        tag.putInt("schema_version", 3);
        tag.putLong("preset_library_revision", 0);
        tag.put("filter_presets", new ListTag());
        return tag;
    }

    private static CompoundTag networkTag() {
        CompoundTag tag = networkV3Tag();
        tag.putInt("schema_version", 5);
        tag.putLong("management_revision", 0);
        tag.putLong("last_tunnel_number", 0);
        tag.putLong("topology_revision", 0);
        tag.put("tunnels", new ListTag());
        tag.put("channels", new ListTag());
        tag.put("direct_bindings", new ListTag());
        tag.put("domain_configurations", new ListTag());
        return tag;
    }

    private static CompoundTag networkV3Tag() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("schema_version", 3);
        tag.putUUID("network_id", NETWORK);
        tag.putUUID("owner_id", OWNER);
        tag.putString("name", "主网络");
        tag.putLong("creation_order", 0);
        ListTag administrators = new ListTag();
        administrators.add(NbtUtils.createUUID(ADMIN));
        tag.put("administrators", administrators);
        tag.putLong("last_node_number", 0);
        tag.put("nodes", new ListTag());
        return tag;
    }

    private static CompoundTag topologyNetworkTag() {
        CompoundTag tag = networkTag();
        tag.putLong("last_node_number", 2);
        ListTag nodes = new ListTag();
        CompoundTag direct = nodeTag(NODE_A, 1, "Direct", POS_A, "block", "down");
        direct.putString("mode", "direct");
        nodes.add(direct);
        CompoundTag domain = nodeTag(NODE_B, 2, "Domain", POS_B, "panel", "north");
        domain.putString("mode", "domain");
        nodes.add(domain);
        tag.put("nodes", nodes);
        tag.putLong("last_tunnel_number", 2);
        tag.putLong("topology_revision", 9);

        ListTag tunnels = new ListTag();
        tunnels.add(tunnelTag(TUNNEL_A, 1, "Primary", 3, true, 2));
        tunnels.add(tunnelTag(TUNNEL_B, 2, "Secondary", 4, false, 1));
        tag.put("tunnels", tunnels);
        ListTag channels = new ListTag();
        channels.add(channelTag(CHANNEL_A, TUNNEL_A, 1, "Input", 5));
        channels.add(channelTag(CHANNEL_B, TUNNEL_A, 2, "Output", 6));
        channels.add(channelTag(new UUID(84, 3), TUNNEL_B, 1, "Default", 0));
        tag.put("channels", channels);
        ListTag bindings = new ListTag();
        bindings.add(directionTag(NODE_A, "channel_id", CHANNEL_A, "input"));
        tag.put("direct_bindings", bindings);
        ListTag domains = new ListTag();
        CompoundTag domainConfiguration = new CompoundTag();
        domainConfiguration.putUUID("node_id", NODE_B);
        domainConfiguration.putString("direction", "output");
        domains.add(domainConfiguration);
        tag.put("domain_configurations", domains);
        return tag;
    }

    private static CompoundTag tunnelTag(
            UUID id, long number, String name, long revision, boolean enabled, long lastChannelNumber) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("tunnel_id", id);
        tag.putLong("tunnel_number", number);
        tag.putString("name", name);
        tag.putLong("revision", revision);
        tag.putBoolean("enabled", enabled);
        tag.putLong("last_channel_number", lastChannelNumber);
        return tag;
    }

    private static CompoundTag channelTag(UUID id, UUID tunnelId, long number, String name, long revision) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("channel_id", id);
        tag.putUUID("tunnel_id", tunnelId);
        tag.putLong("channel_number", number);
        tag.putString("name", name);
        tag.putLong("revision", revision);
        return tag;
    }

    private static CompoundTag directionTag(UUID nodeId, String targetKey, UUID targetId, String direction) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("node_id", nodeId);
        tag.putUUID(targetKey, targetId);
        tag.putString("direction", direction);
        return tag;
    }

    private static void assertRejectedV4(CompoundTag input, String description) {
        input.putInt("schema_version", 4);
        input.remove("management_revision");
        CompoundTag before = input.copy();
        assertThrows(IllegalArgumentException.class, () -> NetworkSavedData.load(NETWORK, input), description);
        assertEquals(before, input, description);
    }

    private static CompoundTag nodeTag(
            UUID id, long number, String name, GlobalPos position, String form, String facing) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("node_id", id);
        tag.putLong("node_number", number);
        tag.putString("name", name);
        tag.putString("dimension", position.dimension().location().toString());
        tag.putInt("x", position.pos().getX());
        tag.putInt("y", position.pos().getY());
        tag.putInt("z", position.pos().getZ());
        tag.putString("form", form);
        tag.putString("facing", facing);
        tag.putLong("revision", 0);
        tag.putBoolean("enabled", true);
        tag.putBoolean("chunk_loading_requested", false);
        tag.putString("mode", "unconfigured");
        return tag;
    }

    private static void assertRejectedNodeList(List<CompoundTag> nodeTags, long lastNumber, String description) {
        CompoundTag input = networkTag();
        input.putLong("last_node_number", lastNumber);
        ListTag nodes = new ListTag();
        nodeTags.forEach(nodes::add);
        input.put("nodes", nodes);
        assertThrows(IllegalArgumentException.class, () -> NetworkSavedData.load(NETWORK, input), description);
    }

    private static void assertModeLoaded(String encodedMode, NodeMode expectedMode) {
        CompoundTag node = nodeTag(NODE_A, 1, "Node A", POS_A, "block", "down");
        node.putLong("revision", 7);
        node.putBoolean("enabled", false);
        node.putBoolean("chunk_loading_requested", true);
        node.putString("mode", encodedMode);
        CompoundTag input = networkTag();
        input.putLong("last_node_number", 1);
        ListTag nodes = new ListTag();
        nodes.add(node);
        input.put("nodes", nodes);

        NetworkNodeRecord loaded =
                NetworkSavedData.load(NETWORK, input).findNode(NODE_A).orElseThrow();
        assertEquals(7, loaded.revision());
        assertFalse(loaded.enabled());
        assertTrue(loaded.chunkLoadingRequested());
        assertEquals(expectedMode, loaded.mode());
    }

    private static CompoundTag ownerTag(boolean withDefault) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("schema_version", 1);
        tag.putUUID("owner_id", OWNER);
        if (withDefault) {
            tag.putUUID("default_network_id", NETWORK);
        }
        return tag;
    }

    private static ListTag emptyListOfType(int type) throws IOException {
        try (DataInputStream input =
                new DataInputStream(new ByteArrayInputStream(new byte[] {(byte) type, 0, 0, 0, 0}))) {
            return ListTag.TYPE.load(input, NbtAccounter.create(1024));
        }
    }

    private static void assertOffThreadRejected(List<Runnable> accesses) throws Exception {
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            for (Runnable access : accesses) {
                Future<?> future = executor.submit(access);
                ExecutionException failure = assertThrows(ExecutionException.class, future::get);
                assertInstanceOf(IllegalStateException.class, failure.getCause());
            }
        }
    }
}
