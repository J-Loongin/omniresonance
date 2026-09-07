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
import io.github.loongin.omniresonance.network.NetworkTunnelRecord;
import io.github.loongin.omniresonance.network.TransferDirection;
import io.github.loongin.omniresonance.networking.NodeMenuRequest;
import io.github.loongin.omniresonance.networking.NodeMenuResponse;
import io.github.loongin.omniresonance.networking.NodeMenuState;
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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.common.IOUtilities;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Physical node hierarchy contracts over real SavedData, shared locks and exact server senders. */
@GameTestHolder(OmniResonanceMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NodeRoutingMenuGameTests {
    private static final UUID SOURCE = new UUID(510, 1);
    private static final UUID TARGET = new UUID(510, 2);
    private static final UUID OWNER = new UUID(511, 1);
    private static final UUID ADMIN = new UUID(511, 2);
    private static final UUID NODE = new UUID(512, 1);
    private static final UUID OTHER_NODE = new UUID(512, 2);
    private static final UUID SESSION = new UUID(513, 1);
    private static final UUID TUNNEL = new UUID(514, 1);
    private static final UUID DISABLED_TUNNEL = new UUID(514, 2);
    private static final UUID TARGET_TUNNEL = new UUID(514, 3);
    private static final UUID CHANNEL = new UUID(515, 1);
    private static final UUID TARGET_CHANNEL = new UUID(515, 3);

    private NodeRoutingMenuGameTests() {}

    @GameTest(template = "bootstrap")
    public static void revocationClosesOnlyTheReferencedNodeMenuAndReleasesItsEdit(GameTestHelper helper)
            throws IOException {
        try (Fixture fixture = new Fixture(helper)) {
            BlockPos position = helper.absolutePos(new BlockPos(2, 3, 2));
            fixture.authority.link(SOURCE, place(helper, position), new ManagedName("Node"));
            ServerPlayer admin = player(helper, ADMIN, position);
            ResonanceNodeMenu menu = fixture.menus.createMenu(89, admin, position, SESSION);
            admin.containerMenu = menu;
            state(
                    helper,
                    menu.handle(admin, new NodeMenuRequest.BeginRename(89, SESSION, 1)),
                    NodeMenuState.LinkedRename.class);
            fixture.menus.revokeNetworkAccess(admin, TARGET);
            helper.assertTrue(admin.containerMenu == menu, "Another network's revocation closed this node");
            fixture.menus.revokeNetworkAccess(admin, SOURCE);
            helper.assertTrue(admin.containerMenu == admin.inventoryMenu, "Revoked node menu remained open");
            ServerPlayer owner = player(helper, OWNER, position);
            NodeManagementService.LinkedEdit edit = fixture.management.acquireLinked(owner, SOURCE, NODE);
            fixture.management.cancel(owner, edit.token());
            helper.succeed();
        }
    }

    /** Catalog refreshes read current server names and revisions without any client search dependency. */
    @GameTest(template = "bootstrap")
    public static void directoryRefreshObservesRenamesAndNewRevisions(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper)) {
            BlockPos position = helper.absolutePos(new BlockPos(2, 3, 2));
            ResonanceNodeBlockEntity entity = place(helper, position);
            fixture.authority.link(SOURCE, entity, new ManagedName("Node"));
            fixture.seedTunnels();
            fixture.setDirectMode();
            ServerPlayer owner = player(helper, OWNER, position);
            ResonanceNodeMenu menu = fixture.menus.createMenu(77, owner, position, SESSION);
            NodeMenuState.DirectTunnelList first = (NodeMenuState.DirectTunnelList) menu.state();
            NetworkTunnelRecord tunnel = fixture.source.findTunnel(TUNNEL).orElseThrow();
            fixture.source
                    .renameTunnel(TUNNEL, tunnel.revision(), new ManagedName("Renamed"))
                    .orElseThrow();
            NodeMenuState.DirectTunnelList next = (NodeMenuState.DirectTunnelList) state(
                            helper,
                            menu.handle(owner, new NodeMenuRequest.PageTunnels(77, SESSION, 1, null, false)),
                            NodeMenuState.DirectTunnelList.class)
                    .state();
            helper.assertTrue(next.revision() > first.revision(), "Rename did not invalidate the catalog revision");
            helper.assertTrue(
                    next.page().entries().getFirst().name().equals("Renamed"),
                    "Catalog refresh did not return the latest name");
            helper.succeed();
        }
    }

    /** Direct navigation pages a revisioned directory and edits only this node's channel direction. */
    @GameTest(template = "bootstrap")
    public static void directHierarchyPagesAndEditsBindings(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper)) {
            BlockPos position = helper.absolutePos(new BlockPos(2, 3, 2));
            ResonanceNodeBlockEntity entity = place(helper, position);
            fixture.authority.link(SOURCE, entity, new ManagedName("Node"));
            fixture.seedTunnels();
            ServerPlayer owner = player(helper, OWNER, position);
            ResonanceNodeMenu menu = fixture.menus.createMenu(71, owner, position, SESSION);

            helper.assertTrue(
                    menu.state() instanceof NodeMenuState.ModeRoot, "Unconfigured node did not open mode root");
            state(
                    helper,
                    menu.handle(owner, new NodeMenuRequest.BeginMode(71, SESSION, 1)),
                    NodeMenuState.LinkedMode.class);
            state(
                    helper,
                    menu.handle(owner, new NodeMenuRequest.SetMode(71, SESSION, 2, NodeMode.DIRECT, false)),
                    NodeMenuState.DirectTunnelList.class);
            NodeMenuState.DirectTunnelList searched = (NodeMenuState.DirectTunnelList) state(
                            helper,
                            menu.handle(owner, new NodeMenuRequest.PageTunnels(71, SESSION, 3, null, false)),
                            NodeMenuState.DirectTunnelList.class)
                    .state();
            helper.assertTrue(
                    searched.page().entries().size() == 3
                            && searched.page().entries().getFirst().tunnelId().equals(TUNNEL),
                    "Tunnel catalog did not return the unfiltered bounded page");
            helper.assertTrue(
                    searched.revision() == fixture.source.topologyRevision(),
                    "Catalog batch did not carry its topology revision");
            state(
                    helper,
                    menu.handle(owner, new NodeMenuRequest.OpenDirect(71, SESSION, 4)),
                    NodeMenuState.DirectTunnelList.class);
            state(
                    helper,
                    menu.handle(owner, new NodeMenuRequest.OpenTunnel(71, SESSION, 5, DISABLED_TUNNEL)),
                    NodeMenuState.RestrictedTunnel.class);
            state(
                    helper,
                    menu.handle(owner, new NodeMenuRequest.Back(71, SESSION, 6)),
                    NodeMenuState.DirectTunnelList.class);
            state(
                    helper,
                    menu.handle(owner, new NodeMenuRequest.OpenTunnel(71, SESSION, 7, TUNNEL)),
                    NodeMenuState.DirectChannelList.class);
            state(
                    helper,
                    menu.handle(owner, new NodeMenuRequest.OpenChannel(71, SESSION, 8, CHANNEL)),
                    NodeMenuState.DirectChannelRoot.class);
            NodeMenuState.DirectBindingEdit draft = (NodeMenuState.DirectBindingEdit) state(
                            helper,
                            menu.handle(owner, new NodeMenuRequest.BeginBinding(71, SESSION, 9, CHANNEL)),
                            NodeMenuState.DirectBindingEdit.class)
                    .state();
            helper.assertTrue(draft.channel().currentDirection() == null, "Unjoined channel was not an empty draft");
            NodeMenuState.DirectChannelRoot joined = (NodeMenuState.DirectChannelRoot) state(
                            helper,
                            menu.handle(
                                    owner,
                                    new NodeMenuRequest.SetBindingDirection(
                                            71, SESSION, 10, TransferDirection.INPUT, false)),
                            NodeMenuState.DirectChannelRoot.class)
                    .state();
            helper.assertTrue(
                    joined.channel().currentDirection() == TransferDirection.INPUT,
                    "Binding commit did not publish current direction");

            state(
                    helper,
                    menu.handle(owner, new NodeMenuRequest.OpenChannelSettings(71, SESSION, 11)),
                    NodeMenuState.DirectChannelSettings.class);
            state(
                    helper,
                    menu.handle(owner, new NodeMenuRequest.BeginBinding(71, SESSION, 12, CHANNEL)),
                    NodeMenuState.DirectBindingEdit.class);
            failure(
                    helper,
                    menu.handle(
                            owner,
                            new NodeMenuRequest.SetBindingDirection(71, SESSION, 13, TransferDirection.OUTPUT, false)),
                    NodeMenuResponse.Reason.RESET_REQUIRED);
            state(
                    helper,
                    menu.handle(
                            owner,
                            new NodeMenuRequest.SetBindingDirection(71, SESSION, 14, TransferDirection.OUTPUT, true)),
                    NodeMenuState.DirectChannelRoot.class);
            state(
                    helper,
                    menu.handle(owner, new NodeMenuRequest.OpenChannelSettings(71, SESSION, 15)),
                    NodeMenuState.DirectChannelSettings.class);
            state(
                    helper,
                    menu.handle(owner, new NodeMenuRequest.RemoveBinding(71, SESSION, 16)),
                    NodeMenuState.DirectChannelRoot.class);
            helper.assertTrue(fixture.source.directBindings(NODE).isEmpty(), "Exit channel retained the binding");
            menu.removed(owner);
            helper.succeed();
        }
    }

    /** DIRECT nodes own channel CRUD while creation stays unbound and the final channel cannot be deleted. */
    @GameTest(template = "bootstrap")
    public static void directNodeManagesChannelsWithoutImplicitBinding(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper)) {
            BlockPos position = helper.absolutePos(new BlockPos(2, 3, 2));
            fixture.authority.link(SOURCE, place(helper, position), new ManagedName("Node"));
            fixture.seedTunnels();
            ServerPlayer owner = player(helper, OWNER, position);
            ResonanceNodeMenu menu = fixture.menus.createMenu(74, owner, position, SESSION);

            menu.handle(owner, new NodeMenuRequest.BeginMode(74, SESSION, 1));
            menu.handle(owner, new NodeMenuRequest.SetMode(74, SESSION, 2, NodeMode.DIRECT, false));
            menu.handle(owner, new NodeMenuRequest.OpenTunnel(74, SESSION, 3, TUNNEL));
            NodeMenuState.DirectChannelEdit create = (NodeMenuState.DirectChannelEdit) state(
                            helper,
                            menu.handle(owner, new NodeMenuRequest.BeginCreateChannel(74, SESSION, 4, "Channel")),
                            NodeMenuState.DirectChannelEdit.class)
                    .state();
            helper.assertTrue(
                    create.existing() == null && create.suggestedName().equals("Channel 2"),
                    "Node did not receive the smallest channel suggestion");
            state(
                    helper,
                    menu.handle(owner, new NodeMenuRequest.SaveChannel(74, SESSION, 5, "Channel 2")),
                    NodeMenuState.DirectChannelList.class);
            UUID createdId = new UUID(517, 1);
            helper.assertTrue(
                    fixture.source.findChannel(createdId).isPresent()
                            && fixture.source.directBindings(NODE).isEmpty(),
                    "Channel creation implicitly joined the current node");

            state(
                    helper,
                    menu.handle(owner, new NodeMenuRequest.OpenChannel(74, SESSION, 6, createdId)),
                    NodeMenuState.DirectChannelRoot.class);
            state(
                    helper,
                    menu.handle(owner, new NodeMenuRequest.OpenChannelSettings(74, SESSION, 7)),
                    NodeMenuState.DirectChannelSettings.class);
            menu.handle(owner, new NodeMenuRequest.BeginRenameChannel(74, SESSION, 8, createdId));
            state(
                    helper,
                    menu.handle(owner, new NodeMenuRequest.SaveChannel(74, SESSION, 9, "Spare")),
                    NodeMenuState.DirectChannelRoot.class);
            menu.handle(owner, new NodeMenuRequest.OpenChannelSettings(74, SESSION, 10));
            state(
                    helper,
                    menu.handle(owner, new NodeMenuRequest.RequestDeleteChannel(74, SESSION, 11, createdId)),
                    NodeMenuState.DirectChannelDelete.class);
            state(
                    helper,
                    menu.handle(owner, new NodeMenuRequest.ConfirmDeleteChannel(74, SESSION, 12)),
                    NodeMenuState.DirectChannelList.class);
            menu.handle(owner, new NodeMenuRequest.OpenChannel(74, SESSION, 13, CHANNEL));
            menu.handle(owner, new NodeMenuRequest.OpenChannelSettings(74, SESSION, 14));
            failure(
                    helper,
                    menu.handle(owner, new NodeMenuRequest.RequestDeleteChannel(74, SESSION, 15, CHANNEL)),
                    NodeMenuResponse.Reason.LAST_CHANNEL);
            menu.handle(owner, new NodeMenuRequest.Back(74, SESSION, 16));
            menu.handle(owner, new NodeMenuRequest.Back(74, SESSION, 17));
            NodeMenuState.DirectChannelEdit reused = (NodeMenuState.DirectChannelEdit) state(
                            helper,
                            menu.handle(owner, new NodeMenuRequest.BeginCreateChannel(74, SESSION, 18, "Channel")),
                            NodeMenuState.DirectChannelEdit.class)
                    .state();
            helper.assertTrue(reused.suggestedName().equals("Channel 2"), "Deleted display name gap was not reused");
            menu.handle(owner, new NodeMenuRequest.CancelEdit(74, SESSION, 19));
            menu.removed(owner);
            helper.succeed();
        }
    }

    /** Reopening a DIRECT node derives its unique tunnel only from persisted channel bindings. */
    @GameTest(template = "bootstrap")
    public static void directInitialRouteUsesOnlyPersistedBindings(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper)) {
            BlockPos position = helper.absolutePos(new BlockPos(2, 3, 2));
            fixture.authority.link(SOURCE, place(helper, position), new ManagedName("Node"));
            fixture.seedTunnels();
            fixture.setDirectMode();
            ServerPlayer owner = player(helper, OWNER, position);

            ResonanceNodeMenu unbound = fixture.menus.createMenu(75, owner, position, new UUID(513, 2));
            state(
                    helper,
                    unbound.handle(owner, new NodeMenuRequest.OpenTunnel(75, new UUID(513, 2), 1, TUNNEL)),
                    NodeMenuState.DirectChannelList.class);
            unbound.removed(owner);
            helper.assertTrue(fixture.source.directTunnelId(NODE).isEmpty(), "Browsing persisted a tunnel");
            helper.assertTrue(
                    fixture.menus
                                    .createMenu(76, owner, position, new UUID(513, 3))
                                    .state()
                            instanceof NodeMenuState.DirectTunnelList,
                    "Unbound node did not reopen at tunnel list");

            fixture.bindNode(CHANNEL);
            helper.assertTrue(
                    fixture.menus
                                            .createMenu(77, owner, position, new UUID(513, 4))
                                            .state()
                                    instanceof NodeMenuState.DirectChannelList list
                            && list.tunnel().tunnelId().equals(TUNNEL),
                    "Bound node did not reopen at its channel list");

            NetworkTunnelRecord tunnel = fixture.source.findTunnel(TUNNEL).orElseThrow();
            fixture.source.setTunnelEnabled(TUNNEL, tunnel.revision(), false).orElseThrow();
            helper.assertTrue(
                    fixture.menus
                                            .createMenu(78, owner, position, new UUID(513, 5))
                                            .state()
                                    instanceof NodeMenuState.RestrictedTunnel restricted
                            && restricted.tunnel().tunnelId().equals(TUNNEL),
                    "Disabled bound tunnel did not reopen at its restriction");
            helper.succeed();
        }
    }

    /** Channel rows enter lock-free detail/settings pages before explicit configuration or management edits. */
    @GameTest(template = "bootstrap")
    public static void channelDetailsAndSettingsRemainReadOnlyUntilAnAction(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper)) {
            BlockPos position = helper.absolutePos(new BlockPos(2, 3, 2));
            fixture.authority.link(SOURCE, place(helper, position), new ManagedName("Node"));
            fixture.seedTunnels();
            fixture.setDirectMode();
            ServerPlayer owner = player(helper, OWNER, position);
            ServerPlayer administrator = player(helper, ADMIN, position);
            ResonanceNodeMenu menu = fixture.menus.createMenu(79, owner, position, new UUID(513, 6));

            menu.handle(owner, new NodeMenuRequest.OpenTunnel(79, new UUID(513, 6), 1, TUNNEL));
            state(
                    helper,
                    menu.handle(owner, new NodeMenuRequest.OpenChannel(79, new UUID(513, 6), 2, CHANNEL)),
                    NodeMenuState.DirectChannelRoot.class);
            NetworkTopologyService.Edit nodeEdit = fixture.topology.acquireNode(administrator, SOURCE, NODE);
            fixture.topology.cancel(administrator, nodeEdit);
            state(
                    helper,
                    menu.handle(owner, new NodeMenuRequest.OpenChannelSettings(79, new UUID(513, 6), 3)),
                    NodeMenuState.DirectChannelSettings.class);
            NetworkTopologyService.Edit channelEdit = fixture.topology.acquireChannel(administrator, SOURCE, CHANNEL);
            fixture.topology.cancel(administrator, channelEdit);
            state(
                    helper,
                    menu.handle(owner, new NodeMenuRequest.Back(79, new UUID(513, 6), 4)),
                    NodeMenuState.DirectChannelRoot.class);
            state(
                    helper,
                    menu.handle(owner, new NodeMenuRequest.Back(79, new UUID(513, 6), 5)),
                    NodeMenuState.DirectChannelList.class);
            helper.succeed();
        }
    }

    /** Selecting another enabled tunnel requires confirmation and never persists the target before a channel join. */
    @GameTest(template = "bootstrap")
    public static void directTunnelSelectionConfirmsAndClearsOldBindings(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper)) {
            BlockPos position = helper.absolutePos(new BlockPos(2, 3, 2));
            fixture.authority.link(SOURCE, place(helper, position), new ManagedName("Node"));
            fixture.seedTunnels();
            fixture.setDirectMode();
            fixture.bindNode(CHANNEL);
            ServerPlayer owner = player(helper, OWNER, position);
            UUID session = new UUID(513, 7);
            ResonanceNodeMenu menu = fixture.menus.createMenu(80, owner, position, session);

            state(
                    helper,
                    menu.handle(owner, new NodeMenuRequest.Back(80, session, 1)),
                    NodeMenuState.DirectTunnelList.class);
            NodeMenuState.DirectTunnelSwitch confirmation = (NodeMenuState.DirectTunnelSwitch) state(
                            helper,
                            menu.handle(owner, new NodeMenuRequest.OpenTunnel(80, session, 2, TARGET_TUNNEL)),
                            NodeMenuState.DirectTunnelSwitch.class)
                    .state();
            helper.assertTrue(
                    confirmation.summary().removedBindingCount() == 1,
                    "Tunnel switch did not report the old binding count");
            state(
                    helper,
                    menu.handle(owner, new NodeMenuRequest.Back(80, session, 3)),
                    NodeMenuState.DirectTunnelList.class);
            helper.assertTrue(fixture.source.directBindings(NODE).size() == 1, "Canceled switch removed binding");

            menu.handle(owner, new NodeMenuRequest.OpenTunnel(80, session, 4, TARGET_TUNNEL));
            state(
                    helper,
                    menu.handle(owner, new NodeMenuRequest.ConfirmTunnelSwitch(80, session, 5)),
                    NodeMenuState.DirectChannelList.class);
            helper.assertTrue(fixture.source.directBindings(NODE).isEmpty(), "Confirmed switch retained old binding");
            helper.assertTrue(fixture.source.directTunnelId(NODE).isEmpty(), "Target tunnel was persisted early");
            helper.succeed();
        }
    }

    /** Domain direction remains unique, reset-confirmed and removable without changing node mode. */
    @GameTest(template = "bootstrap")
    public static void domainHierarchyEditsOneDirection(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper)) {
            BlockPos position = helper.absolutePos(new BlockPos(2, 3, 2));
            fixture.authority.link(SOURCE, place(helper, position), new ManagedName("Node"));
            ServerPlayer owner = player(helper, OWNER, position);
            ResonanceNodeMenu menu = fixture.menus.createMenu(72, owner, position, SESSION);

            menu.handle(owner, new NodeMenuRequest.BeginMode(72, SESSION, 1));
            state(
                    helper,
                    menu.handle(owner, new NodeMenuRequest.SetMode(72, SESSION, 2, NodeMode.DOMAIN, false)),
                    NodeMenuState.DomainRoot.class);
            state(
                    helper,
                    menu.handle(owner, new NodeMenuRequest.BeginDomainEdit(72, SESSION, 3)),
                    NodeMenuState.DomainEdit.class);
            state(
                    helper,
                    menu.handle(
                            owner,
                            new NodeMenuRequest.SetDomainDirection(72, SESSION, 4, TransferDirection.INPUT, false)),
                    NodeMenuState.DomainRoot.class);
            menu.handle(owner, new NodeMenuRequest.BeginDomainEdit(72, SESSION, 5));
            failure(
                    helper,
                    menu.handle(
                            owner,
                            new NodeMenuRequest.SetDomainDirection(72, SESSION, 6, TransferDirection.OUTPUT, false)),
                    NodeMenuResponse.Reason.RESET_REQUIRED);
            state(
                    helper,
                    menu.handle(
                            owner,
                            new NodeMenuRequest.SetDomainDirection(72, SESSION, 7, TransferDirection.OUTPUT, true)),
                    NodeMenuState.DomainRoot.class);
            menu.handle(owner, new NodeMenuRequest.BeginDomainEdit(72, SESSION, 8));
            state(
                    helper,
                    menu.handle(owner, new NodeMenuRequest.RemoveDomain(72, SESSION, 9)),
                    NodeMenuState.DomainRoot.class);
            helper.assertTrue(
                    fixture.source.domainConfiguration(NODE).isEmpty()
                            && fixture.source.findNode(NODE).orElseThrow().mode() == NodeMode.DOMAIN,
                    "Removing domain direction changed mode or retained configuration");
            menu.removed(owner);
            helper.succeed();
        }
    }

    /** Network selection acquires the shared node lock and retains a correctable target-name conflict. */
    @GameTest(template = "bootstrap")
    public static void networkMoveRetriesConflictAndRoutesTarget(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper)) {
            BlockPos position = helper.absolutePos(new BlockPos(2, 3, 2));
            fixture.authority.link(SOURCE, place(helper, position), new ManagedName("Node"));
            fixture.target.createNode(
                    OTHER_NODE,
                    new ManagedName("Node"),
                    GlobalPos.of(Level.OVERWORLD, new BlockPos(12, 64, 12)),
                    NodeForm.BLOCK,
                    Direction.DOWN);
            ServerPlayer owner = player(helper, OWNER, position);
            ResonanceNodeMenu menu = fixture.menus.createMenu(73, owner, position, SESSION);

            NodeMenuState.NetworkSelection selection = (NodeMenuState.NetworkSelection) state(
                            helper,
                            menu.handle(owner, new NodeMenuRequest.OpenNetworkSelection(73, SESSION, 1)),
                            NodeMenuState.NetworkSelection.class)
                    .state();
            helper.assertTrue(selection.page().totalCount() == 2, "Network selection omitted an accessible network");
            state(
                    helper,
                    menu.handle(owner, new NodeMenuRequest.BeginNetworkMove(73, SESSION, 2, TARGET)),
                    NodeMenuState.NetworkMoveEdit.class);
            failure(
                    helper,
                    menu.handle(owner, new NodeMenuRequest.MoveNetwork(73, SESSION, 3, "Node")),
                    NodeMenuResponse.Reason.NAME_CONFLICT);
            NodeMenuState.ModeRoot moved = (NodeMenuState.ModeRoot) state(
                            helper,
                            menu.handle(owner, new NodeMenuRequest.MoveNetwork(73, SESSION, 4, "Moved")),
                            NodeMenuState.ModeRoot.class)
                    .state();
            helper.assertTrue(
                    moved.node().networkId().equals(TARGET)
                            && moved.node().mode() == NodeMode.UNCONFIGURED
                            && fixture.source.findNode(NODE).isEmpty()
                            && fixture.target.findNode(NODE).isPresent(),
                    "Network move did not atomically route the target node");
            menu.removed(owner);
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

    private static ResonanceNodeBlockEntity place(GameTestHelper helper, BlockPos position) {
        BlockState state = ModBlocks.RESONANCE_TRANSFER_NODE
                .get()
                .defaultBlockState()
                .setValue(AbstractResonanceNodeBlock.FACING, Direction.DOWN);
        helper.getLevel().setBlockAndUpdate(position, state);
        ResonanceNodeBlockEntity entity =
                (ResonanceNodeBlockEntity) helper.getLevel().getBlockEntity(position);
        CompoundTag tag = new CompoundTag();
        new NodePersistentState.Valid(NODE, NodeLinkState.BLANK).writeOwnedFields(tag);
        entity.loadCustomOnly(tag, helper.getLevel().registryAccess());
        return entity;
    }

    private static ServerPlayer player(GameTestHelper helper, UUID id, BlockPos near) {
        FakePlayer player = new FakePlayer(helper.getLevel(), new GameProfile(id, "Routing"));
        player.setPos(near.getX() + 0.5, near.getY() + 0.5, near.getZ() + 0.5);
        return player;
    }

    private static final class Fixture implements AutoCloseable {
        private final Path path;
        private final SavedNetworkRepository repository;
        private final NetworkSavedData source;
        private final NetworkSavedData target;
        private final NodeAuthorityService authority;
        private final NodeManagementService management;
        private final NetworkTopologyService topology;
        private final NodeMenuService menus;
        private final NetworkNodeDirectory nodes;

        private Fixture(GameTestHelper helper) throws IOException {
            path = Files.createTempDirectory("omniresonance-node-routing-menu-test-");
            DimensionDataStorage storage = new DimensionDataStorage(
                    path.toFile(), DataFixers.getDataFixer(), helper.getLevel().registryAccess());
            repository = new SavedNetworkRepository(storage, path);
            NetworkMetadata sourceMetadata =
                    new NetworkMetadata(SOURCE, OWNER, new ManagedName("Source"), 0, Set.of(ADMIN));
            NetworkMetadata targetMetadata =
                    new NetworkMetadata(TARGET, OWNER, new ManagedName("Target"), 0, Set.of(ADMIN));
            repository.createNetwork(sourceMetadata);
            repository.createNetwork(targetMetadata);
            source = repository.findLoadedNetwork(SOURCE).orElseThrow();
            target = repository.findLoadedNetwork(TARGET).orElseThrow();
            NetworkDirectory networks = new NetworkDirectory(List.of(sourceMetadata, targetMetadata));
            nodes = new NetworkNodeDirectory(List.of());
            ArrayDeque<UUID> replacementIds = new ArrayDeque<>(List.of(new UUID(516, 1), new UUID(516, 2)));
            authority = new NodeAuthorityService(
                    helper.getLevel().getServer(), repository, nodes, replacementIds::removeFirst);
            EditLockTable locks = new EditLockTable();
            management = new NodeManagementService(
                    helper.getLevel().getServer(), networks, repository, nodes, authority, locks);
            topology = new NetworkTopologyService(
                    helper.getLevel().getServer(),
                    networks,
                    repository,
                    nodes,
                    locks,
                    new ServerConfig.State(1, 1, true, ServerSettings.defaults()),
                    () -> new UUID(517, 1));
            menus = new NodeMenuService(
                    helper.getLevel().getServer(), management, topology, networks, () -> new UUID(518, 1));
        }

        private void seedTunnels() {
            source.createTunnel(TUNNEL, new ManagedName("Ore Processing"), CHANNEL, new ManagedName("Channel 1"), -1);
            NetworkTunnelRecord disabled = source.createTunnel(
                            DISABLED_TUNNEL, new ManagedName("Wood"), new UUID(515, 2), new ManagedName("Default"), -1)
                    .tunnel();
            source.setTunnelEnabled(DISABLED_TUNNEL, disabled.revision(), false).orElseThrow();
            source.createTunnel(
                    TARGET_TUNNEL,
                    new ManagedName("Target tunnel"),
                    TARGET_CHANNEL,
                    new ManagedName("Target channel"),
                    -1);
        }

        private void setDirectMode() {
            NetworkNodeDirectory.Entry current = nodes.byId(NODE).entry().orElseThrow();
            NetworkNodeRecord updated = source.setNodeMode(
                            NODE, current.record().revision(), NodeMode.DIRECT, false)
                    .orElseThrow();
            nodes.update(current, new NetworkNodeDirectory.Entry(SOURCE, updated));
        }

        private void bindNode(UUID channelId) {
            NetworkNodeDirectory.Entry current = nodes.byId(NODE).entry().orElseThrow();
            NetworkNodeRecord updated = source.setDirectBinding(
                    NODE, current.record().revision(), channelId, TransferDirection.INPUT, false, -1);
            nodes.update(current, new NetworkNodeDirectory.Entry(SOURCE, updated));
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
