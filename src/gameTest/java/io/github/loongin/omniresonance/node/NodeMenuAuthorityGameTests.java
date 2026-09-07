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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.common.IOUtilities;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real-world read/reconciliation and pure Menu-validity contracts for node authority. */
@GameTestHolder(OmniResonanceMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NodeMenuAuthorityGameTests {
    private static final UUID NETWORK = new UUID(90, 1);
    private static final UUID NETWORK_B = new UUID(90, 2);
    private static final UUID OWNER = new UUID(91, 1);
    private static final UUID ADMIN = new UUID(91, 2);
    private static final UUID STRANGER = new UUID(91, 3);
    private static final UUID OP_WITHOUT_ROLE = new UUID(91, 4);
    private static final UUID NODE_A = new UUID(92, 1);
    private static final UUID NODE_B = new UUID(92, 2);
    private static final UUID REPLACEMENT = new UUID(93, 1);

    private NodeMenuAuthorityGameTests() {}

    /** Initial inspection reconciles once and returns private linked data only to current network roles. */
    @GameTest(template = "bootstrap")
    public static void inspectionReconcilesAndHidesUnauthorizedLinkedData(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper)) {
            BlockPos posA = helper.absolutePos(new BlockPos(2, 3, 2));
            ResonanceNodeBlockEntity nodeA = place(
                    helper,
                    posA,
                    NODE_A,
                    NodeLinkState.BLANK,
                    ModBlocks.RESONANCE_TRANSFER_PANEL.get(),
                    Direction.WEST);
            ServerPlayer owner = player(helper.getLevel(), OWNER, posA);
            ServerPlayer administrator = player(helper.getLevel(), ADMIN, posA);
            ServerPlayer stranger = player(helper.getLevel(), STRANGER, posA);
            ServerPlayer op = operator(helper.getLevel(), posA);

            NodeManagementService.PhysicalAccess blank = fixture.management.inspectPhysical(owner, posA);
            helper.assertTrue(
                    blank instanceof NodeManagementService.BlankAccess access
                            && access.nodeId().equals(NODE_A)
                            && access.position().equals(GlobalPos.of(Level.OVERWORLD, posA))
                            && access.form() == NodeForm.PANEL
                            && access.facing() == Direction.WEST,
                    "Blank physical inspection returned the wrong snapshot");
            NetworkNodeRecord linked = fixture.authority.link(NETWORK, nodeA, new ManagedName("Input"));
            assertLinked(helper, fixture.management.inspectPhysical(owner, posA), linked);
            assertLinked(helper, fixture.management.inspectPhysical(administrator, posA), linked);
            rejected(
                    helper,
                    NodeManagementService.Reason.NO_ACCESS,
                    () -> fixture.management.inspectPhysical(stranger, posA));
            rejected(
                    helper, NodeManagementService.Reason.NO_ACCESS, () -> fixture.management.inspectPhysical(op, posA));

            BlockPos residualPos = helper.absolutePos(new BlockPos(5, 3, 2));
            ResonanceNodeBlockEntity residual = place(
                    helper,
                    residualPos,
                    NODE_B,
                    NodeLinkState.LINKED,
                    ModBlocks.RESONANCE_TRANSFER_NODE.get(),
                    Direction.DOWN);
            owner.setPos(residualPos.getX() + 0.5, residualPos.getY() + 0.5, residualPos.getZ() + 0.5);
            NodeManagementService.PhysicalAccess repaired = fixture.management.inspectPhysical(owner, residualPos);
            helper.assertTrue(
                    repaired instanceof NodeManagementService.BlankAccess access
                            && access.nodeId().equals(REPLACEMENT)
                            && residual.state()
                                    .orElseThrow()
                                    .equals(new NodePersistentState.Valid(REPLACEMENT, NodeLinkState.BLANK)),
                    "Residual LINKED state was not reconciled to a fresh blank identity");

            NodeManagementService.LinkedEdit edit = fixture.management.acquireLinked(administrator, NETWORK, NODE_A);
            fixture.management.cancel(administrator, edit.token());
            helper.succeed();
        }
    }

    /** Per-gt Menu validity is pure and exact across distance, identity, block, role and unloaded chunks. */
    @GameTest(template = "bootstrap")
    public static void menuValidityNeverMutatesOrLoadsWorldState(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper)) {
            BlockPos pos = helper.absolutePos(new BlockPos(2, 3, 2));
            ResonanceNodeBlockEntity entity = place(
                    helper, pos, NODE_A, NodeLinkState.BLANK, ModBlocks.RESONANCE_TRANSFER_NODE.get(), Direction.NORTH);
            NetworkNodeRecord linked = fixture.authority.link(NETWORK, entity, new ManagedName("Node"));
            ServerPlayer owner = player(helper.getLevel(), OWNER, pos);
            ServerPlayer stranger = player(helper.getLevel(), STRANGER, pos);
            CompoundTag physicalBefore = entity.saveCustomOnly(helper.getLevel().registryAccess());
            fixture.network().setDirty(false);

            for (int check = 0; check < 20; check++) {
                helper.assertTrue(
                        fixture.management.canKeepPhysicalMenuOpen(owner, pos, NODE_A, NETWORK),
                        "Valid linked Menu was rejected");
            }
            helper.assertTrue(!fixture.network().isDirty(), "Validity dirtied network data");
            helper.assertTrue(
                    physicalBefore.equals(
                            entity.saveCustomOnly(helper.getLevel().registryAccess())),
                    "Validity changed physical NBT");
            helper.assertTrue(
                    !fixture.management.canKeepPhysicalMenuOpen(stranger, pos, NODE_A, NETWORK),
                    "Stranger retained linked Menu validity");

            owner.setPos(pos.getX() + 8.5001, pos.getY() + 0.5, pos.getZ() + 0.5);
            helper.assertTrue(
                    !fixture.management.canKeepPhysicalMenuOpen(owner, pos, NODE_A, NETWORK),
                    "Out-of-range Menu stayed valid");
            owner.setPos(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            helper.getLevel().setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            helper.assertTrue(
                    !fixture.management.canKeepPhysicalMenuOpen(owner, pos, NODE_A, NETWORK),
                    "Removed node Menu stayed valid");
            helper.assertTrue(
                    fixture.network().findNode(linked.nodeId()).isPresent()
                            && !fixture.network().isDirty(),
                    "Pure validity removed fixture authority");

            BlockPos far = new BlockPos(1_500_000, 80, 1_500_000);
            owner.setPos(far.getX() + 0.5, far.getY() + 0.5, far.getZ() + 0.5);
            helper.assertTrue(
                    helper.getLevel().getChunkSource().getChunkNow(far.getX() >> 4, far.getZ() >> 4) == null,
                    "Far test chunk started loaded");
            helper.assertTrue(
                    !fixture.management.canKeepPhysicalMenuOpen(owner, far, NODE_B, null),
                    "Missing unloaded blank Menu stayed valid");
            helper.assertTrue(
                    helper.getLevel().getChunkSource().getChunkNow(far.getX() >> 4, far.getZ() >> 4) == null,
                    "Menu validity loaded a missing chunk");
            helper.succeed();
        }
    }

    /** Distance boundary, read-only suggestions and exact linked reads neither allocate locks nor mutate data. */
    @GameTest(template = "bootstrap")
    public static void distanceSuggestionAndLinkedReadsAreExact(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper)) {
            BlockPos pos = helper.absolutePos(new BlockPos(2, 3, 2));
            ResonanceNodeBlockEntity entity = place(
                    helper, pos, NODE_A, NodeLinkState.BLANK, ModBlocks.RESONANCE_TRANSFER_NODE.get(), Direction.DOWN);
            ServerPlayer owner = player(helper.getLevel(), OWNER, pos);
            ServerPlayer administrator = player(helper.getLevel(), ADMIN, pos);
            ServerPlayer stranger = player(helper.getLevel(), STRANGER, pos);

            owner.setPos(pos.getX() + 8.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            helper.assertTrue(
                    fixture.management.inspectPhysical(owner, pos) instanceof NodeManagementService.BlankAccess,
                    "Exact squared-distance 64 boundary was rejected");
            owner.setPos(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            helper.assertTrue(fixture.management.suggestedNodeNumber(owner, NETWORK) == 1, "Wrong first suggestion");
            helper.assertTrue(
                    fixture.management.suggestedNodeNumber(administrator, NETWORK) == 1,
                    "Administrator suggestion differed");
            rejected(
                    helper,
                    NodeManagementService.Reason.NO_ACCESS,
                    () -> fixture.management.suggestedNodeNumber(stranger, NETWORK));

            NetworkNodeRecord linked = fixture.authority.link(NETWORK, entity, new ManagedName("Node"));
            helper.assertTrue(
                    fixture.management.suggestedNodeNumber(owner, NETWORK) == 2, "Suggestion did not advance");
            helper.assertTrue(
                    fixture.management.inspectLinked(owner, NETWORK, NODE_A).equals(linked),
                    "Linked read differed from authority");
            fixture.network().setDirty(false);
            fixture.management.inspectLinked(administrator, NETWORK, NODE_A);
            helper.assertTrue(!fixture.network().isDirty(), "Linked read dirtied data");

            NodeManagementService.LinkedEdit edit = fixture.management.acquireLinked(owner, NETWORK, NODE_A);
            fixture.management.cancel(owner, edit.token());
            helper.succeed();
        }
    }

    /** Cross-network UUID conflicts remain unavailable instead of being exposed as ordinary blank nodes. */
    @GameTest(template = "bootstrap")
    public static void conflictedPhysicalInspectionFailsClosed(GameTestHelper helper) throws IOException {
        BlockPos pos = helper.absolutePos(new BlockPos(2, 3, 2));
        BlockPos other = helper.absolutePos(new BlockPos(5, 3, 2));
        List<Seed> seeds = List.of(
                new Seed(NETWORK, NODE_A, "First", GlobalPos.of(Level.OVERWORLD, pos)),
                new Seed(NETWORK_B, NODE_A, "Second", GlobalPos.of(Level.OVERWORLD, other)));
        try (Fixture fixture = new Fixture(helper, seeds)) {
            ResonanceNodeBlockEntity entity = place(
                    helper, pos, NODE_A, NodeLinkState.BLANK, ModBlocks.RESONANCE_TRANSFER_NODE.get(), Direction.DOWN);
            CompoundTag before = entity.saveCustomOnly(helper.getLevel().registryAccess());
            ServerPlayer owner = player(helper.getLevel(), OWNER, pos);

            rejected(
                    helper,
                    NodeManagementService.Reason.UNAVAILABLE,
                    () -> fixture.management.inspectPhysical(owner, pos));
            helper.assertTrue(
                    before.equals(entity.saveCustomOnly(helper.getLevel().registryAccess())),
                    "Conflict inspection rewrote physical identity");
            helper.succeed();
        }
    }

    private static void assertLinked(
            GameTestHelper helper, NodeManagementService.PhysicalAccess access, NetworkNodeRecord expected) {
        helper.assertTrue(
                access instanceof NodeManagementService.LinkedAccess linked
                        && linked.network().id().equals(NETWORK)
                        && linked.node().equals(expected),
                "Linked inspection returned the wrong authority snapshot");
    }

    private static ServerPlayer player(ServerLevel level, UUID id, BlockPos near) {
        FakePlayer player = new FakePlayer(level, new GameProfile(id, "Menu" + id.getLeastSignificantBits()));
        player.setPos(near.getX() + 0.5, near.getY() + 0.5, near.getZ() + 0.5);
        return player;
    }

    private static ServerPlayer operator(ServerLevel level, BlockPos near) {
        FakePlayer player = new OperatorFakePlayer(level, new GameProfile(OP_WITHOUT_ROLE, "MenuOperator"));
        player.setPos(near.getX() + 0.5, near.getY() + 0.5, near.getZ() + 0.5);
        return player;
    }

    private static ResonanceNodeBlockEntity place(
            GameTestHelper helper,
            BlockPos position,
            UUID nodeId,
            NodeLinkState linkState,
            Block block,
            Direction facing) {
        BlockState state = block.defaultBlockState().setValue(AbstractResonanceNodeBlock.FACING, facing);
        helper.getLevel().setBlockAndUpdate(position, state);
        helper.assertTrue(
                helper.getLevel().getBlockEntity(position) instanceof ResonanceNodeBlockEntity,
                "Node block entity missing");
        ResonanceNodeBlockEntity entity =
                (ResonanceNodeBlockEntity) helper.getLevel().getBlockEntity(position);
        CompoundTag tag = new CompoundTag();
        new NodePersistentState.Valid(nodeId, linkState).writeOwnedFields(tag);
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

    private static final class Fixture implements AutoCloseable {
        private final Path path;
        private final SavedNetworkRepository repository;
        private final NetworkNodeDirectory nodes;
        private final NodeAuthorityService authority;
        private final NodeManagementService management;

        private Fixture(GameTestHelper helper) throws IOException {
            this(helper, List.of());
        }

        private Fixture(GameTestHelper helper, List<Seed> seeds) throws IOException {
            path = Files.createTempDirectory("omniresonance-node-menu-authority-test-");
            DimensionDataStorage storage = new DimensionDataStorage(
                    path.toFile(), DataFixers.getDataFixer(), helper.getLevel().registryAccess());
            repository = new SavedNetworkRepository(storage, path);
            NetworkMetadata metadata = new NetworkMetadata(NETWORK, OWNER, new ManagedName("Menu"), 0, Set.of(ADMIN));
            repository.createNetwork(metadata);
            List<NetworkMetadata> metadataEntries = new java.util.ArrayList<>();
            metadataEntries.add(metadata);
            if (seeds.stream().anyMatch(seed -> seed.networkId().equals(NETWORK_B))) {
                NetworkMetadata second =
                        new NetworkMetadata(NETWORK_B, OWNER, new ManagedName("Menu B"), 1, Set.of(ADMIN));
                repository.createNetwork(second);
                metadataEntries.add(second);
            }
            NetworkDirectory networks = new NetworkDirectory(metadataEntries);
            List<NetworkNodeDirectory.Entry> nodeEntries = new java.util.ArrayList<>();
            for (Seed seed : seeds) {
                NetworkNodeRecord record = repository
                        .findLoadedNetwork(seed.networkId())
                        .orElseThrow()
                        .createNode(
                                seed.nodeId(),
                                new ManagedName(seed.name()),
                                seed.position(),
                                NodeForm.BLOCK,
                                Direction.DOWN);
                nodeEntries.add(new NetworkNodeDirectory.Entry(seed.networkId(), record));
            }
            nodes = new NetworkNodeDirectory(nodeEntries);
            ArrayDeque<UUID> replacements = new ArrayDeque<>(List.of(REPLACEMENT));
            authority = new NodeAuthorityService(
                    helper.getLevel().getServer(), repository, nodes, replacements::removeFirst);
            management = new NodeManagementService(
                    helper.getLevel().getServer(), networks, repository, nodes, authority, new EditLockTable());
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

    private record Seed(UUID networkId, UUID nodeId, String name, GlobalPos position) {}

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
