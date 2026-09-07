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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
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

/** Existing terminal-session behavior for network settings and scoped deletion notices. */
@GameTestHolder(OmniResonanceMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NetworkTerminalSettingsGameTests {
    private static final UUID NETWORK = new UUID(870, 1);
    private static final UUID UNRELATED = new UUID(870, 2);
    private static final UUID OWNER = new UUID(871, 1);
    private static final UUID ADMINISTRATOR = new UUID(871, 2);
    private static final UUID OTHER_OWNER = new UUID(871, 3);
    private static final UUID OWNER_VIEW = new UUID(872, 1);
    private static final UUID ADMIN_VIEW = new UUID(872, 2);
    private static final UUID OTHER_VIEW = new UUID(872, 3);
    private static final UUID OWNER_SESSION = new UUID(873, 1);
    private static final UUID ADMIN_SESSION = new UUID(873, 2);
    private static final UUID OTHER_SESSION = new UUID(873, 3);

    private NetworkTerminalSettingsGameTests() {}

    /** Rename/default flow is authoritative and correctable name failures retain the same edit session. */
    @GameTest(template = "bootstrap")
    public static void settingsFlowKeepsOneHierarchyAndCorrectableRename(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper)) {
            ServerPlayer owner = player(helper, OWNER);
            fixture.open(owner, OWNER_VIEW, OWNER_SESSION);
            state(
                    helper,
                    fixture.terminal.handle(
                            owner, new NetworkTerminalRequest.OpenNetwork(OWNER_VIEW, OWNER_SESSION, 1, NETWORK)),
                    NetworkTerminalState.NetworkRoot.class);
            NetworkTerminalResponse.ViewState settings = state(
                    helper,
                    fixture.terminal.handle(
                            owner, new NetworkTerminalRequest.OpenNetworkSettings(OWNER_VIEW, OWNER_SESSION, 2)),
                    NetworkTerminalState.NetworkSettings.class);
            helper.assertTrue(
                    ((NetworkTerminalState.NetworkSettings) settings.state())
                            .settings()
                            .ownerActions(),
                    "Owner settings omitted owner actions");
            state(
                    helper,
                    fixture.terminal.handle(
                            owner, new NetworkTerminalRequest.BeginRenameNetwork(OWNER_VIEW, OWNER_SESSION, 3)),
                    NetworkTerminalState.NetworkRename.class);
            failure(
                    helper,
                    fixture.terminal.handle(
                            owner, new NetworkTerminalRequest.RenameNetwork(OWNER_VIEW, OWNER_SESSION, 4, "Bad§Name")),
                    NetworkTerminalResponse.Reason.INVALID_NAME);
            NetworkTerminalResponse.ViewState renamed = state(
                    helper,
                    fixture.terminal.handle(
                            owner, new NetworkTerminalRequest.RenameNetwork(OWNER_VIEW, OWNER_SESSION, 5, "Renamed")),
                    NetworkTerminalState.NetworkSettings.class);
            helper.assertTrue(
                    ((NetworkTerminalState.NetworkSettings) renamed.state())
                            .settings()
                            .network()
                            .name()
                            .equals("Renamed"),
                    "Successful rename did not refresh settings");
            NetworkTerminalResponse.ViewState preferred = state(
                    helper,
                    fixture.terminal.handle(
                            owner, new NetworkTerminalRequest.SetDefaultNetwork(OWNER_VIEW, OWNER_SESSION, 6)),
                    NetworkTerminalState.NetworkSettings.class);
            helper.assertTrue(
                    ((NetworkTerminalState.NetworkSettings) preferred.state())
                            .settings()
                            .defaultNetwork(),
                    "Set-default did not return the current owner state");
            helper.succeed();
        }
    }

    /** Deletion returns its initiator to the directory and closes only other sessions on that network. */
    @GameTest(template = "bootstrap")
    public static void deletionClosesOnlySessionsForTheRemovedNetwork(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper)) {
            ServerPlayer owner = player(helper, OWNER);
            ServerPlayer administrator = player(helper, ADMINISTRATOR);
            ServerPlayer otherOwner = player(helper, OTHER_OWNER);
            fixture.open(owner, OWNER_VIEW, OWNER_SESSION);
            fixture.open(administrator, ADMIN_VIEW, ADMIN_SESSION);
            fixture.open(otherOwner, OTHER_VIEW, OTHER_SESSION);

            fixture.terminal.handle(
                    owner, new NetworkTerminalRequest.OpenNetwork(OWNER_VIEW, OWNER_SESSION, 1, NETWORK));
            fixture.terminal.handle(
                    owner, new NetworkTerminalRequest.OpenNetworkSettings(OWNER_VIEW, OWNER_SESSION, 2));
            fixture.terminal.handle(
                    administrator, new NetworkTerminalRequest.OpenNetwork(ADMIN_VIEW, ADMIN_SESSION, 1, NETWORK));
            fixture.terminal.handle(
                    administrator, new NetworkTerminalRequest.OpenNetworkSettings(ADMIN_VIEW, ADMIN_SESSION, 2));
            fixture.terminal.handle(
                    administrator, new NetworkTerminalRequest.BeginRenameNetwork(ADMIN_VIEW, ADMIN_SESSION, 3));
            fixture.terminal.handle(
                    otherOwner, new NetworkTerminalRequest.OpenNetwork(OTHER_VIEW, OTHER_SESSION, 1, UNRELATED));
            for (int tick = 0; tick < 200; tick++) fixture.terminal.tick();

            NetworkTerminalResponse.ViewState confirmation = state(
                    helper,
                    fixture.terminal.handle(
                            owner, new NetworkTerminalRequest.RequestDeleteNetwork(OWNER_VIEW, OWNER_SESSION, 3)),
                    NetworkTerminalState.NetworkDelete.class);
            helper.assertTrue(
                    ((NetworkTerminalState.NetworkDelete) confirmation.state())
                                    .deletion()
                                    .administratorCount()
                            == 1,
                    "Deletion confirmation omitted administrator count");
            NetworkTerminalResponse response = fixture.terminal.handle(
                    owner, new NetworkTerminalRequest.ConfirmDeleteNetwork(OWNER_VIEW, OWNER_SESSION, 4));
            helper.assertTrue(
                    response instanceof NetworkTerminalResponse.Success success
                            && success.page().entries().isEmpty(),
                    "Deletion initiator did not return to an empty directory");
            helper.assertTrue(
                    fixture.notices.size() == 1
                            && fixture.notices.getFirst().playerId().equals(ADMINISTRATOR)
                            && fixture.notices.getFirst().response()
                                    instanceof NetworkTerminalResponse.NetworkDeleted deleted
                            && deleted.networkId().equals(NETWORK),
                    "Affected administrator session did not receive one scoped deletion notice");
            failure(
                    helper,
                    fixture.terminal.handle(
                            administrator,
                            new NetworkTerminalRequest.RenameNetwork(ADMIN_VIEW, ADMIN_SESSION, 4, "Too late")),
                    NetworkTerminalResponse.Reason.SESSION_EXPIRED);
            helper.assertTrue(
                    fixture.terminal.handle(otherOwner, new NetworkTerminalRequest.Back(OTHER_VIEW, OTHER_SESSION, 2))
                            instanceof NetworkTerminalResponse.Success,
                    "Unrelated network session was closed");
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
        return new FakePlayer(
                helper.getLevel(), new GameProfile(id, "TerminalSettings" + id.getLeastSignificantBits()));
    }

    private record Notice(UUID playerId, NetworkTerminalResponse response) {}

    private static final class Profiles implements NetworkAdministrationService.PlayerDirectory {
        @Override
        public Optional<NetworkAdministrationService.PlayerIdentity> online(UUID id) {
            return Optional.empty();
        }

        @Override
        public List<NetworkAdministrationService.PlayerIdentity> snapshotOnline(int maximum) {
            return List.of();
        }

        @Override
        public String knownName(UUID id) {
            return id.equals(OWNER) ? "Owner" : id.toString();
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final Path path;
        private final NetworkTopologyService topology;
        private final NetworkSettingsService settings;
        private final NetworkTerminalService terminal;
        private final List<Notice> notices = new ArrayList<>();

        private Fixture(GameTestHelper helper) throws IOException {
            path = Files.createTempDirectory("omniresonance-terminal-settings-test-");
            DimensionDataStorage storage = new DimensionDataStorage(
                    path.toFile(), DataFixers.getDataFixer(), helper.getLevel().registryAccess());
            SavedNetworkRepository repository = new SavedNetworkRepository(storage, path);
            NetworkMetadata network =
                    new NetworkMetadata(NETWORK, OWNER, new ManagedName("Network"), 0, Set.of(ADMINISTRATOR));
            NetworkMetadata unrelated =
                    new NetworkMetadata(UNRELATED, OTHER_OWNER, new ManagedName("Unrelated"), 0, Set.of());
            repository.createNetwork(network);
            repository.createNetwork(unrelated);
            NetworkDirectory directory = new NetworkDirectory(List.of(network, unrelated));
            NetworkCreationService creation = new NetworkCreationService(repository, directory, UUID::randomUUID);
            EditLockTable locks = new EditLockTable();
            ServerConfig.State config = new ServerConfig.State(1, 1, true, ServerSettings.defaults());
            topology = new NetworkTopologyService(
                    helper.getLevel().getServer(),
                    directory,
                    repository,
                    new NetworkNodeDirectory(List.of()),
                    locks,
                    config,
                    UUID::randomUUID);
            Profiles profiles = new Profiles();
            settings =
                    new NetworkSettingsService(helper.getLevel().getServer(), repository, directory, locks, profiles);
            ArrayDeque<UUID> sessions = new ArrayDeque<>(List.of(OWNER_SESSION, ADMIN_SESSION, OTHER_SESSION));
            terminal = new NetworkTerminalService(
                    helper.getLevel().getServer(),
                    directory,
                    creation,
                    topology,
                    null,
                    settings,
                    null,
                    config,
                    sessions::removeFirst,
                    (player, response) -> notices.add(new Notice(player.getUUID(), response)));
        }

        private void open(ServerPlayer player, UUID view, UUID expectedSession) {
            NetworkTerminalResponse.Success opened =
                    (NetworkTerminalResponse.Success) terminal.handle(player, new NetworkTerminalRequest.Open(view));
            if (!opened.sessionId().equals(expectedSession)) {
                throw new IllegalStateException("Unexpected test terminal session");
            }
        }

        @Override
        public void close() throws IOException {
            terminal.close();
            topology.close();
            settings.close();
            IOUtilities.waitUntilIOWorkerComplete();
            try (var files = Files.walk(path)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(file);
                }
            }
        }
    }
}
