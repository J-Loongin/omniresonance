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
    private static io.github.loongin.omniresonance.transfer.ResourcePolicyEdit editIntent(
            io.github.loongin.omniresonance.transfer.ItemTransferPolicy policy) {
        return io.github.loongin.omniresonance.transfer.ResourcePolicyEdit.fromStored(
                new io.github.loongin.omniresonance.transfer.StoredResourcePolicy(
                        io.github.loongin.omniresonance.transfer.ResourceTransferPolicy.legacy(policy),
                        java.util.Map.of()));
    }

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
    public static void chunkToggleKeepsModeAndChannelPagesInsteadOfReenteringTheRoute(GameTestHelper helper)
            throws Exception {
        try (var f = new Fixture(helper)) {
            var pos = helper.absolutePos(new BlockPos(2, 3, 2));
            f.authority.link(SOURCE, place(helper, pos), new ManagedName("Toggle"));
            f.seedTunnels();
            f.setDirectMode();
            f.bindNode(CHANNEL);
            var owner = player(helper, OWNER, pos);
            var menu = f.menus.createMenu(182, owner, pos, SESSION);
            owner.containerMenu = menu;
            menu.handle(owner, new NodeMenuRequest.OpenModeRoot(182, SESSION, 1));
            menu.handle(owner, new NodeMenuRequest.SetChunkLoadingRequested(182, SESSION, 2, true));
            helper.assertTrue(menu.state() instanceof NodeMenuState.ModeRoot, "Chunk toggle left the mode page");
            helper.assertTrue(
                    ((NodeMenuState.ModeRoot) menu.state()).node().chunkLoadingRequested(),
                    "Chunk toggle failed to update authority");
            menu.handle(owner, new NodeMenuRequest.OpenDirect(182, SESSION, 3));
            var list = (NodeMenuState.DirectChannelList) menu.state();
            menu.handle(owner, new NodeMenuRequest.SetChunkLoadingRequested(182, SESSION, 4, false));
            helper.assertTrue(
                    menu.state() instanceof NodeMenuState.DirectChannelList current
                            && current.page().equals(list.page()),
                    "Chunk toggle replaced the channel cursor window");
            menu.handle(owner, new NodeMenuRequest.OpenChannel(182, SESSION, 5, CHANNEL));
            menu.handle(owner, new NodeMenuRequest.SetChunkLoadingRequested(182, SESSION, 6, true));
            helper.assertTrue(
                    menu.state() instanceof NodeMenuState.DirectChannelRoot, "Chunk toggle left the channel details");
            menu.removed(owner);
        }
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void remoteConfigurationMenuSharesEditorAndRejectsPhysicalOperations(GameTestHelper helper)
            throws Exception {
        try (var f = new Fixture(helper)) {
            var pos = helper.absolutePos(new BlockPos(2, 3, 2));
            f.authority.link(SOURCE, place(helper, pos), new ManagedName("Remote"));
            f.seedTunnels();
            f.setDirectMode();
            f.bindNode(CHANNEL);
            var owner = player(helper, OWNER, pos.offset(500, 0, 500));
            var edit = f.topology.acquireNode(owner, SOURCE, NODE);
            var state = f.menus.bindingEdit(owner, SOURCE, NODE, TUNNEL, CHANNEL);
            var menu = new ResonanceNodeMenu(
                    181, owner.getInventory(), f.menus, pos, SESSION, new NodeMenuService.Initial(NODE, SOURCE, state));
            menu.configureRemote(edit, state);
            owner.containerMenu = menu;
            helper.assertTrue(
                    f.menus.canKeepOpen(owner, menu), "Existing configuration editor required physical proximity");
            var rejected = menu.handle(owner, new NodeMenuRequest.SetChunkLoadingRequested(181, SESSION, 1, true));
            helper.assertTrue(
                    rejected instanceof NodeMenuResponse.Failure
                            && !f.source.findNode(NODE).orElseThrow().chunkLoadingRequested(),
                    "Remote editor changed strong loading");
            helper.assertTrue(
                    menu.handle(owner, new NodeMenuRequest.OpenDirect(181, SESSION, 2))
                            instanceof NodeMenuResponse.Failure,
                    "Remote editor escaped into channel joining");
            menu.handle(owner, new NodeMenuRequest.CancelEdit(181, SESSION, 3));
            var retry = f.topology.acquireNode(owner, SOURCE, NODE);
            f.topology.cancel(owner, retry);
            boolean absent = false;
            try {
                f.menus.openExistingConfiguration(owner, SOURCE, NODE, TARGET_CHANNEL);
            } catch (RuntimeException expected) {
                absent = true;
            }
            helper.assertTrue(absent, "Remote editor admitted a missing binding");
            menu.removed(owner);
        }
        helper.succeed();
    }

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

    @GameTest(template = "bootstrap")
    public static void itemPolicySaveUsesSharedLeaseAndRejectsForeignPreset(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper)) {
            BlockPos position = helper.absolutePos(new BlockPos(2, 3, 2));
            fixture.authority.link(SOURCE, place(helper, position), new ManagedName("Node"));
            fixture.seedTunnels();
            fixture.setDirectMode();
            ServerPlayer owner = player(helper, OWNER, position);
            ResonanceNodeMenu menu = fixture.menus.createMenu(81, owner, position, SESSION);
            menu.handle(owner, new NodeMenuRequest.OpenTunnel(81, SESSION, 1, TUNNEL));
            menu.handle(owner, new NodeMenuRequest.OpenChannel(81, SESSION, 2, CHANNEL));
            menu.handle(owner, new NodeMenuRequest.BeginBinding(81, SESSION, 3, CHANNEL));
            var policy = new io.github.loongin.omniresonance.transfer.ItemTransferPolicy.Input(
                    17,
                    31,
                    io.github.loongin.omniresonance.transfer.RedstoneCondition.NO_SIGNAL,
                    null,
                    io.github.loongin.omniresonance.filter.FilterMode.BLACKLIST,
                    Long.MAX_VALUE);
            state(
                    helper,
                    menu.handle(
                            owner, new NodeMenuRequest.SaveResourcePolicy(81, SESSION, 4, editIntent(policy), false)),
                    NodeMenuState.DirectChannelRoot.class);
            helper.assertTrue(
                    fixture.source
                            .directBindings(NODE)
                            .getFirst()
                            .policy()
                            .equals(io.github.loongin.omniresonance.transfer.ResourceTransferPolicy.legacy(policy)),
                    "Saved item policy did not retain submitted values");
            fixture.source.setDirty(false);
            var polled = state(
                    helper,
                    menu.handle(owner, new NodeMenuRequest.PollItemStatus(81, SESSION, 5)),
                    NodeMenuState.DirectChannelRoot.class);
            helper.assertTrue(
                    ((NodeMenuState.DirectChannelRoot) polled.state())
                                    .policy()
                                    .equals(io.github.loongin.omniresonance.networking.NodeResourcePolicySummary.from(
                                            fixture.source
                                                    .directBindings(NODE)
                                                    .getFirst()
                                                    .storedPolicy()))
                            && !fixture.source.isDirty(),
                    "Status poll mutated or dropped item policy");
            var reopened = state(
                    helper,
                    menu.handle(owner, new NodeMenuRequest.BeginBinding(81, SESSION, 6, CHANNEL)),
                    NodeMenuState.DirectBindingEdit.class);
            helper.assertTrue(
                    ((NodeMenuState.DirectBindingEdit) reopened.state())
                            .policy()
                            .equals(editIntent(policy)),
                    "Node editor did not receive the complete saved item policy");
            var paged = state(
                    helper,
                    menu.handle(owner, new NodeMenuRequest.PageItemPresets(81, SESSION, 7, 0)),
                    NodeMenuState.DirectBindingEdit.class);
            helper.assertTrue(
                    ((NodeMenuState.DirectBindingEdit) paged.state()).policy().equals(editIntent(policy)),
                    "Preset paging dropped item policy draft context");
            menu.handle(owner, new NodeMenuRequest.CancelEdit(81, SESSION, 8));
            helper.assertTrue(
                    fixture.source
                            .directBindings(NODE)
                            .getFirst()
                            .policy()
                            .equals(io.github.loongin.omniresonance.transfer.ResourceTransferPolicy.legacy(policy)),
                    "Cancelled draft changed the policy");
            failure(
                    helper,
                    menu.handle(
                            owner, new NodeMenuRequest.SaveResourcePolicy(81, SESSION, 9, editIntent(policy), false)),
                    NodeMenuResponse.Reason.INVALID_REQUEST);
            menu.handle(owner, new NodeMenuRequest.BeginBinding(81, SESSION, 10, CHANNEL));
            var forged = new io.github.loongin.omniresonance.transfer.ItemTransferPolicy.Input(
                    1,
                    1,
                    io.github.loongin.omniresonance.transfer.RedstoneCondition.IGNORE,
                    new UUID(600, 1),
                    io.github.loongin.omniresonance.filter.FilterMode.WHITELIST,
                    0);
            fixture.repository
                    .createOwner(new UUID(600, 2), null)
                    .putPreset(
                            new io.github.loongin.omniresonance.filter.ItemFilterPreset(
                                    new UUID(600, 1), new ManagedName("Foreign"), 0, Set.of()),
                            0,
                            -1,
                            -1);
            failure(
                    helper,
                    menu.handle(
                            owner, new NodeMenuRequest.SaveResourcePolicy(81, SESSION, 11, editIntent(forged), false)),
                    NodeMenuResponse.Reason.UNAVAILABLE);
            helper.assertTrue(
                    fixture.source
                            .directBindings(NODE)
                            .getFirst()
                            .policy()
                            .equals(io.github.loongin.omniresonance.transfer.ResourceTransferPolicy.legacy(policy)),
                    "Foreign preset request changed saved policy");
            menu.removed(owner);
            helper.succeed();
        }
    }

    @GameTest(template = "bootstrap")
    public static void workingFaceDraftPreviewsAndExplicitSaveStayBoundToTheNode(GameTestHelper helper)
            throws IOException {
        try (Fixture fixture = new Fixture(helper)) {
            BlockPos position = helper.absolutePos(new BlockPos(2, 3, 2));
            fixture.authority.link(SOURCE, place(helper, position), new ManagedName("Node"));
            fixture.seedTunnels();
            fixture.setDirectMode();
            helper.getLevel()
                    .setBlockAndUpdate(
                            position.east(), net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState());
            helper.getLevel()
                    .setBlockAndUpdate(
                            position.above(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            ServerPlayer owner = player(helper, OWNER, position);
            ResonanceNodeMenu menu = fixture.menus.createMenu(91, owner, position, SESSION);
            menu.handle(owner, new NodeMenuRequest.OpenTunnel(91, SESSION, 1, TUNNEL));
            menu.handle(owner, new NodeMenuRequest.OpenChannel(91, SESSION, 2, CHANNEL));
            NodeMenuState.DirectBindingEdit draft = (NodeMenuState.DirectBindingEdit) state(
                            helper,
                            menu.handle(owner, new NodeMenuRequest.BeginBinding(91, SESSION, 3, CHANNEL)),
                            NodeMenuState.DirectBindingEdit.class)
                    .state();
            helper.assertTrue(
                    draft.workingFaces().equals(io.github.loongin.omniresonance.network.WorkingFaces.explicit(0)),
                    "New block draft was not empty");
            helper.assertTrue(draft.previews().size() == 6, "Block preview must include six bounded neighbors");
            helper.assertTrue(
                    draft.previews().stream()
                            .anyMatch(preview -> preview.direction() == Direction.EAST
                                    && net.minecraft.resources.ResourceLocation.withDefaultNamespace("chest")
                                            .equals(preview.blockId())),
                    "Chest preview identity missing");
            helper.assertTrue(
                    draft.previews().stream()
                            .anyMatch(preview -> preview.direction() == Direction.UP
                                    && preview.status()
                                            == io.github.loongin.omniresonance.networking.NodeFacePreview.Status.AIR),
                    "Air was not distinct from unloaded");
            var faces = io.github.loongin.omniresonance.network.WorkingFaces.explicit(48);
            state(
                    helper,
                    menu.handle(
                            owner,
                            new NodeMenuRequest.SaveResourcePolicy(91, SESSION, 4, draft.policy(), faces, false)),
                    NodeMenuState.DirectChannelRoot.class);
            helper.assertTrue(
                    fixture.source
                            .directBindings(NODE)
                            .getFirst()
                            .workingFaces()
                            .equals(faces),
                    "Explicit selection was not atomically saved");
            NodeMenuState.DirectBindingEdit reopened = (NodeMenuState.DirectBindingEdit) state(
                            helper,
                            menu.handle(owner, new NodeMenuRequest.BeginBinding(91, SESSION, 5, CHANNEL)),
                            NodeMenuState.DirectBindingEdit.class)
                    .state();
            helper.assertTrue(reopened.workingFaces().equals(faces), "Saved selection missing on reopen");
            helper.getLevel()
                    .setBlockAndUpdate(
                            position.east(), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            NodeMenuState.DirectBindingEdit refreshed = (NodeMenuState.DirectBindingEdit) state(
                            helper,
                            menu.handle(owner, new NodeMenuRequest.PageItemPresets(91, SESSION, 6, 0)),
                            NodeMenuState.DirectBindingEdit.class)
                    .state();
            helper.assertTrue(
                    refreshed.workingFaces().equals(faces)
                            && refreshed.previews().stream()
                                    .anyMatch(preview -> net.minecraft.resources.ResourceLocation.withDefaultNamespace(
                                                    "stone")
                                            .equals(preview.blockId())),
                    "Refresh altered selection or retained stale preview");
            var empty = (NodeMenuState.DirectChannelRoot) state(
                            helper,
                            menu.handle(
                                    owner,
                                    new NodeMenuRequest.SaveResourcePolicy(
                                            91,
                                            SESSION,
                                            7,
                                            draft.policy(),
                                            io.github.loongin.omniresonance.network.WorkingFaces.explicit(0),
                                            false)),
                            NodeMenuState.DirectChannelRoot.class)
                    .state();
            helper.assertTrue(
                    empty.transferStatus()
                            == io.github.loongin.omniresonance.networking.NodeTransferStatus.NO_WORK_FACES,
                    "Empty save lacks explicit no-face status");
            menu.removed(owner);
            helper.succeed();
        }
    }

    @GameTest(template = "bootstrap")
    public static void panelMenuPreviewAndSaveRejectExpandedWorkingFaces(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper)) {
            BlockPos position = helper.absolutePos(new BlockPos(2, 3, 2));
            helper.getLevel()
                    .setBlockAndUpdate(
                            position.below(), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            fixture.authority.link(SOURCE, place(helper, position, true), new ManagedName("Panel"));
            fixture.seedTunnels();
            fixture.setDirectMode();
            ServerPlayer owner = player(helper, OWNER, position);
            ResonanceNodeMenu menu = fixture.menus.createMenu(92, owner, position, SESSION);
            menu.handle(owner, new NodeMenuRequest.OpenTunnel(92, SESSION, 1, TUNNEL));
            menu.handle(owner, new NodeMenuRequest.OpenChannel(92, SESSION, 2, CHANNEL));
            NodeMenuState.DirectBindingEdit draft = (NodeMenuState.DirectBindingEdit) state(
                            helper,
                            menu.handle(owner, new NodeMenuRequest.BeginBinding(92, SESSION, 3, CHANNEL)),
                            NodeMenuState.DirectBindingEdit.class)
                    .state();
            helper.assertTrue(
                    draft.workingFaces().attached()
                            && draft.previews().size() == 1
                            && draft.previews().getFirst().direction() == Direction.DOWN,
                    "Panel preview expanded beyond attachment");
            var rejected = menu.handle(
                    owner,
                    new NodeMenuRequest.SaveResourcePolicy(
                            92,
                            SESSION,
                            4,
                            draft.policy(),
                            io.github.loongin.omniresonance.network.WorkingFaces.explicit(63),
                            false));
            helper.assertTrue(
                    rejected instanceof NodeMenuResponse.Failure
                            && fixture.source.directBindings(NODE).isEmpty(),
                    "Forged panel faces mutated binding");
            menu.removed(owner);
            helper.succeed();
        }
    }

    @GameTest(template = "bootstrap")
    public static void resourceMenuRejectsForgedUnknownDefaultRowsAndReleasesExpiredTransfers(GameTestHelper helper)
            throws IOException {
        try (Fixture f = new Fixture(helper)) {
            BlockPos position = helper.absolutePos(new BlockPos(2, 3, 2));
            f.authority.link(SOURCE, place(helper, position), new ManagedName("Node"));
            f.seedTunnels();
            f.setDirectMode();
            ServerPlayer owner = player(helper, OWNER, position);
            var menu = f.menus.createMenu(93, owner, position, SESSION);
            owner.containerMenu = menu;
            menu.handle(owner, new NodeMenuRequest.OpenTunnel(93, SESSION, 1, TUNNEL));
            menu.handle(owner, new NodeMenuRequest.OpenChannel(93, SESSION, 2, CHANNEL));
            var edit = (NodeMenuState.DirectBindingEdit) state(
                            helper,
                            menu.handle(owner, new NodeMenuRequest.BeginBinding(93, SESSION, 3, CHANNEL)),
                            NodeMenuState.DirectBindingEdit.class)
                    .state();
            helper.assertTrue(
                    edit.policy().scope().kind() == io.github.loongin.omniresonance.transfer.ResourceScope.Kind.ALL
                            && edit.workingFaces().mask() == 0,
                    "New physical menu did not default ALL and empty block faces");
            var seed = edit.policy();
            var forged = new io.github.loongin.omniresonance.transfer.ResourcePolicyEdit(
                    seed.intervalTicks(),
                    seed.scope(),
                    seed.redstoneCondition(),
                    null,
                    seed.filterMode(),
                    seed.fields(),
                    List.of(new io.github.loongin.omniresonance.transfer.ResourcePolicyEdit.Row(
                            net.minecraft.resources.ResourceLocation.parse("forged:default"),
                            new io.github.loongin.omniresonance.transfer.ResourceTransferPolicy.InputOverride(
                                    Integer.MAX_VALUE,
                                    io.github.loongin.omniresonance.transfer.ResourceTransferPolicy.BatchMode.GREEDY,
                                    1))),
                    List.of(),
                    false);
            f.source.setDirty(false);
            helper.assertTrue(
                    menu.handle(
                                    owner,
                                    registeredRequest(new NodeMenuRequest.SaveResourcePolicy(
                                            93,
                                            SESSION,
                                            4,
                                            forged,
                                            io.github.loongin.omniresonance.network.WorkingFaces.explicit(48),
                                            false)))
                            instanceof NodeMenuResponse.Failure,
                    "Unknown default row was normalized into authority");
            helper.assertTrue(
                    !f.source.isDirty() && f.source.directBindings(NODE).isEmpty(),
                    "Rejected unknown row mutated authority");
            menu.removed(owner);
            menu = f.menus.createMenu(93, owner, position, SESSION);
            owner.containerMenu = menu;
            menu.handle(owner, new NodeMenuRequest.OpenTunnel(93, SESSION, 1, TUNNEL));
            menu.handle(owner, new NodeMenuRequest.OpenChannel(93, SESSION, 2, CHANNEL));
            menu.handle(owner, new NodeMenuRequest.BeginBinding(93, SESSION, 3, CHANNEL));
            byte[] bytes = io.github.loongin.omniresonance.networking.ResourcePolicyEditCodec.encode(seed);
            UUID first = new UUID(930, 1), second = new UUID(930, 2);
            helper.assertTrue(
                    menu.handle(
                                    owner,
                                    new NodeMenuRequest.BeginPolicyUpload(
                                            93,
                                            SESSION,
                                            4,
                                            first,
                                            bytes.length,
                                            io.github.loongin.omniresonance.network.WorkingFaces.explicit(48),
                                            false))
                            instanceof NodeMenuResponse.UploadReady,
                    "Upload metadata rejected");
            menu.handleTransfer(
                    owner,
                    new io.github.loongin.omniresonance.networking.ManagementTransferMessage.Abort(SESSION, first));
            helper.assertTrue(f.menus.transfers().reservedBytes() == 0, "Cancel retained reservation");
            menu.handle(
                    owner,
                    new NodeMenuRequest.BeginPolicyUpload(
                            93,
                            SESSION,
                            5,
                            second,
                            bytes.length,
                            io.github.loongin.omniresonance.network.WorkingFaces.explicit(48),
                            false));
            menu.handleTransfer(
                    owner,
                    new io.github.loongin.omniresonance.networking.ManagementTransferMessage.Chunk(
                            SESSION, first, 0, bytes));
            helper.assertTrue(
                    f.menus.transfers().reservedBytes() == bytes.length, "Old upload damaged current transfer");
            menu.transferTick(owner, f.menus.currentTick() + 200, payload -> {});
            helper.assertTrue(
                    f.menus.transfers().reservedBytes() == 0 && !f.source.isDirty(),
                    "Fixed timeout retained upload or mutated authority");
            helper.assertTrue(
                    menu.handleTransfer(
                                    owner,
                                    new io.github.loongin.omniresonance.networking.ManagementTransferMessage.Finish(
                                            SESSION, second))
                            == null,
                    "Expired finish resumed mutation");
            UUID third = new UUID(930, 3);
            menu.handle(
                    owner,
                    new NodeMenuRequest.BeginPolicyUpload(
                            93,
                            SESSION,
                            6,
                            third,
                            bytes.length,
                            io.github.loongin.omniresonance.network.WorkingFaces.explicit(48),
                            false));
            for (int tick = 0; tick < 201; tick++) f.topology.tick();
            helper.assertTrue(
                    menu.handleTransfer(
                                    owner,
                                    new io.github.loongin.omniresonance.networking.ManagementTransferMessage.Chunk(
                                            SESSION, third, 0, bytes))
                            instanceof NodeMenuResponse.Failure,
                    "Expired lease accepted transfer traffic");
            helper.assertTrue(
                    f.menus.transfers().reservedBytes() == 0 && !f.source.isDirty(),
                    "Lease expiry retained callback or dirtied state");
            menu.removed(owner);
            helper.succeed();
        }
    }

    @GameTest(template = "bootstrap")
    public static void largePolicyRoutesRegisteredFramesAndCommitsFacesExactlyOnce(GameTestHelper helper)
            throws IOException {
        largePolicyRoute(helper, false);
    }

    @GameTest(template = "bootstrap")
    public static void largeDomainPolicyRoutesRegisteredFramesWithoutAChannel(GameTestHelper helper)
            throws IOException {
        largePolicyRoute(helper, true);
    }

    private static void largePolicyRoute(GameTestHelper helper, boolean domain) throws IOException {
        try (Fixture f = new Fixture(helper)) {
            BlockPos position = helper.absolutePos(new BlockPos(2, 3, 2));
            f.authority.link(SOURCE, place(helper, position), new ManagedName("Node"));
            if (!domain) {
                f.seedTunnels();
                f.setDirectMode();
            } else {
                var oldNode = f.nodes.byId(NODE).entry().orElseThrow();
                var changed = f.source
                        .setNodeMode(NODE, oldNode.record().revision(), NodeMode.DOMAIN, false)
                        .orElseThrow();
                f.nodes.update(oldNode, new NetworkNodeDirectory.Entry(SOURCE, changed));
            }
            UUID contextId = domain ? NODE : CHANNEL;
            var ids = new java.util.LinkedHashSet<net.minecraft.resources.ResourceLocation>();
            for (int index = 0; index < 3000; index++)
                ids.add(net.minecraft.resources.ResourceLocation.parse("missing:" + "a".repeat(100) + index));
            var policy = new io.github.loongin.omniresonance.transfer.ResourceTransferPolicy.Input(
                    20,
                    io.github.loongin.omniresonance.transfer.ResourceScope.customSet(ids),
                    io.github.loongin.omniresonance.transfer.RedstoneCondition.IGNORE,
                    null,
                    io.github.loongin.omniresonance.filter.FilterMode.WHITELIST,
                    java.util.Map.of(),
                    16);
            var previous = f.nodes.byId(NODE).entry().orElseThrow();
            var updated = domain
                    ? f.source.saveDomainConfiguration(
                            NODE,
                            previous.record().revision(),
                            new io.github.loongin.omniresonance.transfer.StoredResourcePolicy(
                                    policy, java.util.Map.of()),
                            io.github.loongin.omniresonance.network.WorkingFaces.explicit(1),
                            false)
                    : f.source.setDirectBinding(
                            NODE,
                            previous.record().revision(),
                            CHANNEL,
                            new io.github.loongin.omniresonance.transfer.StoredResourcePolicy(
                                    policy, java.util.Map.of()),
                            io.github.loongin.omniresonance.network.WorkingFaces.explicit(1),
                            false,
                            -1);
            f.nodes.update(previous, new NetworkNodeDirectory.Entry(SOURCE, updated));
            ServerPlayer owner = player(helper, OWNER, position);
            ResonanceNodeMenu menu = f.menus.createMenu(92, owner, position, SESSION);
            owner.containerMenu = menu;
            if (domain) menu.handle(owner, registeredRequest(new NodeMenuRequest.OpenDomain(92, SESSION, 1)));
            else menu.handle(owner, registeredRequest(new NodeMenuRequest.OpenChannel(92, SESSION, 1, CHANNEL)));
            var result = registeredResponse(menu.handle(
                    owner,
                    registeredRequest(
                            domain
                                    ? new NodeMenuRequest.BeginDomainEdit(92, SESSION, 2)
                                    : new NodeMenuRequest.BeginBinding(92, SESSION, 2, CHANNEL))));
            helper.assertTrue(
                    result instanceof NodeMenuResponse.Download, "Large snapshot was not independently downloaded");
            var download = (NodeMenuResponse.Download) result;
            helper.assertTrue(
                    download.length() > 262144
                            && download.metadata().policy() == null
                            && f.menus.transfers().reservedBytes() == download.length(),
                    "Download was not reserved before publication");
            var assembler = new io.github.loongin.omniresonance.networking.ManagementDownloadAssembler();
            var pin = new io.github.loongin.omniresonance.networking.ManagementDownloadAssembler.Expected(
                    SESSION,
                    download.transfer(),
                    io.github.loongin.omniresonance.networking.ManagementTransferMessage.Context.NODE,
                    contextId,
                    io.github.loongin.omniresonance.networking.ManagementTransferMessage.Purpose.NODE_POLICY,
                    download.length());
            assembler.begin(
                    new io.github.loongin.omniresonance.networking.ManagementTransferMessage.Begin(
                            SESSION,
                            download.transfer(),
                            io.github.loongin.omniresonance.networking.ManagementTransferMessage.Direction.DOWNLOAD,
                            pin.context(),
                            contextId,
                            pin.purpose(),
                            download.length()),
                    pin,
                    f.menus.currentTick());
            io.github.loongin.omniresonance.transfer.ResourcePolicyEdit[] decoded =
                    new io.github.loongin.omniresonance.transfer.ResourcePolicyEdit[1];
            for (int tick = 0; decoded[0] == null && tick < 70; tick++) {
                int[] chunks = {0};
                long now = f.menus.currentTick() + tick;
                menu.transferTick(owner, now, payload -> {
                    var message = (io.github.loongin.omniresonance.networking.ManagementTransferMessage)
                            registeredClientPayload(payload);
                    if (message
                            instanceof
                            io.github.loongin.omniresonance.networking.ManagementTransferMessage.Chunk chunk) {
                        chunks[0]++;
                        assembler.append(chunk, now);
                    } else if (message
                            instanceof
                            io.github.loongin.omniresonance.networking.ManagementTransferMessage.Finish finish)
                        assembler.finish(
                                finish,
                                now,
                                view -> decoded[0] =
                                        io.github.loongin.omniresonance.networking.ResourcePolicyEditCodec.decode(
                                                view));
                });
                helper.assertTrue(chunks[0] <= 1, "Tick queued more than one fragment");
            }
            helper.assertTrue(
                    decoded[0] != null
                            && decoded[0].scope().ids().size() == ids.size()
                            && f.menus.transfers().reservedBytes() == 0,
                    "Download did not complete or release");
            var catalog = registeredResponse(
                    menu.handle(owner, registeredRequest(new NodeMenuRequest.ResourceCatalog(92, SESSION, 3, 0))));
            helper.assertTrue(
                    catalog instanceof NodeMenuResponse.Catalog page
                            && page.page().entries().size() == 3,
                    "Catalog was bundled with policy or lost registered descriptors");
            byte[] bytes = io.github.loongin.omniresonance.networking.ResourcePolicyEditCodec.encode(decoded[0]);
            UUID transfer = new UUID(920, 1);
            var ready = menu.handle(
                    owner,
                    registeredRequest(new NodeMenuRequest.BeginPolicyUpload(
                            92,
                            SESSION,
                            4,
                            transfer,
                            bytes.length,
                            io.github.loongin.omniresonance.network.WorkingFaces.explicit(48),
                            false)));
            helper.assertTrue(
                    registeredResponse(ready) instanceof NodeMenuResponse.UploadReady
                            && f.source.findNode(NODE).orElseThrow().revision() == updated.revision(),
                    "Upload admission completed Save early");
            for (int offset = 0; offset < bytes.length; ) {
                int end = Math.min(
                        bytes.length,
                        offset
                                + io.github.loongin.omniresonance.networking.ManagementTransferPool
                                        .MAXIMUM_FRAGMENT_BYTES);
                menu.handleTransfer(
                        owner,
                        registeredTransfer(
                                new io.github.loongin.omniresonance.networking.ManagementTransferMessage.Chunk(
                                        SESSION, transfer, offset, java.util.Arrays.copyOfRange(bytes, offset, end))));
                offset = end;
            }
            var saved = registeredResponse(menu.handleTransfer(
                    owner,
                    registeredTransfer(new io.github.loongin.omniresonance.networking.ManagementTransferMessage.Finish(
                            SESSION, transfer))));
            helper.assertTrue(
                    saved instanceof NodeMenuResponse.State
                            && saved.sequence() == 4
                            && f.source.findNode(NODE).orElseThrow().revision() == updated.revision() + 1,
                    "Logical save sequence or single revision commit lost");
            helper.assertTrue(
                    (domain
                                            ? f.source
                                                    .domainConfiguration(NODE)
                                                    .orElseThrow()
                                                    .workingFaces()
                                            : f.source
                                                    .findDirectBinding(NODE, CHANNEL)
                                                    .orElseThrow()
                                                    .workingFaces())
                                    .equals(io.github.loongin.omniresonance.network.WorkingFaces.explicit(48))
                            && f.menus.transfers().reservedBytes() == 0,
                    "Multipart Save omitted working faces or retained reservation");
            helper.assertTrue(
                    menu.handleTransfer(
                                    owner,
                                    new io.github.loongin.omniresonance.networking.ManagementTransferMessage.Finish(
                                            SESSION, transfer))
                            == null,
                    "Completed transfer retried mutation");
            menu.removed(owner);
            assembler.close();
            helper.succeed();
        }
    }

    @GameTest(template = "bootstrap")
    public static void registeredNodeFrameUsesExactInlineLimit(GameTestHelper helper) {
        var ids = new java.util.ArrayList<net.minecraft.resources.ResourceLocation>();
        for (int index = 0; index < 2031; index++)
            ids.add(net.minecraft.resources.ResourceLocation.parse(
                    "x:" + "a".repeat(120) + String.format(java.util.Locale.ROOT, "%06d", index)));
        var original = io.github.loongin.omniresonance.transfer.ResourcePolicyEdit.fromStored(
                new io.github.loongin.omniresonance.transfer.StoredResourcePolicy(
                        new io.github.loongin.omniresonance.transfer.ResourceTransferPolicy.Input(
                                1,
                                io.github.loongin.omniresonance.transfer.ResourceScope.customSet(ids),
                                io.github.loongin.omniresonance.transfer.RedstoneCondition.IGNORE,
                                null,
                                io.github.loongin.omniresonance.filter.FilterMode.WHITELIST,
                                java.util.Map.of(),
                                0),
                        java.util.Map.of()));
        int padding = 262144 - io.github.loongin.omniresonance.networking.NodePolicyFrames.saveSize(200, original) - 1;
        helper.assertTrue(padding >= 3 && padding <= 128, "Boundary fixture padding invalid");
        ids.add(net.minecraft.resources.ResourceLocation.parse("z:" + "b".repeat(padding - 2)));
        var exact = new io.github.loongin.omniresonance.transfer.ResourcePolicyEdit(
                1,
                io.github.loongin.omniresonance.transfer.ResourcePolicyEdit.Scope.custom(ids),
                original.redstoneCondition(),
                null,
                original.filterMode(),
                original.fields(),
                List.of(),
                List.of(),
                false);
        helper.assertTrue(
                io.github.loongin.omniresonance.networking.NodePolicyFrames.saveSize(200, exact) == 262144,
                "Inline helper did not include exact envelope");
        var request = new NodeMenuRequest.SaveResourcePolicy(
                200, SESSION, 1, exact, io.github.loongin.omniresonance.network.WorkingFaces.explicit(48), false);
        helper.assertTrue(
                registeredRequest(request).equals(request), "Exact registered inline frame did not round trip");
        ids.set(ids.size() - 1, net.minecraft.resources.ResourceLocation.parse(ids.getLast() + "c"));
        var larger = new io.github.loongin.omniresonance.transfer.ResourcePolicyEdit(
                1,
                io.github.loongin.omniresonance.transfer.ResourcePolicyEdit.Scope.custom(ids),
                original.redstoneCondition(),
                null,
                original.filterMode(),
                original.fields(),
                List.of(),
                List.of(),
                false);
        helper.assertTrue(
                io.github.loongin.omniresonance.networking.NodePolicyFrames.saveSize(200, larger) == 262145,
                "One-byte overflow was not routed to multipart");
        boolean rejected = false;
        try {
            registeredRequest(new NodeMenuRequest.SaveResourcePolicy(
                    200, SESSION, 2, larger, io.github.loongin.omniresonance.network.WorkingFaces.explicit(48), false));
        } catch (RuntimeException expected) {
            rejected = expected.getCause() instanceof io.netty.handler.codec.EncoderException;
        }
        helper.assertTrue(rejected, "Oversize registered inline frame was accepted");
        helper.succeed();
    }

    private static NodeMenuRequest registeredRequest(NodeMenuRequest request) {
        return (NodeMenuRequest) registeredServerPayload(request);
    }

    private static io.github.loongin.omniresonance.networking.ManagementTransferMessage registeredTransfer(
            io.github.loongin.omniresonance.networking.ManagementTransferMessage request) {
        return (io.github.loongin.omniresonance.networking.ManagementTransferMessage) registeredServerPayload(request);
    }

    private static net.minecraft.network.protocol.common.custom.CustomPacketPayload registeredServerPayload(
            net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        var buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket.STREAM_CODEC.encode(
                    buffer, new net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket(payload));
            if (buffer.readableBytes() > 262144) throw new IllegalArgumentException("Oversized registered frame");
            return net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket.STREAM_CODEC
                    .decode(buffer)
                    .payload();
        } finally {
            buffer.release();
        }
    }

    private static NodeMenuResponse registeredResponse(NodeMenuResponse response) {
        return (NodeMenuResponse) registeredClientPayload(response);
    }

    private static net.minecraft.network.protocol.common.custom.CustomPacketPayload registeredClientPayload(
            net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(
                io.netty.buffer.Unpooled.buffer(),
                net.minecraft.core.RegistryAccess.EMPTY,
                net.neoforged.neoforge.network.connection.ConnectionType.NEOFORGE);
        try {
            net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket.GAMEPLAY_STREAM_CODEC.encode(
                    buffer, new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(payload));
            if (buffer.readableBytes() > 262144) throw new IllegalArgumentException("Oversized registered frame");
            return net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket.GAMEPLAY_STREAM_CODEC
                    .decode(buffer)
                    .payload();
        } finally {
            buffer.release();
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
        return place(helper, position, false);
    }

    private static ResonanceNodeBlockEntity place(GameTestHelper helper, BlockPos position, boolean panel) {
        BlockState state = (panel ? ModBlocks.RESONANCE_TRANSFER_PANEL : ModBlocks.RESONANCE_TRANSFER_NODE)
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

    @GameTest(template = "bootstrap")
    public static void domainResourceFormSavesInlineAndExpiredUploadCannotCommit(GameTestHelper helper)
            throws IOException {
        try (Fixture f = new Fixture(helper)) {
            BlockPos position = helper.absolutePos(new BlockPos(2, 3, 2));
            f.authority.link(SOURCE, place(helper, position), new ManagedName("Node"));
            ServerPlayer owner = player(helper, OWNER, position);
            ResonanceNodeMenu menu = f.menus.createMenu(97, owner, position, SESSION);
            owner.containerMenu = menu;
            menu.handle(owner, new NodeMenuRequest.BeginMode(97, SESSION, 1));
            menu.handle(owner, new NodeMenuRequest.SetMode(97, SESSION, 2, NodeMode.DOMAIN, false));
            var response = (NodeMenuResponse.State) registeredResponse(
                    menu.handle(owner, registeredRequest(new NodeMenuRequest.BeginDomainEdit(97, SESSION, 3))));
            var edit = (NodeMenuState.DomainEdit) response.state();
            helper.assertTrue(
                    edit.policy() != null && edit.previews().size() == 6,
                    "Domain form lost complete policy or face previews");
            var catalog = menu.handle(owner, registeredRequest(new NodeMenuRequest.ResourceCatalog(97, SESSION, 4, 0)));
            helper.assertTrue(
                    catalog instanceof NodeMenuResponse.Catalog, "Domain form could not load resource catalog");
            var saved = registeredResponse(menu.handle(
                    owner,
                    registeredRequest(new NodeMenuRequest.SaveResourcePolicy(
                            97,
                            SESSION,
                            5,
                            edit.policy(),
                            io.github.loongin.omniresonance.network.WorkingFaces.explicit(1),
                            false))));
            helper.assertTrue(
                    saved instanceof NodeMenuResponse.State state
                            && state.state() instanceof NodeMenuState.DomainRoot
                            && f.source.domainConfiguration(NODE).orElseThrow().configured(),
                    "Inline domain save did not complete authoritative configuration");
            menu.handle(owner, new NodeMenuRequest.BeginDomainEdit(97, SESSION, 6));
            long revision = f.source.findNode(NODE).orElseThrow().revision();
            byte[] bytes = io.github.loongin.omniresonance.networking.ResourcePolicyEditCodec.encode(edit.policy());
            UUID upload = new UUID(970, 1);
            helper.assertTrue(
                    menu.handle(
                                    owner,
                                    new NodeMenuRequest.BeginPolicyUpload(
                                            97,
                                            SESSION,
                                            7,
                                            upload,
                                            bytes.length,
                                            io.github.loongin.omniresonance.network.WorkingFaces.explicit(48),
                                            false))
                            instanceof NodeMenuResponse.UploadReady,
                    "Domain upload was not admitted");
            menu.transferTick(owner, f.menus.currentTick() + 200, ignored -> {});
            menu.handleTransfer(
                    owner,
                    new io.github.loongin.omniresonance.networking.ManagementTransferMessage.Chunk(
                            SESSION, upload, 0, bytes));
            menu.handleTransfer(
                    owner,
                    new io.github.loongin.omniresonance.networking.ManagementTransferMessage.Finish(SESSION, upload));
            helper.assertTrue(
                    f.menus.transfers().reservedBytes() == 0
                            && f.source.findNode(NODE).orElseThrow().revision() == revision,
                    "Expired domain upload retained capacity or changed configuration");
            menu.removed(owner);
        }
        helper.succeed();
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
            management.installChunkAdmission((network, node, moving) ->
                    io.github.loongin.omniresonance.chunkloading.ChunkLoadingReservations.Admission.ALLOWED);
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
