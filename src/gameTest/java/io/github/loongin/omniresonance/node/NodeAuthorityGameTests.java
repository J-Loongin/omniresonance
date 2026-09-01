// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.node;

import io.github.loongin.omniresonance.bootstrap.OmniResonanceMod;
import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.network.NetworkMetadata;
import io.github.loongin.omniresonance.persistence.NetworkSavedData;
import io.github.loongin.omniresonance.persistence.SavedNetworkRepository;
import io.github.loongin.omniresonance.registry.ModBlocks;
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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.IOUtilities;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real-world trusted association, reconciliation and exact-removal contracts. */
@GameTestHolder(OmniResonanceMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NodeAuthorityGameTests {
    private static final UUID NETWORK_A = new UUID(50, 1);
    private static final UUID NETWORK_B = new UUID(50, 2);
    private static final UUID OWNER = new UUID(51, 1);
    private static final UUID NODE_A = new UUID(52, 1);
    private static final UUID NODE_B = new UUID(52, 2);
    private static final UUID NODE_C = new UUID(52, 3);
    private static final UUID REPLACEMENT_A = new UUID(53, 1);
    private static final UUID REPLACEMENT_B = new UUID(53, 2);

    private NodeAuthorityGameTests() {}

    /** Authority-only block-entity transitions require an exact valid identity and preserve failures. */
    @GameTest(template = "bootstrap")
    public static void blockEntityAuthorityTransitionsAreExact(GameTestHelper helper) throws Exception {
        BlockPos pos = helper.absolutePos(new BlockPos(2, 3, 2));
        ResonanceNodeBlockEntity entity = place(helper, pos, ModBlocks.RESONANCE_TRANSFER_NODE.get(), Direction.DOWN);
        UUID original = entity.state().orElseThrow().nodeId();

        entity.linkFromAuthority(original);
        helper.assertTrue(
                entity.state().orElseThrow().linkState() == NodeLinkState.LINKED,
                "Authority did not link matching blank node");
        entity.linkFromAuthority(original);
        boolean mismatchRejected = false;
        try {
            entity.linkFromAuthority(NODE_A);
        } catch (IllegalStateException expected) {
            mismatchRejected = true;
        }
        helper.assertTrue(mismatchRejected, "Mismatched link identity was accepted");
        entity.replaceWithFreshBlank(original, REPLACEMENT_A);
        helper.assertTrue(
                entity.state().orElseThrow().equals(new NodePersistentState.Valid(REPLACEMENT_A, NodeLinkState.BLANK)),
                "Fresh blank replacement did not use supplied identity");

        CompoundTag before = entity.saveCustomOnly(helper.getLevel().registryAccess());
        boolean sameRejected = false;
        try {
            entity.replaceWithFreshBlank(REPLACEMENT_A, REPLACEMENT_A);
        } catch (IllegalArgumentException expected) {
            sameRejected = true;
        }
        helper.assertTrue(sameRejected, "Replacement reused the old identity");
        helper.assertTrue(
                before.equals(entity.saveCustomOnly(helper.getLevel().registryAccess())),
                "Rejected transition changed node NBT");

        entity.loadCustomOnly(new CompoundTag(), helper.getLevel().registryAccess());
        boolean unavailableRejected = false;
        try {
            entity.linkFromAuthority(REPLACEMENT_A);
        } catch (IllegalStateException expected) {
            unavailableRejected = true;
        }
        helper.assertTrue(unavailableRejected && entity.isUnavailable(), "Unavailable state was repaired by mutation");
        helper.succeed();
    }

    /** Trusted linking commits one complete record and rejects later failures without partial numbering/state. */
    @GameTest(template = "bootstrap")
    public static void trustedLinkCommitsAuthorityBeforePhysicalState(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper, List.of(), List.of(REPLACEMENT_A))) {
            BlockPos firstPos = helper.absolutePos(new BlockPos(2, 3, 2));
            ResonanceNodeBlockEntity first =
                    place(helper, firstPos, ModBlocks.RESONANCE_TRANSFER_PANEL.get(), Direction.WEST);
            UUID firstId = first.state().orElseThrow().nodeId();
            NetworkNodeRecord linked = fixture.authority.link(NETWORK_A, first, new ManagedName("Ore input"));
            helper.assertTrue(
                    linked.nodeId().equals(firstId) && linked.nodeNumber() == 1, "Wrong linked identity/number");
            helper.assertTrue(
                    linked.position().equals(GlobalPos.of(Level.OVERWORLD, firstPos)), "Wrong linked position");
            helper.assertTrue(
                    linked.form() == NodeForm.PANEL && linked.facing() == Direction.WEST,
                    "Wrong linked physical snapshot");
            helper.assertTrue(first.state().orElseThrow().linkState() == NodeLinkState.LINKED, "BE stayed blank");
            assertUnique(helper, linked, fixture.directory.byId(firstId));
            helper.assertTrue(fixture.network(NETWORK_A).lastNodeNumber() == 1, "Node number was not committed");

            BlockPos duplicatePos = helper.absolutePos(new BlockPos(5, 3, 2));
            ResonanceNodeBlockEntity duplicate =
                    place(helper, duplicatePos, ModBlocks.RESONANCE_TRANSFER_NODE.get(), Direction.DOWN);
            UUID duplicateId = duplicate.state().orElseThrow().nodeId();
            boolean duplicateNameRejected = false;
            try {
                fixture.authority.link(NETWORK_A, duplicate, new ManagedName("ORE INPUT"));
            } catch (IllegalArgumentException expected) {
                duplicateNameRejected = true;
            }
            helper.assertTrue(duplicateNameRejected, "Duplicate node name was accepted");
            helper.assertTrue(
                    duplicate
                            .state()
                            .orElseThrow()
                            .equals(new NodePersistentState.Valid(duplicateId, NodeLinkState.BLANK)),
                    "Rejected link changed physical state");
            helper.assertTrue(
                    fixture.network(NETWORK_A).lastNodeNumber() == 1
                            && fixture.network(NETWORK_A).nodes().size() == 1,
                    "Rejected link consumed a number or record");
            helper.assertTrue(
                    fixture.directory.byId(duplicateId).status() == NetworkNodeDirectory.Status.ABSENT,
                    "Rejected link changed directory");

            boolean missingNetworkRejected = false;
            try {
                fixture.authority.link(new UUID(99, 99), duplicate, new ManagedName("Other"));
            } catch (IllegalArgumentException expected) {
                missingNetworkRejected = true;
            }
            helper.assertTrue(missingNetworkRejected, "Missing network link was accepted");
            helper.succeed();
        }
    }

    /** Exact records relink blank entities and update only their physical display snapshots. */
    @GameTest(template = "bootstrap")
    public static void exactAuthorityRelinksAndRefreshesPhysicalSnapshot(GameTestHelper helper) throws IOException {
        BlockPos pos = helper.absolutePos(new BlockPos(2, 3, 2));
        GlobalPos globalPos = GlobalPos.of(Level.OVERWORLD, pos);
        Seed seed = new Seed(NETWORK_A, NODE_A, "Node A", globalPos, NodeForm.BLOCK, Direction.DOWN);
        try (Fixture fixture = new Fixture(helper, List.of(seed), List.of(REPLACEMENT_A))) {
            ResonanceNodeBlockEntity entity =
                    place(helper, pos, ModBlocks.RESONANCE_TRANSFER_PANEL.get(), Direction.UP);
            loadState(helper, entity, NODE_A, NodeLinkState.BLANK);

            fixture.authority.reconcileLoaded(entity);

            helper.assertTrue(entity.state().orElseThrow().linkState() == NodeLinkState.LINKED, "Blank did not relink");
            NetworkNodeRecord updated =
                    fixture.network(NETWORK_A).findNode(NODE_A).orElseThrow();
            helper.assertTrue(
                    updated.form() == NodeForm.PANEL && updated.facing() == Direction.UP,
                    "Physical snapshot was not refreshed");
            assertUnique(helper, updated, fixture.directory.byId(NODE_A));
            helper.succeed();
        }
    }

    /** Residual links and copied UUIDs become fresh blank identities without changing the original record. */
    @GameTest(template = "bootstrap")
    public static void missingAuthorityAndCopiedUuidResetOnlyThePhysicalCopy(GameTestHelper helper) throws IOException {
        BlockPos originalPos = helper.absolutePos(new BlockPos(2, 3, 2));
        BlockPos copyPos = helper.absolutePos(new BlockPos(5, 3, 2));
        BlockPos residualPos = helper.absolutePos(new BlockPos(8, 3, 2));
        Seed seed = new Seed(
                NETWORK_A,
                NODE_A,
                "Original",
                GlobalPos.of(Level.OVERWORLD, originalPos),
                NodeForm.BLOCK,
                Direction.DOWN);
        try (Fixture fixture = new Fixture(helper, List.of(seed), List.of(REPLACEMENT_A, REPLACEMENT_B))) {
            ResonanceNodeBlockEntity copy =
                    place(helper, copyPos, ModBlocks.RESONANCE_TRANSFER_NODE.get(), Direction.DOWN);
            loadState(helper, copy, NODE_A, NodeLinkState.LINKED);
            fixture.authority.reconcileLoaded(copy);
            helper.assertTrue(
                    copy.state()
                            .orElseThrow()
                            .equals(new NodePersistentState.Valid(REPLACEMENT_A, NodeLinkState.BLANK)),
                    "Copied UUID inherited original authority");
            helper.assertTrue(
                    fixture.network(NETWORK_A)
                            .findNode(NODE_A)
                            .orElseThrow()
                            .position()
                            .equals(seed.position),
                    "Copy changed original record");

            ResonanceNodeBlockEntity residual =
                    place(helper, residualPos, ModBlocks.RESONANCE_TRANSFER_PANEL.get(), Direction.NORTH);
            loadState(helper, residual, NODE_B, NodeLinkState.LINKED);
            fixture.authority.reconcileLoaded(residual);
            helper.assertTrue(
                    residual.state()
                            .orElseThrow()
                            .equals(new NodePersistentState.Valid(REPLACEMENT_B, NodeLinkState.BLANK)),
                    "Residual linked state was trusted without authority");
            helper.succeed();
        }
    }

    /** A loaded different node removes the old positional ghost, while conflicted authority stays untouched. */
    @GameTest(template = "bootstrap")
    public static void positionMismatchCleansUniqueGhostButConflictBlocksMutation(GameTestHelper helper)
            throws IOException {
        BlockPos oldPos = helper.absolutePos(new BlockPos(2, 3, 2));
        GlobalPos oldGlobal = GlobalPos.of(Level.OVERWORLD, oldPos);
        try (Fixture unique = new Fixture(
                helper,
                List.of(new Seed(NETWORK_A, NODE_A, "Old", oldGlobal, NodeForm.BLOCK, Direction.DOWN)),
                List.of(REPLACEMENT_A))) {
            ResonanceNodeBlockEntity actual =
                    place(helper, oldPos, ModBlocks.RESONANCE_TRANSFER_NODE.get(), Direction.DOWN);
            loadState(helper, actual, NODE_B, NodeLinkState.BLANK);
            unique.authority.reconcileLoaded(actual);
            helper.assertTrue(unique.network(NETWORK_A).findNode(NODE_A).isEmpty(), "Old positional ghost survived");
            helper.assertTrue(actual.state().orElseThrow().nodeId().equals(NODE_B), "Actual blank node was replaced");
        }

        BlockPos otherPos = helper.absolutePos(new BlockPos(6, 3, 2));
        Seed first = new Seed(NETWORK_A, NODE_C, "First", oldGlobal, NodeForm.BLOCK, Direction.DOWN);
        Seed second = new Seed(
                NETWORK_B, NODE_C, "Second", GlobalPos.of(Level.OVERWORLD, otherPos), NodeForm.PANEL, Direction.NORTH);
        try (Fixture conflicted = new Fixture(helper, List.of(first, second), List.of(REPLACEMENT_B))) {
            ResonanceNodeBlockEntity node =
                    place(helper, oldPos, ModBlocks.RESONANCE_TRANSFER_NODE.get(), Direction.DOWN);
            loadState(helper, node, NODE_C, NodeLinkState.LINKED);
            CompoundTag before = node.saveCustomOnly(helper.getLevel().registryAccess());
            conflicted.authority.reconcileLoaded(node);
            helper.assertTrue(
                    before.equals(node.saveCustomOnly(helper.getLevel().registryAccess())),
                    "Conflict rewrote physical state");
            helper.assertTrue(
                    conflicted.network(NETWORK_A).findNode(NODE_C).isPresent()
                            && conflicted.network(NETWORK_B).findNode(NODE_C).isPresent(),
                    "Conflict deleted an authority record");
            helper.succeed();
        }
    }

    /** Exact removal and loaded-position verification delete only proven ghosts and preserve uncertain data. */
    @GameTest(template = "bootstrap")
    public static void removalAndPositionVerificationAreExactAndNeverLoadChunks(GameTestHelper helper)
            throws IOException {
        BlockPos removedPos = helper.absolutePos(new BlockPos(2, 3, 2));
        GlobalPos removedGlobal = GlobalPos.of(Level.OVERWORLD, removedPos);
        GlobalPos farUnloaded = GlobalPos.of(Level.OVERWORLD, new BlockPos(1_000_000, 80, 1_000_000));
        List<Seed> seeds = List.of(
                new Seed(NETWORK_A, NODE_A, "Removed", removedGlobal, NodeForm.BLOCK, Direction.DOWN),
                new Seed(NETWORK_A, NODE_B, "Far", farUnloaded, NodeForm.PANEL, Direction.UP));
        try (Fixture fixture = new Fixture(helper, seeds, List.of(REPLACEMENT_A))) {
            helper.getLevel().setBlockAndUpdate(removedPos, Blocks.AIR.defaultBlockState());
            fixture.authority.verifyRecordedPosition(removedGlobal);
            helper.assertTrue(fixture.network(NETWORK_A).findNode(NODE_A).isEmpty(), "Loaded air was not a ghost");
            fixture.authority.verifyRecordedPosition(farUnloaded);
            helper.assertTrue(fixture.network(NETWORK_A).findNode(NODE_B).isPresent(), "Unloaded record was deleted");

            BlockPos exactPos = helper.absolutePos(new BlockPos(5, 3, 2));
            ResonanceNodeBlockEntity exact =
                    place(helper, exactPos, ModBlocks.RESONANCE_TRANSFER_NODE.get(), Direction.DOWN);
            UUID exactId = exact.state().orElseThrow().nodeId();
            NetworkNodeRecord record = fixture.authority.link(NETWORK_A, exact, new ManagedName("Exact"));
            fixture.authority.removePhysical(exactId, GlobalPos.of(Level.OVERWORLD, exactPos));
            helper.assertTrue(fixture.network(NETWORK_A).findNode(exactId).isEmpty(), "Exact removal kept record");
            helper.assertTrue(
                    fixture.directory.byId(record.nodeId()).status() == NetworkNodeDirectory.Status.ABSENT,
                    "Exact removal kept directory entry");
            helper.succeed();
        }
    }

    /** Startup and chunk work process at most 256 recorded positions per gt and stop when exhausted. */
    @GameTest(template = "bootstrap")
    public static void startupAndChunkReconciliationUseFixedPositionBudget(GameTestHelper helper) throws IOException {
        BlockPos column = helper.absolutePos(new BlockPos(2, 0, 2));
        List<Seed> seeds = new ArrayList<>();
        for (int index = 0; index < 257; index++) {
            seeds.add(new Seed(
                    NETWORK_A,
                    new UUID(70, index + 1),
                    "Ghost " + index,
                    GlobalPos.of(Level.OVERWORLD, new BlockPos(column.getX(), -64 + index, column.getZ())),
                    NodeForm.BLOCK,
                    Direction.DOWN));
        }
        GlobalPos unloaded = GlobalPos.of(Level.OVERWORLD, new BlockPos(1_200_000, 80, 1_200_000));
        seeds.add(new Seed(NETWORK_A, new UUID(70, 999), "Unloaded", unloaded, NodeForm.PANEL, Direction.UP));
        try (Fixture fixture = new Fixture(helper, seeds, List.of(REPLACEMENT_A))) {
            fixture.authority.beginStartupSweep();
            fixture.authority.tick();
            helper.assertTrue(fixture.network(NETWORK_A).nodes().size() == 2, "First gt exceeded 256 positions");
            fixture.authority.tick();
            helper.assertTrue(fixture.network(NETWORK_A).nodes().size() == 1, "Second gt did not resume cursor");
            fixture.authority.tick();
            helper.assertTrue(
                    fixture.network(NETWORK_A).findNode(new UUID(70, 999)).isPresent(),
                    "Exhausted ticks loaded or removed unloaded node");

            GlobalPos chunkGhost =
                    GlobalPos.of(Level.OVERWORLD, new BlockPos(column.getX() + 1, 200, column.getZ() + 1));
            NetworkNodeRecord record = fixture.network(NETWORK_A)
                    .createNode(
                            new UUID(70, 1000),
                            new ManagedName("Chunk ghost"),
                            chunkGhost,
                            NodeForm.BLOCK,
                            Direction.DOWN);
            fixture.directory.add(new NetworkNodeDirectory.Entry(NETWORK_A, record));
            fixture.authority.enqueueChunk(
                    Level.OVERWORLD,
                    chunkGhost.pos().getX() >> 4,
                    chunkGhost.pos().getZ() >> 4);
            fixture.authority.tick();
            helper.assertTrue(
                    fixture.network(NETWORK_A).findNode(record.nodeId()).isEmpty(),
                    "Chunk event did not verify indexed positions");
            helper.assertTrue(fixture.network(NETWORK_A).nodes().size() == 1, "Chunk work changed unloaded record");
            helper.succeed();
        }
    }

    /** Physical load and actual replacement publish one event; same-block state changes do not remove authority. */
    @GameTest(template = "bootstrap")
    public static void physicalLifecyclePublishesLoadedAndExactRemovedEvents(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(2, 3, 2));
        LifecycleObserver observer = new LifecycleObserver(helper.getLevel(), pos);
        NeoForge.EVENT_BUS.register(observer);
        ResonanceNodeBlockEntity entity = place(helper, pos, ModBlocks.RESONANCE_TRANSFER_PANEL.get(), Direction.NORTH);
        UUID nodeId = entity.state().orElseThrow().nodeId();
        helper.runAfterDelay(1, () -> {
            try {
                helper.assertTrue(
                        observer.loaded == 1 && observer.loadedEntity == entity, "Loaded event was not exact");
                helper.getLevel()
                        .setBlockAndUpdate(
                                pos,
                                entity.getBlockState().setValue(AbstractResonanceNodeBlock.FACING, Direction.SOUTH));
                helper.assertTrue(observer.removed == 0, "Same-block state change emitted removal");
                helper.getLevel().setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                helper.assertTrue(
                        observer.removed == 1 && observer.removedId.equals(nodeId) && observer.removedPos.equals(pos),
                        "Physical removal event was missing or imprecise");
                helper.succeed();
            } finally {
                NeoForge.EVENT_BUS.unregister(observer);
            }
        });
    }

    private static ResonanceNodeBlockEntity place(
            GameTestHelper helper, BlockPos worldPos, Block block, Direction facing) {
        BlockState state = block.defaultBlockState().setValue(AbstractResonanceNodeBlock.FACING, facing);
        helper.getLevel().setBlockAndUpdate(worldPos, state);
        var blockEntity = helper.getLevel().getBlockEntity(worldPos);
        helper.assertTrue(blockEntity instanceof ResonanceNodeBlockEntity, "Node block entity missing");
        return (ResonanceNodeBlockEntity) blockEntity;
    }

    private static void loadState(
            GameTestHelper helper, ResonanceNodeBlockEntity entity, UUID nodeId, NodeLinkState linkState) {
        CompoundTag tag = new CompoundTag();
        new NodePersistentState.Valid(nodeId, linkState).writeOwnedFields(tag);
        entity.loadCustomOnly(tag, helper.getLevel().registryAccess());
    }

    private static void assertUnique(
            GameTestHelper helper, NetworkNodeRecord expected, NetworkNodeDirectory.Lookup lookup) {
        helper.assertTrue(lookup.status() == NetworkNodeDirectory.Status.UNIQUE, "Node lookup is not unique");
        helper.assertTrue(lookup.entry().orElseThrow().record().equals(expected), "Node directory record differs");
    }

    private record Seed(
            UUID networkId, UUID nodeId, String name, GlobalPos position, NodeForm form, Direction facing) {}

    private static final class LifecycleObserver {
        private final net.minecraft.server.level.ServerLevel level;
        private final BlockPos position;
        private int loaded;
        private int removed;
        private ResonanceNodeBlockEntity loadedEntity;
        private UUID removedId;
        private BlockPos removedPos;

        private LifecycleObserver(net.minecraft.server.level.ServerLevel level, BlockPos position) {
            this.level = level;
            this.position = position;
        }

        @SubscribeEvent
        public void onLoaded(NodeLifecycleEvent.Loaded event) {
            if (event.level() == level && event.entity().getBlockPos().equals(position)) {
                loaded++;
                loadedEntity = event.entity();
            }
        }

        @SubscribeEvent
        public void onRemoved(NodeLifecycleEvent.Removed event) {
            if (event.level() == level && event.position().equals(position)) {
                removed++;
                removedId = event.nodeId();
                removedPos = event.position();
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final Path directoryPath;
        private final SavedNetworkRepository repository;
        private final NetworkNodeDirectory directory;
        private final NodeAuthorityService authority;

        private Fixture(GameTestHelper helper, List<Seed> seeds, List<UUID> replacements) throws IOException {
            directoryPath = Files.createTempDirectory("omniresonance-node-authority-test-");
            DimensionDataStorage storage = new DimensionDataStorage(
                    directoryPath.toFile(),
                    DataFixers.getDataFixer(),
                    helper.getLevel().registryAccess());
            repository = new SavedNetworkRepository(storage, directoryPath);
            repository.createNetwork(new NetworkMetadata(NETWORK_A, OWNER, new ManagedName("Network A"), 0, Set.of()));
            repository.createNetwork(new NetworkMetadata(NETWORK_B, OWNER, new ManagedName("Network B"), 1, Set.of()));
            List<NetworkNodeDirectory.Entry> entries = new ArrayList<>();
            for (Seed seed : seeds) {
                NetworkNodeRecord record = network(seed.networkId)
                        .createNode(seed.nodeId, new ManagedName(seed.name), seed.position, seed.form, seed.facing);
                entries.add(new NetworkNodeDirectory.Entry(seed.networkId, record));
            }
            directory = new NetworkNodeDirectory(entries);
            ArrayDeque<UUID> ids = new ArrayDeque<>(replacements);
            authority = new NodeAuthorityService(helper.getLevel().getServer(), repository, directory, () -> {
                if (ids.isEmpty()) {
                    throw new IllegalStateException("No deterministic replacement UUID available");
                }
                return ids.removeFirst();
            });
        }

        private NetworkSavedData network(UUID id) {
            return repository.findLoadedNetwork(id).orElseThrow();
        }

        @Override
        public void close() throws IOException {
            authority.close();
            IOUtilities.waitUntilIOWorkerComplete();
            try (var files = Files.walk(directoryPath)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }
}
