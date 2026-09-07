// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.network;

import com.mojang.authlib.GameProfile;
import io.github.loongin.omniresonance.bootstrap.OmniResonanceMod;
import io.github.loongin.omniresonance.config.ServerConfig;
import io.github.loongin.omniresonance.config.ServerSettings;
import io.github.loongin.omniresonance.node.NetworkNodeDirectory;
import io.github.loongin.omniresonance.node.NetworkNodeRecord;
import io.github.loongin.omniresonance.node.NodeForm;
import io.github.loongin.omniresonance.node.NodeMode;
import io.github.loongin.omniresonance.persistence.NetworkSavedData;
import io.github.loongin.omniresonance.persistence.SavedNetworkRepository;
import io.github.loongin.omniresonance.security.EditLockTable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.common.IOUtilities;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real-player permission and shared-lock contracts for tunnel, channel and node topology management. */
@GameTestHolder(OmniResonanceMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NetworkTopologyManagementGameTests {
    private static final UUID NETWORK = new UUID(330, 1);
    private static final UUID OWNER = new UUID(330, 2);
    private static final UUID ADMIN = new UUID(330, 3);
    private static final UUID STRANGER = new UUID(330, 4);
    private static final UUID OP = new UUID(330, 5);
    private static final UUID NODE_A = new UUID(331, 1);
    private static final UUID NODE_B = new UUID(331, 2);
    private static final UUID TUNNEL = new UUID(332, 1);
    private static final UUID TUNNEL_SECOND = new UUID(332, 2);
    private static final UUID CHANNEL = new UUID(333, 1);
    private static final UUID TARGET_CHANNEL = new UUID(335, 1);

    private NetworkTopologyManagementGameTests() {}

    /** Parent collection locks serialize creation while roles and quotas are revalidated on commit. */
    @GameTest(template = "bootstrap")
    public static void collectionEditsEnforceRolesLocksAndQuota(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper, new ServerSettings(32, 1, 1, 1))) {
            ServerPlayer owner = player(helper, OWNER);
            ServerPlayer administrator = player(helper, ADMIN);
            ServerPlayer stranger = player(helper, STRANGER);
            ServerPlayer operator = operator(helper);
            helper.assertTrue(operator.hasPermissions(4), "OP fixture lacks permission level");

            NetworkTopologyService.Edit ownerEdit = fixture.service.acquireTunnelCollection(owner, NETWORK);
            rejected(
                    helper,
                    NetworkTopologyService.Reason.LOCKED,
                    () -> fixture.service.acquireTunnelCollection(administrator, NETWORK));
            rejected(
                    helper,
                    NetworkTopologyService.Reason.NO_ACCESS,
                    () -> fixture.service.acquireTunnelCollection(stranger, NETWORK));
            rejected(
                    helper,
                    NetworkTopologyService.Reason.NO_ACCESS,
                    () -> fixture.service.acquireTunnelCollection(operator, NETWORK));

            NetworkSavedData.TunnelCreation creation = fixture.service.createTunnel(
                    owner, ownerEdit, new ManagedName("Main"), new ManagedName("Channel 1"));
            NetworkTunnelRecord tunnel = creation.tunnel();
            helper.assertTrue(tunnel.tunnelNumber() == 1 && tunnel.enabled(), "Wrong first tunnel");
            NetworkTopologyService.Edit quotaEdit = fixture.service.acquireTunnelCollection(administrator, NETWORK);
            rejected(
                    helper,
                    NetworkTopologyService.Reason.QUOTA_REACHED,
                    () -> fixture.service.createTunnel(
                            administrator, quotaEdit, new ManagedName("Second"), new ManagedName("Channel 1")));
            helper.assertTrue(fixture.data.lastTunnelNumber() == 1, "Quota rejection consumed a tunnel number");
            fixture.service.cancel(administrator, quotaEdit);

            helper.assertTrue(creation.initialChannel().channelNumber() == 1, "Wrong mandatory initial channel number");
            helper.succeed();
        }
    }

    /** Zero channel quota keeps the mandatory first channel while blocking extras and final-channel deletion. */
    @GameTest(template = "bootstrap")
    public static void mandatoryChannelSurvivesZeroQuotaAndDeletion(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper, new ServerSettings(32, 4, 0, 1))) {
            ServerPlayer owner = player(helper, OWNER);
            ServerPlayer administrator = player(helper, ADMIN);
            ServerPlayer stranger = player(helper, STRANGER);

            helper.assertTrue(
                    fixture.service
                            .suggestedTunnelName(owner, NETWORK, new ManagedNamePrefix("Tunnel"))
                            .value()
                            .equals("Tunnel 1"),
                    "Wrong smallest tunnel suggestion");
            rejected(
                    helper,
                    NetworkTopologyService.Reason.NO_ACCESS,
                    () -> fixture.service.suggestedTunnelName(stranger, NETWORK, new ManagedNamePrefix("Tunnel")));
            NetworkTopologyService.Edit tunnelEdit = fixture.service.acquireTunnelCollection(owner, NETWORK);
            NetworkSavedData.TunnelCreation creation = fixture.service.createTunnel(
                    owner, tunnelEdit, new ManagedName("Tunnel 1"), new ManagedName("Channel 1"));
            helper.assertTrue(
                    fixture.data.channelCount(creation.tunnel().tunnelId()) == 1,
                    "Mandatory channel was not created under zero quota");
            helper.assertTrue(
                    fixture.service
                            .suggestedChannelName(
                                    administrator,
                                    NETWORK,
                                    creation.tunnel().tunnelId(),
                                    new ManagedNamePrefix("Channel"))
                            .value()
                            .equals("Channel 2"),
                    "Wrong smallest channel suggestion");

            NetworkTopologyService.Edit channelEdit = fixture.service.acquireChannelCollection(
                    owner, NETWORK, creation.tunnel().tunnelId());
            rejected(
                    helper,
                    NetworkTopologyService.Reason.QUOTA_REACHED,
                    () -> fixture.service.createChannel(owner, channelEdit, new ManagedName("Extra")));
            fixture.service.cancel(owner, channelEdit);
            rejected(
                    helper,
                    NetworkTopologyService.Reason.LAST_CHANNEL,
                    () -> fixture.service.beginChannelDeletion(
                            owner, NETWORK, creation.initialChannel().channelId()));
            helper.assertTrue(
                    fixture.data
                            .findChannel(creation.initialChannel().channelId())
                            .isPresent(),
                    "Last-channel rejection changed authority");
            helper.succeed();
        }
    }

    /** Edit leases expire at 200 gt, heartbeat extends them, and exact object locks do not block unrelated objects. */
    @GameTest(template = "bootstrap")
    public static void objectLocksHeartbeatAndExpireAtExactTicks(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper, ServerSettings.defaults())) {
            fixture.seedTunnelAndChannel();
            ServerPlayer owner = player(helper, OWNER);
            ServerPlayer administrator = player(helper, ADMIN);

            NetworkTopologyService.Edit tunnelEdit = fixture.service.acquireTunnel(owner, NETWORK, TUNNEL);
            NetworkTopologyService.Edit channelEdit = fixture.service.acquireChannel(owner, NETWORK, CHANNEL);
            rejected(
                    helper,
                    NetworkTopologyService.Reason.LOCKED,
                    () -> fixture.service.acquireTunnel(administrator, NETWORK, TUNNEL));
            fixture.service.cancel(owner, channelEdit);
            for (int tick = 0; tick < 199; tick++) {
                fixture.service.tick();
            }
            rejected(
                    helper,
                    NetworkTopologyService.Reason.LOCKED,
                    () -> fixture.service.acquireTunnel(administrator, NETWORK, TUNNEL));
            fixture.service.tick();
            NetworkTopologyService.Edit replacement = fixture.service.acquireTunnel(administrator, NETWORK, TUNNEL);
            fixture.service.cancel(administrator, replacement);

            NetworkTopologyService.Edit heartbeat = fixture.service.acquireTunnel(owner, NETWORK, TUNNEL);
            for (int tick = 0; tick < 100; tick++) {
                fixture.service.tick();
            }
            fixture.service.heartbeat(owner, heartbeat);
            for (int tick = 0; tick < 100; tick++) {
                fixture.service.tick();
            }
            rejected(
                    helper,
                    NetworkTopologyService.Reason.LOCKED,
                    () -> fixture.service.acquireTunnel(administrator, NETWORK, TUNNEL));
            fixture.service.cancel(owner, heartbeat);
            helper.succeed();
        }
    }

    /** Channel cascade refuses a live affected-node edit and later updates SavedData plus the derived directory. */
    @GameTest(template = "bootstrap")
    public static void channelDeletionChecksAffectedNodeLocksAndCommitsAtomically(GameTestHelper helper)
            throws IOException {
        try (Fixture fixture = new Fixture(helper, ServerSettings.defaults())) {
            fixture.seedTunnelAndChannel();
            fixture.seedDirectBindings();
            ServerPlayer owner = player(helper, OWNER);
            ServerPlayer administrator = player(helper, ADMIN);

            NetworkTopologyService.Edit nodeEdit = fixture.service.acquireNode(owner, NETWORK, NODE_A);
            NetworkTopologyService.DeletionEdit deletion =
                    fixture.service.beginChannelDeletion(administrator, NETWORK, CHANNEL);
            helper.assertTrue(deletion.impact().bindingCount() == 2, "Wrong deletion impact");
            rejected(
                    helper,
                    NetworkTopologyService.Reason.LOCKED,
                    () -> fixture.service.confirmChannelDeletion(administrator, deletion));
            helper.assertTrue(fixture.data.findChannel(CHANNEL).isPresent(), "Locked delete removed channel");
            fixture.service.cancel(owner, nodeEdit);

            List<NetworkNodeRecord> changed = fixture.service.confirmChannelDeletion(administrator, deletion);
            helper.assertTrue(changed.size() == 2, "Cascade did not update both nodes");
            helper.assertTrue(fixture.data.findChannel(CHANNEL).isEmpty(), "Channel survived confirmed delete");
            for (NetworkNodeRecord node : changed) {
                NetworkNodeDirectory.Lookup lookup = fixture.nodes.byId(node.nodeId());
                helper.assertTrue(
                        lookup.status() == NetworkNodeDirectory.Status.UNIQUE
                                && lookup.entry().orElseThrow().record().equals(node),
                        "Derived node directory did not receive cascade revision");
            }
            helper.succeed();
        }
    }

    /** A direct tunnel switch owns the node lock and commits all old bindings under one revision. */
    @GameTest(template = "bootstrap")
    public static void directTunnelSwitchLocksPreviewsAndCommitsAtomically(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper, ServerSettings.defaults())) {
            fixture.seedTunnelSwitchBindings();
            fixture.data.setDirty(false);
            ServerPlayer owner = player(helper, OWNER);
            ServerPlayer administrator = player(helper, ADMIN);
            NetworkNodeRecord before = fixture.data.findNode(NODE_A).orElseThrow();
            long topologyRevision = fixture.data.topologyRevision();

            NetworkTopologyService.Edit ordinaryBinding = fixture.service.acquireNode(owner, NETWORK, NODE_A);
            rejected(
                    helper,
                    NetworkTopologyService.Reason.TUNNEL_SWITCH_REQUIRED,
                    () -> fixture.service.setDirectBinding(
                            owner, ordinaryBinding, TARGET_CHANNEL, TransferDirection.OUTPUT, false));
            fixture.service.cancel(owner, ordinaryBinding);
            helper.assertTrue(
                    fixture.data.directBindings(NODE_A).size() == 3, "Ordinary cross-tunnel save changed bindings");

            NetworkTopologyService.TunnelSwitchEdit switchEdit =
                    fixture.service.requestTunnelSwitch(owner, NETWORK, NODE_A, TUNNEL_SECOND);

            helper.assertTrue(switchEdit.removedBindingCount() == 3, "Wrong tunnel-switch binding count");
            helper.assertTrue(
                    switchEdit.targetTunnelId().equals(TUNNEL_SECOND)
                            && switchEdit.targetName().value().equals("Target"),
                    "Wrong tunnel-switch target");
            helper.assertTrue(!fixture.data.isDirty(), "Tunnel-switch preview dirtied authority");
            rejected(
                    helper,
                    NetworkTopologyService.Reason.LOCKED,
                    () -> fixture.service.requestTunnelSwitch(administrator, NETWORK, NODE_A, TUNNEL_SECOND));

            NetworkNodeRecord switched = fixture.service.confirmTunnelSwitch(owner, switchEdit);

            helper.assertTrue(switched.revision() == before.revision() + 1, "Node revision changed more than once");
            helper.assertTrue(
                    fixture.data.topologyRevision() == topologyRevision + 1,
                    "Topology revision changed more than once");
            helper.assertTrue(fixture.data.directBindings(NODE_A).isEmpty(), "Old direct bindings survived switch");
            helper.assertTrue(fixture.data.directTunnelId(NODE_A).isEmpty(), "Target tunnel was persisted early");
            NetworkNodeDirectory.Lookup lookup = fixture.nodes.byId(NODE_A);
            helper.assertTrue(
                    lookup.status() == NetworkNodeDirectory.Status.UNIQUE
                            && lookup.entry().orElseThrow().record().equals(switched),
                    "Derived node directory missed tunnel-switch revision");
            helper.succeed();
        }
    }

    /** Disabled targets and stale switch summaries preserve every old direct binding. */
    @GameTest(template = "bootstrap")
    public static void rejectedDirectTunnelSwitchPreservesOldBindings(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper, ServerSettings.defaults())) {
            fixture.seedTunnelSwitchBindings();
            ServerPlayer owner = player(helper, OWNER);
            List<DirectNodeBinding> before = fixture.data.directBindings(NODE_A);
            NetworkTunnelRecord target = fixture.data.findTunnel(TUNNEL_SECOND).orElseThrow();
            fixture.data
                    .setTunnelEnabled(TUNNEL_SECOND, target.revision(), false)
                    .orElseThrow();
            fixture.data.setDirty(false);

            rejected(
                    helper,
                    NetworkTopologyService.Reason.TUNNEL_DISABLED,
                    () -> fixture.service.requestTunnelSwitch(owner, NETWORK, NODE_A, TUNNEL_SECOND));
            helper.assertTrue(fixture.data.directBindings(NODE_A).equals(before), "Disabled target removed bindings");
            helper.assertTrue(!fixture.data.isDirty(), "Disabled target dirtied authority");

            NetworkTunnelRecord disabled =
                    fixture.data.findTunnel(TUNNEL_SECOND).orElseThrow();
            fixture.data
                    .setTunnelEnabled(TUNNEL_SECOND, disabled.revision(), true)
                    .orElseThrow();
            NetworkTopologyService.TunnelSwitchEdit switchEdit =
                    fixture.service.requestTunnelSwitch(owner, NETWORK, NODE_A, TUNNEL_SECOND);
            NetworkTunnelRecord enabled = fixture.data.findTunnel(TUNNEL_SECOND).orElseThrow();
            fixture.data
                    .renameTunnel(TUNNEL_SECOND, enabled.revision(), new ManagedName("Changed"))
                    .orElseThrow();
            fixture.data.setDirty(false);

            rejected(
                    helper,
                    NetworkTopologyService.Reason.STALE_REVISION,
                    () -> fixture.service.confirmTunnelSwitch(owner, switchEdit));
            helper.assertTrue(fixture.data.directBindings(NODE_A).equals(before), "Stale switch removed bindings");
            helper.assertTrue(!fixture.data.isDirty(), "Stale switch dirtied authority");
            helper.succeed();
        }
    }

    private static ServerPlayer player(GameTestHelper helper, UUID id) {
        return new FakePlayer(helper.getLevel(), new GameProfile(id, "Topology" + id.getLeastSignificantBits()));
    }

    private static ServerPlayer operator(GameTestHelper helper) {
        return new OperatorFakePlayer(helper, new GameProfile(OP, "TopologyOperator"));
    }

    private static void rejected(GameTestHelper helper, NetworkTopologyService.Reason expected, Runnable operation) {
        try {
            operation.run();
        } catch (NetworkTopologyService.Rejected rejected) {
            helper.assertTrue(rejected.reason() == expected, "Expected " + expected + ", got " + rejected.reason());
            return;
        }
        helper.fail("Expected topology rejection " + expected);
    }

    private static final class OperatorFakePlayer extends FakePlayer {
        private OperatorFakePlayer(GameTestHelper helper, GameProfile profile) {
            super(helper.getLevel(), profile);
        }

        @Override
        public boolean hasPermissions(int level) {
            return true;
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final Path path;
        private final SavedNetworkRepository repository;
        private final NetworkSavedData data;
        private final NetworkNodeDirectory nodes;
        private final EditLockTable locks;
        private final NetworkTopologyService service;

        private Fixture(GameTestHelper helper, ServerSettings settings) throws IOException {
            path = Files.createTempDirectory("omniresonance-topology-management-test-");
            DimensionDataStorage storage = new DimensionDataStorage(
                    path.toFile(), DataFixers.getDataFixer(), helper.getLevel().registryAccess());
            repository = new SavedNetworkRepository(storage, path);
            NetworkMetadata metadata =
                    new NetworkMetadata(NETWORK, OWNER, new ManagedName("Topology"), 0, Set.of(ADMIN));
            repository.createNetwork(metadata);
            data = repository.findLoadedNetwork(NETWORK).orElseThrow();
            seedNode(NODE_A, "Node A", 1);
            seedNode(NODE_B, "Node B", 2);
            NetworkDirectory networks = new NetworkDirectory(List.of(metadata));
            List<NetworkNodeDirectory.Entry> entries = new ArrayList<>();
            for (NetworkNodeRecord node : data.nodes()) {
                entries.add(new NetworkNodeDirectory.Entry(NETWORK, node));
            }
            nodes = new NetworkNodeDirectory(entries);
            locks = new EditLockTable();
            ArrayDeque<UUID> ids = new ArrayDeque<>(List.of(TUNNEL, CHANNEL, new UUID(334, 1), new UUID(334, 2)));
            service = new NetworkTopologyService(
                    helper.getLevel().getServer(),
                    networks,
                    repository,
                    nodes,
                    locks,
                    new ServerConfig.State(1, 1, true, settings),
                    ids::removeFirst);
        }

        private void seedNode(UUID id, String name, int x) {
            NetworkNodeRecord node = data.createNode(
                    id,
                    new ManagedName(name),
                    GlobalPos.of(Level.OVERWORLD, new BlockPos(x, 64, 0)),
                    NodeForm.BLOCK,
                    Direction.DOWN);
            data.setNodeMode(id, node.revision(), NodeMode.DIRECT, false).orElseThrow();
        }

        private void seedTunnelAndChannel() {
            if (data.findTunnel(TUNNEL).isEmpty()) {
                NetworkSavedData.TunnelCreation creation =
                        data.createTunnel(TUNNEL, new ManagedName("Main"), CHANNEL, new ManagedName("Items"), -1);
                data.createChannel(
                        TUNNEL, creation.tunnel().revision(), new UUID(333, 2), new ManagedName("Reserve"), -1);
            }
        }

        private void seedDirectBindings() {
            NetworkNodeRecord first = data.findNode(NODE_A).orElseThrow();
            NetworkNodeRecord updatedA =
                    data.setDirectBinding(NODE_A, first.revision(), CHANNEL, TransferDirection.INPUT, false, -1);
            nodes.update(nodes.byId(NODE_A).entry().orElseThrow(), new NetworkNodeDirectory.Entry(NETWORK, updatedA));
            NetworkNodeRecord second = data.findNode(NODE_B).orElseThrow();
            NetworkNodeRecord updatedB =
                    data.setDirectBinding(NODE_B, second.revision(), CHANNEL, TransferDirection.OUTPUT, false, -1);
            nodes.update(nodes.byId(NODE_B).entry().orElseThrow(), new NetworkNodeDirectory.Entry(NETWORK, updatedB));
        }

        private void seedTunnelSwitchBindings() {
            NetworkSavedData.TunnelCreation source =
                    data.createTunnel(TUNNEL, new ManagedName("Source"), CHANNEL, new ManagedName("A"), -1);
            data.createChannel(TUNNEL, source.tunnel().revision(), new UUID(333, 2), new ManagedName("B"), -1);
            data.createChannel(
                    TUNNEL,
                    data.findTunnel(TUNNEL).orElseThrow().revision(),
                    new UUID(333, 3),
                    new ManagedName("C"),
                    -1);
            data.createTunnel(
                    TUNNEL_SECOND, new ManagedName("Target"), TARGET_CHANNEL, new ManagedName("Target channel"), -1);
            NetworkNodeDirectory.Entry original = nodes.byId(NODE_A).entry().orElseThrow();
            NetworkNodeRecord node = original.record();
            for (UUID channelId : List.of(CHANNEL, new UUID(333, 2), new UUID(333, 3))) {
                node = data.setDirectBinding(NODE_A, node.revision(), channelId, TransferDirection.INPUT, false, -1);
            }
            nodes.update(original, new NetworkNodeDirectory.Entry(NETWORK, node));
        }

        @Override
        public void close() throws IOException {
            service.close();
            locks.clear();
            IOUtilities.waitUntilIOWorkerComplete();
            try (var files = Files.walk(path)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(file);
                }
            }
        }
    }
}
