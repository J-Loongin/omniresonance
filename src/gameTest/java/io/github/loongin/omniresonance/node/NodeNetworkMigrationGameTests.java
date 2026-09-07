// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.node;

import com.mojang.authlib.GameProfile;
import io.github.loongin.omniresonance.bootstrap.OmniResonanceMod;
import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.network.NetworkChannelRecord;
import io.github.loongin.omniresonance.network.NetworkDirectory;
import io.github.loongin.omniresonance.network.NetworkMetadata;
import io.github.loongin.omniresonance.network.TransferDirection;
import io.github.loongin.omniresonance.persistence.NetworkSavedData;
import io.github.loongin.omniresonance.persistence.SavedNetworkRepository;
import io.github.loongin.omniresonance.registry.ModBlocks;
import io.github.loongin.omniresonance.security.EditLockTable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.common.IOUtilities;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Cross-network node move contracts over real physical nodes, two SavedData shards and the derived directory. */
@GameTestHolder(OmniResonanceMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NodeNetworkMigrationGameTests {
    private static final UUID SOURCE = new UUID(340, 1);
    private static final UUID TARGET = new UUID(340, 2);
    private static final UUID INACCESSIBLE = new UUID(340, 3);
    private static final UUID OWNER = new UUID(341, 1);
    private static final UUID TARGET_OWNER = new UUID(341, 2);
    private static final UUID STRANGER = new UUID(341, 3);
    private static final UUID NODE = new UUID(342, 1);
    private static final UUID EXISTING = new UUID(342, 2);
    private static final UUID TUNNEL = new UUID(343, 1);
    private static final UUID CHANNEL = new UUID(343, 2);

    private NodeNetworkMigrationGameTests() {}

    /** A successful move preserves public state, clears source configuration and assigns target numbering. */
    @GameTest(template = "bootstrap")
    public static void successfulMoveUpdatesBothShardsDirectoryAndKeepsPhysicalLinked(GameTestHelper helper)
            throws IOException {
        try (Fixture fixture = new Fixture(helper)) {
            BlockPos pos = helper.absolutePos(new BlockPos(2, 3, 2));
            ResonanceNodeBlockEntity entity = placeBlank(helper, pos, NODE);
            NetworkNodeRecord linked = fixture.authority.link(SOURCE, entity, new ManagedName("Migrating"));
            linked = fixture.configureSource(linked);
            fixture.addTargetNode(new ManagedName("Existing"));
            ServerPlayer owner = player(helper, OWNER, pos);

            NodeManagementService.NetworkMoveEdit edit =
                    fixture.management.beginNetworkMove(owner, SOURCE, NODE, TARGET);
            NetworkNodeRecord moved = fixture.management.moveNetwork(owner, edit, new ManagedName("Migrating"));

            helper.assertTrue(fixture.source().findNode(NODE).isEmpty(), "Source retained moved node");
            helper.assertTrue(fixture.source().directBindings(NODE).isEmpty(), "Source retained moved binding");
            helper.assertTrue(moved.nodeNumber() == 2, "Target did not allocate its next node number");
            helper.assertTrue(moved.nodeId().equals(NODE), "Move changed node UUID");
            helper.assertTrue(moved.name().value().equals("Migrating"), "Move changed node name");
            helper.assertTrue(moved.enabled() && moved.chunkLoadingRequested(), "Move lost public switches");
            helper.assertTrue(moved.mode() == NodeMode.UNCONFIGURED, "Move retained old mode");
            helper.assertTrue(fixture.target().findNode(NODE).orElseThrow().equals(moved), "Target record differs");
            NetworkNodeDirectory.Entry indexed =
                    fixture.nodes.byId(NODE).entry().orElseThrow();
            helper.assertTrue(
                    indexed.networkId().equals(TARGET) && indexed.record().equals(moved), "Directory stale");
            helper.assertTrue(
                    entity.state().orElseThrow().nodeId().equals(NODE)
                            && entity.state().orElseThrow().linkState() == NodeLinkState.LINKED,
                    "Physical node identity or generic linked hint changed");

            NetworkSavedData reloadedSource =
                    NetworkSavedData.load(SOURCE, fixture.source().save(new CompoundTag(), RegistryAccess.EMPTY));
            NetworkSavedData reloadedTarget =
                    NetworkSavedData.load(TARGET, fixture.target().save(new CompoundTag(), RegistryAccess.EMPTY));
            helper.assertTrue(reloadedSource.findNode(NODE).isEmpty(), "Source move did not survive reload");
            helper.assertTrue(
                    reloadedTarget.findNode(NODE).orElseThrow().equals(moved), "Target move did not survive reload");
            helper.succeed();
        }
    }

    /** Target name conflict changes nothing and retains the exact edit for a corrected retry. */
    @GameTest(template = "bootstrap")
    public static void nameConflictRetainsMoveEditWithoutPartialMutation(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper)) {
            BlockPos pos = helper.absolutePos(new BlockPos(2, 3, 2));
            ResonanceNodeBlockEntity entity = placeBlank(helper, pos, NODE);
            fixture.authority.link(SOURCE, entity, new ManagedName("Node"));
            fixture.addTargetNode(new ManagedName("Node"));
            ServerPlayer owner = player(helper, OWNER, pos);
            CompoundTag sourceBefore = fixture.source().save(new CompoundTag(), RegistryAccess.EMPTY);
            CompoundTag targetBefore = fixture.target().save(new CompoundTag(), RegistryAccess.EMPTY);
            NetworkNodeDirectory.Entry directoryBefore =
                    fixture.nodes.byId(NODE).entry().orElseThrow();

            NodeManagementService.NetworkMoveEdit edit =
                    fixture.management.beginNetworkMove(owner, SOURCE, NODE, TARGET);
            rejected(
                    helper,
                    NodeManagementService.Reason.NAME_CONFLICT,
                    () -> fixture.management.moveNetwork(owner, edit, new ManagedName("node")));
            helper.assertTrue(
                    sourceBefore.equals(fixture.source().save(new CompoundTag(), RegistryAccess.EMPTY)),
                    "Name conflict changed source");
            helper.assertTrue(
                    targetBefore.equals(fixture.target().save(new CompoundTag(), RegistryAccess.EMPTY)),
                    "Name conflict changed target");
            helper.assertTrue(
                    fixture.nodes.byId(NODE).entry().orElseThrow().equals(directoryBefore),
                    "Name conflict changed directory");
            helper.assertTrue(entity.state().orElseThrow().linkState() == NodeLinkState.LINKED, "Hint changed");

            NetworkNodeRecord moved = fixture.management.moveNetwork(owner, edit, new ManagedName("Corrected"));
            helper.assertTrue(moved.name().value().equals("Corrected"), "Corrected retry failed");
            helper.succeed();
        }
    }

    /** Target permission and stale source revisions reject before either network or the directory can change. */
    @GameTest(template = "bootstrap")
    public static void permissionAndStaleRevisionFailuresLeaveAuthorityUnchanged(GameTestHelper helper)
            throws IOException {
        try (Fixture fixture = new Fixture(helper)) {
            BlockPos pos = helper.absolutePos(new BlockPos(2, 3, 2));
            ResonanceNodeBlockEntity entity = placeBlank(helper, pos, NODE);
            fixture.authority.link(SOURCE, entity, new ManagedName("Node"));
            ServerPlayer owner = player(helper, OWNER, pos);
            rejected(
                    helper,
                    NodeManagementService.Reason.NO_ACCESS,
                    () -> fixture.management.beginNetworkMove(owner, SOURCE, NODE, INACCESSIBLE));

            NodeManagementService.NetworkMoveEdit edit =
                    fixture.management.beginNetworkMove(owner, SOURCE, NODE, TARGET);
            NetworkNodeRecord current = fixture.source().findNode(NODE).orElseThrow();
            NetworkNodeRecord renamed = fixture.source()
                    .renameNode(NODE, current.revision(), new ManagedName("Externally changed"))
                    .orElseThrow();
            fixture.nodes.update(
                    fixture.nodes.byId(NODE).entry().orElseThrow(), new NetworkNodeDirectory.Entry(SOURCE, renamed));
            CompoundTag sourceBefore = fixture.source().save(new CompoundTag(), RegistryAccess.EMPTY);
            CompoundTag targetBefore = fixture.target().save(new CompoundTag(), RegistryAccess.EMPTY);

            rejected(
                    helper,
                    NodeManagementService.Reason.STALE_REVISION,
                    () -> fixture.management.moveNetwork(owner, edit, new ManagedName("Moved")));
            helper.assertTrue(
                    sourceBefore.equals(fixture.source().save(new CompoundTag(), RegistryAccess.EMPTY)),
                    "Stale move changed source");
            helper.assertTrue(
                    targetBefore.equals(fixture.target().save(new CompoundTag(), RegistryAccess.EMPTY)),
                    "Stale move changed target");
            helper.assertTrue(
                    fixture.nodes.byId(NODE).entry().orElseThrow().networkId().equals(SOURCE),
                    "Stale move changed directory network");
            helper.succeed();
        }
    }

    /** A physical mismatch rejects the move without using migration as an implicit reconciliation mutation. */
    @GameTest(template = "bootstrap")
    public static void physicalMismatchDoesNotPartiallyReconcileDuringMove(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper)) {
            BlockPos pos = helper.absolutePos(new BlockPos(2, 3, 2));
            ResonanceNodeBlockEntity entity = placeBlank(helper, pos, NODE);
            fixture.authority.link(SOURCE, entity, new ManagedName("Node"));
            ServerPlayer owner = player(helper, OWNER, pos);
            NodeManagementService.NetworkMoveEdit edit =
                    fixture.management.beginNetworkMove(owner, SOURCE, NODE, TARGET);
            CompoundTag sourceBefore = fixture.source().save(new CompoundTag(), RegistryAccess.EMPTY);
            helper.getLevel()
                    .setBlockAndUpdate(
                            pos, entity.getBlockState().setValue(AbstractResonanceNodeBlock.FACING, Direction.UP));

            rejected(
                    helper,
                    NodeManagementService.Reason.STALE_REVISION,
                    () -> fixture.management.moveNetwork(owner, edit, new ManagedName("Moved")));

            helper.assertTrue(
                    sourceBefore.equals(fixture.source().save(new CompoundTag(), RegistryAccess.EMPTY)),
                    "Failed move reconciled or otherwise changed source authority");
            helper.assertTrue(fixture.target().findNode(NODE).isEmpty(), "Failed move changed target");
            helper.assertTrue(
                    fixture.nodes.byId(NODE).entry().orElseThrow().networkId().equals(SOURCE),
                    "Failed move changed directory");
            helper.succeed();
        }
    }

    private static ResonanceNodeBlockEntity placeBlank(GameTestHelper helper, BlockPos position, UUID nodeId) {
        BlockState state = ModBlocks.RESONANCE_TRANSFER_NODE
                .get()
                .defaultBlockState()
                .setValue(AbstractResonanceNodeBlock.FACING, Direction.DOWN);
        helper.getLevel().setBlockAndUpdate(position, state);
        ResonanceNodeBlockEntity entity =
                (ResonanceNodeBlockEntity) helper.getLevel().getBlockEntity(position);
        CompoundTag tag = new CompoundTag();
        new NodePersistentState.Valid(nodeId, NodeLinkState.BLANK).writeOwnedFields(tag);
        entity.loadCustomOnly(tag, helper.getLevel().registryAccess());
        return entity;
    }

    private static ServerPlayer player(GameTestHelper helper, UUID id, BlockPos position) {
        FakePlayer player =
                new FakePlayer(helper.getLevel(), new GameProfile(id, "Move" + id.getLeastSignificantBits()));
        player.setPos(position.getX() + 0.5, position.getY() + 0.5, position.getZ() + 0.5);
        return player;
    }

    private static void rejected(GameTestHelper helper, NodeManagementService.Reason expected, Runnable operation) {
        try {
            operation.run();
        } catch (NodeManagementService.Rejected rejected) {
            helper.assertTrue(rejected.reason() == expected, "Expected " + expected + ", got " + rejected.reason());
            return;
        }
        helper.fail("Expected node move rejection " + expected);
    }

    private static final class Fixture implements AutoCloseable {
        private final Path path;
        private final SavedNetworkRepository repository;
        private final NetworkNodeDirectory nodes = new NetworkNodeDirectory(List.of());
        private final NodeAuthorityService authority;
        private final NodeManagementService management;

        private Fixture(GameTestHelper helper) throws IOException {
            path = Files.createTempDirectory("omniresonance-node-network-move-test-");
            DimensionDataStorage storage = new DimensionDataStorage(
                    path.toFile(), DataFixers.getDataFixer(), helper.getLevel().registryAccess());
            repository = new SavedNetworkRepository(storage, path);
            NetworkMetadata source = new NetworkMetadata(SOURCE, OWNER, new ManagedName("Source"), 0, Set.of());
            NetworkMetadata target =
                    new NetworkMetadata(TARGET, TARGET_OWNER, new ManagedName("Target"), 0, Set.of(OWNER));
            NetworkMetadata inaccessible =
                    new NetworkMetadata(INACCESSIBLE, STRANGER, new ManagedName("Private"), 0, Set.of());
            repository.createNetwork(source);
            repository.createNetwork(target);
            repository.createNetwork(inaccessible);
            NetworkDirectory networks = new NetworkDirectory(List.of(source, target, inaccessible));
            authority = new NodeAuthorityService(helper.getLevel().getServer(), repository, nodes, UUID::randomUUID);
            management = new NodeManagementService(
                    helper.getLevel().getServer(), networks, repository, nodes, authority, new EditLockTable());
        }

        private NetworkNodeRecord configureSource(NetworkNodeRecord linked) {
            NetworkSavedData source = source();
            NetworkNodeRecord requested = source.setNodeChunkLoadingRequested(NODE, linked.revision(), true)
                    .orElseThrow();
            updateDirectory(linked, requested, SOURCE);
            NetworkNodeRecord direct = source.setNodeMode(NODE, requested.revision(), NodeMode.DIRECT, false)
                    .orElseThrow();
            updateDirectory(requested, direct, SOURCE);
            NetworkChannelRecord channel = source.createTunnel(
                            TUNNEL, new ManagedName("Main"), CHANNEL, new ManagedName("Items"), -1)
                    .initialChannel();
            NetworkNodeRecord configured = source.setDirectBinding(
                    NODE, direct.revision(), channel.channelId(), TransferDirection.INPUT, false, -1);
            updateDirectory(direct, configured, SOURCE);
            return configured;
        }

        private NetworkNodeRecord addTargetNode(ManagedName name) {
            NetworkSavedData target = target();
            NetworkNodeRecord record = target.createNode(
                    EXISTING,
                    name,
                    GlobalPos.of(Level.OVERWORLD, new BlockPos(100, 64, 100)),
                    NodeForm.BLOCK,
                    Direction.DOWN);
            nodes.add(new NetworkNodeDirectory.Entry(TARGET, record));
            return record;
        }

        private void updateDirectory(NetworkNodeRecord previous, NetworkNodeRecord updated, UUID networkId) {
            nodes.update(
                    new NetworkNodeDirectory.Entry(networkId, previous),
                    new NetworkNodeDirectory.Entry(networkId, updated));
        }

        private NetworkSavedData source() {
            return repository.findLoadedNetwork(SOURCE).orElseThrow();
        }

        private NetworkSavedData target() {
            return repository.findLoadedNetwork(TARGET).orElseThrow();
        }

        @Override
        public void close() throws IOException {
            management.close();
            authority.close();
            IOUtilities.waitUntilIOWorkerComplete();
            try (var files = Files.walk(path)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(file);
                }
            }
        }
    }
}
