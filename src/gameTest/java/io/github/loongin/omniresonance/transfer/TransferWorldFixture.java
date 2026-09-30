// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import io.github.loongin.omniresonance.config.ServerSettings;
import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.network.NetworkMetadata;
import io.github.loongin.omniresonance.network.TransferDirection;
import io.github.loongin.omniresonance.network.WorkingFaces;
import io.github.loongin.omniresonance.node.AbstractResonanceNodeBlock;
import io.github.loongin.omniresonance.node.NetworkNodeDirectory;
import io.github.loongin.omniresonance.node.NetworkNodeRecord;
import io.github.loongin.omniresonance.node.NodeForm;
import io.github.loongin.omniresonance.node.NodeMode;
import io.github.loongin.omniresonance.node.NodePersistentState;
import io.github.loongin.omniresonance.node.ResonanceNodeBlockEntity;
import io.github.loongin.omniresonance.persistence.NetworkSavedData;
import io.github.loongin.omniresonance.persistence.SavedNetworkRepository;
import io.github.loongin.omniresonance.registry.ModBlocks;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.DimensionDataStorage;

/** Package-local world/network fixture. Identity derives from the test origin and role; close retires runtime,
 * removes owned blocks, and deletes only its own temporary SavedData directory. */
final class TransferWorldFixture implements AutoCloseable {
    final GameTestHelper helper;
    final Path path;
    final SavedNetworkRepository repository;
    final NetworkSavedData data;
    final DimensionDataStorage storage;
    final UUID network, owner, tunnel, channel;
    final NetworkNodeDirectory nodes = new NetworkNodeDirectory(List.of());
    final List<BlockPos> positions = new ArrayList<>();
    ResourceDirectRuntime runtime;

    TransferWorldFixture(GameTestHelper helper) throws Exception {
        this(helper, ResourceAdapterDirectory.nativeDefaults());
    }

    TransferWorldFixture(GameTestHelper helper, ResourceAdapterDirectory directory) throws Exception {
        this.helper = helper;
        network = identity("network");
        owner = identity("owner");
        tunnel = identity("tunnel");
        channel = identity("channel");
        path = Files.createTempDirectory("omniresonance-transfer-world-");
        storage = new DimensionDataStorage(
                path.toFile(), DataFixers.getDataFixer(), helper.getLevel().registryAccess());
        repository = new SavedNetworkRepository(storage, path, directory);
        repository.createNetwork(new NetworkMetadata(network, owner, new ManagedName("Transfer test"), 0, Set.of()));
        data = repository.findLoadedNetwork(network).orElseThrow();
        data.createTunnel(tunnel, new ManagedName("Main"), channel, new ManagedName("Items"), -1);
    }

    private UUID identity(String role) {
        return UUID.nameUUIDFromBytes(
                (helper.absolutePos(BlockPos.ZERO) + "/" + role).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    BlockPos pos(int x) {
        return helper.absolutePos(new BlockPos(x, 3, 2));
    }

    UUID node(int x, TransferDirection direction) {
        BlockPos pos = pos(x);
        positions.add(pos);
        helper.getLevel().setBlock(pos.below(), Blocks.CHEST.defaultBlockState(), 3);
        helper.getLevel()
                .setBlock(
                        pos,
                        ModBlocks.RESONANCE_TRANSFER_NODE
                                .get()
                                .defaultBlockState()
                                .setValue(AbstractResonanceNodeBlock.FACING, Direction.DOWN),
                        3);
        ResonanceNodeBlockEntity entity =
                (ResonanceNodeBlockEntity) helper.getLevel().getBlockEntity(pos);
        UUID id = identity("node/" + x);
        CompoundTag tag = new CompoundTag();
        NodePersistentState.linked(id).writeOwnedFields(tag);
        entity.loadCustomOnly(tag, helper.getLevel().registryAccess());
        NetworkNodeRecord n = data.createNode(
                id,
                new ManagedName("Node " + x),
                GlobalPos.of(helper.getLevel().dimension(), pos),
                NodeForm.BLOCK,
                Direction.DOWN);
        n = data.setNodeMode(id, n.revision(), NodeMode.DIRECT, false).orElseThrow();
        n = data.setDirectBinding(
                id,
                n.revision(),
                channel,
                ItemTransferPolicy.defaults(direction),
                WorkingFaces.attachedFace(),
                false,
                -1);
        nodes.add(new NetworkNodeDirectory.Entry(network, n));
        return id;
    }

    void policy(UUID id, ItemTransferPolicy policy) {
        NetworkNodeRecord n = data.findNode(id).orElseThrow();
        data.setDirectBinding(id, n.revision(), channel, policy, false, -1);
        sync();
    }

    void sync() {
        for (NetworkNodeRecord n : data.nodes()) {
            var previous = nodes.byId(n.nodeId()).entry().orElseThrow();
            if (!previous.record().equals(n)) nodes.update(previous, new NetworkNodeDirectory.Entry(network, n));
        }
    }

    ChestBlockEntity chest(int x) {
        return (ChestBlockEntity) helper.getLevel().getBlockEntity(pos(x).below());
    }

    void start() {
        runtime = new ResourceDirectRuntime(
                helper.getLevel().getServer(), repository, nodes, ServerSettings.defaults(), () -> 0);
    }

    void tick(long tick) {
        runtime.tick(tick, ServerSettings.defaults());
    }

    public void close() throws Exception {
        if (runtime != null) runtime.close();
        for (BlockPos pos : positions) {
            helper.getLevel().setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            helper.getLevel().setBlock(pos.below(), Blocks.AIR.defaultBlockState(), 3);
        }
        try (var files = Files.walk(path)) {
            for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }
}
