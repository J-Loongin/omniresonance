// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.persistence;

import io.github.loongin.omniresonance.bootstrap.OmniResonanceMod;
import io.github.loongin.omniresonance.exchange.ExchangeConsent;
import io.github.loongin.omniresonance.exchange.ExchangeInvitation;
import io.github.loongin.omniresonance.exchange.ExchangeTerms;
import io.github.loongin.omniresonance.filter.FilterMode;
import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.network.NetworkMetadata;
import io.github.loongin.omniresonance.transfer.ResourceScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.common.IOUtilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Isolated native persistence evidence; never touches player worlds or existing exchange files. */
@GameTestHolder(OmniResonanceMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ExchangePersistenceGameTests {
    private ExchangePersistenceGameTests() {}

    @GameTest(template = "bootstrap")
    public static void pairedChannelTransfersOfflineAndUnpairingStopsEveryFutureTransfer(GameTestHelper helper)
            throws IOException {
        Path path = Files.createTempDirectory("omniresonance-paired-runtime-");
        try {
            var storage = storage(helper, path);
            var repository = new SavedNetworkRepository(storage, path);
            var a = new NetworkMetadata(new UUID(1601, 1), new UUID(1602, 1), new ManagedName("A"), 0, Set.of());
            var b = new NetworkMetadata(new UUID(1601, 2), new UUID(1602, 2), new ManagedName("B"), 0, Set.of());
            repository.createNetwork(a);
            repository.createNetwork(b);
            var directory = new io.github.loongin.omniresonance.network.NetworkDirectory(java.util.List.of(a, b));
            var data = ExchangeSavedData.create(new ExchangeSavedData.Limits(20, 20));
            UUID code = new UUID(1603, 1), pair = new UUID(1604, 1), channel = new UUID(1605, 1);
            data.issue(code, b.ownerId(), b, 0);
            data.proposePair(pair, code, a.ownerId(), a, b, 0, 1);
            data.approvePair(pair, b.ownerId(), b, a, 0, 2);
            storage.set(ExchangeSavedData.STORAGE_ID, data);
            helper.assertTrue(
                    repository.hasUnresolvedExchanges(a.id()),
                    "Empty approved tunnel did not prevent network deletion");
            var terms = new ExchangeTerms(
                    ResourceScope.all(),
                    FilterMode.WHITELIST,
                    io.github.loongin.omniresonance.exchange.fixtures.ExchangeFilters.allResources(),
                    64,
                    Map.of(),
                    1);
            data.createChannel(channel, pair, a.ownerId(), a, b, 1, new ManagedName("Energy"), true, terms);
            data.approve(channel, ExchangeConsent.Side.TARGET, b.ownerId(), a, b, 0, 3);
            var ledger = repository.domainStorage(a.id()).activate().orElseThrow();
            var key = io.github.loongin.omniresonance.transfer.EnergyVariant.INSTANCE.key();
            try (var deposit = ledger.reserveDeposit(key, 1000, -1).orElseThrow()) {
                deposit.commit(1000);
            }
            long[] tick = {4};
            try (var runtime = new io.github.loongin.omniresonance.exchange.ExchangeRuntime(
                    helper.getLevel().getServer(),
                    repository,
                    directory,
                    io.github.loongin.omniresonance.config.ServerSettings::defaults,
                    ignored -> null,
                    () -> tick[0])) {
                data.setDirty(false);
                var empty = runtime.diagnostics(a.id(), tick[0]);
                helper.assertTrue(
                        !empty.observed() && !data.isDirty() && repository.inspectDomain(b.id()) == null,
                        "Reading diagnostics activated or dirtied authority");
                for (int i = 0; i < 40 && ledger.amount(key) == 1000; i++) {
                    runtime.step(new io.github.loongin.omniresonance.transfer.TransferWorkBudget(
                            64, 1000000000, 1000000, () -> 0));
                    tick[0]++;
                }
                helper.assertTrue(
                        ledger.amount(key) == 936, "Paired offline channel failed to transfer exactly one rate window");
                helper.assertTrue(
                        repository
                                        .domainStorage(b.id())
                                        .activatedLedger()
                                        .orElseThrow()
                                        .amount(key)
                                == 64,
                        "Paired transfer did not conserve resources");
                var sent = runtime.diagnostics(a.id(), tick[0]);
                var received = runtime.diagnostics(b.id(), tick[0]);
                helper.assertTrue(
                        sent.sent() == 1 && sent.received() == 0 && received.sent() == 0 && received.received() == 1,
                        "Exchange diagnostics confused resource quantities or endpoint perspectives");
                data.closePair(pair, a.ownerId(), a, 1);
                for (int i = 0; i < 20; i++) {
                    runtime.step(new io.github.loongin.omniresonance.transfer.TransferWorkBudget(
                            64, 1000000000, 1000000, () -> 0));
                    tick[0]++;
                }
                helper.assertTrue(ledger.amount(key) == 936, "Unpaired channel kept transferring");
                helper.assertTrue(runtime.failure() == null, "Paired runtime failed");
            }
            helper.succeed();
        } finally {
            removeDirectory(path);
        }
    }

    @GameTest(template = "bootstrap")
    public static void approvedExchangeSurvivesNativeSaveAndReload(GameTestHelper helper) throws IOException {
        Path directory = Files.createTempDirectory("omniresonance-exchange-save-test-");
        try {
            ExchangeSavedData.Limits limits = new ExchangeSavedData.Limits(4, 4);
            UUID ownerA = new UUID(1, 1),
                    ownerB = new UUID(1, 2),
                    invitation = new UUID(2, 1),
                    agreement = new UUID(3, 1);
            NetworkMetadata source =
                    new NetworkMetadata(new UUID(4, 1), ownerA, new ManagedName("Source"), 0, Set.of());
            NetworkMetadata target =
                    new NetworkMetadata(new UUID(4, 2), ownerB, new ManagedName("Target"), 1, Set.of());
            ExchangeSavedData data = ExchangeSavedData.create(limits);
            data.issue(invitation, ownerB, target, 0);
            data.propose(
                    agreement,
                    invitation,
                    ownerA,
                    source,
                    target,
                    0,
                    1,
                    new ExchangeTerms(ResourceScope.all(), FilterMode.WHITELIST, null, Long.MAX_VALUE, Map.of(), 3));
            data.approve(agreement, ExchangeConsent.Side.TARGET, ownerB, source, target, 0, 2);
            CompoundTag expected =
                    data.save(new CompoundTag(), helper.getLevel().registryAccess());
            DimensionDataStorage storage = storage(helper, directory);
            storage.set(ExchangeSavedData.STORAGE_ID, data);
            storage.save();
            IOUtilities.waitUntilIOWorkerComplete();
            helper.assertTrue(
                    Files.exists(directory.resolve(ExchangeSavedData.STORAGE_ID + ".dat")),
                    "Native shard was not written");
            SavedData.Factory<ExchangeSavedData> factory = new SavedData.Factory<>(
                    () -> {
                        throw new IllegalStateException("Read must not recreate missing exchange authority");
                    },
                    (tag, registries) -> ExchangeSavedData.load(tag, limits));
            ExchangeSavedData loaded = storage(helper, directory).get(factory, ExchangeSavedData.STORAGE_ID);
            helper.assertTrue(loaded != null, "Exchange shard did not reload");
            helper.assertTrue(
                    expected.equals(
                            loaded.save(new CompoundTag(), helper.getLevel().registryAccess())),
                    "Native save changed authority or terms");
            helper.assertTrue(
                    loaded.invitation(invitation).orElseThrow().state() == ExchangeInvitation.State.CONSUMED,
                    "Consumption was lost");
            helper.assertTrue(
                    loaded.agreement(agreement).orElseThrow().consent().permitsExecution(source, target),
                    "Offline approved consent was lost");
            helper.assertTrue(
                    loaded.agreementsFor(source.id()).equals(Set.of(agreement))
                            && loaded.agreementsFor(target.id()).equals(Set.of(agreement)),
                    "Network indices did not rebuild");
            helper.succeed();
        } finally {
            IOUtilities.waitUntilIOWorkerComplete();
            try (var files = Files.walk(directory)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    @GameTest(template = "bootstrap")
    public static void repositoryNeverReplacesFailedAuthority(GameTestHelper helper) throws IOException {
        Path directory = Files.createTempDirectory("omniresonance-exchange-corrupt-test-");
        try {
            Path file = directory.resolve(ExchangeSavedData.STORAGE_ID + ".dat");
            byte[] broken = new byte[] {1, 2, 3, 4};
            Files.write(file, broken);
            DimensionDataStorage storage = storage(helper, directory);
            SavedExchangeRepository repository =
                    new SavedExchangeRepository(storage, directory, new ExchangeSavedData.Limits(4, 4));
            expectFailure(repository::find);
            expectFailure(repository::create);
            storage.save();
            IOUtilities.waitUntilIOWorkerComplete();
            helper.assertTrue(
                    java.util.Arrays.equals(broken, Files.readAllBytes(file)), "Failed authority was overwritten");
            Files.delete(file);
            expectFailure(repository::create);
            storage.save();
            IOUtilities.waitUntilIOWorkerComplete();
            helper.assertTrue(!Files.exists(file), "Failed authority was recreated after removal");
            helper.succeed();
        } finally {
            removeDirectory(directory);
        }
    }

    @GameTest(template = "bootstrap")
    public static void missingRepositoryReadDoesNotCreateAndCachedAuthorityIsShared(GameTestHelper helper)
            throws IOException {
        Path directory = Files.createTempDirectory("omniresonance-exchange-repository-test-");
        try {
            DimensionDataStorage storage = storage(helper, directory);
            ExchangeSavedData.Limits limits = new ExchangeSavedData.Limits(4, 4);
            SavedExchangeRepository first = new SavedExchangeRepository(storage, directory, limits);
            helper.assertTrue(first.find().isEmpty(), "Missing query invented exchange data");
            storage.save();
            IOUtilities.waitUntilIOWorkerComplete();
            helper.assertTrue(
                    !Files.exists(directory.resolve(ExchangeSavedData.STORAGE_ID + ".dat")), "Read created a shard");
            ExchangeSavedData created = first.create();
            SavedExchangeRepository second = new SavedExchangeRepository(storage, directory, limits);
            helper.assertTrue(second.find().orElseThrow() == created, "Repository duplicated cached authority");
            expectFailure(second::create);
            storage.save();
            IOUtilities.waitUntilIOWorkerComplete();
            helper.assertTrue(
                    new SavedExchangeRepository(storage(helper, directory), directory, limits)
                            .find()
                            .isPresent(),
                    "Repository failed to reload normal save");
            helper.succeed();
        } finally {
            removeDirectory(directory);
        }
    }

    @GameTest(template = "bootstrap")
    public static void domainTransferSurvivesCompletedNativeSave(GameTestHelper helper) throws IOException {
        Path directory = Files.createTempDirectory("omniresonance-exchange-transfer-test-");
        try {
            DimensionDataStorage storage = storage(helper, directory);
            SavedNetworkRepository repository = new SavedNetworkRepository(storage, directory);
            UUID owner = new UUID(701, 1), sourceId = new UUID(702, 1), targetId = new UUID(702, 2);
            repository.createNetwork(new NetworkMetadata(sourceId, owner, new ManagedName("Source"), 0, Set.of()));
            repository.createNetwork(new NetworkMetadata(targetId, owner, new ManagedName("Target"), 1, Set.of()));
            var source = repository.domainStorage(sourceId).activate().orElseThrow();
            var target = repository.domainStorage(targetId).activate().orElseThrow();
            var key = new io.github.loongin.omniresonance.transfer.ResourceVariantKey(
                    net.minecraft.resources.ResourceLocation.parse("example:resource"), new byte[] {4, 5, 6});
            try (var deposit = source.reserveDeposit(key, Long.MAX_VALUE, -1).orElseThrow()) {
                deposit.commit(Long.MAX_VALUE);
            }
            long moved = Long.MAX_VALUE - 1;
            helper.assertTrue(source.transferTo(target, key, moved, -1) == moved, "Wrong transfer quantity");
            storage.save();
            IOUtilities.waitUntilIOWorkerComplete();
            SavedNetworkRepository reloaded = new SavedNetworkRepository(storage(helper, directory), directory);
            reloaded.loadNetworks();
            helper.assertTrue(
                    reloaded.domainStorage(sourceId).activate().orElseThrow().amount(key) == 1,
                    "Source debit did not persist");
            helper.assertTrue(
                    reloaded.domainStorage(targetId).activate().orElseThrow().amount(key) == moved,
                    "Target credit did not persist");
            helper.succeed();
        } finally {
            removeDirectory(directory);
        }
    }

    @GameTest(template = "bootstrap")
    public static void serverRuntimeTransfersApprovedRulesAndMaintainsExpiredApplications(GameTestHelper helper)
            throws IOException {
        Path directory = Files.createTempDirectory("omniresonance-exchange-runtime-test-");
        try {
            var storage = storage(helper, directory);
            var repository = new SavedNetworkRepository(storage, directory);
            UUID ownerA = new UUID(800, 1),
                    ownerB = new UUID(800, 2),
                    sourceId = new UUID(801, 1),
                    targetId = new UUID(801, 2);
            var source = new NetworkMetadata(sourceId, ownerA, new ManagedName("Source"), 0, Set.of());
            var target = new NetworkMetadata(targetId, ownerB, new ManagedName("Target"), 1, Set.of());
            repository.createNetwork(source);
            repository.createNetwork(target);
            var networks =
                    new io.github.loongin.omniresonance.network.NetworkDirectory(java.util.List.of(source, target));
            long[] tick = {12000};
            var data = ExchangeSavedData.create(new ExchangeSavedData.Limits(8, 8));
            UUID invitation = new UUID(802, 1),
                    agreement = new UUID(803, 1),
                    expired = new UUID(803, 2),
                    oldCode = new UUID(802, 2);
            var terms = new ExchangeTerms(
                    ResourceScope.all(),
                    FilterMode.WHITELIST,
                    io.github.loongin.omniresonance.exchange.fixtures.ExchangeFilters.allResources(),
                    64,
                    Map.of(),
                    1);
            data.issue(invitation, ownerB, target, 0);
            data.propose(
                    agreement,
                    invitation,
                    ownerA,
                    source,
                    target,
                    0,
                    1,
                    new ExchangeTerms(ResourceScope.all(), FilterMode.WHITELIST, null, 64, Map.of(), 1));
            data.approve(agreement, ExchangeConsent.Side.TARGET, ownerB, source, target, 0, 2);
            data.issue(oldCode, ownerB, target, 0);
            data.propose(expired, oldCode, ownerA, source, target, 0, 1, terms);
            storage.set(ExchangeSavedData.STORAGE_ID, data);
            var key = io.github.loongin.omniresonance.transfer.EnergyVariant.INSTANCE.key();
            var ledger = repository.domainStorage(sourceId).activate().orElseThrow();
            try (var d = ledger.reserveDeposit(key, 1000, -1).orElseThrow()) {
                d.commit(1000);
            }
            try (var runtime = new io.github.loongin.omniresonance.exchange.ExchangeRuntime(
                    helper.getLevel().getServer(),
                    repository,
                    networks,
                    io.github.loongin.omniresonance.config.ServerSettings::defaults,
                    ignored -> null,
                    () -> tick[0])) {
                long[] cpu = {0};
                var exhausted = new io.github.loongin.omniresonance.transfer.TransferWorkBudget(1, 1, 1, () -> cpu[0]);
                cpu[0] = 2;
                runtime.step(exhausted);
                helper.assertTrue(
                        repository.inspectDomain(targetId) == null,
                        "Exhausted shared CPU budget still activated storage");
                helper.assertTrue(
                        !data.agreement(expired).orElseThrow().consent().revoked(),
                        "Exhausted budget still ran maintenance");
                for (int i = 0; i < 20; i++) {
                    runtime.step(new io.github.loongin.omniresonance.transfer.TransferWorkBudget(
                            64, 1000000000, 1000000, () -> 0));
                    tick[0]++;
                }
                helper.assertTrue(
                        ledger.amount(key) == 1000 && repository.inspectDomain(targetId) == null,
                        "Approved legacy rule without a preset accessed target storage");
                helper.assertTrue(
                        runtime.terminalController()
                                        .filterStatus(data.agreement(agreement).orElseThrow())
                                == io.github.loongin.omniresonance.exchange.ExchangeFilterStatus.MISSING,
                        "Missing preset reason was lost");
                data.revise(agreement, ExchangeConsent.Side.SOURCE, ownerA, source, target, 1, terms);
                helper.assertTrue(
                        !data.agreement(agreement).orElseThrow().consent().approvedByBoth(),
                        "Adding a preset retained old approvals");
                data.approve(agreement, ExchangeConsent.Side.TARGET, ownerB, source, target, 2, tick[0]);
                for (int i = 0; i < 20; i++) {
                    runtime.step(new io.github.loongin.omniresonance.transfer.TransferWorkBudget(
                            64, 1000000000, 1000000, () -> 0));
                    if (ledger.amount(key) < 1000) break;
                    tick[0]++;
                }
                helper.assertTrue(runtime.failure() == null, "Runtime unexpectedly failed");
                helper.assertTrue(
                        repository
                                        .domainStorage(targetId)
                                        .activatedLedger()
                                        .orElseThrow()
                                        .amount(key)
                                == 64,
                        "Approved offline rule did not transfer");
                helper.assertTrue(ledger.amount(key) == 936, "Source amount is not conserved");
                helper.assertTrue(
                        data.agreement(expired).orElseThrow().consent().revoked(),
                        "Expired pending rule was not archived");
                runtime.tagsChanged();
                tick[0]++;
                runtime.step(new io.github.loongin.omniresonance.transfer.TransferWorkBudget(
                        64, 1000000000, 1000000, () -> 0));
                helper.assertTrue(runtime.failure() == null, "Tag invalidation failed");
            }
            helper.succeed();
        } finally {
            removeDirectory(directory);
        }
    }

    @GameTest(template = "bootstrap")
    public static void runtimeMissingDataStaysAbsentAndCorruptionDoesNotCreateAuthority(GameTestHelper helper)
            throws IOException {
        Path directory = Files.createTempDirectory("omniresonance-exchange-runtime-empty-");
        try {
            var storage = storage(helper, directory);
            var repository = new SavedNetworkRepository(storage, directory);
            var networks = new io.github.loongin.omniresonance.network.NetworkDirectory(java.util.List.of());
            try (var runtime = new io.github.loongin.omniresonance.exchange.ExchangeRuntime(
                    helper.getLevel().getServer(),
                    repository,
                    networks,
                    io.github.loongin.omniresonance.config.ServerSettings::defaults,
                    ignored -> null)) {
                runtime.step(new io.github.loongin.omniresonance.transfer.TransferWorkBudget(1, 1000, 100, () -> 0));
                storage.save();
                IOUtilities.waitUntilIOWorkerComplete();
                helper.assertTrue(
                        !Files.exists(directory.resolve(ExchangeSavedData.STORAGE_ID + ".dat")),
                        "Idle runtime created a shard");
            }
            Path file = directory.resolve(ExchangeSavedData.STORAGE_ID + ".dat");
            byte[] broken = {1, 2, 3};
            Files.write(file, broken);
            var freshStorage = storage(helper, directory);
            var fresh = new SavedNetworkRepository(freshStorage, directory);
            try (var runtime = new io.github.loongin.omniresonance.exchange.ExchangeRuntime(
                    helper.getLevel().getServer(),
                    fresh,
                    networks,
                    io.github.loongin.omniresonance.config.ServerSettings::defaults,
                    ignored -> null)) {
                helper.assertTrue(runtime.failure() != null, "Unreadable exchange was treated as missing");
                runtime.step(new io.github.loongin.omniresonance.transfer.TransferWorkBudget(1, 1000, 100, () -> 0));
                freshStorage.save();
                IOUtilities.waitUntilIOWorkerComplete();
                helper.assertTrue(
                        java.util.Arrays.equals(broken, Files.readAllBytes(file)),
                        "Isolated runtime rewrote corrupted data");
            }
            helper.succeed();
        } finally {
            removeDirectory(directory);
        }
    }

    @GameTest(template = "bootstrap")
    public static void draftsUseOwnedPresetsAndRetainOnlyAuthoritativeSnapshots(GameTestHelper helper)
            throws IOException {
        Path directory = Files.createTempDirectory("omniresonance-exchange-draft-test-");
        try {
            var repository = new SavedNetworkRepository(storage(helper, directory), directory);
            UUID a = new UUID(901, 1),
                    b = new UUID(901, 2),
                    presetId = new UUID(902, 1),
                    invitation = new UUID(903, 1),
                    agreementId = new UUID(904, 1);
            var source = new NetworkMetadata(new UUID(905, 1), a, new ManagedName("Source"), 0, Set.of(b));
            var target = new NetworkMetadata(new UUID(905, 2), b, new ManagedName("Target"), 1, Set.of());
            repository.createNetwork(source);
            repository.createNetwork(target);
            var networks =
                    new io.github.loongin.omniresonance.network.NetworkDirectory(java.util.List.of(source, target));
            var owner = repository.createOwner(a, source.id());
            repository.createOwner(b, target.id());
            var preset = new io.github.loongin.omniresonance.filter.ResourceFilterPreset(
                    presetId,
                    new ManagedName("Energy"),
                    0,
                    java.util.List.of(new io.github.loongin.omniresonance.filter.ResourceFilterRule.Match(
                            new UUID(902, 2),
                            io.github.loongin.omniresonance.transfer.ResourceTypes.ENERGY,
                            io.github.loongin.omniresonance.filter.ResourceFilterRule.Selector.wholeType(),
                            io.github.loongin.omniresonance.filter.ComponentCondition.idOnly())));
            owner.putPreset(preset, 0, 8, 8);
            var resolver = new io.github.loongin.omniresonance.exchange.ExchangeDraftResolver(repository, networks);
            var selected = new io.github.loongin.omniresonance.exchange.ExchangeTermsDraft(
                    ResourceScope.all(),
                    FilterMode.WHITELIST,
                    new io.github.loongin.omniresonance.exchange.ExchangeTermsDraft.OwnerPreset(presetId, 1),
                    64,
                    Map.of(),
                    1);
            var terms = resolver.resolve(a, source.id(), selected, null, -1);
            var data = repository.exchangeRepository().create();
            data.issue(invitation, b, target, 0);
            data.propose(agreementId, invitation, a, source, target, 0, 1, terms);
            data.approve(agreementId, ExchangeConsent.Side.TARGET, b, source, target, 0, 2);
            owner.putPreset(
                    new io.github.loongin.omniresonance.filter.ResourceFilterPreset(
                            presetId, preset.name(), 1, java.util.List.of()),
                    1,
                    8,
                    8);
            expectFailure(() -> resolver.resolve(a, source.id(), selected, null, -1));
            var keep = new io.github.loongin.omniresonance.exchange.ExchangeTermsDraft(
                    ResourceScope.all(),
                    FilterMode.WHITELIST,
                    new io.github.loongin.omniresonance.exchange.ExchangeTermsDraft.KeepApproved(),
                    64,
                    Map.of(),
                    1);
            owner.setDirty(false);
            data.setDirty(false);
            var retained = resolver.resolve(b, target.id(), keep, agreementId, 1);
            helper.assertTrue(retained.filter() == terms.filter(), "Receiver failed to retain the approved snapshot");
            helper.assertTrue(
                    retained.filter().presets().get(presetId).rules().size() == 1,
                    "Owner library edits changed approved terms");
            expectFailure(() -> resolver.resolve(a, source.id(), keep, null, -1));
            boolean denied = false;
            try {
                resolver.resolve(b, source.id(), selected, null, -1);
            } catch (SecurityException expected) {
                denied = true;
            }
            helper.assertTrue(denied, "Administrator authored exchange terms");
            helper.assertTrue(!owner.isDirty() && !data.isDirty(), "Draft parsing mutated authority");
            helper.succeed();
        } finally {
            removeDirectory(directory);
        }
    }

    @GameTest(template = "bootstrap")
    public static void terminalOperationsAuthorizeCreateSubmitApproveAndAudit(GameTestHelper helper)
            throws IOException {
        Path directory = Files.createTempDirectory("omniresonance-exchange-terminal-test-");
        try {
            var repository = new SavedNetworkRepository(storage(helper, directory), directory);
            UUID a = new UUID(950, 1), b = new UUID(950, 2);
            var source = new NetworkMetadata(new UUID(951, 1), a, new ManagedName("Source"), 0, Set.of(b));
            var target = new NetworkMetadata(new UUID(951, 2), b, new ManagedName("Target"), 1, Set.of());
            repository.createNetwork(source);
            repository.createNetwork(target);
            var networks =
                    new io.github.loongin.omniresonance.network.NetworkDirectory(java.util.List.of(source, target));
            var ownerA = new net.neoforged.neoforge.common.util.FakePlayer(
                    helper.getLevel(), new com.mojang.authlib.GameProfile(a, "Sender"));
            var ownerB = new net.neoforged.neoforge.common.util.FakePlayer(
                    helper.getLevel(), new com.mojang.authlib.GameProfile(b, "Receiver"));
            var attached = new java.util.concurrent.atomic.AtomicInteger();
            var controller = new io.github.loongin.omniresonance.exchange.ExchangeTerminalController(
                    helper.getLevel().getServer(),
                    repository,
                    networks,
                    io.github.loongin.omniresonance.config.ServerSettings::defaults,
                    attached::incrementAndGet);
            var empty = (io.github.loongin.omniresonance.exchange.ExchangeTerminalController.Rules) controller.execute(
                    ownerA,
                    source.id(),
                    new io.github.loongin.omniresonance.networking.ExchangeIntent.ListRules(false));
            helper.assertTrue(
                    empty.entries().isEmpty()
                            && repository.exchangeRepository().find().isEmpty(),
                    "Read created exchange authority");
            boolean denied = false;
            try {
                controller.execute(
                        ownerB, source.id(), new io.github.loongin.omniresonance.networking.ExchangeIntent.IssueCode());
            } catch (SecurityException expected) {
                denied = true;
            }
            helper.assertTrue(
                    denied && repository.exchangeRepository().find().isEmpty(),
                    "Administrator created receiving authority");
            var code = (io.github.loongin.omniresonance.exchange.ExchangeTerminalController.Code) controller.execute(
                    ownerB, target.id(), new io.github.loongin.omniresonance.networking.ExchangeIntent.IssueCode());
            helper.assertTrue(attached.get() > 0, "New authority did not notify runtime");
            var draft = new io.github.loongin.omniresonance.exchange.ExchangeTermsDraft(
                    ResourceScope.all(),
                    FilterMode.WHITELIST,
                    new io.github.loongin.omniresonance.exchange.ExchangeTermsDraft.None(),
                    64,
                    Map.of(),
                    1);
            var proposed =
                    (io.github.loongin.omniresonance.exchange.ExchangeTerminalController.Rule) controller.execute(
                            ownerA,
                            source.id(),
                            new io.github.loongin.omniresonance.networking.ExchangeIntent.Propose(code.value(), draft));
            var approved =
                    (io.github.loongin.omniresonance.exchange.ExchangeTerminalController.Rule) controller.execute(
                            ownerB,
                            target.id(),
                            new io.github.loongin.omniresonance.networking.ExchangeIntent.Change(
                                    proposed.agreement().id(),
                                    0,
                                    io.github.loongin.omniresonance.networking.ExchangeIntent.Action.APPROVE));
            helper.assertTrue(
                    approved.agreement().consent().permitsExecution(source, target), "Owner approval did not commit");
            var projected = controller.projectRule(
                    ownerA, source.id(), proposed.agreement().id());
            helper.assertTrue(
                    projected.state() == io.github.loongin.omniresonance.networking.ExchangeRuleView.State.APPROVED,
                    "Authorized projection did not reflect approval");
            helper.assertTrue(
                    projected.source().network().equals(source.id())
                            && projected.target().network().equals(target.id()),
                    "Projection changed exchange direction");
            controller.execute(
                    ownerB,
                    source.id(),
                    new io.github.loongin.omniresonance.networking.ExchangeIntent.Change(
                            proposed.agreement().id(),
                            1,
                            io.github.loongin.omniresonance.networking.ExchangeIntent.Action.PAUSE));
            denied = false;
            try {
                controller.execute(
                        ownerB,
                        source.id(),
                        new io.github.loongin.omniresonance.networking.ExchangeIntent.Change(
                                proposed.agreement().id(),
                                2,
                                io.github.loongin.omniresonance.networking.ExchangeIntent.Action.RESUME));
            } catch (SecurityException expected) {
                denied = true;
            }
            helper.assertTrue(denied, "Administrator resumed exchange");
            var audit = repository.findLoadedNetwork(target.id()).orElseThrow().auditEntries();
            helper.assertTrue(
                    audit.size() == 2 && audit.getFirst().target().equals(target.id()),
                    "Receiving audit is missing or exposes the code identity");
            controller.close();
            expectFailure(() -> controller.execute(
                    ownerA,
                    source.id(),
                    new io.github.loongin.omniresonance.networking.ExchangeIntent.ListRules(false)));
            helper.succeed();
        } finally {
            removeDirectory(directory);
        }
    }

    @GameTest(template = "bootstrap")
    public static void firstTerminalCreationImmediatelyAttachesToRuntime(GameTestHelper helper) throws IOException {
        Path directory = Files.createTempDirectory("omniresonance-exchange-first-code-test-");
        try {
            var repository = new SavedNetworkRepository(storage(helper, directory), directory);
            UUID owner = new UUID(960, 1), sourceId = new UUID(961, 1), targetId = new UUID(961, 2);
            var source = new NetworkMetadata(sourceId, owner, new ManagedName("Source"), 0, Set.of());
            var target = new NetworkMetadata(targetId, owner, new ManagedName("Target"), 1, Set.of());
            repository.createNetwork(source);
            repository.createNetwork(target);
            var networks =
                    new io.github.loongin.omniresonance.network.NetworkDirectory(java.util.List.of(source, target));
            var player = new net.neoforged.neoforge.common.util.FakePlayer(
                    helper.getLevel(), new com.mojang.authlib.GameProfile(owner, "Owner"));
            long future = helper.getLevel().getServer().overworld().getGameTime() + 12000;
            try (var runtime = new io.github.loongin.omniresonance.exchange.ExchangeRuntime(
                    helper.getLevel().getServer(),
                    repository,
                    networks,
                    io.github.loongin.omniresonance.config.ServerSettings::defaults,
                    ignored -> null,
                    () -> future)) {
                var controller = runtime.terminalController();
                var code =
                        (io.github.loongin.omniresonance.exchange.ExchangeTerminalController.Code) controller.execute(
                                player,
                                targetId,
                                new io.github.loongin.omniresonance.networking.ExchangeIntent.IssueCode());
                var draft = new io.github.loongin.omniresonance.exchange.ExchangeTermsDraft(
                        ResourceScope.all(),
                        FilterMode.WHITELIST,
                        new io.github.loongin.omniresonance.exchange.ExchangeTermsDraft.None(),
                        64,
                        Map.of(),
                        1);
                var pending =
                        (io.github.loongin.omniresonance.exchange.ExchangeTerminalController.Rule) controller.execute(
                                player,
                                sourceId,
                                new io.github.loongin.omniresonance.networking.ExchangeIntent.Propose(
                                        code.value(), draft));
                runtime.step(new io.github.loongin.omniresonance.transfer.TransferWorkBudget(
                        1, 1000000000, 1000000, () -> 0));
                helper.assertTrue(runtime.failure() == null, "First authority attachment failed");
                helper.assertTrue(
                        repository
                                .exchangeRepository()
                                .find()
                                .orElseThrow()
                                .agreement(pending.agreement().id())
                                .orElseThrow()
                                .consent()
                                .revoked(),
                        "First-created data was not processed by runtime");
            }
            helper.succeed();
        } finally {
            removeDirectory(directory);
        }
    }

    private static void expectFailure(Runnable action) {
        try {
            action.run();
        } catch (IllegalStateException expected) {
            return;
        }
        throw new IllegalStateException("Expected failed exchange operation");
    }

    private static void removeDirectory(Path directory) throws IOException {
        IOUtilities.waitUntilIOWorkerComplete();
        try (var files = Files.walk(directory)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    private static DimensionDataStorage storage(GameTestHelper helper, Path directory) {
        return new DimensionDataStorage(
                directory.toFile(), DataFixers.getDataFixer(), helper.getLevel().registryAccess());
    }
}
