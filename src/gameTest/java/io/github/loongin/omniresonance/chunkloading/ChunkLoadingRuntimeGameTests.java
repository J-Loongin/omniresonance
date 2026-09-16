// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.chunkloading;

import io.github.loongin.omniresonance.bootstrap.OmniResonanceMod;
import io.github.loongin.omniresonance.config.ServerConfig;
import io.github.loongin.omniresonance.config.ServerSettings;
import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.network.NetworkDirectory;
import io.github.loongin.omniresonance.network.NetworkMetadata;
import io.github.loongin.omniresonance.node.NetworkNodeDirectory;
import io.github.loongin.omniresonance.node.NetworkNodeRecord;
import io.github.loongin.omniresonance.node.NodeAuthorityService;
import io.github.loongin.omniresonance.node.NodeMode;
import io.github.loongin.omniresonance.node.ResonanceNodeBlockEntity;
import io.github.loongin.omniresonance.persistence.SavedNetworkRepository;
import io.github.loongin.omniresonance.registry.ModBlocks;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(OmniResonanceMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChunkLoadingRuntimeGameTests {
    private ChunkLoadingRuntimeGameTests() {}

    @GameTest(template = "bootstrap")
    public static void newAdmissionIsAtomicRejectsWithoutQueueAndReturnsReleasedQuota(GameTestHelper h)
            throws Exception {
        try (var f = new Fixture(h)) {
            var one = f.node(new BlockPos(1, 1, 1));
            var other = f.node(new BlockPos(17, 129, 1));
            f.admissionConfig = f.config(2, true, 1, 1);
            f.runtime.tick(f.admissionConfig);
            var owner = f.player(one.position().pos());
            one = f.toggle(owner, one, true);
            f.reject(
                    owner,
                    other,
                    true,
                    io.github.loongin.omniresonance.node.NodeManagementService.Reason.CHUNK_OWNER_LIMIT);
            f.admissionConfig = f.config(3, true, 10, 1);
            f.reject(
                    owner,
                    other,
                    true,
                    io.github.loongin.omniresonance.node.NodeManagementService.Reason.CHUNK_SERVER_LIMIT);
            f.admissionConfig = f.config(4, false, 10, 1);
            f.reject(
                    owner,
                    other,
                    true,
                    io.github.loongin.omniresonance.node.NodeManagementService.Reason.CHUNK_DISABLED);
            f.admissionConfig = f.config(5, true, 10, 1);
            h.assertTrue(
                    !f.data().findNode(other.nodeId()).orElseThrow().chunkLoadingRequested(),
                    "Denied admission saved an enabled flag");
            f.runtime.tick(f.admissionConfig);
            h.assertTrue(f.runtime.reservedCount() == 1, "Same-tick admission oversold quota");
            one = f.toggle(owner, one, false);
            f.runtime.tick(f.admissionConfig);
            h.assertTrue(
                    !f.data().findNode(other.nodeId()).orElseThrow().chunkLoadingRequested()
                            && f.runtime.physicalCount() == 0,
                    "Denied request was queued and enabled later");
            other = f.toggle(owner, other, true);
            f.runtime.tick(f.admissionConfig);
            h.assertTrue(
                    f.runtime.reservedCount() == 1 && f.runtime.physicalCount() == 1,
                    "Released reservation was not reusable");
        }
        h.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void lowerQuotasPauseAndRestartPreserveExistingEntitlements(GameTestHelper h) throws Exception {
        try (var f = new Fixture(h)) {
            var one = f.node(new BlockPos(1, 1, 1));
            var other = f.node(new BlockPos(17, 129, 1));
            var third = f.node(new BlockPos(33, 129, 1));
            var owner = f.player(one.position().pos());
            one = f.toggle(owner, one, true);
            other = f.toggle(owner, other, true);
            f.runtime.tick(f.config);
            f.admissionConfig = f.config(2, true, 0, 0);
            f.runtime.tick(f.admissionConfig);
            h.assertTrue(
                    f.runtime.physicalCount() == 2 && f.runtime.ownerCount(f.network.ownerId()) == 2,
                    "Lowering quota revoked existing loading");
            one = f.enabled(one, false);
            other = f.enabled(other, false);
            f.runtime.tick(f.admissionConfig);
            h.assertTrue(
                    f.runtime.physicalCount() == 0 && f.runtime.reservedCount() == 2, "Pause freed the reserved quota");
            f.reject(
                    owner,
                    third,
                    true,
                    io.github.loongin.omniresonance.node.NodeManagementService.Reason.CHUNK_OWNER_LIMIT);
            f.runtime.close();
            try (var restored = new ChunkLoadingRuntime(
                    h.getLevel().getServer(), f.repository, f.networks, f.nodes, f.authority, f.admissionConfig)) {
                restored.tick(f.admissionConfig);
                h.assertTrue(
                        restored.reservedCount() == 2 && restored.physicalCount() == 0,
                        "Restart lost paused entitlements");
                f.enabled(one, true);
                f.enabled(other, true);
                restored.tick(f.admissionConfig);
                h.assertTrue(
                        restored.physicalCount() == 2 && restored.reservedCount() == 2,
                        "Resuming competed for quota instead of using saved entitlements");
            }
        }
        h.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void zeroCostSharingAndOwnershipMovesRespectReservationOwnership(GameTestHelper h) throws Exception {
        try (var f = new Fixture(h)) {
            var one = f.node(new BlockPos(1, 1, 1));
            var shared = f.node(new BlockPos(2, 1, 1));
            var owner = f.player(one.position().pos());
            one = f.toggle(owner, one, true);
            f.admissionConfig = f.config(2, true, 0, 0);
            f.runtime.tick(f.admissionConfig);
            f.toggle(owner, shared, true);
            f.runtime.tick(f.admissionConfig);
            h.assertTrue(
                    f.runtime.reservedCount() == 1 && f.runtime.ownerCount(f.network.ownerId()) == 1,
                    "Shared node consumed another reservation");
            var target = new NetworkMetadata(
                    new UUID(1105, 1),
                    new UUID(1105, 2),
                    new ManagedName("Other owner"),
                    1,
                    Set.of(f.network.ownerId()));
            f.repository.createNetwork(target);
            f.networks.add(target);
            var edit = f.management.beginNetworkMove(owner, f.network.id(), one.nodeId(), target.id());
            boolean rejected = false;
            try {
                f.management.moveNetwork(owner, edit, new ManagedName("Moved"));
            } catch (io.github.loongin.omniresonance.node.NodeManagementService.Rejected failure) {
                rejected = failure.reason()
                        == io.github.loongin.omniresonance.node.NodeManagementService.Reason.CHUNK_OWNER_LIMIT;
            } finally {
                f.management.cancel(owner, edit.token());
            }
            h.assertTrue(
                    rejected && f.data().findNode(one.nodeId()).isPresent(),
                    "Cross-owner move bypassed admission or changed the source on failure");
            var same = new NetworkMetadata(
                    new UUID(1106, 1), f.network.ownerId(), new ManagedName("Same owner"), 2, Set.of());
            f.repository.createNetwork(same);
            f.networks.add(same);
            var move = f.management.beginNetworkMove(owner, f.network.id(), one.nodeId(), same.id());
            var moved = f.management.moveNetwork(owner, move, new ManagedName("Moved"));
            f.runtime.tick(f.admissionConfig);
            h.assertTrue(
                    moved.chunkLoadingRequested() && f.runtime.reservedCount() == 1,
                    "Same-owner migration lost an existing entitlement");
        }
        h.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void impossibleRecordedCoordinatesNeverReceiveALoadingTicket(GameTestHelper h) throws Exception {
        try (var f = new Fixture(h)) {
            var node = f.data()
                    .createNode(
                            new UUID(1103, 1),
                            new ManagedName("Invalid position"),
                            net.minecraft.core.GlobalPos.of(
                                    h.getLevel().dimension(), new BlockPos(30000001, 100000, 0)),
                            io.github.loongin.omniresonance.node.NodeForm.BLOCK,
                            net.minecraft.core.Direction.NORTH);
            f.nodes.add(new NetworkNodeDirectory.Entry(f.network.id(), node));
            node = f.request(node);
            f.runtime.tick(f.config);
            h.assertTrue(
                    f.runtime.physicalCount() == 0
                            && f.runtime.status(node.nodeId()) == ChunkLoadingAllocator.Status.UNAVAILABLE
                            && f.data().findNode(node.nodeId()).isPresent(),
                    "Impossible position was loaded or destructively treated as an observed ghost");
        }
        h.succeed();
    }

    @GameTest(template = "bootstrap", timeoutTicks = 1000)
    public static void unloadedGhostIsLoadedOnlyAfterQuotaAndThenReleasesItsTicket(GameTestHelper h) throws Exception {
        var f = new Fixture(h);
        h.testInfo.addListener(new net.minecraft.gametest.framework.GameTestListener() {
            private boolean closed;

            private void cleanup() {
                if (closed) return;
                closed = true;
                try {
                    f.close();
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            }

            public void testStructureLoaded(net.minecraft.gametest.framework.GameTestInfo test) {}

            public void testPassed(
                    net.minecraft.gametest.framework.GameTestInfo test,
                    net.minecraft.gametest.framework.GameTestRunner runner) {
                cleanup();
            }

            public void testFailed(
                    net.minecraft.gametest.framework.GameTestInfo test,
                    net.minecraft.gametest.framework.GameTestRunner runner) {
                cleanup();
            }

            public void testAddedForRerun(
                    net.minecraft.gametest.framework.GameTestInfo oldTest,
                    net.minecraft.gametest.framework.GameTestInfo next,
                    net.minecraft.gametest.framework.GameTestRunner runner) {
                cleanup();
            }
        });
        var origin = new net.minecraft.world.level.ChunkPos(h.absolutePos(BlockPos.ZERO));
        var pos =
                new BlockPos((origin.x + 64) * 16, h.absolutePos(BlockPos.ZERO).getY() + 1, (origin.z + 64) * 16);
        var node = f.data()
                .createNode(
                        new UUID(1102, 1),
                        new ManagedName("Ghost"),
                        net.minecraft.core.GlobalPos.of(h.getLevel().dimension(), pos),
                        io.github.loongin.omniresonance.node.NodeForm.BLOCK,
                        net.minecraft.core.Direction.NORTH);
        f.nodes.add(new NetworkNodeDirectory.Entry(f.network.id(), node));
        node = f.request(node);
        f.runtime.tick(f.config(2, false, 0, 0));
        h.assertTrue(
                f.runtime.physicalCount() == 0
                        && h.getLevel().getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null,
                "Global disable loaded a remote chunk");
        var enabled = f.config(3, true, 0, 0);
        f.runtime.tick(enabled);
        h.assertTrue(
                f.runtime.physicalCount() == 1
                        && f.runtime.status(node.nodeId()) == ChunkLoadingAllocator.Status.QUEUED,
                "Unverified remote node was advertised active");
        UUID nodeId = node.nodeId();
        h.succeedWhen(() -> {
            f.runtime.tick(enabled);
            h.assertTrue(
                    f.data().findNode(nodeId).isEmpty() && f.runtime.physicalCount() == 0,
                    "Remote ghost has not been removed and released");
        });
    }

    @GameTest(template = "bootstrap")
    public static void rebuildingRuntimeRestoresRequestsWithoutPersistedTickets(GameTestHelper h) throws Exception {
        try (var f = new Fixture(h)) {
            var node = f.request(f.node(new BlockPos(1, 1, 1)));
            f.runtime.tick(f.config);
            f.runtime.close();
            try (var restored = new ChunkLoadingRuntime(
                    h.getLevel().getServer(),
                    f.repository,
                    new NetworkDirectory(List.of(f.network)),
                    f.nodes,
                    f.authority,
                    f.config)) {
                restored.tick(f.config);
                h.assertTrue(
                        restored.physicalCount() == 1
                                && restored.status(node.nodeId()) == ChunkLoadingAllocator.Status.ACTIVE,
                        "Runtime reconstruction lost a saved request");
            }
        }
        h.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void actualRequestedNodesDeduplicateReleaseAndKeepTheirPersistedIntent(GameTestHelper h)
            throws Exception {
        try (var f = new Fixture(h)) {
            var one = f.node(new BlockPos(1, 1, 1));
            var two = f.node(new BlockPos(2, 1, 1));
            one = f.request(one);
            two = f.request(two);
            f.runtime.tick(f.config);
            h.assertTrue(
                    f.runtime.status(one.nodeId()) == ChunkLoadingAllocator.Status.ACTIVE
                            && f.runtime.physicalCount() == 1,
                    "Actual verified nodes did not share a ticket");
            one = f.enabled(one, false);
            f.runtime.tick(f.config);
            h.assertTrue(
                    f.runtime.physicalCount() == 1
                            && f.runtime.status(one.nodeId()) == ChunkLoadingAllocator.Status.NODE_DISABLED,
                    "Disabling one node removed another reference");
            f.enabled(two, false);
            f.runtime.tick(f.config);
            h.assertTrue(
                    f.runtime.physicalCount() == 0
                            && f.data().findNode(one.nodeId()).orElseThrow().chunkLoadingRequested(),
                    "Disabled requests were erased or tickets leaked");
        }
        h.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void globalDisableAndQuotaZeroRetainRequestsAndAllowReadmission(GameTestHelper h) throws Exception {
        try (var f = new Fixture(h)) {
            var node = f.request(f.node(new BlockPos(1, 1, 1)));
            f.runtime.tick(f.config);
            f.runtime.tick(f.config(2, false, 25, 500));
            h.assertTrue(
                    f.runtime.physicalCount() == 0
                            && f.data().findNode(node.nodeId()).orElseThrow().chunkLoadingRequested(),
                    "Global disable changed requested data");
            f.runtime.tick(f.config(3, true, 0, 0));
            h.assertTrue(
                    f.runtime.physicalCount() == 1 && f.runtime.reservedCount() == 1,
                    "Lowered quota revoked an existing entitlement");
            f.runtime.tick(f.config(4, true, 25, 500));
            h.assertTrue(f.runtime.physicalCount() == 1, "Restored quota did not readmit saved request");
        }
        h.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void corruptDomainDropsOnlyDomainReferencesAndLoadedGhostsAreRemoved(GameTestHelper h)
            throws Exception {
        try (var f = new Fixture(h)) {
            var direct = f.request(f.node(new BlockPos(1, 1, 1)));
            var domain = f.node(new BlockPos(2, 1, 1));
            var old = domain;
            domain = f.data()
                    .setNodeMode(domain.nodeId(), domain.revision(), NodeMode.DOMAIN, true)
                    .orElseThrow();
            f.update(old, domain);
            domain = f.request(domain);
            f.data().markStorageBuckets(1);
            f.runtime.tick(f.config);
            h.assertTrue(
                    f.runtime.status(domain.nodeId()) == ChunkLoadingAllocator.Status.DOMAIN_UNAVAILABLE
                            && f.runtime.status(direct.nodeId()) == ChunkLoadingAllocator.Status.ACTIVE
                            && f.runtime.physicalCount() == 1,
                    "Broken domain removed a direct reference or kept a domain reference");
            h.setBlock(new BlockPos(1, 1, 1), net.minecraft.world.level.block.Blocks.AIR);
            f.runtime.changed(f.network.id());
            f.runtime.tick(f.config);
            h.assertTrue(
                    f.data().findNode(direct.nodeId()).isEmpty() && f.runtime.physicalCount() == 0,
                    "Loaded ghost retained a ticket or authority record");
        }
        h.succeed();
    }

    private static final class Fixture implements AutoCloseable {
        final GameTestHelper helper;
        final Path path;
        final SavedNetworkRepository repository;
        final NetworkMetadata network;
        final NetworkNodeDirectory nodes = new NetworkNodeDirectory(List.of());
        final NodeAuthorityService authority;
        final NetworkDirectory networks;
        final io.github.loongin.omniresonance.node.NodeManagementService management;
        ServerConfig.State admissionConfig;
        final ChunkLoadingRuntime runtime;
        long nextNode;
        final ServerConfig.State config = new ServerConfig.State(1, 1, true, ServerSettings.defaults());

        Fixture(GameTestHelper helper) throws Exception {
            this.helper = helper;
            path = Files.createTempDirectory("omniresonance-chunk-runtime-");
            repository = new SavedNetworkRepository(
                    new DimensionDataStorage(
                            path.toFile(),
                            DataFixers.getDataFixer(),
                            helper.getLevel().registryAccess()),
                    path);
            network = new NetworkMetadata(
                    new UUID(1100, 1), new UUID(1100, 2), new ManagedName("Chunk Test"), 0, Set.of());
            repository.createNetwork(network);
            authority = new NodeAuthorityService(
                    helper.getLevel().getServer(), repository, nodes, () -> new UUID(1101, ++nextNode));
            networks = new NetworkDirectory(List.of(network));
            management = new io.github.loongin.omniresonance.node.NodeManagementService(
                    helper.getLevel().getServer(),
                    networks,
                    repository,
                    nodes,
                    authority,
                    new io.github.loongin.omniresonance.security.EditLockTable());
            admissionConfig = config;
            runtime = new ChunkLoadingRuntime(
                    helper.getLevel().getServer(), repository, networks, nodes, authority, config);
            management.installChunkAdmission(
                    (networkId, node, moving) -> runtime.admission(networkId, node, moving, admissionConfig));
        }

        net.minecraft.server.level.ServerPlayer player(BlockPos near) {
            var result = new net.neoforged.neoforge.common.util.FakePlayer(
                    helper.getLevel(), new com.mojang.authlib.GameProfile(network.ownerId(), "AdmissionOwner"));
            result.setPos(near.getX() + 0.5, near.getY() + 0.5, near.getZ() + 0.5);
            return result;
        }

        NetworkNodeRecord toggle(
                net.minecraft.server.level.ServerPlayer player, NetworkNodeRecord node, boolean requested) {
            var edit = management.acquireLinked(player, network.id(), node.nodeId());
            try {
                return management.setChunkLoadingRequested(
                        player, network.id(), edit.node().revision(), requested, edit.token());
            } catch (RuntimeException failure) {
                management.cancel(player, edit.token());
                throw failure;
            }
        }

        void reject(
                net.minecraft.server.level.ServerPlayer player,
                NetworkNodeRecord node,
                boolean requested,
                io.github.loongin.omniresonance.node.NodeManagementService.Reason reason) {
            var before = data().findNode(node.nodeId()).orElseThrow();
            boolean rejected = false;
            try {
                toggle(player, node, requested);
            } catch (io.github.loongin.omniresonance.node.NodeManagementService.Rejected failure) {
                rejected = failure.reason() == reason;
            }
            helper.assertTrue(rejected, "Unexpected admission result");
            helper.assertTrue(
                    data().findNode(node.nodeId()).orElseThrow().equals(before),
                    "Rejected admission changed node authority");
        }

        io.github.loongin.omniresonance.persistence.NetworkSavedData data() {
            return repository.findLoadedNetwork(network.id()).orElseThrow();
        }

        NetworkNodeRecord node(BlockPos pos) {
            BlockPos absolute = helper.absolutePos(pos);
            if (pos.getX() == 2) {
                var first = helper.absolutePos(new BlockPos(1, pos.getY(), pos.getZ()));
                absolute = first.offset((first.getX() & 15) == 15 ? -1 : 1, 0, 0);
            }
            helper.getLevel()
                    .setBlockAndUpdate(
                            absolute, ModBlocks.RESONANCE_TRANSFER_NODE.get().defaultBlockState());
            var entity = (ResonanceNodeBlockEntity) helper.getLevel().getBlockEntity(absolute);
            authority.reconcileLoaded(entity);
            return authority.link(network.id(), entity, new ManagedName("Node " + pos.getX()));
        }

        void update(NetworkNodeRecord old, NetworkNodeRecord next) {
            nodes.update(
                    new NetworkNodeDirectory.Entry(network.id(), old),
                    new NetworkNodeDirectory.Entry(network.id(), next));
        }

        NetworkNodeRecord request(NetworkNodeRecord node) {
            var next = data().setNodeChunkLoadingRequested(node.nodeId(), node.revision(), true)
                    .orElseThrow();
            update(node, next);
            return next;
        }

        NetworkNodeRecord enabled(NetworkNodeRecord node, boolean enabled) {
            var next = data().setNodeEnabled(node.nodeId(), node.revision(), enabled)
                    .orElseThrow();
            update(node, next);
            return next;
        }

        ServerConfig.State config(long revision, boolean enabled, int owner, int server) {
            var s = config.settings();
            return new ServerConfig.State(
                    1,
                    revision,
                    true,
                    new ServerSettings(
                            s.networksPerOwner(),
                            s.tunnelsPerNetwork(),
                            s.channelsPerTunnel(),
                            s.channelBindingsPerDirectNode(),
                            s.administratorsPerNetwork(),
                            s.scheduler(),
                            s.filterLimits(),
                            s.recoveryLimits(),
                            s.storageVariantLimitPerNetwork(),
                            s.terminalSync(),
                            s.directStorageAccess(),
                            new ServerSettings.ChunkLoading(enabled, owner, server)));
        }

        public void close() throws Exception {
            runtime.close();
            management.close();
            authority.close();
            net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
            try (var paths = Files.walk(path)) {
                for (var p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
    }
}
