// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.node;

import com.mojang.authlib.GameProfile;
import io.github.loongin.omniresonance.bootstrap.OmniResonanceMod;
import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.network.NetworkDirectory;
import io.github.loongin.omniresonance.network.NetworkMetadata;
import io.github.loongin.omniresonance.persistence.NetworkSavedData;
import io.github.loongin.omniresonance.persistence.SavedNetworkRepository;
import io.github.loongin.omniresonance.registry.ModBlocks;
import io.github.loongin.omniresonance.security.EditLockTable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.common.IOUtilities;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real-player authorization, edit-lock and revision contracts for the internal node-management service. */
@GameTestHolder(OmniResonanceMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NodeManagementGameTests {
    private static final UUID NETWORK = new UUID(80, 1);
    private static final UUID OWNER = new UUID(81, 1);
    private static final UUID ADMIN = new UUID(81, 2);
    private static final UUID STRANGER = new UUID(81, 3);
    private static final UUID OP_WITHOUT_ROLE = new UUID(81, 4);
    private static final UUID NODE_A = new UUID(82, 1);
    private static final UUID NODE_B = new UUID(82, 2);
    private static final UUID NODE_C = new UUID(82, 3);

    private NodeManagementGameTests() {}

    @GameTest(template = "bootstrap")
    public static void navigationRequiresOperatorAndRechecksPendingTravel(GameTestHelper helper) throws IOException {
        try (var f = new Fixture(helper)) {
            var pos = helper.absolutePos(new BlockPos(3, 3, 3));
            var entity = placeBlank(helper, pos, NODE_A, ModBlocks.RESONANCE_TRANSFER_NODE.get(), Direction.DOWN);
            f.authority.link(NETWORK, entity, new ManagedName("Permission node"));
            var owner = new TravelPlayer(helper.getLevel(), new GameProfile(OWNER, "TravelPermission"));
            var settings = io.github.loongin.omniresonance.config.ServerSettings.Navigation.defaults();
            try (var navigation = new NodeNavigationService(
                    helper.getLevel().getServer(), f.management, f.authority, settings, (p, frame) -> {})) {
                owner.permissionLevel = 0;
                boolean denied = false;
                try {
                    navigation.teleport(owner, NETWORK, NODE_A);
                } catch (SecurityException expected) {
                    denied = true;
                }
                helper.assertTrue(
                        denied && navigation.pendingCount() == 0 && navigation.ticketCount() == 0,
                        "Non-operator network owner was allowed to request travel");
                navigation.highlight(owner, NETWORK, NODE_A);
                owner.permissionLevel = 2;
                var distant = f.network()
                        .createNode(
                                NODE_B,
                                new ManagedName("Permission distant"),
                                GlobalPos.of(helper.getLevel().dimension(), pos.offset(2048, 0, 2048)),
                                NodeForm.BLOCK,
                                Direction.NORTH);
                f.nodes.add(new NetworkNodeDirectory.Entry(NETWORK, distant));
                navigation.teleport(owner, NETWORK, NODE_B);
                helper.assertTrue(
                        navigation.pendingCount() == 1 && navigation.ticketCount() == 1,
                        "Operator could not request bounded travel");
                owner.permissionLevel = 0;
                navigation.tick(settings);
                helper.assertTrue(
                        navigation.pendingCount() == 0 && navigation.ticketCount() == 0 && owner.moves == 0,
                        "Revoked operator retained travel or temporary tickets");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void navigationIsRoleBoundedAndUsesSafeLoadedDestinations(GameTestHelper helper) throws IOException {
        try (var f = new Fixture(helper)) {
            var pos = helper.absolutePos(new BlockPos(3, 3, 3));
            var entity = placeBlank(helper, pos, NODE_A, ModBlocks.RESONANCE_TRANSFER_NODE.get(), Direction.DOWN);
            f.authority.link(NETWORK, entity, new ManagedName("Travel node"));
            for (var ground : BlockPos.betweenClosed(pos.offset(-2, -1, -2), pos.offset(2, -1, 2)))
                helper.getLevel()
                        .setBlockAndUpdate(ground, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            var owner = new TravelPlayer(helper.getLevel(), new GameProfile(OWNER, "TravelOwner"));
            owner.setPos(pos.getX() + 0.5, pos.getY() + 4, pos.getZ() + 0.5);
            var frames = new java.util.ArrayList<io.github.loongin.omniresonance.networking.NodeHighlightFrame>();
            var settings = io.github.loongin.omniresonance.config.ServerSettings.Navigation.defaults();
            try (var navigation = new NodeNavigationService(
                    helper.getLevel().getServer(),
                    f.management,
                    f.authority,
                    settings,
                    (p, frame) -> frames.add(frame))) {
                rejected(
                        helper,
                        NodeManagementService.Reason.NO_ACCESS,
                        () -> navigation.highlight(player(helper, STRANGER, pos), NETWORK, NODE_A));
                navigation.highlight(owner, NETWORK, NODE_A);
                helper.assertTrue(
                        frames.getLast().node().equals(NODE_A) && navigation.ticketCount() == 0,
                        "Highlight loaded or selected the wrong node");
                navigation.highlight(owner, NETWORK, NODE_A);
                helper.assertTrue(frames.getLast().durationTicks() == 0, "Second highlight did not cancel");
                navigation.teleport(owner, NETWORK, NODE_A);
                helper.assertTrue(owner.moves == 0, "Travel moved before server tick validation");
                navigation.tick(settings);
                helper.assertTrue(
                        owner.moves == 1 && navigation.pendingCount() == 0 && navigation.ticketCount() == 0,
                        "Safe travel did not finish and release its task");
                helper.assertTrue(
                        !f.network().findNode(NODE_A).orElseThrow().chunkLoadingRequested(),
                        "Travel enabled permanent loading");
                helper.assertTrue(
                        NodeNavigationService.safe(
                                helper.getLevel(),
                                owner,
                                new net.minecraft.world.level.ChunkPos(pos),
                                owner.blockPosition()),
                        "Destination was unsafe");
                helper.getLevel()
                        .setBlockAndUpdate(
                                owner.blockPosition().below(),
                                net.minecraft.world.level.block.Blocks.MAGMA_BLOCK.defaultBlockState());
                helper.assertTrue(
                        !NodeNavigationService.safe(
                                helper.getLevel(),
                                owner,
                                new net.minecraft.world.level.ChunkPos(pos),
                                owner.blockPosition()),
                        "Hazardous support accepted");
                boolean rejected = false;
                try {
                    navigation.teleport(owner, NETWORK, NODE_A);
                } catch (IllegalStateException expected) {
                    rejected = true;
                }
                helper.assertTrue(rejected, "Repeated travel bypassed interval/cooldown");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void navigationCancelsTemporaryTicketsAndRejectsGhosts(GameTestHelper helper) throws IOException {
        try (var f = new Fixture(helper)) {
            var pos = helper.absolutePos(new BlockPos(3, 3, 3));
            var entity = placeBlank(helper, pos, NODE_A, ModBlocks.RESONANCE_TRANSFER_NODE.get(), Direction.DOWN);
            f.authority.link(NETWORK, entity, new ManagedName("Ghost"));
            var owner = new TravelPlayer(helper.getLevel(), new GameProfile(OWNER, "TravelGhost"));
            owner.setPos(pos.getX() + 0.5, pos.getY() + 4, pos.getZ() + 0.5);
            var settings = io.github.loongin.omniresonance.config.ServerSettings.Navigation.defaults();
            try (var navigation = new NodeNavigationService(
                    helper.getLevel().getServer(), f.management, f.authority, settings, (p, frame) -> {})) {
                helper.getLevel().removeBlock(pos, false);
                navigation.teleport(owner, NETWORK, NODE_A);
                navigation.tick(settings);
                helper.assertTrue(owner.moves == 0 && navigation.pendingCount() == 0, "Ghost travel moved the player");
                navigation.disconnect(owner);
                var distant = f.network()
                        .createNode(
                                NODE_B,
                                new ManagedName("Distant"),
                                GlobalPos.of(helper.getLevel().dimension(), pos.offset(1024, 0, 1024)),
                                NodeForm.BLOCK,
                                Direction.NORTH);
                f.nodes.add(new NetworkNodeDirectory.Entry(NETWORK, distant));
                navigation.teleport(owner, NETWORK, NODE_B);
                helper.assertTrue(
                        navigation.pendingCount() == 1 && navigation.ticketCount() == 1,
                        "Unloaded travel did not acquire a bounded temporary ticket");
                navigation.tick(new io.github.loongin.omniresonance.config.ServerSettings.Navigation(
                        true, 200, true, true, false, 20, 100, 64));
                helper.assertTrue(
                        navigation.pendingCount() == 0 && navigation.ticketCount() == 0 && owner.moves == 0,
                        "Disabling temporary loading leaked task/ticket or moved player");
            }
        }
        helper.succeed();
    }

    private static final class TravelPlayer extends FakePlayer {
        int moves;
        int permissionLevel = 2;

        @Override
        public boolean hasPermissions(int level) {
            return permissionLevel >= level;
        }

        TravelPlayer(ServerLevel level, GameProfile profile) {
            super(level, profile);
        }

        @Override
        public void teleportTo(ServerLevel level, double x, double y, double z, float yaw, float pitch) {
            moves++;
            setPos(x, y, z);
        }
    }

    /** Only current roles and exact nearby blanks enter editing; locks are per node rather than global. */
    @GameTest(template = "bootstrap")
    public static void rolesDistanceAndPerNodeLocksGateBlankEditing(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper)) {
            BlockPos posA = helper.absolutePos(new BlockPos(2, 3, 2));
            BlockPos posB = helper.absolutePos(new BlockPos(5, 3, 2));
            placeBlank(helper, posA, NODE_A, ModBlocks.RESONANCE_TRANSFER_NODE.get(), Direction.DOWN);
            placeBlank(helper, posB, NODE_B, ModBlocks.RESONANCE_TRANSFER_PANEL.get(), Direction.NORTH);
            ServerPlayer owner = player(helper, OWNER, posA);
            ServerPlayer administrator = player(helper, ADMIN, posA);
            ServerPlayer stranger = player(helper, STRANGER, posB);
            ServerPlayer opWithoutRole = operatorWithoutRole(helper, posB);
            helper.assertTrue(opWithoutRole.hasPermissions(4), "OP test player did not receive permission level 4");

            EditLockTable.Token first = fixture.management.acquireBlank(owner, posA, NETWORK);
            rejected(
                    helper,
                    NodeManagementService.Reason.LOCKED,
                    () -> fixture.management.acquireBlank(administrator, posA, NETWORK));
            EditLockTable.Token second = fixture.management.acquireBlank(owner, posB, NETWORK);
            rejected(
                    helper,
                    NodeManagementService.Reason.NO_ACCESS,
                    () -> fixture.management.acquireBlank(stranger, posB, NETWORK));
            rejected(
                    helper,
                    NodeManagementService.Reason.NO_ACCESS,
                    () -> fixture.management.acquireBlank(opWithoutRole, posB, NETWORK));
            fixture.management.cancel(owner, first);
            fixture.management.cancel(owner, second);

            EditLockTable.Token mismatched = fixture.management.acquireBlank(owner, posA, NETWORK);
            rejected(
                    helper,
                    NodeManagementService.Reason.UNAVAILABLE,
                    () -> fixture.management.linkBlank(
                            owner, posB, NETWORK, new ManagedName("Wrong position"), mismatched));
            helper.assertTrue(
                    fixture.locks.tryAcquire(NODE_A, ADMIN, 0).isPresent(),
                    "Mismatched blank submission retained its edit lease");
            fixture.locks.releasePlayer(ADMIN);

            owner.setPos(posA.getX() + 9.5, posA.getY() + 0.5, posA.getZ() + 0.5);
            rejected(
                    helper,
                    NodeManagementService.Reason.OUT_OF_RANGE,
                    () -> fixture.management.acquireBlank(owner, posA, NETWORK));
            helper.succeed();
        }
    }

    /** A validated blank link commits every authority surface, releases its lock and retains correctable failures. */
    @GameTest(template = "bootstrap")
    public static void blankLinkIsAtomicAndNameConflictKeepsTheDraftLock(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper)) {
            BlockPos posA = helper.absolutePos(new BlockPos(2, 3, 2));
            BlockPos posB = helper.absolutePos(new BlockPos(5, 3, 2));
            ResonanceNodeBlockEntity nodeA =
                    placeBlank(helper, posA, NODE_A, ModBlocks.RESONANCE_TRANSFER_NODE.get(), Direction.DOWN);
            ResonanceNodeBlockEntity nodeB =
                    placeBlank(helper, posB, NODE_B, ModBlocks.RESONANCE_TRANSFER_PANEL.get(), Direction.NORTH);
            ServerPlayer owner = player(helper, OWNER, posA);
            ServerPlayer administrator = player(helper, ADMIN, posA);

            EditLockTable.Token tokenA = fixture.management.acquireBlank(owner, posA, NETWORK);
            NetworkNodeRecord first =
                    fixture.management.linkBlank(owner, posA, NETWORK, new ManagedName("Node A"), tokenA);
            helper.assertTrue(first.nodeId().equals(NODE_A) && first.nodeNumber() == 1, "Wrong linked node identity");
            helper.assertTrue(
                    first.revision() == 0
                            && first.enabled()
                            && !first.chunkLoadingRequested()
                            && first.mode() == NodeMode.UNCONFIGURED,
                    "Linked node did not use v3 defaults");
            helper.assertTrue(
                    nodeA.state().orElseThrow().linkState() == NodeLinkState.LINKED, "Physical node stayed BLANK");
            assertUnique(helper, first, fixture.nodes.byId(NODE_A));
            NodeManagementService.LinkedEdit reacquired =
                    fixture.management.acquireLinked(administrator, NETWORK, NODE_A);
            fixture.management.cancel(administrator, reacquired.token());

            owner.setPos(posB.getX() + 0.5, posB.getY() + 0.5, posB.getZ() + 0.5);
            EditLockTable.Token tokenB = fixture.management.acquireBlank(owner, posB, NETWORK);
            rejected(
                    helper,
                    NodeManagementService.Reason.NAME_CONFLICT,
                    () -> fixture.management.linkBlank(owner, posB, NETWORK, new ManagedName("node a"), tokenB));
            NetworkNodeRecord second =
                    fixture.management.linkBlank(owner, posB, NETWORK, new ManagedName("Node B"), tokenB);
            helper.assertTrue(second.nodeNumber() == 2, "Corrected link did not retain the same draft lock");
            helper.assertTrue(
                    nodeB.state().orElseThrow().linkState() == NodeLinkState.LINKED,
                    "Corrected link did not update physical state");
            helper.assertTrue(fixture.network().nodes().size() == 2, "Link failure or retry partially changed nodes");

            BlockPos posC = helper.absolutePos(new BlockPos(8, 3, 2));
            placeBlank(helper, posC, NODE_C, ModBlocks.RESONANCE_TRANSFER_NODE.get(), Direction.DOWN);
            owner.setPos(posC.getX() + 0.5, posC.getY() + 0.5, posC.getZ() + 0.5);
            EditLockTable.Token removedToken = fixture.management.acquireBlank(owner, posC, NETWORK);
            helper.getLevel().setBlockAndUpdate(posC, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            rejected(
                    helper,
                    NodeManagementService.Reason.UNAVAILABLE,
                    () -> fixture.management.linkBlank(owner, posC, NETWORK, new ManagedName("Removed"), removedToken));
            helper.assertTrue(
                    fixture.locks.tryAcquire(NODE_C, ADMIN, 0).isPresent(),
                    "Removed blank node retained an unusable edit lease");
            fixture.locks.releasePlayer(ADMIN);
            helper.succeed();
        }
    }

    /** Every linked edit rechecks token/revision/state and preserves hidden settings while disabled. */
    @GameTest(template = "bootstrap")
    public static void linkedEditsEnforceRevisionModeAndDisabledState(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper)) {
            BlockPos posA = helper.absolutePos(new BlockPos(2, 3, 2));
            BlockPos posB = helper.absolutePos(new BlockPos(5, 3, 2));
            ResonanceNodeBlockEntity nodeA =
                    placeBlank(helper, posA, NODE_A, ModBlocks.RESONANCE_TRANSFER_NODE.get(), Direction.DOWN);
            ResonanceNodeBlockEntity nodeB =
                    placeBlank(helper, posB, NODE_B, ModBlocks.RESONANCE_TRANSFER_NODE.get(), Direction.DOWN);
            fixture.authority.link(NETWORK, nodeA, new ManagedName("Node A"));
            fixture.authority.link(NETWORK, nodeB, new ManagedName("Node B"));
            ServerPlayer owner = player(helper, OWNER, posA);
            ServerPlayer administrator = player(helper, ADMIN, posA);

            NodeManagementService.LinkedEdit renameEdit = fixture.management.acquireLinked(owner, NETWORK, NODE_A);
            rejected(
                    helper,
                    NodeManagementService.Reason.LOCK_EXPIRED,
                    () -> fixture.management.rename(
                            administrator,
                            NETWORK,
                            renameEdit.node().revision(),
                            new ManagedName("Stolen"),
                            renameEdit.token()));
            NetworkNodeRecord renamed = fixture.management.rename(
                    owner, NETWORK, renameEdit.node().revision(), new ManagedName("Crusher input"), renameEdit.token());
            NetworkNodeRecord requested = fixture.management.setChunkLoadingRequested(
                    administrator,
                    NETWORK,
                    renamed.revision(),
                    true,
                    fixture.management
                            .acquireLinked(administrator, NETWORK, NODE_A)
                            .token());
            NetworkNodeRecord direct = fixture.management.setMode(
                    owner,
                    NETWORK,
                    requested.revision(),
                    NodeMode.DIRECT,
                    false,
                    fixture.management.acquireLinked(owner, NETWORK, NODE_A).token());

            NodeManagementService.LinkedEdit switchEdit =
                    fixture.management.acquireLinked(administrator, NETWORK, NODE_A);
            rejected(
                    helper,
                    NodeManagementService.Reason.RESET_REQUIRED,
                    () -> fixture.management.setMode(
                            administrator, NETWORK, direct.revision(), NodeMode.DOMAIN, false, switchEdit.token()));
            NetworkNodeRecord domain = fixture.management.setMode(
                    administrator, NETWORK, direct.revision(), NodeMode.DOMAIN, true, switchEdit.token());
            NetworkNodeRecord disabled = fixture.management.setEnabled(
                    owner,
                    NETWORK,
                    domain.revision(),
                    false,
                    fixture.management.acquireLinked(owner, NETWORK, NODE_A).token());
            helper.assertTrue(
                    !disabled.enabled() && disabled.chunkLoadingRequested() && disabled.mode() == NodeMode.DOMAIN,
                    "Disabling discarded request or mode");

            NodeManagementService.LinkedEdit disabledEdit =
                    fixture.management.acquireLinked(administrator, NETWORK, NODE_A);
            rejected(
                    helper,
                    NodeManagementService.Reason.NODE_DISABLED,
                    () -> fixture.management.setMode(
                            administrator, NETWORK, disabled.revision(), NodeMode.DIRECT, true, disabledEdit.token()));
            NetworkNodeRecord enabled = fixture.management.setEnabled(
                    administrator, NETWORK, disabled.revision(), true, disabledEdit.token());

            NodeManagementService.LinkedEdit staleEdit = fixture.management.acquireLinked(owner, NETWORK, NODE_A);
            helper.getLevel()
                    .setBlockAndUpdate(
                            posA, nodeA.getBlockState().setValue(AbstractResonanceNodeBlock.FACING, Direction.UP));
            fixture.authority.reconcileLoaded(nodeA);
            NetworkNodeRecord physicallyChanged =
                    fixture.network().findNode(NODE_A).orElseThrow();
            helper.assertTrue(
                    physicallyChanged.revision() == enabled.revision() + 1,
                    "Physical change did not invalidate edit revision");
            rejected(
                    helper,
                    NodeManagementService.Reason.STALE_REVISION,
                    () -> fixture.management.rename(
                            owner, NETWORK, staleEdit.node().revision(), new ManagedName("Stale"), staleEdit.token()));
            fixture.management.cancel(owner, staleEdit.token());

            NodeManagementService.LinkedEdit conflictEdit = fixture.management.acquireLinked(owner, NETWORK, NODE_A);
            rejected(
                    helper,
                    NodeManagementService.Reason.NAME_CONFLICT,
                    () -> fixture.management.rename(
                            owner,
                            NETWORK,
                            physicallyChanged.revision(),
                            new ManagedName("node b"),
                            conflictEdit.token()));
            fixture.management.cancel(owner, conflictEdit.token());
            assertUnique(helper, physicallyChanged, fixture.nodes.byId(NODE_A));
            helper.succeed();
        }
    }

    /** Tick expiry, heartbeat, player release, authority loss and close release exactly the intended leases. */
    @GameTest(template = "bootstrap")
    public static void lockLifecycleIsBoundedAndAuthorityLossFailsClosed(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper)) {
            BlockPos posA = helper.absolutePos(new BlockPos(2, 3, 2));
            BlockPos posB = helper.absolutePos(new BlockPos(5, 3, 2));
            ResonanceNodeBlockEntity nodeA =
                    placeBlank(helper, posA, NODE_A, ModBlocks.RESONANCE_TRANSFER_NODE.get(), Direction.DOWN);
            ResonanceNodeBlockEntity nodeB =
                    placeBlank(helper, posB, NODE_B, ModBlocks.RESONANCE_TRANSFER_NODE.get(), Direction.DOWN);
            fixture.authority.link(NETWORK, nodeA, new ManagedName("Node A"));
            fixture.authority.link(NETWORK, nodeB, new ManagedName("Node B"));
            ServerPlayer owner = player(helper, OWNER, posA);
            ServerPlayer administrator = player(helper, ADMIN, posA);

            fixture.management.acquireLinked(owner, NETWORK, NODE_A);
            for (int tick = 0; tick < 199; tick++) {
                fixture.management.tick();
            }
            rejected(
                    helper,
                    NodeManagementService.Reason.LOCKED,
                    () -> fixture.management.acquireLinked(administrator, NETWORK, NODE_A));
            fixture.management.tick();
            fixture.management.cancel(
                    administrator,
                    fixture.management
                            .acquireLinked(administrator, NETWORK, NODE_A)
                            .token());

            NodeManagementService.LinkedEdit heartbeatEdit = fixture.management.acquireLinked(owner, NETWORK, NODE_A);
            for (int tick = 0; tick < 100; tick++) {
                fixture.management.tick();
            }
            fixture.management.heartbeat(owner, heartbeatEdit.token());
            for (int tick = 0; tick < 100; tick++) {
                fixture.management.tick();
            }
            rejected(
                    helper,
                    NodeManagementService.Reason.LOCKED,
                    () -> fixture.management.acquireLinked(administrator, NETWORK, NODE_A));
            for (int tick = 0; tick < 100; tick++) {
                fixture.management.tick();
            }
            fixture.management.cancel(
                    administrator,
                    fixture.management
                            .acquireLinked(administrator, NETWORK, NODE_A)
                            .token());

            fixture.management.acquireLinked(owner, NETWORK, NODE_A);
            fixture.management.acquireLinked(owner, NETWORK, NODE_B);
            fixture.management.releasePlayer(OWNER);
            fixture.management.cancel(
                    administrator,
                    fixture.management
                            .acquireLinked(administrator, NETWORK, NODE_A)
                            .token());
            fixture.management.cancel(
                    administrator,
                    fixture.management
                            .acquireLinked(administrator, NETWORK, NODE_B)
                            .token());

            NodeManagementService.LinkedEdit removed = fixture.management.acquireLinked(owner, NETWORK, NODE_A);
            fixture.authority.removePhysical(NODE_A, GlobalPos.of(Level.OVERWORLD, posA));
            rejected(
                    helper,
                    NodeManagementService.Reason.UNAVAILABLE,
                    () -> fixture.management.heartbeat(owner, removed.token()));
            helper.assertTrue(
                    fixture.locks.tryAcquire(NODE_A, ADMIN, 500).isPresent(),
                    "Authority loss did not release the stale lease");
            fixture.locks.releasePlayer(ADMIN);

            fixture.management.close();
            rejected(
                    helper,
                    NodeManagementService.Reason.UNAVAILABLE,
                    () -> fixture.management.acquireLinked(owner, NETWORK, NODE_B));
            helper.succeed();
        }
    }

    private static ServerPlayer player(GameTestHelper helper, UUID id, BlockPos near) {
        FakePlayer player =
                new FakePlayer(helper.getLevel(), new GameProfile(id, "Node" + id.getLeastSignificantBits()));
        player.setPos(near.getX() + 0.5, near.getY() + 0.5, near.getZ() + 0.5);
        return player;
    }

    private static ServerPlayer operatorWithoutRole(GameTestHelper helper, BlockPos near) {
        FakePlayer player = new OperatorFakePlayer(helper.getLevel(), new GameProfile(OP_WITHOUT_ROLE, "NodeOperator"));
        player.setPos(near.getX() + 0.5, near.getY() + 0.5, near.getZ() + 0.5);
        return player;
    }

    private static ResonanceNodeBlockEntity placeBlank(
            GameTestHelper helper, BlockPos position, UUID nodeId, Block block, Direction facing) {
        BlockState state = block.defaultBlockState().setValue(AbstractResonanceNodeBlock.FACING, facing);
        helper.getLevel().setBlockAndUpdate(position, state);
        helper.assertTrue(
                helper.getLevel().getBlockEntity(position) instanceof ResonanceNodeBlockEntity,
                "Node block entity missing");
        ResonanceNodeBlockEntity entity =
                (ResonanceNodeBlockEntity) helper.getLevel().getBlockEntity(position);
        CompoundTag tag = new CompoundTag();
        new NodePersistentState.Valid(nodeId, NodeLinkState.BLANK).writeOwnedFields(tag);
        entity.loadCustomOnly(tag, helper.getLevel().registryAccess());
        return entity;
    }

    private static void rejected(GameTestHelper helper, NodeManagementService.Reason expected, Runnable operation) {
        try {
            operation.run();
        } catch (NodeManagementService.Rejected rejected) {
            helper.assertTrue(rejected.reason() == expected, "Expected " + expected + ", got " + rejected.reason());
            return;
        }
        helper.fail("Expected node-management rejection " + expected);
    }

    private static void assertUnique(
            GameTestHelper helper, NetworkNodeRecord expected, NetworkNodeDirectory.Lookup lookup) {
        helper.assertTrue(lookup.status() == NetworkNodeDirectory.Status.UNIQUE, "Node lookup is not unique");
        helper.assertTrue(lookup.entry().orElseThrow().record().equals(expected), "Node directory record differs");
    }

    private static final class Fixture implements AutoCloseable {
        private final Path path;
        private final SavedNetworkRepository repository;
        private final NetworkNodeDirectory nodes;
        private final NodeAuthorityService authority;
        private final EditLockTable locks;
        private final NodeManagementService management;

        private Fixture(GameTestHelper helper) throws IOException {
            path = Files.createTempDirectory("omniresonance-node-management-test-");
            DimensionDataStorage storage = new DimensionDataStorage(
                    path.toFile(), DataFixers.getDataFixer(), helper.getLevel().registryAccess());
            repository = new SavedNetworkRepository(storage, path);
            NetworkMetadata metadata =
                    new NetworkMetadata(NETWORK, OWNER, new ManagedName("Management"), 0, Set.of(ADMIN));
            repository.createNetwork(metadata);
            NetworkDirectory networks = new NetworkDirectory(List.of(metadata));
            nodes = new NetworkNodeDirectory(List.of());
            ArrayDeque<UUID> replacements = new ArrayDeque<>(List.of(new UUID(83, 1), new UUID(83, 2)));
            authority = new NodeAuthorityService(
                    helper.getLevel().getServer(), repository, nodes, replacements::removeFirst);
            locks = new EditLockTable();
            management = new NodeManagementService(
                    helper.getLevel().getServer(), networks, repository, nodes, authority, locks);
            management.installChunkAdmission((network, node, moving) ->
                    io.github.loongin.omniresonance.chunkloading.ChunkLoadingReservations.Admission.ALLOWED);
        }

        private NetworkSavedData network() {
            return repository.findLoadedNetwork(NETWORK).orElseThrow();
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

    private static final class OperatorFakePlayer extends FakePlayer {
        private OperatorFakePlayer(ServerLevel level, GameProfile profile) {
            super(level, profile);
        }

        @Override
        public boolean hasPermissions(int permissionLevel) {
            return true;
        }
    }
}
