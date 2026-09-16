// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.node;

import com.mojang.authlib.GameProfile;
import io.github.loongin.omniresonance.bootstrap.OmniResonanceMod;
import io.github.loongin.omniresonance.config.ServerConfig;
import io.github.loongin.omniresonance.config.ServerSettings;
import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.network.NetworkDirectory;
import io.github.loongin.omniresonance.network.NetworkMetadata;
import io.github.loongin.omniresonance.network.NetworkTopologyService;
import io.github.loongin.omniresonance.networking.NodeMenuRequest;
import io.github.loongin.omniresonance.networking.NodeMenuResponse;
import io.github.loongin.omniresonance.networking.NodeMenuState;
import io.github.loongin.omniresonance.networking.NodeNetworkPage;
import io.github.loongin.omniresonance.persistence.NetworkSavedData;
import io.github.loongin.omniresonance.persistence.SavedNetworkRepository;
import io.github.loongin.omniresonance.registry.ModBlocks;
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
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.common.IOUtilities;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Server Menu state-machine contracts using actual players, world nodes, SavedData and edit locks. */
@GameTestHolder(OmniResonanceMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NodeMenuOperationsGameTests {
    private static final UUID OWNER = new UUID(111, 1);
    private static final UUID ADMIN = new UUID(111, 2);
    private static final UUID STRANGER = new UUID(111, 3);
    private static final UUID NODE = new UUID(112, 1);
    private static final UUID SESSION_A = new UUID(113, 1);
    private static final UUID SESSION_B = new UUID(113, 2);

    private NodeMenuOperationsGameTests() {}

    /** Wrong sender/envelope/replay never consumes the next valid operation or acquires authority. */
    @GameTest(template = "bootstrap")
    public static void envelopeAndReplayAreBoundToTheActualMenu(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper, 1)) {
            BlockPos pos = helper.absolutePos(new BlockPos(2, 3, 2));
            ResonanceNodeBlockEntity entity = place(helper, pos, NODE);
            fixture.authority.link(fixture.networkId(0), entity, new ManagedName("Node"));
            ServerPlayer owner = player(helper, OWNER, pos);
            ServerPlayer administrator = player(helper, ADMIN, pos);
            ResonanceNodeMenu menu = fixture.menus.createMenu(21, owner, pos, SESSION_A);

            failure(
                    helper,
                    menu.handle(owner, new NodeMenuRequest.BeginRename(22, SESSION_A, 1)),
                    NodeMenuResponse.Reason.INVALID_REQUEST);
            failure(
                    helper,
                    menu.handle(owner, new NodeMenuRequest.BeginRename(21, SESSION_B, 1)),
                    NodeMenuResponse.Reason.INVALID_REQUEST);
            failure(
                    helper,
                    menu.handle(administrator, new NodeMenuRequest.BeginRename(21, SESSION_A, 1)),
                    NodeMenuResponse.Reason.INVALID_REQUEST);
            state(
                    helper,
                    menu.handle(owner, new NodeMenuRequest.BeginRename(21, SESSION_A, 1)),
                    NodeMenuState.LinkedRename.class);
            failure(
                    helper,
                    menu.handle(owner, new NodeMenuRequest.BeginRename(21, SESSION_A, 1)),
                    NodeMenuResponse.Reason.STALE_REQUEST);
            state(
                    helper,
                    menu.handle(owner, new NodeMenuRequest.CancelEdit(21, SESSION_A, 2)),
                    NodeMenuState.ModeRoot.class);
            menu.removed(owner);
            helper.succeed();
        }
    }

    /** Canceling an unlinked node edit returns to selection and releases the blank-node lease. */
    @GameTest(template = "bootstrap")
    public static void blankEditCancelReturnsToSelectionAndReleasesLock(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper, 1)) {
            BlockPos pos = helper.absolutePos(new BlockPos(2, 3, 2));
            place(helper, pos, NODE);
            ServerPlayer owner = player(helper, OWNER, pos);
            ServerPlayer administrator = player(helper, ADMIN, pos);
            ResonanceNodeMenu first = fixture.menus.createMenu(23, owner, pos, SESSION_A);
            ResonanceNodeMenu second = fixture.menus.createMenu(24, administrator, pos, SESSION_B);
            UUID networkId = fixture.networkId(0);

            state(
                    helper,
                    first.handle(owner, new NodeMenuRequest.BeginBlank(23, SESSION_A, 1, networkId)),
                    NodeMenuState.BlankEdit.class);
            state(
                    helper,
                    first.handle(owner, new NodeMenuRequest.CancelEdit(23, SESSION_A, 2)),
                    NodeMenuState.BlankList.class);
            state(
                    helper,
                    second.handle(administrator, new NodeMenuRequest.BeginBlank(24, SESSION_B, 1, networkId)),
                    NodeMenuState.BlankEdit.class);
            state(
                    helper,
                    second.handle(administrator, new NodeMenuRequest.CancelEdit(24, SESSION_B, 2)),
                    NodeMenuState.BlankList.class);
            first.removed(owner);
            second.removed(administrator);
            helper.succeed();
        }
    }

    /** Blank paging, edit locking, heartbeat, invalid-name retry and link commit are one bounded session. */
    @GameTest(template = "bootstrap")
    public static void blankFlowPagesLocksRetriesAndLinks(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper, 130)) {
            BlockPos pos = helper.absolutePos(new BlockPos(2, 3, 2));
            place(helper, pos, NODE);
            ServerPlayer owner = player(helper, OWNER, pos);
            ServerPlayer administrator = player(helper, ADMIN, pos);
            ResonanceNodeMenu first = fixture.menus.createMenu(31, owner, pos, SESSION_A);
            ResonanceNodeMenu second = fixture.menus.createMenu(32, administrator, pos, SESSION_B);

            NodeNetworkPage firstPage = ((NodeMenuState.BlankList) first.state()).page();
            helper.assertTrue(
                    firstPage.entries().size() == 128 && firstPage.totalCount() == 130 && firstPage.hasNext(),
                    "Initial blank network page was not bounded");
            UUID anchor = firstPage.entries().getLast().networkId();
            failure(
                    helper,
                    first.handle(owner, new NodeMenuRequest.Page(31, SESSION_A, 1, new UUID(999, 1), false)),
                    NodeMenuResponse.Reason.INVALID_REQUEST);
            NodeMenuState.BlankList next = (NodeMenuState.BlankList) state(
                            helper,
                            first.handle(owner, new NodeMenuRequest.Page(31, SESSION_A, 2, anchor, false)),
                            NodeMenuState.BlankList.class)
                    .state();
            helper.assertTrue(
                    next.page().entries().size() == 2
                            && next.page().hasPrevious()
                            && !next.page().hasNext(),
                    "Second blank network page was incorrect");

            UUID selected = fixture.networkId(129);
            state(
                    helper,
                    first.handle(owner, new NodeMenuRequest.BeginBlank(31, SESSION_A, 3, selected)),
                    NodeMenuState.BlankEdit.class);
            failure(
                    helper,
                    second.handle(administrator, new NodeMenuRequest.BeginBlank(32, SESSION_B, 1, selected)),
                    NodeMenuResponse.Reason.LOCKED);
            helper.assertTrue(
                    first.handle(owner, new NodeMenuRequest.Heartbeat(31, SESSION_A, 4)) == null,
                    "Successful heartbeat returned a full response");
            failure(
                    helper,
                    first.handle(owner, new NodeMenuRequest.Link(31, SESSION_A, 5, " ")),
                    NodeMenuResponse.Reason.INVALID_NAME);
            NodeMenuResponse.State linked = state(
                    helper,
                    first.handle(owner, new NodeMenuRequest.Link(31, SESSION_A, 6, "Linked")),
                    NodeMenuState.ModeRoot.class);
            helper.assertTrue(
                    linked.state() instanceof NodeMenuState.ModeRoot root
                            && root.node().networkId().equals(selected)
                            && fixture.network(selected).findNode(NODE).isPresent(),
                    "Blank link did not publish selected authority");
            state(
                    helper,
                    second.handle(administrator, new NodeMenuRequest.BeginRename(32, SESSION_B, 2)),
                    NodeMenuState.LinkedRename.class);
            second.handle(administrator, new NodeMenuRequest.CancelEdit(32, SESSION_B, 3));
            first.removed(owner);
            second.removed(administrator);
            helper.succeed();
        }
    }

    /** Linked operations serialize edits, preserve disabled settings and require explicit mode reset confirmation. */
    @GameTest(template = "bootstrap")
    public static void linkedOperationsRespectLocksRevisionAndDisabledState(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper, 1)) {
            BlockPos pos = helper.absolutePos(new BlockPos(2, 3, 2));
            ResonanceNodeBlockEntity entity = place(helper, pos, NODE);
            UUID networkId = fixture.networkId(0);
            fixture.authority.link(networkId, entity, new ManagedName("Node"));
            ServerPlayer owner = player(helper, OWNER, pos);
            ServerPlayer administrator = player(helper, ADMIN, pos);
            ResonanceNodeMenu first = fixture.menus.createMenu(41, owner, pos, SESSION_A);
            ResonanceNodeMenu second = fixture.menus.createMenu(42, administrator, pos, SESSION_B);

            state(
                    helper,
                    first.handle(owner, new NodeMenuRequest.BeginRename(41, SESSION_A, 1)),
                    NodeMenuState.LinkedRename.class);
            failure(
                    helper,
                    second.handle(administrator, new NodeMenuRequest.BeginMode(42, SESSION_B, 1)),
                    NodeMenuResponse.Reason.LOCKED);
            NodeMenuResponse.State renamed = state(
                    helper,
                    first.handle(owner, new NodeMenuRequest.Rename(41, SESSION_A, 2, "Input")),
                    NodeMenuState.ModeRoot.class);
            helper.assertTrue(node(renamed).nodeName().equals("Input"), "Rename did not publish authority");

            state(
                    helper,
                    second.handle(administrator, new NodeMenuRequest.BeginMode(42, SESSION_B, 2)),
                    NodeMenuState.LinkedMode.class);
            NodeMenuResponse.State direct = state(
                    helper,
                    second.handle(administrator, new NodeMenuRequest.SetMode(42, SESSION_B, 3, NodeMode.DIRECT, false)),
                    NodeMenuState.DirectTunnelList.class);
            helper.assertTrue(node(direct).mode() == NodeMode.DIRECT, "Initial mode selection failed");

            NodeMenuResponse.State requested = state(
                    helper,
                    first.handle(owner, new NodeMenuRequest.SetChunkLoadingRequested(41, SESSION_A, 3, true)),
                    NodeMenuState.ModeRoot.class);
            helper.assertTrue(node(requested).chunkLoadingRequested(), "Request toggle was not authoritative");
            NodeMenuResponse.State disabled = state(
                    helper,
                    first.handle(owner, new NodeMenuRequest.SetEnabled(41, SESSION_A, 4, false)),
                    NodeMenuState.ModeRoot.class);
            helper.assertTrue(
                    !node(disabled).enabled()
                            && node(disabled).chunkLoadingRequested()
                            && node(disabled).mode() == NodeMode.DIRECT,
                    "Disabling discarded request or mode");
            failure(
                    helper,
                    first.handle(owner, new NodeMenuRequest.BeginRename(41, SESSION_A, 5)),
                    NodeMenuResponse.Reason.NODE_DISABLED);
            state(
                    helper,
                    first.handle(owner, new NodeMenuRequest.SetEnabled(41, SESSION_A, 6, true)),
                    NodeMenuState.DirectTunnelList.class);

            state(
                    helper,
                    first.handle(owner, new NodeMenuRequest.OpenModeRoot(41, SESSION_A, 7)),
                    NodeMenuState.ModeRoot.class);
            state(
                    helper,
                    first.handle(owner, new NodeMenuRequest.BeginMode(41, SESSION_A, 8)),
                    NodeMenuState.LinkedMode.class);
            failure(
                    helper,
                    first.handle(owner, new NodeMenuRequest.SetMode(41, SESSION_A, 9, NodeMode.DOMAIN, false)),
                    NodeMenuResponse.Reason.RESET_REQUIRED);
            NodeMenuResponse.State domain = state(
                    helper,
                    first.handle(owner, new NodeMenuRequest.SetMode(41, SESSION_A, 10, NodeMode.DOMAIN, true)),
                    NodeMenuState.DomainRoot.class);
            helper.assertTrue(node(domain).mode() == NodeMode.DOMAIN, "Confirmed mode switch failed");
            first.removed(owner);
            second.removed(administrator);
            helper.succeed();
        }
    }

    /** Menu removal and expired heartbeat release exact edits for another viewer without persistent side effects. */
    @GameTest(template = "bootstrap")
    public static void menuCloseAndHeartbeatExpiryReleaseEdits(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper, 1)) {
            BlockPos pos = helper.absolutePos(new BlockPos(2, 3, 2));
            ResonanceNodeBlockEntity entity = place(helper, pos, NODE);
            fixture.authority.link(fixture.networkId(0), entity, new ManagedName("Node"));
            ServerPlayer owner = player(helper, OWNER, pos);
            ServerPlayer administrator = player(helper, ADMIN, pos);
            ResonanceNodeMenu first = fixture.menus.createMenu(51, owner, pos, SESSION_A);
            ResonanceNodeMenu second = fixture.menus.createMenu(52, administrator, pos, SESSION_B);

            first.handle(owner, new NodeMenuRequest.BeginRename(51, SESSION_A, 1));
            first.removed(owner);
            state(
                    helper,
                    second.handle(administrator, new NodeMenuRequest.BeginRename(52, SESSION_B, 1)),
                    NodeMenuState.LinkedRename.class);
            second.handle(administrator, new NodeMenuRequest.CancelEdit(52, SESSION_B, 2));

            ResonanceNodeMenu expiring = fixture.menus.createMenu(53, owner, pos, new UUID(113, 3));
            expiring.handle(owner, new NodeMenuRequest.BeginMode(53, new UUID(113, 3), 1));
            for (int tick = 0; tick < 200; tick++) {
                fixture.management.tick();
            }
            failure(
                    helper,
                    expiring.handle(owner, new NodeMenuRequest.Heartbeat(53, new UUID(113, 3), 2)),
                    NodeMenuResponse.Reason.LOCK_EXPIRED);
            state(
                    helper,
                    second.handle(administrator, new NodeMenuRequest.BeginMode(52, SESSION_B, 3)),
                    NodeMenuState.LinkedMode.class);
            second.handle(administrator, new NodeMenuRequest.CancelEdit(52, SESSION_B, 4));
            second.removed(administrator);
            expiring.removed(owner);
            helper.succeed();
        }
    }

    private static NodeMenuResponse.State state(
            GameTestHelper helper, NodeMenuResponse response, Class<? extends NodeMenuState> expected) {
        helper.assertTrue(
                response instanceof NodeMenuResponse.State state && expected.isInstance(state.state()),
                "Expected state " + expected.getSimpleName() + ", got " + response);
        return (NodeMenuResponse.State) response;
    }

    private static void failure(GameTestHelper helper, NodeMenuResponse response, NodeMenuResponse.Reason expected) {
        helper.assertTrue(
                response instanceof NodeMenuResponse.Failure failure && failure.reason() == expected,
                "Expected failure " + expected + ", got " + response);
    }

    private static io.github.loongin.omniresonance.networking.NodeMenuNodeSummary node(
            NodeMenuResponse.State response) {
        return switch (response.state()) {
            case NodeMenuState.ModeRoot root -> root.node();
            case NodeMenuState.DirectTunnelList list -> list.node();
            case NodeMenuState.DomainRoot root -> root.node();
            default -> throw new IllegalArgumentException("Response has no routed node summary");
        };
    }

    private static ResonanceNodeBlockEntity place(GameTestHelper helper, BlockPos position, UUID nodeId) {
        BlockState state = ModBlocks.RESONANCE_TRANSFER_NODE
                .get()
                .defaultBlockState()
                .setValue(AbstractResonanceNodeBlock.FACING, Direction.DOWN);
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

    private static ServerPlayer player(GameTestHelper helper, UUID id, BlockPos near) {
        FakePlayer player =
                new FakePlayer(helper.getLevel(), new GameProfile(id, "Operations" + id.getLeastSignificantBits()));
        player.setPos(near.getX() + 0.5, near.getY() + 0.5, near.getZ() + 0.5);
        return player;
    }

    private static final class Fixture implements AutoCloseable {
        private final Path path;
        private final SavedNetworkRepository repository;
        private final List<NetworkMetadata> metadata;
        private final NodeAuthorityService authority;
        private final NodeManagementService management;
        private final NetworkTopologyService topology;
        private final NodeMenuService menus;

        private Fixture(GameTestHelper helper, int networkCount) throws IOException {
            path = Files.createTempDirectory("omniresonance-node-menu-operations-test-");
            DimensionDataStorage storage = new DimensionDataStorage(
                    path.toFile(), DataFixers.getDataFixer(), helper.getLevel().registryAccess());
            repository = new SavedNetworkRepository(storage, path);
            metadata = new ArrayList<>(networkCount);
            for (int index = 0; index < networkCount; index++) {
                NetworkMetadata entry = new NetworkMetadata(
                        new UUID(110, index + 1), OWNER, new ManagedName("Network " + index), index, Set.of(ADMIN));
                repository.createNetwork(entry);
                metadata.add(entry);
            }
            NetworkDirectory networks = new NetworkDirectory(metadata);
            NetworkNodeDirectory nodes = new NetworkNodeDirectory(List.of());
            ArrayDeque<UUID> replacements = new ArrayDeque<>(List.of(new UUID(114, 1), new UUID(114, 2)));
            authority = new NodeAuthorityService(
                    helper.getLevel().getServer(), repository, nodes, replacements::removeFirst);
            EditLockTable locks = new EditLockTable();
            management = new NodeManagementService(
                    helper.getLevel().getServer(), networks, repository, nodes, authority, locks);
            management.installChunkAdmission((network, node, moving) ->
                    io.github.loongin.omniresonance.chunkloading.ChunkLoadingReservations.Admission.ALLOWED);
            topology = new NetworkTopologyService(
                    helper.getLevel().getServer(),
                    networks,
                    repository,
                    nodes,
                    locks,
                    new ServerConfig.State(1, 1, true, ServerSettings.defaults()),
                    () -> new UUID(116, 1));
            menus = new NodeMenuService(
                    helper.getLevel().getServer(), management, topology, networks, () -> new UUID(115, 1));
        }

        private UUID networkId(int index) {
            return metadata.get(index).id();
        }

        private NetworkSavedData network(UUID id) {
            return repository.findLoadedNetwork(id).orElseThrow();
        }

        @Override
        public void close() throws IOException {
            menus.close();
            topology.close();
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
