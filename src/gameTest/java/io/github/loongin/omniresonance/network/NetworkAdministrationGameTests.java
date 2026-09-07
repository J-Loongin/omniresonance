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
import io.github.loongin.omniresonance.persistence.NetworkSavedData;
import io.github.loongin.omniresonance.persistence.SavedNetworkRepository;
import io.github.loongin.omniresonance.security.EditLockTable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.common.IOUtilities;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real server authority with a deterministic online-identity adapter, never external profile lookups. */
@GameTestHolder(OmniResonanceMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NetworkAdministrationGameTests {
    private static final UUID NETWORK = new UUID(810, 1);
    private static final UUID OTHER = new UUID(810, 2);
    private static final UUID OWNER = new UUID(811, 1);
    private static final UUID ADMIN = new UUID(811, 2);
    private static final UUID NEXT = new UUID(811, 3);
    private static final UUID STRANGER = new UUID(811, 4);

    private NetworkAdministrationGameTests() {}

    @GameTest(template = "bootstrap")
    public static void candidatePagesKeepOneSnapshotAndOfflineCommitRefreshesIt(GameTestHelper helper)
            throws IOException {
        try (Fixture f = new Fixture(helper, 128)) {
            for (int i = 0; i < 260; i++) f.profiles.add(new UUID(840, i), "Extra" + i);
            AtomicLong ids = new AtomicLong();
            java.util.function.Supplier<UUID> newId = () -> new UUID(841, ids.incrementAndGet());
            NetworkTopologyService topology = new NetworkTopologyService(
                    helper.getLevel().getServer(),
                    f.directory,
                    f.repository,
                    new NetworkNodeDirectory(List.of()),
                    f.locks,
                    new ServerConfig.State(1, 1, true, settings(128)),
                    newId);
            NetworkTerminalService terminal = new NetworkTerminalService(
                    helper.getLevel().getServer(),
                    f.directory,
                    new NetworkCreationService(f.repository, f.directory, newId),
                    topology,
                    f.service,
                    null,
                    new ServerConfig.State(1, 1, true, settings(128)),
                    newId,
                    (target, notice) -> {});
            try {
                ServerPlayer owner = player(helper, OWNER);
                UUID view = new UUID(842, 1);
                UUID session = ((NetworkTerminalResponse.Success)
                                terminal.handle(owner, new NetworkTerminalRequest.Open(view)))
                        .sessionId();
                terminal.handle(owner, new NetworkTerminalRequest.OpenNetwork(view, session, 1, NETWORK));
                terminal.handle(owner, new NetworkTerminalRequest.OpenMembers(view, session, 2));
                var first = (NetworkTerminalState.AdministratorCandidates)
                        ((NetworkTerminalResponse.ViewState) terminal.handle(
                                        owner,
                                        new NetworkTerminalRequest.OpenAdministratorCandidates(view, session, 3)))
                                .state();
                helper.assertTrue(
                        first.page().entries().size() == 128 && first.page().totalCount() == 262,
                        "First candidate batch mismatch");
                UUID snapshot = first.page().snapshotId();
                var wrong = (NetworkTerminalResponse.Failure) terminal.handle(
                        owner, new NetworkTerminalRequest.PageAdministratorCandidates(view, session, 4, view, 128));
                helper.assertTrue(
                        wrong.reason() == NetworkTerminalResponse.Reason.INVALID_REQUEST, "Foreign snapshot accepted");
                terminal.handle(
                        owner, new NetworkTerminalRequest.PageAdministratorCandidates(view, session, 5, snapshot, 128));
                var last = (NetworkTerminalState.AdministratorCandidates)
                        ((NetworkTerminalResponse.ViewState) terminal.handle(
                                        owner,
                                        new NetworkTerminalRequest.PageAdministratorCandidates(
                                                view, session, 6, snapshot, 256)))
                                .state();
                helper.assertTrue(
                        last.page().entries().size() == 6 && !last.page().hasNext(), "Last candidate batch mismatch");
                UUID late = new UUID(843, 1);
                f.profiles.add(late, "Late");
                var invented = (NetworkTerminalResponse.Failure)
                        terminal.handle(owner, new NetworkTerminalRequest.AddAdministrator(view, session, 7, late));
                helper.assertTrue(
                        invented.reason() == NetworkTerminalResponse.Reason.INVALID_REQUEST,
                        "Target outside snapshot accepted");
                f.profiles.online.remove(ADMIN);
                var offline = (NetworkTerminalResponse.Failure)
                        terminal.handle(owner, new NetworkTerminalRequest.AddAdministrator(view, session, 8, ADMIN));
                helper.assertTrue(
                        offline.reason() == NetworkTerminalResponse.Reason.PLAYER_OFFLINE, "Offline target was added");
                var replacement = (NetworkTerminalState.AdministratorCandidates) offline.state();
                helper.assertTrue(
                        !replacement.page().snapshotId().equals(snapshot)
                                && replacement.page().offset() == 0,
                        "Offline failure did not replace the selection snapshot");
                helper.assertTrue(f.data.administratorCount() == 0, "Rejected selection modified members");
                var stale = (NetworkTerminalResponse.Failure) terminal.handle(
                        owner, new NetworkTerminalRequest.PageAdministratorCandidates(view, session, 9, snapshot, 128));
                helper.assertTrue(
                        stale.reason() == NetworkTerminalResponse.Reason.INVALID_REQUEST,
                        "Stale snapshot continuation accepted");
                helper.succeed();
            } finally {
                terminal.close();
                topology.close();
            }
        }
    }

    @GameTest(template = "bootstrap")
    public static void terminalMembershipLifecycleRevokesOnlyTheTargetNetwork(GameTestHelper helper)
            throws IOException {
        try (Fixture f = new Fixture(helper, 128)) {
            ServerPlayer owner = player(helper, OWNER);
            ServerPlayer admin = player(helper, ADMIN);
            AtomicLong ids = new AtomicLong();
            var newId = (java.util.function.Supplier<UUID>) () -> new UUID(819, ids.incrementAndGet());
            NetworkTopologyService topology = new NetworkTopologyService(
                    helper.getLevel().getServer(),
                    f.directory,
                    f.repository,
                    new NetworkNodeDirectory(List.of()),
                    f.locks,
                    new ServerConfig.State(1, 1, true, settings(128)),
                    newId);
            List<NetworkTerminalResponse> notices = new ArrayList<>();
            NetworkTerminalService terminal = new NetworkTerminalService(
                    helper.getLevel().getServer(),
                    f.directory,
                    new NetworkCreationService(f.repository, f.directory, newId),
                    topology,
                    f.service,
                    null,
                    new ServerConfig.State(1, 1, true, settings(128)),
                    newId,
                    (target, notice) -> notices.add(notice));
            try {
                UUID view = new UUID(818, 1);
                var opened =
                        (NetworkTerminalResponse.Success) terminal.handle(owner, new NetworkTerminalRequest.Open(view));
                UUID session = opened.sessionId();
                terminal.handle(owner, new NetworkTerminalRequest.OpenNetwork(view, session, 1, NETWORK));
                var members = (NetworkTerminalResponse.ViewState)
                        terminal.handle(owner, new NetworkTerminalRequest.OpenMembers(view, session, 2));
                helper.assertTrue(
                        ((NetworkTerminalState.Members) members.state())
                                        .page()
                                        .entries()
                                        .size()
                                == 1,
                        "Initial owner missing");
                var candidates = (NetworkTerminalResponse.ViewState) terminal.handle(
                        owner, new NetworkTerminalRequest.OpenAdministratorCandidates(view, session, 3));
                helper.assertTrue(
                        ((NetworkTerminalState.AdministratorCandidates) candidates.state())
                                        .page()
                                        .totalCount()
                                == 2,
                        "Candidate roster mismatch");
                var added = (NetworkTerminalResponse.ViewState)
                        terminal.handle(owner, new NetworkTerminalRequest.AddAdministrator(view, session, 4, ADMIN));
                helper.assertTrue(
                        ((NetworkTerminalState.Members) added.state())
                                .selectedMemberId()
                                .equals(ADMIN),
                        "New member not selected");
                UUID adminView = new UUID(818, 2);
                var adminOpened = (NetworkTerminalResponse.Success)
                        terminal.handle(admin, new NetworkTerminalRequest.Open(adminView));
                UUID adminSession = adminOpened.sessionId();
                terminal.handle(admin, new NetworkTerminalRequest.OpenNetwork(adminView, adminSession, 1, NETWORK));
                terminal.handle(admin, new NetworkTerminalRequest.OpenMembers(adminView, adminSession, 2));
                var denied = (NetworkTerminalResponse.Failure) terminal.handle(
                        admin,
                        new NetworkTerminalRequest.RequestRemoveAdministrator(adminView, adminSession, 3, OWNER));
                helper.assertTrue(
                        denied.reason() == NetworkTerminalResponse.Reason.NO_ACCESS, "Admin could manage members");
                terminal.handle(owner, new NetworkTerminalRequest.RequestRemoveAdministrator(view, session, 5, ADMIN));
                terminal.handle(owner, new NetworkTerminalRequest.ConfirmRemoveAdministrator(view, session, 6));
                helper.assertTrue(
                        notices.size() == 1 && notices.getFirst() instanceof NetworkTerminalResponse.AccessRevoked,
                        "Revocation notice missing");
                var revoked = (NetworkTerminalResponse.AccessRevoked) notices.getFirst();
                helper.assertTrue(
                        revoked.viewId().equals(adminView)
                                && revoked.networkId().equals(NETWORK),
                        "Wrong view revoked");
                var stale = (NetworkTerminalResponse.Failure)
                        terminal.handle(admin, new NetworkTerminalRequest.OpenMembers(adminView, adminSession, 4));
                helper.assertTrue(
                        stale.reason() == NetworkTerminalResponse.Reason.SESSION_EXPIRED, "Revoked session survived");
                helper.assertTrue(f.directory.accessibleTo(ADMIN).equals(List.of(f.other)), "Unrelated access lost");
                terminal.handle(owner, new NetworkTerminalRequest.OpenAdministratorCandidates(view, session, 7));
                terminal.handle(owner, new NetworkTerminalRequest.AddAdministrator(view, session, 8, ADMIN));
                terminal.handle(owner, new NetworkTerminalRequest.RequestRemoveAdministrator(view, session, 9, ADMIN));
                terminal.handle(owner, new NetworkTerminalRequest.Close(view, session));
                f.service.add(owner, NETWORK, NEXT);
                helper.assertTrue(f.data.administratorCount() == 2, "Closing confirmation left its edit locked");
                helper.succeed();
            } finally {
                terminal.close();
                topology.close();
            }
        }
    }

    @GameTest(template = "bootstrap")
    public static void onlyOwnerCanAddAndRemoveMembers(GameTestHelper helper) throws IOException {
        try (Fixture f = new Fixture(helper, 128)) {
            ServerPlayer owner = player(helper, OWNER);
            ServerPlayer admin = player(helper, ADMIN);
            f.service.add(owner, NETWORK, ADMIN);
            helper.assertTrue(
                    f.service.inspectNetwork(admin, NETWORK).administrators().contains(ADMIN), "Admin cannot inspect");
            for (ServerPlayer denied : List.of(admin, player(helper, STRANGER), operator(helper))) {
                f.data.setDirty(false);
                rejected(
                        helper,
                        NetworkAdministrationService.Reason.NO_ACCESS,
                        () -> f.service.add(denied, NETWORK, NEXT));
                rejected(
                        helper,
                        NetworkAdministrationService.Reason.NO_ACCESS,
                        () -> f.service.beginRemoval(denied, NETWORK, ADMIN));
                helper.assertTrue(!f.data.isDirty(), "Denied member edit dirtied data");
            }
            rejected(
                    helper,
                    NetworkAdministrationService.Reason.OWNER_TARGET,
                    () -> f.service.add(owner, NETWORK, OWNER));
            rejected(
                    helper,
                    NetworkAdministrationService.Reason.OWNER_TARGET,
                    () -> f.service.beginRemoval(owner, NETWORK, OWNER));
            f.profiles.online.remove(ADMIN);
            f.service.remove(owner, f.service.beginRemoval(owner, NETWORK, ADMIN));
            rejected(
                    helper,
                    NetworkAdministrationService.Reason.NO_ACCESS,
                    () -> f.service.inspectNetwork(admin, NETWORK));
            helper.assertTrue(
                    f.directory.accessibleTo(ADMIN).equals(List.of(f.other)), "Other network access was revoked");
            helper.assertTrue(
                    f.data.managementRevision() == 2 && f.data.topologyRevision() == 0, "Wrong revision changed");
            helper.succeed();
        }
    }

    @GameTest(template = "bootstrap")
    public static void onlineRevalidationAndLiveQuotaNeverPartiallyAdd(GameTestHelper helper) throws IOException {
        try (Fixture f = new Fixture(helper, 1)) {
            ServerPlayer owner = player(helper, OWNER);
            List<NetworkAdministrationService.PlayerIdentity> candidates = f.service.onlineCandidates(owner, NETWORK);
            helper.assertTrue(
                    candidates.size() == 2 && candidates.getFirst().id().equals(ADMIN),
                    "Wrong candidate exclusion/order");
            f.profiles.online.remove(ADMIN);
            f.data.setDirty(false);
            rejected(
                    helper,
                    NetworkAdministrationService.Reason.PLAYER_OFFLINE,
                    () -> f.service.add(owner, NETWORK, ADMIN));
            helper.assertTrue(!f.data.isDirty(), "Offline addition dirtied authority");
            f.service.add(owner, NETWORK, NEXT);
            f.profiles.add(ADMIN, "Admin");
            rejected(
                    helper,
                    NetworkAdministrationService.Reason.QUOTA_REACHED,
                    () -> f.service.add(owner, NETWORK, ADMIN));
            rejected(
                    helper,
                    NetworkAdministrationService.Reason.ALREADY_ADMINISTRATOR,
                    () -> f.service.add(owner, NETWORK, NEXT));
            f.service.applyConfiguration(new ServerConfig.State(1, 2, true, settings(0)));
            helper.assertTrue(f.data.administratorCount() == 1, "Lowered quota removed an existing member");
            f.service.remove(owner, f.service.beginRemoval(owner, NETWORK, NEXT));
            rejected(
                    helper,
                    NetworkAdministrationService.Reason.QUOTA_REACHED,
                    () -> f.service.add(owner, NETWORK, ADMIN));
            helper.succeed();
        }
    }

    @GameTest(template = "bootstrap")
    public static void removalLocksExpireAndStaleEditsCannotChangeMembership(GameTestHelper helper) throws IOException {
        try (Fixture f = new Fixture(helper, 128)) {
            ServerPlayer owner = player(helper, OWNER);
            f.service.add(owner, NETWORK, ADMIN);
            var edit = f.service.beginRemoval(owner, NETWORK, ADMIN);
            rejected(helper, NetworkAdministrationService.Reason.LOCKED, () -> f.service.add(owner, NETWORK, NEXT));
            for (int tick = 0; tick < 40; tick++) f.service.tick();
            f.service.heartbeat(owner, edit);
            for (int tick = 40; tick < 200; tick++) f.service.tick();
            f.service.heartbeat(owner, edit);
            for (int tick = 0; tick < 200; tick++) f.service.tick();
            rejected(helper, NetworkAdministrationService.Reason.LOCK_EXPIRED, () -> f.service.remove(owner, edit));
            var current = f.service.beginRemoval(owner, NETWORK, ADMIN);
            var update = f.data.prepareAdministratorChange(NEXT, true, f.data.managementRevision(), 128);
            var index = f.directory.prepareMetadataReplacement(update.previous(), update.next());
            f.data.commitAdministratorChange(update);
            f.directory.commitMetadataReplacement(index);
            f.data.setDirty(false);
            rejected(
                    helper, NetworkAdministrationService.Reason.STALE_REVISION, () -> f.service.remove(owner, current));
            helper.assertTrue(!f.data.isDirty() && f.data.administratorCount() == 2, "Stale removal mutated authority");
            var cancelled = f.service.beginRemoval(owner, NETWORK, ADMIN);
            f.service.cancel(owner, cancelled);
            rejected(
                    helper, NetworkAdministrationService.Reason.LOCK_EXPIRED, () -> f.service.remove(owner, cancelled));
            helper.succeed();
        }
    }

    @GameTest(template = "bootstrap")
    public static void memberChangesSurviveNormalSavedDataReload(GameTestHelper helper) throws IOException {
        try (Fixture f = new Fixture(helper, 128)) {
            ServerPlayer owner = player(helper, OWNER);
            f.service.add(owner, NETWORK, ADMIN);
            f.storage.save();
            IOUtilities.waitUntilIOWorkerComplete();
            DimensionDataStorage reloadedStorage = new DimensionDataStorage(
                    f.path.toFile(),
                    DataFixers.getDataFixer(),
                    helper.getLevel().registryAccess());
            SavedNetworkRepository reloaded = new SavedNetworkRepository(reloadedStorage, f.path);
            reloaded.loadNetworkData();
            NetworkSavedData network = reloaded.findLoadedNetwork(NETWORK).orElseThrow();
            helper.assertTrue(network.metadata().administrators().equals(Set.of(ADMIN)), "Members lost on reload");
            helper.assertTrue(network.managementRevision() == 1, "Management revision lost on reload");
            helper.assertTrue(
                    f.service.onlineCandidates(owner, NETWORK).size() == 1, "Existing admin remains a candidate");
            helper.succeed();
        }
    }

    private static ServerSettings settings(int limit) {
        return new ServerSettings(32, 1024, 256, 16, limit);
    }

    private static ServerPlayer player(GameTestHelper helper, UUID id) {
        return new FakePlayer(helper.getLevel(), new GameProfile(id, "Member" + id.getLeastSignificantBits()));
    }

    private static ServerPlayer operator(GameTestHelper helper) {
        return new FakePlayer(helper.getLevel(), new GameProfile(new UUID(811, 5), "MemberOperator")) {
            @Override
            public boolean hasPermissions(int level) {
                return true;
            }
        };
    }

    private static void rejected(GameTestHelper helper, NetworkAdministrationService.Reason reason, Runnable action) {
        try {
            action.run();
        } catch (NetworkAdministrationService.Rejected rejected) {
            helper.assertTrue(rejected.reason() == reason, "Expected " + reason + ", got " + rejected.reason());
            return;
        }
        helper.fail("Expected rejection " + reason);
    }

    private static final class Profiles implements NetworkAdministrationService.PlayerDirectory {
        private final Map<UUID, NetworkAdministrationService.PlayerIdentity> online = new HashMap<>();

        void add(UUID id, String name) {
            online.put(id, new NetworkAdministrationService.PlayerIdentity(id, name));
        }

        @Override
        public Optional<NetworkAdministrationService.PlayerIdentity> online(UUID id) {
            return Optional.ofNullable(online.get(id));
        }

        @Override
        public List<NetworkAdministrationService.PlayerIdentity> snapshotOnline(int maximum) {
            if (online.size() > maximum) throw new IllegalArgumentException("Roster exceeds limit");
            return new ArrayList<>(online.values());
        }

        @Override
        public String knownName(UUID id) {
            return online(id)
                    .map(NetworkAdministrationService.PlayerIdentity::name)
                    .orElse(id.toString());
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final Path path;
        private final DimensionDataStorage storage;
        private final SavedNetworkRepository repository;
        private final EditLockTable locks = new EditLockTable();
        private final NetworkSavedData data;
        private final NetworkMetadata other;
        private final NetworkDirectory directory;
        private final Profiles profiles = new Profiles();
        private final NetworkAdministrationService service;

        private Fixture(GameTestHelper helper, int limit) throws IOException {
            path = Files.createTempDirectory("omniresonance-members-test-");
            storage = new DimensionDataStorage(
                    path.toFile(), DataFixers.getDataFixer(), helper.getLevel().registryAccess());
            repository = new SavedNetworkRepository(storage, path);
            NetworkMetadata metadata = new NetworkMetadata(NETWORK, OWNER, new ManagedName("Members"), 0, Set.of());
            other = new NetworkMetadata(OTHER, OWNER, new ManagedName("Other"), 1, Set.of(ADMIN));
            repository.createNetwork(metadata);
            repository.createNetwork(other);
            data = repository.findLoadedNetwork(NETWORK).orElseThrow();
            directory = new NetworkDirectory(List.of(metadata, other));
            profiles.add(OWNER, "Owner");
            profiles.add(ADMIN, "Admin");
            profiles.add(NEXT, "Next");
            service = new NetworkAdministrationService(
                    helper.getLevel().getServer(),
                    repository,
                    directory,
                    locks,
                    profiles,
                    new ServerConfig.State(1, 1, true, settings(limit)));
        }

        @Override
        public void close() throws IOException {
            service.close();
            IOUtilities.waitUntilIOWorkerComplete();
            try (var files = Files.walk(path)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(file);
                }
            }
        }
    }
}
