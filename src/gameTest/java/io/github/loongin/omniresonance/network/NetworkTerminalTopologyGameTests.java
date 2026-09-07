// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.network;

import com.mojang.authlib.GameProfile;
import io.github.loongin.omniresonance.bootstrap.OmniResonanceMod;
import io.github.loongin.omniresonance.config.ServerConfig;
import io.github.loongin.omniresonance.config.ServerSettings;
import io.github.loongin.omniresonance.networking.NetworkTerminalRequest;
import io.github.loongin.omniresonance.networking.NetworkTerminalResponse;
import io.github.loongin.omniresonance.networking.NetworkTerminalState;
import io.github.loongin.omniresonance.node.NetworkNodeDirectory;
import io.github.loongin.omniresonance.persistence.SavedNetworkRepository;
import io.github.loongin.omniresonance.security.EditLockTable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.common.IOUtilities;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** One-session terminal topology hierarchy, operation and multiplayer lock contracts. */
@GameTestHolder(OmniResonanceMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NetworkTerminalTopologyGameTests {
    private static final UUID NETWORK = new UUID(350, 1);
    private static final UUID OWNER = new UUID(350, 2);
    private static final UUID ADMIN = new UUID(350, 3);
    private static final UUID VIEW_A = new UUID(351, 1);
    private static final UUID VIEW_B = new UUID(351, 2);
    private static final UUID SESSION_A = new UUID(352, 1);
    private static final UUID SESSION_B = new UUID(352, 2);
    private static final UUID TUNNEL = new UUID(353, 1);
    private static final UUID CHANNEL = new UUID(353, 2);

    private NetworkTerminalTopologyGameTests() {}

    /** CRUD stays in one session, correctable locks consume sequence, and delete uses the server-held summary. */
    @GameTest(template = "bootstrap")
    public static void terminalTopologyFlowIsAuthoritativeAndLocked(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper)) {
            ServerPlayer owner = player(helper, OWNER);
            ServerPlayer administrator = player(helper, ADMIN);
            fixture.open(owner, VIEW_A, SESSION_A);
            fixture.open(administrator, VIEW_B, SESSION_B);

            state(
                    helper,
                    fixture.handle(owner, new NetworkTerminalRequest.OpenNetwork(VIEW_A, SESSION_A, 1, NETWORK)),
                    NetworkTerminalState.NetworkRoot.class);
            state(
                    helper,
                    fixture.handle(owner, new NetworkTerminalRequest.OpenTunnels(VIEW_A, SESSION_A, 2)),
                    NetworkTerminalState.TunnelList.class);
            state(
                    helper,
                    fixture.handle(owner, new NetworkTerminalRequest.BeginCreateTunnel(VIEW_A, SESSION_A, 3, "Tunnel")),
                    NetworkTerminalState.TunnelEdit.class);

            fixture.handle(administrator, new NetworkTerminalRequest.OpenNetwork(VIEW_B, SESSION_B, 1, NETWORK));
            fixture.handle(administrator, new NetworkTerminalRequest.OpenTunnels(VIEW_B, SESSION_B, 2));
            failure(
                    helper,
                    fixture.handle(
                            administrator,
                            new NetworkTerminalRequest.BeginCreateTunnel(VIEW_B, SESSION_B, 3, "Tunnel")),
                    NetworkTerminalResponse.Reason.LOCKED);
            helper.assertTrue(
                    fixture.handle(owner, new NetworkTerminalRequest.Heartbeat(VIEW_A, SESSION_A, 4)) == null,
                    "Successful terminal heartbeat returned state");
            state(
                    helper,
                    fixture.handle(
                            owner, new NetworkTerminalRequest.CreateTunnel(VIEW_A, SESSION_A, 5, "Main", "Channel 1")),
                    NetworkTerminalState.TunnelList.class);

            NetworkTerminalResponse.ViewState channels = state(
                    helper,
                    fixture.handle(owner, new NetworkTerminalRequest.OpenChannels(VIEW_A, SESSION_A, 6, TUNNEL)),
                    NetworkTerminalState.ChannelList.class);
            helper.assertTrue(
                    channels.state() instanceof NetworkTerminalState.ChannelList list
                            && list.page().entries().size() == 1
                            && list.page().entries().getFirst().channelId().equals(CHANNEL)
                            && list.page().entries().getFirst().name().equals("Channel 1"),
                    "Terminal did not expose exactly the mandatory read-only channel");
            state(
                    helper,
                    fixture.handle(owner, new NetworkTerminalRequest.OpenTunnelSettings(VIEW_A, SESSION_A, 7)),
                    NetworkTerminalState.TunnelSettings.class);
            NetworkTopologyService.Edit readOnlyCheck = fixture.topology.acquireTunnel(administrator, NETWORK, TUNNEL);
            fixture.topology.cancel(administrator, readOnlyCheck);
            state(
                    helper,
                    fixture.handle(owner, new NetworkTerminalRequest.Back(VIEW_A, SESSION_A, 8)),
                    NetworkTerminalState.ChannelList.class);
            fixture.handle(owner, new NetworkTerminalRequest.OpenTunnelSettings(VIEW_A, SESSION_A, 9));
            NetworkTerminalResponse.ViewState disabled = state(
                    helper,
                    fixture.handle(
                            owner, new NetworkTerminalRequest.SetTunnelEnabled(VIEW_A, SESSION_A, 10, TUNNEL, false)),
                    NetworkTerminalState.ChannelList.class);
            helper.assertTrue(
                    disabled.state() instanceof NetworkTerminalState.ChannelList list
                            && !list.tunnel().enabled(),
                    "Tunnel switch was predicted or not published");
            fixture.handle(owner, new NetworkTerminalRequest.OpenTunnelSettings(VIEW_A, SESSION_A, 11));
            state(
                    helper,
                    fixture.handle(owner, new NetworkTerminalRequest.BeginRenameTunnel(VIEW_A, SESSION_A, 12, TUNNEL)),
                    NetworkTerminalState.TunnelEdit.class);
            NetworkTerminalResponse.ViewState renamed = state(
                    helper,
                    fixture.handle(owner, new NetworkTerminalRequest.RenameTunnel(VIEW_A, SESSION_A, 13, "Renamed")),
                    NetworkTerminalState.ChannelList.class);
            helper.assertTrue(
                    renamed.state() instanceof NetworkTerminalState.ChannelList list
                            && list.tunnel().name().equals("Renamed"),
                    "Rename did not return to the scoped channel page");
            fixture.handle(owner, new NetworkTerminalRequest.OpenTunnelSettings(VIEW_A, SESSION_A, 14));
            state(
                    helper,
                    fixture.handle(
                            owner, new NetworkTerminalRequest.RequestDeleteTunnel(VIEW_A, SESSION_A, 15, TUNNEL)),
                    NetworkTerminalState.DeleteConfirmation.class);
            state(
                    helper,
                    fixture.handle(owner, new NetworkTerminalRequest.Back(VIEW_A, SESSION_A, 16)),
                    NetworkTerminalState.ChannelList.class);
            fixture.handle(owner, new NetworkTerminalRequest.OpenTunnelSettings(VIEW_A, SESSION_A, 17));
            fixture.handle(owner, new NetworkTerminalRequest.RequestDeleteTunnel(VIEW_A, SESSION_A, 18, TUNNEL));
            NetworkTerminalResponse.ViewState deleted = state(
                    helper,
                    fixture.handle(owner, new NetworkTerminalRequest.ConfirmDelete(VIEW_A, SESSION_A, 19)),
                    NetworkTerminalState.TunnelList.class);
            helper.assertTrue(
                    deleted.state() instanceof NetworkTerminalState.TunnelList list
                            && list.page().entries().isEmpty(),
                    "Confirmed tunnel delete retained topology");
            helper.assertTrue(fixture.data().findChannel(CHANNEL).isEmpty(), "Cascade retained channel");
            helper.succeed();
        }
    }

    private static NetworkTerminalResponse.ViewState state(
            GameTestHelper helper, NetworkTerminalResponse response, Class<? extends NetworkTerminalState> expected) {
        helper.assertTrue(
                response instanceof NetworkTerminalResponse.ViewState state && expected.isInstance(state.state()),
                "Expected terminal state " + expected.getSimpleName() + ", got " + response);
        return (NetworkTerminalResponse.ViewState) response;
    }

    private static void failure(
            GameTestHelper helper, NetworkTerminalResponse response, NetworkTerminalResponse.Reason expected) {
        helper.assertTrue(
                response instanceof NetworkTerminalResponse.Failure failure && failure.reason() == expected,
                "Expected terminal failure " + expected + ", got " + response);
    }

    private static ServerPlayer player(GameTestHelper helper, UUID id) {
        return new FakePlayer(helper.getLevel(), new GameProfile(id, "Terminal" + id.getLeastSignificantBits()));
    }

    private static final class Fixture implements AutoCloseable {
        private final Path path;
        private final SavedNetworkRepository repository;
        private final NetworkTopologyService topology;
        private final NetworkTerminalService terminal;

        private Fixture(GameTestHelper helper) throws IOException {
            path = Files.createTempDirectory("omniresonance-terminal-topology-test-");
            DimensionDataStorage storage = new DimensionDataStorage(
                    path.toFile(), DataFixers.getDataFixer(), helper.getLevel().registryAccess());
            repository = new SavedNetworkRepository(storage, path);
            NetworkMetadata metadata =
                    new NetworkMetadata(NETWORK, OWNER, new ManagedName("Network"), 0, Set.of(ADMIN));
            repository.createNetwork(metadata);
            NetworkDirectory directory = new NetworkDirectory(List.of(metadata));
            NetworkCreationService creation = new NetworkCreationService(repository, directory, UUID::randomUUID);
            ArrayDeque<UUID> objects = new ArrayDeque<>(List.of(TUNNEL, CHANNEL));
            EditLockTable locks = new EditLockTable();
            ServerConfig.State settings = new ServerConfig.State(1, 1, true, ServerSettings.defaults());
            topology = new NetworkTopologyService(
                    helper.getLevel().getServer(),
                    directory,
                    repository,
                    new NetworkNodeDirectory(List.of()),
                    locks,
                    settings,
                    objects::removeFirst);
            ArrayDeque<UUID> sessions = new ArrayDeque<>(List.of(SESSION_A, SESSION_B));
            terminal = new NetworkTerminalService(
                    helper.getLevel().getServer(), directory, creation, topology, settings, sessions::removeFirst);
        }

        private void open(ServerPlayer player, UUID view, UUID expectedSession) {
            NetworkTerminalResponse.Success opened =
                    (NetworkTerminalResponse.Success) terminal.handle(player, new NetworkTerminalRequest.Open(view));
            if (!opened.sessionId().equals(expectedSession)) {
                throw new IllegalStateException("Unexpected test terminal session");
            }
        }

        private NetworkTerminalResponse handle(ServerPlayer player, NetworkTerminalRequest request) {
            return terminal.handle(player, request);
        }

        private io.github.loongin.omniresonance.persistence.NetworkSavedData data() {
            return repository.findLoadedNetwork(NETWORK).orElseThrow();
        }

        @Override
        public void close() throws IOException {
            terminal.close();
            topology.close();
            IOUtilities.waitUntilIOWorkerComplete();
            try (var files = Files.walk(path)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(file);
                }
            }
        }
    }
}
