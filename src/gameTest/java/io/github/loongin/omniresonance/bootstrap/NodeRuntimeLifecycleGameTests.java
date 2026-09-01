// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.bootstrap;

import io.github.loongin.omniresonance.config.ServerConfig;
import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.network.NetworkCreationService;
import io.github.loongin.omniresonance.network.NetworkDirectory;
import io.github.loongin.omniresonance.network.NetworkMetadata;
import io.github.loongin.omniresonance.network.NetworkTerminalService;
import io.github.loongin.omniresonance.node.AbstractResonanceNodeBlock;
import io.github.loongin.omniresonance.node.NetworkNodeDirectory;
import io.github.loongin.omniresonance.node.NetworkNodeRecord;
import io.github.loongin.omniresonance.node.NodeAuthorityService;
import io.github.loongin.omniresonance.node.NodeForm;
import io.github.loongin.omniresonance.node.NodeLifecycleEvent;
import io.github.loongin.omniresonance.node.NodeLinkState;
import io.github.loongin.omniresonance.node.NodePersistentState;
import io.github.loongin.omniresonance.node.ResonanceNodeBlockEntity;
import io.github.loongin.omniresonance.persistence.NetworkSavedData;
import io.github.loongin.omniresonance.persistence.SavedNetworkRepository;
import io.github.loongin.omniresonance.registry.ModBlocks;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.common.IOUtilities;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Mod-instance runtime composition and lifecycle routing for node authority. */
@GameTestHolder(OmniResonanceMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NodeRuntimeLifecycleGameTests {
    private static final UUID NETWORK = new UUID(80, 1);
    private static final UUID OWNER = new UUID(80, 2);
    private static final UUID NODE = new UUID(80, 3);

    private NodeRuntimeLifecycleGameTests() {}

    /** Pending events drain only after runtime publication; ticks, removal and stop reach the shared authority. */
    @GameTest(template = "bootstrap")
    public static void runtimePublishesThenRoutesBoundedNodeLifecycle(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        BlockPos pos = helper.absolutePos(new BlockPos(2, 3, 2));
        GlobalPos globalPos = GlobalPos.of(Level.OVERWORLD, pos);
        ResonanceNodeBlockEntity entity = place(helper, pos);
        loadState(helper, entity, NODE, NodeLinkState.BLANK);
        try (Fixture fixture = new Fixture(helper, globalPos)) {
            AtomicReference<NodeAuthorityService> authorityRef = new AtomicReference<>();
            NetworkRuntimeRegistry registry =
                    NetworkRuntimeRegistry.forTesting(new ServerConfig(), (actualServer, state) -> {
                        helper.assertTrue(actualServer == server, "Factory received another server");
                        NodeAuthorityService authority = fixture.authority(actualServer);
                        authorityRef.set(authority);
                        return new NetworkRuntimeRegistry.RuntimeComponents(
                                fixture.terminal(actualServer, state), authority);
                    });
            registry.onNodeLifecycle(new NodeLifecycleEvent.Loaded(helper.getLevel(), entity));
            helper.assertTrue(
                    entity.state().orElseThrow().linkState() == NodeLinkState.BLANK, "Pre-start event mutated node");

            registry.onServerStarted(new ServerStartedEvent(server));
            registry.onServerTick(new ServerTickEvent.Pre(() -> true, server));
            helper.assertTrue(
                    entity.state().orElseThrow().linkState() == NodeLinkState.LINKED,
                    "Published runtime did not drain/reconcile node");

            GlobalPos ghost = GlobalPos.of(Level.OVERWORLD, pos.above(4));
            NetworkNodeRecord ghostRecord = fixture.network.createNode(
                    new UUID(80, 4), new ManagedName("Ghost"), ghost, NodeForm.BLOCK, Direction.DOWN);
            fixture.nodes.add(new NetworkNodeDirectory.Entry(NETWORK, ghostRecord));
            registry.onChunkLoaded(
                    helper.getLevel(), ghost.pos().getX() >> 4, ghost.pos().getZ() >> 4);
            registry.onServerTick(new ServerTickEvent.Pre(() -> true, server));
            helper.assertTrue(
                    fixture.network.findNode(ghostRecord.nodeId()).isEmpty(),
                    "Chunk observation did not reach authority tick");

            helper.getLevel().setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            registry.onNodeLifecycle(new NodeLifecycleEvent.Removed(helper.getLevel(), NODE, pos));
            helper.assertTrue(fixture.network.findNode(NODE).isEmpty(), "Removal did not reach shared authority");
            registry.onServerStopped(new ServerStoppedEvent(server));
            boolean closed = false;
            try {
                authorityRef.get().tick();
            } catch (IllegalStateException expected) {
                closed = true;
            }
            helper.assertTrue(closed, "Server stop retained usable node authority");
            helper.succeed();
        }
    }

    /** Initialization failure never interprets pending linked state as missing authority. */
    @GameTest(template = "bootstrap")
    public static void unavailableRuntimeLeavesPendingNodeStateUntouched(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        BlockPos pos = helper.absolutePos(new BlockPos(2, 3, 2));
        ResonanceNodeBlockEntity entity = place(helper, pos);
        loadState(helper, entity, NODE, NodeLinkState.LINKED);
        CompoundTag before = entity.saveCustomOnly(helper.getLevel().registryAccess());
        NetworkRuntimeRegistry registry =
                NetworkRuntimeRegistry.forTesting(new ServerConfig(), (actualServer, state) -> {
                    throw new IllegalStateException("Expected node runtime failure");
                });
        registry.onNodeLifecycle(new NodeLifecycleEvent.Loaded(helper.getLevel(), entity));
        registry.onServerStarted(new ServerStartedEvent(server));
        registry.onServerTick(new ServerTickEvent.Pre(() -> true, server));
        helper.assertTrue(
                before.equals(entity.saveCustomOnly(helper.getLevel().registryAccess())),
                "Unavailable runtime rewrote pending node");
        registry.onServerStopped(new ServerStoppedEvent(server));
        helper.succeed();
    }

    private static ResonanceNodeBlockEntity place(GameTestHelper helper, BlockPos pos) {
        helper.getLevel()
                .setBlockAndUpdate(
                        pos,
                        ModBlocks.RESONANCE_TRANSFER_NODE
                                .get()
                                .defaultBlockState()
                                .setValue(AbstractResonanceNodeBlock.FACING, Direction.DOWN));
        var blockEntity = helper.getLevel().getBlockEntity(pos);
        helper.assertTrue(blockEntity instanceof ResonanceNodeBlockEntity, "Node block entity missing");
        return (ResonanceNodeBlockEntity) blockEntity;
    }

    private static void loadState(
            GameTestHelper helper, ResonanceNodeBlockEntity entity, UUID nodeId, NodeLinkState linkState) {
        CompoundTag tag = new CompoundTag();
        new NodePersistentState.Valid(nodeId, linkState).writeOwnedFields(tag);
        entity.loadCustomOnly(tag, helper.getLevel().registryAccess());
    }

    private static final class Fixture implements AutoCloseable {
        private final Path path;
        private final SavedNetworkRepository repository;
        private final NetworkDirectory networks;
        private final NetworkNodeDirectory nodes;
        private final NetworkCreationService creation;
        private final NetworkSavedData network;
        private NodeAuthorityService authority;

        private Fixture(GameTestHelper helper, GlobalPos nodePosition) throws IOException {
            path = Files.createTempDirectory("omniresonance-node-runtime-test-");
            DimensionDataStorage storage = new DimensionDataStorage(
                    path.toFile(), DataFixers.getDataFixer(), helper.getLevel().registryAccess());
            repository = new SavedNetworkRepository(storage, path);
            NetworkMetadata metadata = new NetworkMetadata(NETWORK, OWNER, new ManagedName("Runtime"), 0, Set.of());
            repository.createNetwork(metadata);
            network = repository.findLoadedNetwork(NETWORK).orElseThrow();
            NetworkNodeRecord record =
                    network.createNode(NODE, new ManagedName("Node"), nodePosition, NodeForm.BLOCK, Direction.DOWN);
            networks = new NetworkDirectory(List.of(metadata));
            nodes = new NetworkNodeDirectory(List.of(new NetworkNodeDirectory.Entry(NETWORK, record)));
            creation = new NetworkCreationService(repository, networks, () -> new UUID(81, 1));
        }

        private NetworkTerminalService terminal(MinecraftServer server, ServerConfig.State state) {
            return new NetworkTerminalService(server, networks, creation, state, () -> new UUID(82, 1));
        }

        private NodeAuthorityService authority(MinecraftServer server) {
            authority = new NodeAuthorityService(server, repository, nodes, () -> new UUID(83, 1));
            return authority;
        }

        @Override
        public void close() throws IOException {
            if (authority != null) {
                authority.close();
            }
            IOUtilities.waitUntilIOWorkerComplete();
            try (var files = Files.walk(path)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(file);
                }
            }
        }
    }
}
