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
import io.github.loongin.omniresonance.persistence.SavedNetworkRepository;
import io.github.loongin.omniresonance.registry.ModBlocks;
import io.github.loongin.omniresonance.registry.ModMenus;
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
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.IOUtilities;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real block interaction and server Menu lifecycle contracts without client rendering. */
@GameTestHolder(OmniResonanceMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NodeMenuLifecycleGameTests {
    private static final UUID NETWORK = new UUID(100, 1);
    private static final UUID OWNER = new UUID(101, 1);
    private static final UUID ADMIN = new UUID(101, 2);
    private static final UUID STRANGER = new UUID(101, 3);
    private static final UUID NODE = new UUID(102, 1);
    private static final UUID OTHER_NODE = new UUID(102, 2);
    private static final UUID SESSION = new UUID(103, 1);

    private NodeMenuLifecycleGameTests() {}

    /** Both physical forms publish one server-only open intent through ordinary held-item interaction. */
    @GameTest(template = "bootstrap")
    public static void bothFormsPublishServerMenuOpenIntent(GameTestHelper helper) {
        BlockPos nodePos = helper.absolutePos(new BlockPos(2, 3, 2));
        BlockPos panelPos = helper.absolutePos(new BlockPos(5, 3, 2));
        place(helper, nodePos, NODE, ModBlocks.RESONANCE_TRANSFER_NODE.get(), Direction.DOWN);
        place(helper, panelPos, OTHER_NODE, ModBlocks.RESONANCE_TRANSFER_PANEL.get(), Direction.WEST);
        ServerPlayer player = player(helper, OWNER, nodePos);
        OpenObserver observer = new OpenObserver();
        NeoForge.EVENT_BUS.register(observer);
        try {
            ItemInteractionResult nodeResult = interact(helper, player, nodePos, InteractionHand.MAIN_HAND);
            player.setPos(panelPos.getX() + 0.5, panelPos.getY() + 0.5, panelPos.getZ() + 0.5);
            ItemInteractionResult panelResult = interact(helper, player, panelPos, InteractionHand.OFF_HAND);
            helper.assertTrue(
                    nodeResult == ItemInteractionResult.SUCCESS && panelResult == ItemInteractionResult.SUCCESS,
                    "Node interaction did not consume on the server");
            helper.assertTrue(
                    observer.positions.equals(List.of(nodePos, panelPos))
                            && observer.players.equals(List.of(player, player)),
                    "Physical forms did not publish exact open intents");
            helper.succeed();
        } finally {
            NeoForge.EVENT_BUS.unregister(observer);
        }
    }

    /** Server menus expose only bounded initial states and use the registered zero-slot Menu type. */
    @GameTest(template = "bootstrap")
    public static void initialMenusSeparateBlankLinkedAndUnauthorizedStates(GameTestHelper helper) throws IOException {
        BlockPos pos = helper.absolutePos(new BlockPos(2, 3, 2));
        try (Fixture empty = new Fixture(helper, false)) {
            place(helper, pos, NODE, ModBlocks.RESONANCE_TRANSFER_NODE.get(), Direction.DOWN);
            ServerPlayer owner = player(helper, OWNER, pos);
            ResonanceNodeMenu menu = empty.menus.createMenu(7, owner, pos, SESSION);
            helper.assertTrue(menu.state() instanceof NodeMenuState.NoNetworks, "Blank/no-network state was wrong");
            helper.assertTrue(menu.getType() == ModMenus.RESONANCE_NODE.get(), "Wrong node MenuType");
            helper.assertTrue(menu.slots.isEmpty(), "Node Menu unexpectedly exposed inventory slots");
            helper.assertTrue(menu.quickMoveStack(owner, 0).isEmpty(), "Zero-slot Menu moved an item");
            helper.assertTrue(menu.stillValid(owner), "Fresh blank Menu was invalid");
            menu.removed(owner);
            menu.removed(owner);
        }

        try (Fixture linked = new Fixture(helper, true)) {
            ResonanceNodeBlockEntity entity =
                    place(helper, pos, NODE, ModBlocks.RESONANCE_TRANSFER_PANEL.get(), Direction.NORTH);
            linked.authority.link(NETWORK, entity, new ManagedName("Input"));
            ServerPlayer owner = player(helper, OWNER, pos);
            ServerPlayer stranger = player(helper, STRANGER, pos);
            ResonanceNodeMenu ownerMenu = linked.menus.createMenu(8, owner, pos, SESSION);
            ResonanceNodeMenu deniedMenu = linked.menus.createMenu(9, stranger, pos, new UUID(103, 2));
            helper.assertTrue(
                    ownerMenu.state() instanceof NodeMenuState.ModeRoot root
                            && root.node().nodeId().equals(NODE)
                            && root.node().networkId().equals(NETWORK),
                    "Authorized linked root was incomplete");
            helper.assertTrue(
                    deniedMenu.state() instanceof NodeMenuState.NoAccess,
                    "Unauthorized Menu received a private linked state");
            helper.assertTrue(deniedMenu.stillValid(stranger), "Denied read-only Menu did not remain physically valid");
            helper.assertTrue(ownerMenu.linkedNetworkId().orElseThrow().equals(NETWORK), "Linked identity missing");
            helper.assertTrue(deniedMenu.linkedNetworkId().isEmpty(), "Denied Menu retained private network ID");
            ownerMenu.removed(owner);
            deniedMenu.removed(stranger);
            helper.succeed();
        }
    }

    /** stillValid closes on distance, physical replacement or UUID change without loading alternate chunks. */
    @GameTest(template = "bootstrap")
    public static void menuValidityUsesExactPhysicalIdentity(GameTestHelper helper) throws IOException {
        BlockPos pos = helper.absolutePos(new BlockPos(2, 3, 2));
        try (Fixture fixture = new Fixture(helper, true)) {
            ResonanceNodeBlockEntity entity =
                    place(helper, pos, NODE, ModBlocks.RESONANCE_TRANSFER_NODE.get(), Direction.DOWN);
            fixture.authority.link(NETWORK, entity, new ManagedName("Node"));
            ServerPlayer owner = player(helper, OWNER, pos);
            ResonanceNodeMenu menu = fixture.menus.createMenu(10, owner, pos, SESSION);
            helper.assertTrue(menu.stillValid(owner), "Valid linked Menu started invalid");

            owner.setPos(pos.getX() + 8.5001, pos.getY() + 0.5, pos.getZ() + 0.5);
            helper.assertTrue(!menu.stillValid(owner), "Out-of-range Menu stayed valid");
            owner.setPos(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            loadState(helper, entity, OTHER_NODE, NodeLinkState.LINKED);
            helper.assertTrue(!menu.stillValid(owner), "Changed UUID Menu stayed valid");
            menu.removed(owner);

            ResonanceNodeBlockEntity replacement =
                    place(helper, pos, NODE, ModBlocks.RESONANCE_TRANSFER_NODE.get(), Direction.DOWN);
            loadState(helper, replacement, NODE, NodeLinkState.LINKED);
            ResonanceNodeMenu removed = fixture.menus.createMenu(11, owner, pos, new UUID(103, 3));
            helper.getLevel().setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            helper.assertTrue(!removed.stillValid(owner), "Removed physical node Menu stayed valid");
            removed.removed(owner);
            helper.succeed();
        }
    }

    @GameTest(template = "bootstrap")
    public static void inlineRequestsRecheckPhysicalMenuAndReleaseExpiredEdits(GameTestHelper helper)
            throws IOException {
        BlockPos pos = helper.absolutePos(new BlockPos(2, 3, 2));
        try (Fixture fixture = new Fixture(helper, true)) {
            var entity = place(helper, pos, NODE, ModBlocks.RESONANCE_TRANSFER_NODE.get(), Direction.DOWN);
            fixture.authority.link(NETWORK, entity, new ManagedName("Node"));
            var owner = player(helper, OWNER, pos);
            var menu = fixture.menus.createMenu(12, owner, pos, SESSION);
            owner.containerMenu = menu;
            menu.handle(owner, new NodeMenuRequest.BeginRename(12, SESSION, 1));
            owner.setPos(pos.getX() + 8.5001, pos.getY() + 0.5, pos.getZ() + 0.5);
            var response = menu.handle(owner, new NodeMenuRequest.Rename(12, SESSION, 2, "Forbidden"));
            helper.assertTrue(
                    response instanceof NodeMenuResponse.Failure,
                    "An inline rename must reject an out-of-range physical menu before its next tick");
            helper.assertTrue(owner.containerMenu == owner.inventoryMenu, "Invalid physical menu was not closed");
            owner.setPos(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            var edit = fixture.management.acquireLinked(owner, NETWORK, NODE);
            helper.assertTrue(edit.node().name().value().equals("Node"), "Rejected rename changed authority");
            fixture.management.cancel(owner, edit.token());
            helper.assertTrue(!menu.stillValid(owner), "Returning in range revived the rejected menu");
        }
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void inlineRequestsCloseMenusWhosePhysicalNodeDisappeared(GameTestHelper helper) throws IOException {
        BlockPos pos = helper.absolutePos(new BlockPos(2, 3, 2));
        try (Fixture fixture = new Fixture(helper, true)) {
            var entity = place(helper, pos, NODE, ModBlocks.RESONANCE_TRANSFER_NODE.get(), Direction.DOWN);
            fixture.authority.link(NETWORK, entity, new ManagedName("Node"));
            var owner = player(helper, OWNER, pos);
            var menu = fixture.menus.createMenu(13, owner, pos, SESSION);
            owner.containerMenu = menu;
            helper.getLevel().setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            var response = menu.handle(owner, new NodeMenuRequest.SetEnabled(13, SESSION, 1, false));
            helper.assertTrue(response instanceof NodeMenuResponse.Failure, "Removed node accepted an inline mutation");
            helper.assertTrue(owner.containerMenu == owner.inventoryMenu, "Removed node menu was not retired");
        }
        helper.succeed();
    }

    private static ItemInteractionResult interact(
            GameTestHelper helper, ServerPlayer player, BlockPos pos, InteractionHand hand) {
        BlockState state = helper.getLevel().getBlockState(pos);
        return state.useItemOn(
                new ItemStack(Items.STICK),
                helper.getLevel(),
                player,
                hand,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
    }

    private static ResonanceNodeBlockEntity place(
            GameTestHelper helper, BlockPos position, UUID nodeId, Block block, Direction facing) {
        BlockState state = block.defaultBlockState().setValue(AbstractResonanceNodeBlock.FACING, facing);
        helper.getLevel().setBlockAndUpdate(position, state);
        helper.assertTrue(
                helper.getLevel().getBlockEntity(position) instanceof ResonanceNodeBlockEntity,
                "Node block entity missing");
        ResonanceNodeBlockEntity entity =
                (ResonanceNodeBlockEntity) helper.getLevel().getBlockEntity(position);
        loadState(helper, entity, nodeId, NodeLinkState.BLANK);
        return entity;
    }

    private static void loadState(
            GameTestHelper helper, ResonanceNodeBlockEntity entity, UUID nodeId, NodeLinkState linkState) {
        CompoundTag tag = new CompoundTag();
        new NodePersistentState.Valid(nodeId, linkState).writeOwnedFields(tag);
        entity.loadCustomOnly(tag, helper.getLevel().registryAccess());
    }

    private static ServerPlayer player(GameTestHelper helper, UUID id, BlockPos near) {
        FakePlayer player =
                new FakePlayer(helper.getLevel(), new GameProfile(id, "Lifecycle" + id.getLeastSignificantBits()));
        player.setPos(near.getX() + 0.5, near.getY() + 0.5, near.getZ() + 0.5);
        return player;
    }

    private static final class OpenObserver {
        private final java.util.ArrayList<BlockPos> positions = new java.util.ArrayList<>();
        private final java.util.ArrayList<ServerPlayer> players = new java.util.ArrayList<>();

        @SubscribeEvent
        public void onOpen(NodeMenuOpenEvent event) {
            positions.add(event.position());
            players.add(event.player());
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final Path path;
        private final NodeAuthorityService authority;
        private final NodeManagementService management;
        private final NetworkTopologyService topology;
        private final NodeMenuService menus;

        private Fixture(GameTestHelper helper, boolean withNetwork) throws IOException {
            path = Files.createTempDirectory("omniresonance-node-menu-lifecycle-test-");
            DimensionDataStorage storage = new DimensionDataStorage(
                    path.toFile(), DataFixers.getDataFixer(), helper.getLevel().registryAccess());
            SavedNetworkRepository repository = new SavedNetworkRepository(storage, path);
            NetworkDirectory networks;
            if (withNetwork) {
                NetworkMetadata metadata =
                        new NetworkMetadata(NETWORK, OWNER, new ManagedName("Menu"), 0, Set.of(ADMIN));
                repository.createNetwork(metadata);
                networks = new NetworkDirectory(List.of(metadata));
            } else {
                networks = new NetworkDirectory(List.of());
            }
            NetworkNodeDirectory nodes = new NetworkNodeDirectory(List.of());
            ArrayDeque<UUID> replacements = new ArrayDeque<>(List.of(new UUID(104, 1), new UUID(104, 2)));
            authority = new NodeAuthorityService(
                    helper.getLevel().getServer(), repository, nodes, replacements::removeFirst);
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
                    () -> new UUID(106, 1));
            menus = new NodeMenuService(
                    helper.getLevel().getServer(), management, topology, networks, () -> new UUID(105, 1));
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
