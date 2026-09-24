// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.network;

import com.mojang.authlib.GameProfile;
import io.github.loongin.omniresonance.bootstrap.OmniResonanceMod;
import io.github.loongin.omniresonance.config.ServerConfig;
import io.github.loongin.omniresonance.config.ServerSettings;
import io.github.loongin.omniresonance.persistence.ManagedSavedDataNames;
import io.github.loongin.omniresonance.persistence.NetworkSavedData;
import io.github.loongin.omniresonance.persistence.OwnerSavedData;
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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.common.IOUtilities;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Authoritative network settings tests using real SavedData, indexes, locks and temporary paths. */
@GameTestHolder(OmniResonanceMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NetworkSettingsGameTests {
    private static final UUID FIRST = new UUID(850, 1);
    private static final UUID TARGET = new UUID(850, 2);
    private static final UUID UNRELATED = new UUID(850, 3);
    private static final UUID OWNER = new UUID(851, 1);
    private static final UUID ADMINISTRATOR = new UUID(851, 2);
    private static final UUID STRANGER = new UUID(851, 3);
    private static final UUID OTHER_OWNER = new UUID(851, 4);

    private NetworkSettingsGameTests() {}

    /** Owner/admin rename, owner-only defaults and OP-without-role all use live role checks. */
    @GameTest(template = "bootstrap")
    public static void renameDefaultAndPermissionsUseCurrentRoles(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper)) {
            ServerPlayer owner = player(helper, OWNER);
            ServerPlayer administrator = player(helper, ADMINISTRATOR);
            ServerPlayer stranger = player(helper, STRANGER);
            ServerPlayer operator = operator(helper);

            helper.assertTrue(fixture.settings.inspect(owner, TARGET).ownerActions(), "Owner actions missing");
            helper.assertTrue(
                    !fixture.settings.inspect(administrator, TARGET).ownerActions(),
                    "Administrator received owner actions");
            rejected(helper, NetworkSettingsService.Reason.NO_ACCESS, () -> fixture.settings.inspect(stranger, TARGET));
            rejected(helper, NetworkSettingsService.Reason.NO_ACCESS, () -> fixture.settings.inspect(operator, TARGET));

            NetworkSettingsService.RenameEdit edit = fixture.settings.beginRename(administrator, TARGET);
            NetworkMetadata renamed = fixture.settings.rename(administrator, edit, "Unrelated");
            helper.assertTrue(
                    renamed.name().value().equals("Unrelated"),
                    "Other owner's matching name incorrectly blocked administrator rename");
            helper.assertTrue(fixture.target.managementRevision() == 1, "Rename revision mismatch");
            helper.assertTrue(fixture.target.topologyRevision() == 0, "Rename changed topology revision");
            helper.assertTrue(
                    fixture.directory.containsName(OWNER, new ManagedName("unrelated")),
                    "Owner-scoped name index was not replaced");

            fixture.target.setDirty(false);
            NetworkSettingsService.RenameEdit noOp = fixture.settings.beginRename(owner, TARGET);
            fixture.settings.rename(owner, noOp, "Unrelated");
            helper.assertTrue(!fixture.target.isDirty(), "No-op rename dirtied the network");
            helper.assertTrue(fixture.target.managementRevision() == 1, "No-op rename consumed a revision");

            NetworkSettingsService.RenameEdit conflict = fixture.settings.beginRename(owner, TARGET);
            rejected(
                    helper,
                    NetworkSettingsService.Reason.NAME_CONFLICT,
                    () -> fixture.settings.rename(owner, conflict, "FIRST"));
            rejected(
                    helper,
                    NetworkSettingsService.Reason.LOCKED,
                    () -> fixture.settings.beginRename(administrator, TARGET));
            fixture.settings.cancel(owner, conflict);
            NetworkSettingsService.RenameEdit invalid = fixture.settings.beginRename(owner, TARGET);
            rejected(
                    helper,
                    NetworkSettingsService.Reason.INVALID_NAME,
                    () -> fixture.settings.rename(owner, invalid, "Bad§Name"));
            fixture.settings.cancel(owner, invalid);
            helper.assertTrue(!fixture.target.isDirty(), "Rejected rename dirtied the network");
            helper.assertTrue(fixture.target.managementRevision() == 1, "Rejected rename consumed a revision");

            fixture.settings.setDefault(owner, TARGET);
            OwnerSavedData ownerData = fixture.repository.findOwner(OWNER).orElseThrow();
            helper.assertTrue(
                    ownerData.defaultNetworkId().equals(Optional.of(TARGET)),
                    "Explicit default operation did not create the owner pointer");
            ownerData.setDirty(false);
            fixture.settings.setDefault(owner, TARGET);
            helper.assertTrue(!ownerData.isDirty(), "Repeated default operation dirtied the owner shard");
            rejected(
                    helper,
                    NetworkSettingsService.Reason.NO_ACCESS,
                    () -> fixture.settings.setDefault(administrator, TARGET));
            rejected(
                    helper,
                    NetworkSettingsService.Reason.NO_ACCESS,
                    () -> fixture.settings.setDefault(owner, UNRELATED));
            helper.succeed();
        }
    }

    /** A case-only display rename keeps UUID access, consumes one revision and releases the metadata lock. */
    @GameTest(template = "bootstrap")
    public static void caseOnlyNetworkRenamePreservesIdentityAndReleasesLock(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper)) {
            ServerPlayer owner = player(helper, OWNER);
            ServerPlayer administrator = player(helper, ADMINISTRATOR);
            fixture.settings.rename(owner, fixture.settings.beginRename(owner, TARGET), "Alpha");
            long revision = fixture.target.managementRevision();
            fixture.target.setDirty(false);

            NetworkMetadata renamed =
                    fixture.settings.rename(owner, fixture.settings.beginRename(owner, TARGET), "ALPHA");

            helper.assertTrue(renamed.name().value().equals("ALPHA"), "Case-only rename lost display casing");
            helper.assertTrue(
                    fixture.target.managementRevision() == revision + 1,
                    "Case-only rename did not consume exactly one revision");
            helper.assertTrue(fixture.target.isDirty(), "Case-only rename was not marked for persistence");
            helper.assertTrue(
                    fixture.directory.find(TARGET).orElseThrow().equals(renamed),
                    "Case-only rename lost UUID directory access");
            NetworkSettingsService.RenameEdit next = fixture.settings.beginRename(administrator, TARGET);
            fixture.target.setDirty(false);
            fixture.settings.rename(administrator, next, "ALPHA");
            helper.assertTrue(
                    !fixture.target.isDirty() && fixture.target.managementRevision() == revision + 1,
                    "Exact same-name save changed the network");
            helper.succeed();
        }
    }

    /** Rename and administrator management serialize on the same network metadata lock. */
    @GameTest(template = "bootstrap")
    public static void settingsAndMembershipShareOneLock(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper)) {
            ServerPlayer owner = player(helper, OWNER);
            ServerPlayer administrator = player(helper, ADMINISTRATOR);

            NetworkAdministrationService.RemovalEdit removal =
                    fixture.administration.beginRemoval(owner, TARGET, ADMINISTRATOR);
            rejected(
                    helper,
                    NetworkSettingsService.Reason.LOCKED,
                    () -> fixture.settings.beginRename(administrator, TARGET));
            fixture.administration.cancel(owner, removal);

            NetworkSettingsService.RenameEdit rename = fixture.settings.beginRename(administrator, TARGET);
            rejected(
                    helper,
                    NetworkAdministrationService.Reason.LOCKED,
                    () -> fixture.administration.beginRemoval(owner, TARGET, ADMINISTRATOR));
            fixture.settings.cancel(administrator, rename);
            helper.succeed();
        }
    }

    /** Empty topology definitions may disappear with a network and queued writes cannot revive it. */
    @GameTest(template = "bootstrap")
    public static void emptyDeletionPersistsAndSelectsEarliestDefault(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper)) {
            ServerPlayer owner = player(helper, OWNER);
            fixture.repository.createOwner(OWNER, TARGET);
            fixture.target.createTunnel(
                    new UUID(852, 1), new ManagedName("Tunnel"), new UUID(852, 2), new ManagedName("Channel"), -1);
            fixture.storage.save();

            NetworkSettingsService.DeletionEdit edit = fixture.settings.beginDeletion(owner, TARGET);
            helper.assertTrue(edit.administratorCount() == 1, "Wrong administrator count");
            helper.assertTrue(edit.tunnelCount() == 1, "Wrong tunnel count");
            helper.assertTrue(edit.channelCount() == 1, "Wrong channel count");
            fixture.settings.delete(owner, edit);
            var audit = fixture.repository.findOwner(OWNER).orElseThrow().auditEntries();
            helper.assertTrue(
                    audit.size() == 1
                            && audit.getFirst().action().getPath().equals("delete_network")
                            && audit.getFirst().target().equals(TARGET),
                    "Deleted-network audit must survive in the owner shard");
            IOUtilities.waitUntilIOWorkerComplete();
            fixture.storage.save();
            IOUtilities.waitUntilIOWorkerComplete();

            helper.assertTrue(fixture.repository.findLoadedNetwork(TARGET).isEmpty(), "Repository retained deletion");
            helper.assertTrue(fixture.directory.find(TARGET).isEmpty(), "Directory retained deletion");
            helper.assertTrue(
                    fixture.repository
                            .findOwner(OWNER)
                            .orElseThrow()
                            .defaultNetworkId()
                            .equals(Optional.of(FIRST)),
                    "Deleted default did not select earliest remaining network");
            helper.assertTrue(
                    fixture.directory.accessibleTo(ADMINISTRATOR).isEmpty(),
                    "Deleted network remained accessible to administrator");
            helper.assertTrue(fixture.directory.find(UNRELATED).isPresent(), "Unrelated network was removed");

            SavedNetworkRepository reloaded = new SavedNetworkRepository(fixture.newStorage(), fixture.path);
            List<NetworkMetadata> networks = reloaded.loadNetworks();
            helper.assertTrue(
                    networks.stream().noneMatch(network -> network.id().equals(TARGET)),
                    "Deleted network returned after queued save and reload");
            helper.assertTrue(
                    reloaded.findOwner(OWNER).orElseThrow().defaultNetworkId().equals(Optional.of(FIRST)),
                    "Fallback default did not persist");
            helper.succeed();
        }
    }

    /** Node records and any fixed bucket path fail closed before authority changes. */
    @GameTest(template = "bootstrap")
    public static void nodesAndStoragePathsBlockDeletion(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper)) {
            ServerPlayer owner = player(helper, OWNER);
            GlobalPos position = GlobalPos.of(Level.OVERWORLD, new BlockPos(2, 64, 2));
            fixture.target.createNode(
                    new UUID(853, 1),
                    new ManagedName("Closed node"),
                    position,
                    io.github.loongin.omniresonance.node.NodeForm.BLOCK,
                    Direction.NORTH);
            fixture.target.setNodeEnabled(new UUID(853, 1), 0, false).orElseThrow();
            rejected(
                    helper,
                    NetworkSettingsService.Reason.HAS_NODES,
                    () -> fixture.settings.beginDeletion(owner, TARGET));
            fixture.target.removeNode(new UUID(853, 1), position);

            Path bucket = fixture.path.resolve(ManagedSavedDataNames.networkBucket(TARGET, 37) + ".dat");
            Files.write(bucket, new byte[] {1});
            rejected(
                    helper,
                    NetworkSettingsService.Reason.STORAGE_UNVERIFIED,
                    () -> fixture.settings.beginDeletion(owner, TARGET));
            helper.assertTrue(
                    fixture.repository.findLoadedNetwork(TARGET).isPresent(), "Rejected delete detached data");
            helper.assertTrue(fixture.directory.find(TARGET).isPresent(), "Rejected delete changed directory");
            helper.succeed();
        }
    }

    /** Management/topology changes and exact 200 gt expiry invalidate old deletion intent without mutation. */
    @GameTest(template = "bootstrap")
    public static void staleAndExpiredDeletionEditsCannotCommit(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper)) {
            ServerPlayer owner = player(helper, OWNER);
            NetworkSettingsService.DeletionEdit stale = fixture.settings.beginDeletion(owner, TARGET);
            fixture.target.createTunnel(
                    new UUID(854, 1), new ManagedName("Changed"), new UUID(854, 2), new ManagedName("Channel"), -1);
            rejected(helper, NetworkSettingsService.Reason.STALE_REVISION, () -> fixture.settings.delete(owner, stale));

            NetworkSettingsService.DeletionEdit expired = fixture.settings.beginDeletion(owner, TARGET);
            for (int tick = 0; tick < 200; tick++) {
                fixture.settings.tick();
            }
            rejected(helper, NetworkSettingsService.Reason.LOCK_EXPIRED, () -> fixture.settings.delete(owner, expired));
            helper.assertTrue(fixture.repository.findLoadedNetwork(TARGET).isPresent(), "Expired edit deleted data");

            NetworkSettingsService.DeletionEdit revision = fixture.settings.beginDeletion(owner, TARGET);
            NetworkSavedData.PreparedAdministratorChange member = fixture.target.prepareAdministratorChange(
                    new UUID(854, 3), true, fixture.target.managementRevision(), -1);
            NetworkDirectory.PreparedMetadataReplacement index =
                    fixture.directory.prepareMetadataReplacement(member.previous(), member.next());
            fixture.target.commitAdministratorChange(member);
            fixture.directory.commitMetadataReplacement(index);
            rejected(
                    helper,
                    NetworkSettingsService.Reason.STALE_REVISION,
                    () -> fixture.settings.delete(owner, revision));
            helper.assertTrue(fixture.directory.find(TARGET).isPresent(), "Stale summary removed the network");
            helper.succeed();
        }
    }

    /** Recovery prevents final deletion even when added after the deletion summary was issued. */
    @GameTest(template = "bootstrap")
    public static void recoveryPreventsDeletionAndPreservesDefault(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper)) {
            ServerPlayer owner = player(helper, OWNER);
            OwnerSavedData ownerData = fixture.repository.createOwner(OWNER, TARGET);
            var edit = fixture.settings.beginDeletion(owner, TARGET);
            var key = new io.github.loongin.omniresonance.transfer.ResourceVariantKey(
                    net.minecraft.resources.ResourceLocation.parse("unknown:raw"), new byte[0]);
            try (var reservation =
                    fixture.target.recovery().reserve(key, 7, 64, 1048576).orElseThrow()) {
                reservation.commit(7);
            }
            rejected(
                    helper,
                    NetworkSettingsService.Reason.STORAGE_UNVERIFIED,
                    () -> fixture.settings.delete(owner, edit));
            helper.assertTrue(
                    ownerData.defaultNetworkId().equals(Optional.of(TARGET)), "Recovery rejection changed default");
            helper.assertTrue(
                    fixture.repository.findLoadedNetwork(TARGET).isPresent(), "Recovery rejection detached data");
            helper.assertTrue(fixture.directory.find(TARGET).isPresent(), "Recovery rejection changed directory");
            helper.assertTrue(fixture.target.recovery().amount(key) == 7, "Recovery disappeared");
            helper.succeed();
        }
    }

    /** A native callback cannot delete the original recovery owner while extraction reserves its known remainder. */
    @GameTest(template = "bootstrap")
    public static void activeRecoveryReservationPreventsNetworkDeletion(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper)) {
            ServerPlayer owner = player(helper, OWNER);
            var edit = fixture.settings.beginDeletion(owner, TARGET);
            var key = new io.github.loongin.omniresonance.transfer.ResourceVariantKey(
                    net.minecraft.resources.ResourceLocation.parse("unknown:reserved"), new byte[0]);
            try (var reservation =
                    fixture.target.recovery().reserve(key, 7, 64, 1048576).orElseThrow()) {
                helper.assertTrue(fixture.target.recovery().isEmpty(), "Reservation unexpectedly persisted resources");
                rejected(
                        helper,
                        NetworkSettingsService.Reason.STORAGE_UNVERIFIED,
                        () -> fixture.settings.delete(owner, edit));
                reservation.commit(7);
                helper.assertTrue(
                        fixture.repository
                                        .findLoadedNetwork(TARGET)
                                        .orElseThrow()
                                        .recovery()
                                        .amount(key)
                                == 7,
                        "Known remainder lost its original network");
            }
            helper.succeed();
        }
    }

    /** Non-default deletion preserves the pointer, then last owned deletion clears it without replacement creation. */
    @GameTest(template = "bootstrap")
    public static void nonDefaultAndLastOwnedDeletionUpdateOnlyNecessaryPointer(GameTestHelper helper)
            throws IOException {
        try (Fixture fixture = new Fixture(helper)) {
            ServerPlayer owner = player(helper, OWNER);
            OwnerSavedData ownerData = fixture.repository.createOwner(OWNER, FIRST);

            fixture.settings.delete(owner, fixture.settings.beginDeletion(owner, TARGET));
            helper.assertTrue(
                    ownerData.defaultNetworkId().equals(Optional.of(FIRST)),
                    "Deleting a non-default network changed the pointer");
            ownerData.setDirty(false);
            fixture.settings.delete(owner, fixture.settings.beginDeletion(owner, FIRST));

            helper.assertTrue(ownerData.defaultNetworkId().isEmpty(), "Deleting the last owned network kept a pointer");
            helper.assertTrue(ownerData.isDirty(), "Clearing the final default was not persisted");
            helper.assertTrue(fixture.directory.ownedCount(OWNER) == 0, "Owned network survived deletion");
            helper.assertTrue(fixture.directory.find(UNRELATED).isPresent(), "Other owner's network was affected");
            helper.succeed();
        }
    }

    /** A nonregular exact main shard path makes emptiness unverifiable and remains untouched. */
    @GameTest(template = "bootstrap")
    public static void nonregularMainPathBlocksDeletion(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper)) {
            Path main = fixture.path.resolve(ManagedSavedDataNames.network(TARGET) + ".dat");
            Files.createDirectory(main);

            rejected(
                    helper,
                    NetworkSettingsService.Reason.STORAGE_UNVERIFIED,
                    () -> fixture.settings.beginDeletion(player(helper, OWNER), TARGET));

            helper.assertTrue(Files.isDirectory(main), "Rejected deletion changed the nonregular path");
            helper.assertTrue(
                    fixture.repository.findLoadedNetwork(TARGET).isPresent(), "Rejected deletion detached data");
            helper.succeed();
        }
    }

    private static ServerPlayer player(GameTestHelper helper, UUID id) {
        return new FakePlayer(helper.getLevel(), new GameProfile(id, "Settings" + id.getLeastSignificantBits()));
    }

    private static ServerPlayer operator(GameTestHelper helper) {
        return new FakePlayer(helper.getLevel(), new GameProfile(new UUID(851, 5), "SettingsOperator")) {
            @Override
            public boolean hasPermissions(int level) {
                return true;
            }
        };
    }

    private static void rejected(GameTestHelper helper, NetworkSettingsService.Reason expected, Runnable action) {
        try {
            action.run();
        } catch (NetworkSettingsService.Rejected rejected) {
            helper.assertTrue(rejected.reason() == expected, "Expected " + expected + ", got " + rejected.reason());
            return;
        }
        helper.fail("Expected settings rejection " + expected);
    }

    private static void rejected(GameTestHelper helper, NetworkAdministrationService.Reason expected, Runnable action) {
        try {
            action.run();
        } catch (NetworkAdministrationService.Rejected rejected) {
            helper.assertTrue(rejected.reason() == expected, "Expected " + expected + ", got " + rejected.reason());
            return;
        }
        helper.fail("Expected administration rejection " + expected);
    }

    private static final class Profiles implements NetworkAdministrationService.PlayerDirectory {
        private final Map<UUID, NetworkAdministrationService.PlayerIdentity> entries = new HashMap<>();

        private Profiles() {
            add(OWNER, "Owner");
            add(ADMINISTRATOR, "Administrator");
        }

        private void add(UUID id, String name) {
            entries.put(id, new NetworkAdministrationService.PlayerIdentity(id, name));
        }

        @Override
        public Optional<NetworkAdministrationService.PlayerIdentity> online(UUID id) {
            return Optional.ofNullable(entries.get(id));
        }

        @Override
        public List<NetworkAdministrationService.PlayerIdentity> snapshotOnline(int maximum) {
            if (entries.size() > maximum) throw new IllegalArgumentException("Roster exceeds limit");
            return new ArrayList<>(entries.values());
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
        private final NetworkSavedData target;
        private final NetworkDirectory directory;
        private final EditLockTable locks = new EditLockTable();
        private final NetworkAdministrationService administration;
        private final NetworkSettingsService settings;

        private Fixture(GameTestHelper helper) throws IOException {
            path = Files.createTempDirectory("omniresonance-network-settings-test-");
            storage = NetworkSettingsGameTests.newStorage(helper, path);
            repository = new SavedNetworkRepository(storage, path);
            NetworkMetadata first = metadata(FIRST, OWNER, "First", 0, Set.of());
            NetworkMetadata selected = metadata(TARGET, OWNER, "Target", 1, Set.of(ADMINISTRATOR));
            NetworkMetadata unrelated = metadata(UNRELATED, OTHER_OWNER, "Unrelated", 0, Set.of());
            repository.createNetwork(first);
            repository.createNetwork(selected);
            repository.createNetwork(unrelated);
            target = repository.findLoadedNetwork(TARGET).orElseThrow();
            directory = new NetworkDirectory(List.of(first, selected, unrelated));
            Profiles profiles = new Profiles();
            administration = new NetworkAdministrationService(
                    helper.getLevel().getServer(),
                    repository,
                    directory,
                    locks,
                    profiles,
                    new ServerConfig.State(1, 1, true, ServerSettings.defaults()));
            settings =
                    new NetworkSettingsService(helper.getLevel().getServer(), repository, directory, locks, profiles);
        }

        private DimensionDataStorage newStorage() {
            return new DimensionDataStorage(
                    path.toFile(), DataFixers.getDataFixer(), net.minecraft.core.RegistryAccess.EMPTY);
        }

        @Override
        public void close() throws IOException {
            settings.close();
            administration.close();
            locks.clear();
            IOUtilities.waitUntilIOWorkerComplete();
            try (var files = Files.walk(path)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(file);
                }
            }
        }
    }

    private static DimensionDataStorage newStorage(GameTestHelper helper, Path path) {
        return new DimensionDataStorage(
                path.toFile(), DataFixers.getDataFixer(), helper.getLevel().registryAccess());
    }

    private static NetworkMetadata metadata(UUID id, UUID owner, String name, long order, Set<UUID> administrators) {
        return new NetworkMetadata(id, owner, new ManagedName(name), order, administrators);
    }
}
