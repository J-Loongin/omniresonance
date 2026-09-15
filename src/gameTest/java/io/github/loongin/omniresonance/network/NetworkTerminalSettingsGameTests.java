// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.network;

import static io.github.loongin.omniresonance.filter.fixtures.FullFilterAssertions.itemIds;

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

    @GameTest(template = "bootstrap")
    public static void terminalTagDraftSavesAppendIndependentRulesAndCanBeCancelled(GameTestHelper helper)
            throws IOException {
        try (Fixture f = new Fixture(helper)) {
            ServerPlayer owner = player(helper, OWNER);
            var library = f.repository.createOwner(OWNER, null);
            UUID preset = new UUID(990, 1);
            library.putPreset(
                    new io.github.loongin.omniresonance.filter.ItemFilterPreset(
                            preset, new ManagedName("Tags"), 0, Set.of()),
                    0,
                    -1,
                    -1);
            f.open(owner, OWNER_VIEW, OWNER_SESSION);
            f.terminal.handle(owner, new NetworkTerminalRequest.OpenNetwork(OWNER_VIEW, OWNER_SESSION, 1, NETWORK));
            f.terminal.handle(owner, new NetworkTerminalRequest.OpenFilters(OWNER_VIEW, OWNER_SESSION, 2));
            f.terminal.handle(owner, new NetworkTerminalRequest.OpenPreset(OWNER_VIEW, OWNER_SESSION, 3, preset, 0, 0));
            long sequence = 4;
            for (String tag : List.of("c:ingots/iron", "c:ingots/gold")) {
                var begin = f.terminal.handle(
                        owner,
                        new NetworkTerminalRequest.BeginResourceRule(
                                OWNER_VIEW, OWNER_SESSION, sequence++, preset, null, false));
                helper.assertTrue(begin instanceof NetworkTerminalResponse.ViewState, "Tag draft admission failed");
                var intent = new io.github.loongin.omniresonance.filter.ResourceRuleIntent.Match(
                        io.github.loongin.omniresonance.transfer.ResourceTypes.ITEM,
                        io.github.loongin.omniresonance.filter.ResourceFilterRule.Selector.tag(
                                net.minecraft.resources.ResourceLocation.parse(tag)),
                        io.github.loongin.omniresonance.filter.ComponentCondition.Mode.ID_ONLY,
                        Set.of(),
                        null);
                var saved = f.terminal.handle(
                        owner,
                        new NetworkTerminalRequest.SaveResourceRule(OWNER_VIEW, OWNER_SESSION, sequence++, intent));
                helper.assertTrue(saved instanceof NetworkTerminalResponse.ViewState, "Tag rule save failed");
            }
            var saved = library.findPreset(preset).orElseThrow();
            helper.assertTrue(
                    saved.rules().size() == 2
                            && !saved.rules()
                                    .get(0)
                                    .id()
                                    .equals(saved.rules().get(1).id()),
                    "Pasted tag overwrote a prior rule instead of appending its own OR branch");
            f.terminal.handle(
                    owner,
                    new NetworkTerminalRequest.BeginResourceRule(
                            OWNER_VIEW, OWNER_SESSION, sequence++, preset, null, false));
            f.terminal.handle(owner, new NetworkTerminalRequest.Back(OWNER_VIEW, OWNER_SESSION, sequence));
            helper.assertTrue(
                    library.findPreset(preset).orElseThrow().equals(saved),
                    "Cancelling a tag draft changed saved rules");
            helper.succeed();
        }
    }

    @GameTest(template = "bootstrap")
    public static void storageRouteRequiresFinishedSnapshotAndCancelsWhenLeavingThePage(GameTestHelper helper)
            throws IOException {
        try (Fixture f = new Fixture(helper)) {
            ServerPlayer owner = player(helper, OWNER);
            var base = ServerSettings.defaults();
            f.terminal.applyConfiguration(new ServerConfig.State(
                    1,
                    2,
                    true,
                    new ServerSettings(
                            base.networksPerOwner(),
                            base.tunnelsPerNetwork(),
                            base.channelsPerTunnel(),
                            base.channelBindingsPerDirectNode(),
                            base.administratorsPerNetwork(),
                            base.scheduler(),
                            base.filterLimits(),
                            base.recoveryLimits(),
                            base.storageVariantLimitPerNetwork(),
                            base.terminalSync(),
                            ServerSettings.DirectStorageAccess.READ_WRITE)));
            var replies = new ArrayList<io.github.loongin.omniresonance.networking.TerminalStorageResponse>();
            f.terminal.installInventory(
                    id -> f.repository.domainStorage(id).activate().orElseThrow(), (p, frame) -> {});
            f.terminal.installStorageAccess(
                    id -> f.repository.domainStorage(id).activate().orElseThrow(),
                    id -> f.repository.findLoadedNetwork(id).orElseThrow().recovery(),
                    (p, response) -> replies.add(response));
            f.open(owner, OWNER_VIEW, OWNER_SESSION);
            f.terminal.handle(owner, new NetworkTerminalRequest.OpenNetwork(OWNER_VIEW, OWNER_SESSION, 1, NETWORK));
            f.terminal.inventory(
                    owner,
                    new io.github.loongin.omniresonance.networking.DomainInventoryRequest(
                            OWNER_VIEW, OWNER_SESSION, 1, true));
            owner.inventoryMenu.setCarried(
                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_INGOT, 12));
            f.terminal.storage(
                    owner,
                    new io.github.loongin.omniresonance.networking.TerminalStorageRequest(
                            OWNER_VIEW, OWNER_SESSION, 1, 1, 0, owner.inventoryMenu.getStateId(), -1, 0, false));
            helper.assertTrue(
                    replies.getLast().status()
                            == io.github.loongin.omniresonance.networking.TerminalStorageResponse.Status.DENIED,
                    "Storage route admitted before full sync End");
            f.terminal.inventoryStep(
                    new io.github.loongin.omniresonance.transfer.TransferWorkBudget(100, 1000000, 1, () -> 0));
            f.terminal.storage(
                    owner,
                    new io.github.loongin.omniresonance.networking.TerminalStorageRequest(
                            OWNER_VIEW, OWNER_SESSION, 1, 2, 0, owner.inventoryMenu.getStateId(), -1, 0, false));
            f.terminal.handle(owner, new NetworkTerminalRequest.Back(OWNER_VIEW, OWNER_SESSION, 2));
            f.terminal.inventoryStep(
                    new io.github.loongin.omniresonance.transfer.TransferWorkBudget(100, 1000000, 1, () -> 0));
            var ledger = f.repository.domainStorage(NETWORK).activate().orElseThrow();
            helper.assertTrue(
                    ledger.variantCount() == 0
                            && owner.inventoryMenu.getCarried().getCount() == 12,
                    "Leaving the inventory page did not cancel pending storage work");
            f.terminal.handle(owner, new NetworkTerminalRequest.OpenNetwork(OWNER_VIEW, OWNER_SESSION, 3, NETWORK));
            f.terminal.inventory(
                    owner,
                    new io.github.loongin.omniresonance.networking.DomainInventoryRequest(
                            OWNER_VIEW, OWNER_SESSION, 2, true));
            f.terminal.inventoryStep(
                    new io.github.loongin.omniresonance.transfer.TransferWorkBudget(100, 1000000, 1, () -> 0));
            f.terminal.storage(
                    owner,
                    new io.github.loongin.omniresonance.networking.TerminalStorageRequest(
                            OWNER_VIEW, OWNER_SESSION, 2, 1, 0, owner.inventoryMenu.getStateId(), -1, 0, false));
            f.terminal.inventoryStep(
                    new io.github.loongin.omniresonance.transfer.TransferWorkBudget(100, 1000000, 1, () -> 0));
            helper.assertTrue(
                    replies.getLast().moved() == 12
                            && owner.inventoryMenu.getCarried().isEmpty(),
                    "Authenticated current snapshot did not execute real storage work");
            helper.succeed();
        }
    }

    @GameTest(template = "bootstrap")
    public static void inventoryRevocationStopsQueuedAbsoluteDeltasBeforeAnyFurtherData(GameTestHelper helper)
            throws IOException {
        try (Fixture fixture = new Fixture(helper)) {
            ServerPlayer owner = player(helper, OWNER), admin = player(helper, ADMINISTRATOR);
            fixture.open(owner, OWNER_VIEW, OWNER_SESSION);
            fixture.open(admin, ADMIN_VIEW, ADMIN_SESSION);
            fixture.terminal.handle(
                    admin, new NetworkTerminalRequest.OpenNetwork(ADMIN_VIEW, ADMIN_SESSION, 1, NETWORK));
            var frames = new ArrayList<io.github.loongin.omniresonance.networking.DomainInventoryFrame>();
            fixture.terminal.installInventory(
                    id -> fixture.repository.domainStorage(id).activate().orElseThrow(),
                    (player, frame) -> frames.add(frame));
            var buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            try {
                io.github.loongin.omniresonance.networking.DomainInventoryRequest.STREAM_CODEC.encode(
                        buffer,
                        new io.github.loongin.omniresonance.networking.DomainInventoryRequest(
                                ADMIN_VIEW, ADMIN_SESSION, 1, true));
                fixture.terminal.inventory(
                        admin,
                        io.github.loongin.omniresonance.networking.DomainInventoryRequest.STREAM_CODEC.decode(buffer));
            } finally {
                buffer.release();
            }
            fixture.terminal.inventoryStep(
                    new io.github.loongin.omniresonance.transfer.TransferWorkBudget(1, 1000000, 1, () -> 0));
            helper.assertTrue(
                    frames.getLast() instanceof io.github.loongin.omniresonance.networking.DomainInventoryFrame.End,
                    "Authorized empty full sync did not complete");
            frames.clear();
            var ledger = fixture.repository.domainStorage(NETWORK).activate().orElseThrow();
            var key = new io.github.loongin.omniresonance.transfer.ResourceVariantKey(
                    net.minecraft.resources.ResourceLocation.parse("example:opaque"), new byte[] {3});
            try (var put = ledger.reserveDeposit(key, 7, -1).orElseThrow()) {
                put.commit(7);
            }
            var data = fixture.repository.findLoadedNetwork(NETWORK).orElseThrow();
            var change = data.prepareAdministratorChange(ADMINISTRATOR, false, data.managementRevision(), 128);
            var replacement = fixture.directory.prepareMetadataReplacement(change.previous(), change.next());
            data.commitAdministratorChange(change);
            fixture.directory.commitMetadataReplacement(replacement);
            fixture.terminal.inventoryStep(
                    new io.github.loongin.omniresonance.transfer.TransferWorkBudget(1, 1000000, 1, () -> 0));
            helper.assertTrue(
                    frames.size() == 1
                            && frames.getFirst()
                                    instanceof io.github.loongin.omniresonance.networking.DomainInventoryFrame.Failed,
                    "Revoked viewer received inventory data instead of an ordered failure");
            helper.assertTrue(ledger.amount(key) == 7, "Cancelling synchronization changed inventory");
            helper.succeed();
        }
    }

    @GameTest(template = "bootstrap")
    public static void inventoryUsesTheRealTerminalSessionAndCancelsBeforeQueuedActivation(GameTestHelper helper)
            throws IOException {
        try (Fixture fixture = new Fixture(helper)) {
            ServerPlayer owner = player(helper, OWNER);
            fixture.open(owner, OWNER_VIEW, OWNER_SESSION);
            fixture.terminal.handle(
                    owner, new NetworkTerminalRequest.OpenNetwork(OWNER_VIEW, OWNER_SESSION, 1, NETWORK));
            var sent = new ArrayList<io.github.loongin.omniresonance.networking.DomainInventoryFrame>();
            int[] activations = {0};
            fixture.terminal.installInventory(
                    id -> {
                        activations[0]++;
                        helper.assertTrue(id.equals(NETWORK), "Client selected a foreign inventory network");
                        var ledger =
                                fixture.repository.domainStorage(id).activate().orElseThrow();
                        var key = new io.github.loongin.omniresonance.transfer.ResourceVariantKey(
                                net.minecraft.resources.ResourceLocation.parse("example:opaque"), new byte[] {1});
                        try (var deposit = ledger.reserveDeposit(key, 42, -1).orElseThrow()) {
                            deposit.commit(42);
                        }
                        return ledger;
                    },
                    (player, frame) -> sent.add(frame));
            fixture.terminal.inventory(
                    player(helper, OTHER_OWNER),
                    new io.github.loongin.omniresonance.networking.DomainInventoryRequest(
                            OWNER_VIEW, OWNER_SESSION, 1, true));
            fixture.terminal.inventory(
                    owner,
                    new io.github.loongin.omniresonance.networking.DomainInventoryRequest(
                            OWNER_VIEW, OWNER_SESSION, 1, true));
            fixture.terminal.inventory(
                    owner,
                    new io.github.loongin.omniresonance.networking.DomainInventoryRequest(
                            OWNER_VIEW, OWNER_SESSION, 1, false));
            fixture.terminal.inventoryStep(
                    new io.github.loongin.omniresonance.transfer.TransferWorkBudget(1, 1000000, 1, () -> 0));
            helper.assertTrue(
                    activations[0] == 0 && sent.isEmpty(),
                    "Cancelled queued view activated storage or spoofed actor received data");
            fixture.terminal.inventory(
                    owner,
                    new io.github.loongin.omniresonance.networking.DomainInventoryRequest(
                            OWNER_VIEW, OWNER_SESSION, 2, true));
            fixture.terminal.inventoryStep(
                    new io.github.loongin.omniresonance.transfer.TransferWorkBudget(1, 1000000, 1, () -> 0));
            helper.assertTrue(activations[0] == 1, "Authorized inventory was not admitted exactly once");
            helper.assertTrue(
                    sent.getFirst() instanceof io.github.loongin.omniresonance.networking.DomainInventoryFrame.Begin,
                    "Real session did not send Begin first");
            helper.assertTrue(
                    sent.getLast() instanceof io.github.loongin.omniresonance.networking.DomainInventoryFrame.End end
                            && end.count() == 1,
                    "Real session did not seal the correct inventory");
            var data = (io.github.loongin.omniresonance.networking.DomainInventoryFrame.Data) sent.get(1);
            helper.assertTrue(
                    io.github.loongin.omniresonance.networking.DomainInventoryRecordCodec.decode(data.data())
                                    .amount()
                            == 42,
                    "Inventory stream changed the exact quantity");
            fixture.terminal.closePlayer(owner);
            try (var journal = fixture.repository
                    .domainStorage(NETWORK)
                    .activate()
                    .orElseThrow()
                    .openChanges(1)) {
                helper.assertTrue(journal.pendingCount() == 0, "Closed session retained the inventory publisher");
            }
            helper.succeed();
        }
    }

    @GameTest(template = "bootstrap")
    public static void networkHomeReportsKnownDomainFailureWithoutActivatingHealthyStorage(GameTestHelper helper)
            throws IOException {
        try (Fixture fixture = new Fixture(helper)) {
            ServerPlayer owner = player(helper, OWNER);
            fixture.open(owner, OWNER_VIEW, OWNER_SESSION);
            var domain = fixture.repository.domainStorage(NETWORK);
            var before = domain.state();
            var initial = state(
                    helper,
                    fixture.terminal.handle(
                            owner, new NetworkTerminalRequest.OpenNetwork(OWNER_VIEW, OWNER_SESSION, 1, NETWORK)),
                    NetworkTerminalState.NetworkRoot.class);
            helper.assertTrue(
                    !((NetworkTerminalState.NetworkRoot) initial.state()).domainUnavailable(),
                    "Inactive storage was reported as failed");
            helper.assertTrue(domain.state() == before, "Opening the terminal activated resource buckets");
            fixture.repository.findLoadedNetwork(NETWORK).orElseThrow().markStorageBuckets(1L);
            helper.assertTrue(domain.activate().isEmpty(), "Missing bucket was accepted");
            var failed = state(
                    helper,
                    fixture.terminal.handle(
                            owner, new NetworkTerminalRequest.OpenNetwork(OWNER_VIEW, OWNER_SESSION, 2, NETWORK)),
                    NetworkTerminalState.NetworkRoot.class);
            helper.assertTrue(
                    ((NetworkTerminalState.NetworkRoot) failed.state()).domainUnavailable(),
                    "Terminal hid the known storage failure");
            fixture.open(player(helper, ADMINISTRATOR), ADMIN_VIEW, ADMIN_SESSION);
            ServerPlayer other = player(helper, OTHER_OWNER);
            fixture.open(other, OTHER_VIEW, OTHER_SESSION);
            var healthy = state(
                    helper,
                    fixture.terminal.handle(
                            other, new NetworkTerminalRequest.OpenNetwork(OTHER_VIEW, OTHER_SESSION, 1, UNRELATED)),
                    NetworkTerminalState.NetworkRoot.class);
            helper.assertTrue(
                    !((NetworkTerminalState.NetworkRoot) healthy.state()).domainUnavailable(),
                    "One failed domain marked another network unavailable");
            helper.succeed();
        }
    }

    @GameTest(template = "bootstrap")
    public static void ruleEditWireRoutePinsSourceAndCommitsWholePresetOnce(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper)) {
            ServerPlayer owner = player(helper, OWNER);
            var library = fixture.repository.createOwner(OWNER, null);
            UUID id = new UUID(890, 1);
            var oldId = net.minecraft.resources.ResourceLocation.parse("minecraft:iron_ingto");
            var keptId = net.minecraft.resources.ResourceLocation.parse("minecraft:stone");
            library.putPreset(
                    new io.github.loongin.omniresonance.filter.ItemFilterPreset(
                            id, new ManagedName("Ores"), 0, Set.of(oldId, keptId)),
                    0,
                    -1,
                    -1);
            fixture.open(owner, OWNER_VIEW, OWNER_SESSION);
            fixture.terminal.handle(
                    owner, new NetworkTerminalRequest.OpenNetwork(OWNER_VIEW, OWNER_SESSION, 1, NETWORK));
            fixture.terminal.handle(owner, new NetworkTerminalRequest.OpenFilters(OWNER_VIEW, OWNER_SESSION, 2));
            fixture.terminal.handle(
                    owner, new NetworkTerminalRequest.OpenPreset(OWNER_VIEW, OWNER_SESSION, 3, id, 0, 0));
            var wire = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            try {
                wire.writeByte(40)
                        .writeUUID(OWNER_VIEW)
                        .writeUUID(OWNER_SESSION)
                        .writeLong(4);
                wire.writeByte(6).writeBoolean(true).writeUUID(id);
                wire.writeUtf(oldId.toString(), 65535);
                var response = state(
                        helper,
                        fixture.terminal.handle(owner, NetworkTerminalRequest.STREAM_CODEC.decode(wire)),
                        NetworkTerminalState.PresetEdit.class);
                helper.assertTrue(
                        ((NetworkTerminalState.PresetEdit) response.state())
                                .originalRule()
                                .equals(oldId.toString()),
                        "Server response lost the pinned edit source");
            } finally {
                wire.release();
            }
            long revision = library.presetLibraryRevision();
            library.setDirty(false);
            failure(
                    helper,
                    fixture.terminal.handle(
                            owner, new NetworkTerminalRequest.SavePresetEdit(OWNER_VIEW, OWNER_SESSION, 5, "Bad ID")),
                    NetworkTerminalResponse.Reason.INVALID_REQUEST);
            helper.assertTrue(
                    !library.isDirty()
                            && library.presetLibraryRevision() == revision
                            && itemIds(library.findPreset(id).orElseThrow()).equals(Set.of(oldId, keptId)),
                    "Invalid replacement changed authority");
            state(
                    helper,
                    fixture.terminal.handle(
                            owner,
                            new NetworkTerminalRequest.SavePresetEdit(
                                    OWNER_VIEW, OWNER_SESSION, 6, "minecraft:iron_ingot")),
                    NetworkTerminalState.Preset.class);
            var next = library.findPreset(id).orElseThrow();
            helper.assertTrue(
                    next.name().value().equals("Ores")
                            && next.revision() == 1
                            && library.presetLibraryRevision() == revision + 1
                            && itemIds(next)
                                    .equals(Set.of(
                                            keptId,
                                            net.minecraft.resources.ResourceLocation.parse("minecraft:iron_ingot"))),
                    "Replacement must preserve identity and other rules with exactly one commit");
            failure(
                    helper,
                    fixture.terminal.handle(
                            owner,
                            new NetworkTerminalRequest.SavePresetEdit(
                                    OWNER_VIEW, OWNER_SESSION, 6, "minecraft:diamond")),
                    NetworkTerminalResponse.Reason.STALE_REQUEST);
            helper.assertTrue(library.findPreset(id).orElseThrow().equals(next), "Replay changed authority");
            failure(
                    helper,
                    fixture.terminal.handle(
                            owner,
                            new NetworkTerminalRequest.BeginPresetEdit(
                                    OWNER_VIEW,
                                    OWNER_SESSION,
                                    7,
                                    io.github.loongin.omniresonance.filter.PresetEditOperation.EDIT_RULE,
                                    id,
                                    "minecraft:diamond")),
                    NetworkTerminalResponse.Reason.INVALID_REQUEST);
            failure(
                    helper,
                    fixture.terminal.handle(
                            owner,
                            new NetworkTerminalRequest.BeginPresetEdit(
                                    OWNER_VIEW,
                                    OWNER_SESSION,
                                    8,
                                    io.github.loongin.omniresonance.filter.PresetEditOperation.EDIT_RULE,
                                    id)),
                    NetworkTerminalResponse.Reason.INVALID_REQUEST);
            state(
                    helper,
                    fixture.terminal.handle(
                            owner,
                            new NetworkTerminalRequest.BeginPresetEdit(
                                    OWNER_VIEW,
                                    OWNER_SESSION,
                                    9,
                                    io.github.loongin.omniresonance.filter.PresetEditOperation.EDIT_RULE,
                                    id,
                                    "minecraft:iron_ingot")),
                    NetworkTerminalState.PresetEdit.class);
            state(
                    helper,
                    fixture.terminal.handle(
                            owner, new NetworkTerminalRequest.CancelEdit(OWNER_VIEW, OWNER_SESSION, 10)),
                    NetworkTerminalState.Preset.class);
            helper.assertTrue(
                    library.findPreset(id).orElseThrow().equals(next)
                            && library.presetLibraryRevision() == revision + 1,
                    "Missing, forged or cancelled edit changed authority through terminal route");
            helper.succeed();
        }
    }

    @GameTest(template = "bootstrap")
    public static void presetLifecycleUsesExplicitSavesAndOwnerLibrary(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper)) {
            ServerPlayer owner = player(helper, OWNER);
            fixture.open(owner, OWNER_VIEW, OWNER_SESSION);
            fixture.terminal.handle(
                    owner, new NetworkTerminalRequest.OpenNetwork(OWNER_VIEW, OWNER_SESSION, 1, NETWORK));
            state(
                    helper,
                    fixture.terminal.handle(
                            owner, new NetworkTerminalRequest.OpenFilters(OWNER_VIEW, OWNER_SESSION, 2)),
                    NetworkTerminalState.Filters.class);
            state(
                    helper,
                    fixture.terminal.handle(
                            owner,
                            new NetworkTerminalRequest.BeginPresetEdit(
                                    OWNER_VIEW,
                                    OWNER_SESSION,
                                    3,
                                    io.github.loongin.omniresonance.filter.PresetEditOperation.CREATE,
                                    null)),
                    NetworkTerminalState.PresetEdit.class);
            helper.assertTrue(fixture.repository.findOwner(OWNER).isEmpty(), "Browsing or drafting created owner data");
            state(
                    helper,
                    fixture.terminal.handle(
                            owner, new NetworkTerminalRequest.SavePresetEdit(OWNER_VIEW, OWNER_SESSION, 4, "Ores")),
                    NetworkTerminalState.Preset.class);
            var library = fixture.repository.findOwner(OWNER).orElseThrow();
            var preset = library.findPreset(new ManagedName("Ores")).orElseThrow();
            helper.assertTrue(itemIds(preset).isEmpty(), "New preset contains implicit rules");
            fixture.terminal.handle(
                    owner,
                    new NetworkTerminalRequest.BeginPresetEdit(
                            OWNER_VIEW,
                            OWNER_SESSION,
                            5,
                            io.github.loongin.omniresonance.filter.PresetEditOperation.ADD_RULE,
                            preset.id()));
            state(
                    helper,
                    fixture.terminal.handle(
                            owner,
                            new NetworkTerminalRequest.SavePresetEdit(
                                    OWNER_VIEW, OWNER_SESSION, 6, "minecraft:iron_ingot")),
                    NetworkTerminalState.Preset.class);
            helper.assertTrue(
                    itemIds(library.findPreset(preset.id()).orElseThrow()).size() == 1, "Rule was not saved");
            fixture.terminal.handle(
                    owner,
                    new NetworkTerminalRequest.BeginPresetEdit(
                            OWNER_VIEW,
                            OWNER_SESSION,
                            7,
                            io.github.loongin.omniresonance.filter.PresetEditOperation.RENAME,
                            preset.id()));
            fixture.terminal.handle(owner, new NetworkTerminalRequest.CancelEdit(OWNER_VIEW, OWNER_SESSION, 8));
            helper.assertTrue(
                    library.findPreset(preset.id()).orElseThrow().name().value().equals("Ores"),
                    "Cancel changed preset");
            fixture.terminal.handle(
                    owner,
                    new NetworkTerminalRequest.BeginPresetEdit(
                            OWNER_VIEW,
                            OWNER_SESSION,
                            9,
                            io.github.loongin.omniresonance.filter.PresetEditOperation.COPY,
                            preset.id()));
            state(
                    helper,
                    fixture.terminal.handle(
                            owner, new NetworkTerminalRequest.SavePresetEdit(OWNER_VIEW, OWNER_SESSION, 10, "Copy")),
                    NetworkTerminalState.Preset.class);
            var copy = library.findPreset(new ManagedName("Copy")).orElseThrow();
            helper.assertTrue(
                    !copy.id().equals(preset.id()) && itemIds(copy).size() == 1, "Copy lost rules or reused UUID");
            fixture.terminal.handle(
                    owner,
                    new NetworkTerminalRequest.BeginPresetEdit(
                            OWNER_VIEW,
                            OWNER_SESSION,
                            11,
                            io.github.loongin.omniresonance.filter.PresetEditOperation.DELETE,
                            copy.id()));
            state(
                    helper,
                    fixture.terminal.handle(
                            owner, new NetworkTerminalRequest.SavePresetEdit(OWNER_VIEW, OWNER_SESSION, 12, "")),
                    NetworkTerminalState.Filters.class);
            helper.assertTrue(
                    library.findPreset(copy.id()).isEmpty()
                            && library.findPreset(preset.id()).isPresent(),
                    "Delete removed wrong preset");
            fixture.terminal.handle(
                    owner, new NetworkTerminalRequest.OpenPreset(OWNER_VIEW, OWNER_SESSION, 13, preset.id(), 1, 0));
            fixture.terminal.handle(owner, new NetworkTerminalRequest.Back(OWNER_VIEW, OWNER_SESSION, 14));
            fixture.terminal.handle(
                    owner,
                    new NetworkTerminalRequest.BeginPresetEdit(
                            OWNER_VIEW,
                            OWNER_SESSION,
                            15,
                            io.github.loongin.omniresonance.filter.PresetEditOperation.CREATE,
                            null));
            state(
                    helper,
                    fixture.terminal.handle(
                            owner, new NetworkTerminalRequest.CancelEdit(OWNER_VIEW, OWNER_SESSION, 16)),
                    NetworkTerminalState.Filters.class);
            failure(
                    helper,
                    fixture.terminal.handle(
                            owner,
                            new NetworkTerminalRequest.SavePresetEdit(OWNER_VIEW, OWNER_SESSION, 16, "Replayed")),
                    NetworkTerminalResponse.Reason.STALE_REQUEST);
            failure(
                    helper,
                    fixture.terminal.handle(
                            owner, new NetworkTerminalRequest.OpenFilters(OWNER_VIEW, new UUID(873, 99), 17)),
                    NetworkTerminalResponse.Reason.SESSION_EXPIRED);
            fixture.terminal.handle(
                    owner, new NetworkTerminalRequest.OpenPreset(OWNER_VIEW, OWNER_SESSION, 17, preset.id(), 1, 0));
            fixture.terminal.handle(
                    owner,
                    new NetworkTerminalRequest.BeginPresetEdit(
                            OWNER_VIEW,
                            OWNER_SESSION,
                            18,
                            io.github.loongin.omniresonance.filter.PresetEditOperation.RENAME,
                            preset.id()));
            fixture.terminal.handle(owner, new NetworkTerminalRequest.Close(OWNER_VIEW, OWNER_SESSION));
            var reacquired = fixture.filters.begin(
                    owner, NETWORK, io.github.loongin.omniresonance.filter.PresetEditOperation.RENAME, preset.id());
            fixture.filters.cancel(owner, reacquired);
            helper.succeed();
        }
    }

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

    @GameTest(template = "bootstrap")
    public static void terminalFullRuleRouteSamplesInventoryAndCompletesChangedSlotFailure(GameTestHelper helper)
            throws IOException {
        try (Fixture f = new Fixture(helper)) {
            ServerPlayer owner = player(helper, OWNER);
            var library = f.repository.createOwner(OWNER, null);
            UUID id = new UUID(890, 90);
            library.putPreset(
                    new io.github.loongin.omniresonance.filter.ItemFilterPreset(
                            id, new ManagedName("Full"), 0, Set.of()),
                    0,
                    -1,
                    -1);
            f.open(owner, OWNER_VIEW, OWNER_SESSION);
            f.terminal.handle(owner, new NetworkTerminalRequest.OpenNetwork(OWNER_VIEW, OWNER_SESSION, 1, NETWORK));
            f.terminal.handle(owner, new NetworkTerminalRequest.OpenFilters(OWNER_VIEW, OWNER_SESSION, 2));
            var page = (NetworkTerminalResponse.ViewState) f.terminal.handle(
                    owner, new NetworkTerminalRequest.OpenPreset(OWNER_VIEW, OWNER_SESSION, 3, id, 0, 0));
            helper.assertTrue(
                    ((NetworkTerminalState.Preset) page.state()).rules().fullDomain(),
                    "Actual terminal did not activate full rule pages");
            f.terminal.handle(
                    owner, new NetworkTerminalRequest.BeginResourceRule(OWNER_VIEW, OWNER_SESSION, 4, id, null, false));
            owner.getInventory()
                    .setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WATER_BUCKET));
            var response = f.terminal.handle(
                    owner,
                    new NetworkTerminalRequest.SampleResourceRule(
                            OWNER_VIEW,
                            OWNER_SESSION,
                            5,
                            io.github.loongin.omniresonance.transfer.ResourceTypes.FLUID,
                            0,
                            0));
            helper.assertTrue(response == null, "Sample prepared response prematurely completed request");
            f.filters.sampleStep(
                    new io.github.loongin.omniresonance.transfer.TransferWorkBudget(1, 1000, 1000, () -> 0));
            owner.getInventory()
                    .setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE));
            f.filters.sampleStep(
                    new io.github.loongin.omniresonance.transfer.TransferWorkBudget(1, 1000, 1000, () -> 0));
            var failed = (NetworkTerminalResponse.FullRule) f.notices.getLast().response();
            helper.assertTrue(
                    failed.sequence() == 5
                            && failed.snapshot().length == 0
                            && failed.failure().equals("inventory_changed"),
                    "Changed inventory left a pending request or published an old sample");
            f.terminal.handle(
                    owner,
                    new NetworkTerminalRequest.SampleResourceRule(
                            OWNER_VIEW,
                            OWNER_SESSION,
                            6,
                            io.github.loongin.omniresonance.transfer.ResourceTypes.ITEM,
                            0,
                            0));
            f.filters.sampleStep(
                    new io.github.loongin.omniresonance.transfer.TransferWorkBudget(1, 1000, 1000, () -> 0));
            var sample = (NetworkTerminalResponse.FullRule) f.notices.getLast().response();
            var intent = new io.github.loongin.omniresonance.filter.ResourceRuleIntent.Match(
                    io.github.loongin.omniresonance.transfer.ResourceTypes.ITEM,
                    io.github.loongin.omniresonance.filter.ResourceFilterRule.Selector.exact(
                            net.minecraft.resources.ResourceLocation.parse("minecraft:stone")),
                    io.github.loongin.omniresonance.filter.ComponentCondition.Mode.FULL,
                    Set.of(),
                    sample.sampleToken());
            var saved = f.terminal.handle(
                    owner, new NetworkTerminalRequest.SaveResourceRule(OWNER_VIEW, OWNER_SESSION, 7, intent));
            helper.assertTrue(
                    saved instanceof NetworkTerminalResponse.ViewState
                            && library.findPreset(id).orElseThrow().rules().size() == 1,
                    "Actual typed terminal sample save did not commit");
            helper.assertTrue(owner.getInventory().getItem(0).getCount() == 1, "ITEM sample consumed inventory");
            helper.succeed();
        }
    }

    @GameTest(template = "bootstrap")
    public static void terminalSnapshotAndUploadUseTheSameNodeGlobalPoolAndRejectStaleTransfer(GameTestHelper helper)
            throws IOException {
        try (Fixture f = new Fixture(helper)) {
            ServerPlayer owner = player(helper, OWNER);
            var library = f.repository.createOwner(OWNER, null);
            UUID id = new UUID(890, 91), ruleId = new UUID(891, 91);
            var selectedValues =
                    new ArrayList<io.github.loongin.omniresonance.filter.ComponentCondition.SelectedComponent>();
            Set<net.minecraft.resources.ResourceLocation> selectedKeys = new java.util.HashSet<>();
            for (int i = 0; i < 3000; i++) {
                var key = net.minecraft.resources.ResourceLocation.parse("test:" + i + "a".repeat(90));
                selectedKeys.add(key);
                selectedValues.add(new io.github.loongin.omniresonance.filter.ComponentCondition.SelectedComponent(
                        key,
                        io.github.loongin.omniresonance.transfer.CanonicalResourceNbt.encode(
                                net.minecraft.nbt.IntTag.valueOf(i))));
            }
            var components = io.github.loongin.omniresonance.filter.ComponentCondition.fromPersistenceSnapshot(
                    new io.github.loongin.omniresonance.filter.ComponentCondition.PersistenceSnapshot(
                            io.github.loongin.omniresonance.filter.ComponentCondition.Mode.SELECTED,
                            io.github.loongin.omniresonance.transfer.ResourceTypes.ITEM,
                            null,
                            selectedValues));
            var rule = new io.github.loongin.omniresonance.filter.ResourceFilterRule.Match(
                    ruleId,
                    io.github.loongin.omniresonance.transfer.ResourceTypes.ITEM,
                    io.github.loongin.omniresonance.filter.ResourceFilterRule.Selector.exact(
                            net.minecraft.resources.ResourceLocation.parse("minecraft:stone")),
                    components);
            library.putPreset(
                    new io.github.loongin.omniresonance.filter.ResourceFilterPreset(
                            id, new ManagedName("Large"), 0, List.of(rule)),
                    0,
                    -1,
                    -1);
            f.open(owner, OWNER_VIEW, OWNER_SESSION);
            f.terminal.handle(owner, new NetworkTerminalRequest.OpenNetwork(OWNER_VIEW, OWNER_SESSION, 1, NETWORK));
            f.terminal.handle(owner, new NetworkTerminalRequest.OpenFilters(OWNER_VIEW, OWNER_SESSION, 2));
            f.terminal.handle(owner, new NetworkTerminalRequest.OpenPreset(OWNER_VIEW, OWNER_SESSION, 3, id, 0, 0));
            var ready = (NetworkTerminalResponse.RuleTransferReady) f.terminal.handle(
                    owner, new NetworkTerminalRequest.ReadResourceRule(OWNER_VIEW, OWNER_SESSION, 4, id, 0, ruleId));
            helper.assertTrue(
                    !ready.upload() && f.menus.transfers().reservedBytes() == ready.length(),
                    "Terminal used an independent pool");
            for (int tick = 0; tick < 10 && f.menus.transfers().reservedBytes() > 0; tick++) f.terminal.tick();
            var assembled = new java.io.ByteArrayOutputStream();
            for (var message : f.fragments)
                assembled.writeBytes(
                        ((io.github.loongin.omniresonance.networking.ManagementTransferMessage.Chunk) message).data());
            var opened =
                    io.github.loongin.omniresonance.networking.FullFilterCodec.readSnapshot(assembled.toByteArray());
            helper.assertTrue(
                    opened.rules().getFirst().id().equals(ruleId)
                            && f.menus.transfers().reservedBytes() == 0,
                    "Full snapshot lost identity or leaked reservation");
            f.terminal.handle(
                    owner,
                    new NetworkTerminalRequest.BeginResourceRule(OWNER_VIEW, OWNER_SESSION, 5, id, ruleId, false));
            var intent = new io.github.loongin.omniresonance.filter.ResourceRuleIntent.Match(
                    rule.resourceTypeId(),
                    rule.selector(),
                    io.github.loongin.omniresonance.filter.ComponentCondition.Mode.SELECTED,
                    selectedKeys,
                    null);
            byte[] wire = io.github.loongin.omniresonance.networking.FullFilterCodec.intent(intent);
            UUID transfer = new UUID(892, 91);
            f.terminal.handle(
                    owner,
                    new NetworkTerminalRequest.PrepareResourceRuleUpload(
                            OWNER_VIEW, OWNER_SESSION, 6, transfer, wire.length));
            helper.assertTrue(
                    f.menus.transfers().reservedBytes() == wire.length, "Upload did not use shared reservation");
            for (int offset = 0; offset < wire.length; ) {
                int end = Math.min(
                        wire.length,
                        offset
                                + io.github.loongin.omniresonance.networking.ManagementTransferPool
                                        .MAXIMUM_FRAGMENT_BYTES);
                f.terminal.handleTransfer(
                        owner,
                        new io.github.loongin.omniresonance.networking.ManagementTransferMessage.Chunk(
                                OWNER_SESSION, transfer, offset, java.util.Arrays.copyOfRange(wire, offset, end)));
                offset = end;
            }
            var saved = f.terminal.handleTransfer(
                    owner,
                    new io.github.loongin.omniresonance.networking.ManagementTransferMessage.Finish(
                            OWNER_SESSION, transfer));
            helper.assertTrue(
                    saved instanceof NetworkTerminalResponse.ViewState
                            && saved.sequence() == 6
                            && library.findPreset(id).orElseThrow().revision() == 1
                            && f.menus.transfers().reservedBytes() == 0,
                    "Logical upload request did not finish exactly once");
            helper.assertTrue(
                    f.terminal.handleTransfer(
                                    owner,
                                    new io.github.loongin.omniresonance.networking.ManagementTransferMessage.Finish(
                                            OWNER_SESSION, transfer))
                            == null,
                    "Completed transfer replay reached authority");
            helper.succeed();
        }
    }

    @GameTest(template = "bootstrap")
    public static void registeredTerminalRequestBoundaryIncludesPayloadIdentifier(GameTestHelper helper) {
        int maximum = 262144;
        while (!io.github.loongin.omniresonance.networking.FullFilterCodec.intentFitsPacket(maximum)) maximum--;
        var intent = sizedIntent(maximum);
        var buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            var request = new NetworkTerminalRequest.SaveResourceRule(OWNER_VIEW, OWNER_SESSION, 1, intent);
            net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket.STREAM_CODEC.encode(
                    buffer, new net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket(request));
            helper.assertTrue(
                    buffer.readableBytes() == 262144,
                    "Inline decision excluded registered request ID: " + buffer.readableBytes());
            var decoded = (NetworkTerminalRequest.SaveResourceRule)
                    net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket.STREAM_CODEC
                            .decode(buffer)
                            .payload();
            helper.assertTrue(
                    ((io.github.loongin.omniresonance.filter.ResourceRuleIntent.Match) decoded.intent())
                            .selectedKeys()
                            .equals(intent.selectedKeys()),
                    "Registered request lost selected keys");
            buffer.clear();
            boolean rejected = false;
            try {
                net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket.STREAM_CODEC.encode(
                        buffer,
                        new net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket(
                                new NetworkTerminalRequest.SaveResourceRule(
                                        OWNER_VIEW, OWNER_SESSION, 2, sizedIntent(maximum + 1))));
            } catch (RuntimeException expected) {
                rejected = expected instanceof io.netty.handler.codec.EncoderException
                        || expected.getCause() instanceof io.netty.handler.codec.EncoderException;
                if (!rejected) throw expected;
            }
            helper.assertTrue(rejected, "Registered request exceeding the complete envelope was accepted");
        } finally {
            buffer.release();
        }
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void registeredTerminalResponseBoundaryIncludesPayloadIdentifier(GameTestHelper helper) {
        var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(
                io.netty.buffer.Unpooled.buffer(),
                net.minecraft.core.RegistryAccess.EMPTY,
                net.neoforged.neoforge.network.connection.ConnectionType.NEOFORGE);
        try {
            var response = new NetworkTerminalResponse.FullRule(
                    OWNER_VIEW, OWNER_SESSION, 1, null, 0, 0, "", new byte[262050]);
            net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket.GAMEPLAY_STREAM_CODEC.encode(
                    buffer, new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(response));
            helper.assertTrue(buffer.readableBytes() == 262144, "Unexpected full registered response size");
            var decoded = (NetworkTerminalResponse.FullRule)
                    net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket.GAMEPLAY_STREAM_CODEC
                            .decode(buffer)
                            .payload();
            helper.assertTrue(decoded.snapshot().length == 262050, "Registered response lost snapshot bytes");
            buffer.clear();
            boolean rejected = false;
            try {
                net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket.GAMEPLAY_STREAM_CODEC.encode(
                        buffer,
                        new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
                                new NetworkTerminalResponse.FullRule(
                                        OWNER_VIEW, OWNER_SESSION, 2, null, 0, 0, "", new byte[262051])));
            } catch (RuntimeException expected) {
                rejected = expected instanceof io.netty.handler.codec.EncoderException
                        || expected.getCause() instanceof io.netty.handler.codec.EncoderException;
                if (!rejected) throw expected;
            }
            helper.assertTrue(rejected, "Registered response exceeding the complete envelope was accepted");
        } finally {
            buffer.release();
        }
        helper.succeed();
    }

    private static io.github.loongin.omniresonance.filter.ResourceRuleIntent.Match sizedIntent(int size) {
        Set<net.minecraft.resources.ResourceLocation> keys = new java.util.HashSet<>();
        for (String namespace : List.of("a", "b", "c"))
            keys.add(net.minecraft.resources.ResourceLocation.parse(namespace + ":" + "x".repeat(65533)));
        var initialKey = net.minecraft.resources.ResourceLocation.parse("d:" + "x".repeat(64000));
        keys.add(initialKey);
        var initial = new io.github.loongin.omniresonance.filter.ResourceRuleIntent.Match(
                io.github.loongin.omniresonance.transfer.ResourceTypes.ITEM,
                io.github.loongin.omniresonance.filter.ResourceFilterRule.Selector.wholeType(),
                io.github.loongin.omniresonance.filter.ComponentCondition.Mode.SELECTED,
                keys,
                null);
        int extra = size - io.github.loongin.omniresonance.networking.FullFilterCodec.intentSize(initial);
        keys.remove(initialKey);
        keys.add(net.minecraft.resources.ResourceLocation.parse("d:" + "x".repeat(64000 + extra)));
        return new io.github.loongin.omniresonance.filter.ResourceRuleIntent.Match(
                initial.typeId(), initial.selector(), initial.mode(), keys, null);
    }

    @GameTest(template = "bootstrap")
    public static void terminalLargeNativeSampleCapacityFailureAllowsSameEditRetry(GameTestHelper helper)
            throws IOException {
        try (Fixture f = new Fixture(helper)) {
            ServerPlayer owner = player(helper, OWNER);
            UUID preset = new UUID(890, 93);
            var library = f.repository.createOwner(OWNER, null);
            library.putPreset(
                    new io.github.loongin.omniresonance.filter.ItemFilterPreset(
                            preset, new ManagedName("Capacity"), 0, Set.of()),
                    0,
                    -1,
                    -1);
            f.open(owner, OWNER_VIEW, OWNER_SESSION);
            f.terminal.handle(owner, new NetworkTerminalRequest.OpenNetwork(OWNER_VIEW, OWNER_SESSION, 1, NETWORK));
            f.terminal.handle(owner, new NetworkTerminalRequest.OpenFilters(OWNER_VIEW, OWNER_SESSION, 2));
            f.terminal.handle(owner, new NetworkTerminalRequest.OpenPreset(OWNER_VIEW, OWNER_SESSION, 3, preset, 0, 0));
            f.terminal.handle(
                    owner,
                    new NetworkTerminalRequest.BeginResourceRule(OWNER_VIEW, OWNER_SESSION, 4, preset, null, false));
            var stack = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE);
            var data = new net.minecraft.nbt.CompoundTag();
            data.putByteArray("payload", new byte[0]);
            stack.set(
                    net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                    net.minecraft.world.item.component.CustomData.of(data));
            int overhead = io.github.loongin.omniresonance.transfer.ItemVariant.from(
                            stack, helper.getLevel().registryAccess())
                    .key()
                    .canonicalBytes()
                    .length;
            data.putByteArray("payload", new byte[262144 - overhead]);
            stack.set(
                    net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                    net.minecraft.world.item.component.CustomData.of(data));
            var variant = io.github.loongin.omniresonance.transfer.ItemVariant.from(
                    stack, helper.getLevel().registryAccess());
            var rule = new io.github.loongin.omniresonance.filter.ResourceFilterRule.Match(
                    new UUID(891, 93),
                    io.github.loongin.omniresonance.transfer.ResourceTypes.ITEM,
                    io.github.loongin.omniresonance.filter.ResourceFilterRule.Selector.exact(variant.itemId()),
                    io.github.loongin.omniresonance.filter.ComponentCondition.full(variant));
            var snapshot = new io.github.loongin.omniresonance.filter.ResourceFilterPreset(
                    preset, new ManagedName("Capacity"), 0, List.of(rule));
            helper.assertTrue(
                    !io.github.loongin.omniresonance.networking.FullFilterCodec.snapshotFitsPacket(
                            io.github.loongin.omniresonance.networking.FullFilterCodec.snapshotSize(snapshot), true),
                    "Valid native sample did not require multipart delivery");
            owner.getInventory().setItem(0, stack);
            var pool = f.menus.transfers();
            for (int i = 0; i < 4; i++)
                pool.beginUpload(
                        new UUID(893, i),
                        new UUID(894, i),
                        new UUID(895, i),
                        16777216,
                        helper.getLevel().getGameTime());
            f.terminal.handle(
                    owner,
                    new NetworkTerminalRequest.SampleResourceRule(
                            OWNER_VIEW, OWNER_SESSION, 5, rule.resourceTypeId(), 0, 0));
            f.filters.sampleStep(
                    new io.github.loongin.omniresonance.transfer.TransferWorkBudget(1, 1000, 1000, () -> 0));
            helper.assertTrue(
                    f.notices.size() == 1
                            && f.notices.getFirst().response() instanceof NetworkTerminalResponse.Failure failure
                            && failure.sequence() == 5
                            && failure.sessionId().equals(OWNER_SESSION),
                    "Capacity rejection stranded the original sample request instead of one bounded Failure");
            f.filters.sampleStep(
                    new io.github.loongin.omniresonance.transfer.TransferWorkBudget(1, 1000, 1000, () -> 0));
            f.terminal.tick();
            helper.assertTrue(
                    f.notices.size() == 1
                            && pool.reservedBytes() == 67108864
                            && library.findPreset(preset).orElseThrow().revision() == 0
                            && net.minecraft.world.item.ItemStack.matches(
                                    stack, owner.getInventory().getItem(0)),
                    "Rejected sample duplicated delivery, leaked capacity or changed authority/inventory");
            for (int i = 0; i < 4; i++)
                helper.assertTrue(
                        pool.abort(new UUID(893, i), new UUID(894, i), new UUID(895, i)),
                        "Sample rejection cancelled an unrelated reservation");
            f.terminal.handle(
                    owner,
                    new NetworkTerminalRequest.SampleResourceRule(
                            OWNER_VIEW, OWNER_SESSION, 6, rule.resourceTypeId(), 0, 0));
            f.filters.sampleStep(
                    new io.github.loongin.omniresonance.transfer.TransferWorkBudget(1, 1000, 1000, () -> 0));
            var ready = (NetworkTerminalResponse.RuleTransferReady)
                    f.notices.getLast().response();
            helper.assertTrue(
                    f.notices.size() == 2 && ready.sequence() == 6 && !ready.upload(),
                    "Same-edit retry did not admit its own sample response");
            for (int tick = 0; tick < 10 && pool.reservedBytes() > 0; tick++) f.terminal.tick();
            var assembled = new java.io.ByteArrayOutputStream();
            for (var message : f.fragments)
                assembled.writeBytes(
                        ((io.github.loongin.omniresonance.networking.ManagementTransferMessage.Chunk) message).data());
            var downloaded =
                    io.github.loongin.omniresonance.networking.FullFilterCodec.readSnapshot(assembled.toByteArray());
            helper.assertTrue(
                    downloaded.rules().size() == 1 && pool.reservedBytes() == 0,
                    "Retry snapshot was unreadable or retained its transfer");
            var intent = new io.github.loongin.omniresonance.filter.ResourceRuleIntent.Match(
                    rule.resourceTypeId(),
                    rule.selector(),
                    io.github.loongin.omniresonance.filter.ComponentCondition.Mode.FULL,
                    Set.of(),
                    ready.sampleToken());
            helper.assertTrue(
                    f.terminal.handle(
                                            owner,
                                            new NetworkTerminalRequest.SaveResourceRule(
                                                    OWNER_VIEW, OWNER_SESSION, 7, intent))
                                    instanceof NetworkTerminalResponse.ViewState
                            && library.findPreset(preset).orElseThrow().revision() == 1,
                    "Admission failure lost the edit or retry FULL sample authorization");
            helper.succeed();
        }
    }

    @GameTest(template = "bootstrap", timeoutTicks = 430)
    public static void terminalSampleTimeoutCancelsExactWorkAndKeepsSameEditRetry(GameTestHelper helper)
            throws IOException {
        Fixture f = new Fixture(helper);
        ServerPlayer owner = player(helper, OWNER);
        UUID preset = new UUID(890, 92);
        var library = f.repository.createOwner(OWNER, null);
        library.putPreset(
                new io.github.loongin.omniresonance.filter.ItemFilterPreset(
                        preset, new ManagedName("Timeout"), 0, Set.of()),
                0,
                -1,
                -1);
        f.open(owner, OWNER_VIEW, OWNER_SESSION);
        f.terminal.handle(owner, new NetworkTerminalRequest.OpenNetwork(OWNER_VIEW, OWNER_SESSION, 1, NETWORK));
        f.terminal.handle(owner, new NetworkTerminalRequest.OpenFilters(OWNER_VIEW, OWNER_SESSION, 2));
        f.terminal.handle(owner, new NetworkTerminalRequest.OpenPreset(OWNER_VIEW, OWNER_SESSION, 3, preset, 0, 0));
        f.terminal.handle(
                owner, new NetworkTerminalRequest.BeginResourceRule(OWNER_VIEW, OWNER_SESSION, 4, preset, null, false));
        owner.getInventory()
                .setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WATER_BUCKET));
        f.terminal.handle(
                owner,
                new NetworkTerminalRequest.SampleResourceRule(
                        OWNER_VIEW,
                        OWNER_SESSION,
                        5,
                        io.github.loongin.omniresonance.transfer.ResourceTypes.FLUID,
                        0,
                        0));
        long[] sequence = {5}, replacementSequence = {0};
        boolean[] finished = {false};
        for (int tick = 1; tick <= 402; tick++) {
            int elapsed = tick;
            helper.runAtTickTime(tick, () -> {
                if (finished[0]) return;
                try {
                    f.terminal.tick();
                    if (elapsed % 40 == 0)
                        f.terminal.handle(
                                owner, new NetworkTerminalRequest.Heartbeat(OWNER_VIEW, OWNER_SESSION, ++sequence[0]));
                    if (elapsed == 201) {
                        helper.assertTrue(
                                f.notices.size() == 1
                                        && f.notices.getFirst().response()
                                                instanceof NetworkTerminalResponse.Failure failure
                                        && failure.sequence() == 5,
                                "Sample timeout did not finish exactly its original logical request");
                        long calls = 0;
                        for (int step = 0; step < 3; step++) {
                            var budget = new io.github.loongin.omniresonance.transfer.TransferWorkBudget(
                                    1, 1000, 1000, () -> 0);
                            f.filters.sampleStep(budget);
                            calls += budget.calls();
                        }
                        helper.assertTrue(calls == 0, "Expired logical sample still performed native calls: " + calls);
                        helper.assertTrue(f.notices.size() == 1, "Expired logical sample published a late result");
                        f.terminal.handle(
                                owner,
                                new NetworkTerminalRequest.SampleResourceRule(
                                        OWNER_VIEW,
                                        OWNER_SESSION,
                                        ++sequence[0],
                                        io.github.loongin.omniresonance.transfer.ResourceTypes.FLUID,
                                        0,
                                        0));
                    }
                    if (elapsed == 390) {
                        replacementSequence[0] = ++sequence[0];
                        f.terminal.handle(
                                owner,
                                new NetworkTerminalRequest.SampleResourceRule(
                                        OWNER_VIEW,
                                        OWNER_SESSION,
                                        sequence[0],
                                        io.github.loongin.omniresonance.transfer.ResourceTypes.FLUID,
                                        0,
                                        0));
                    }
                    if (elapsed == 402) {
                        helper.assertTrue(
                                f.notices.size() == 1,
                                "Old deadline cancelled a replacement sample sharing actor/edit");
                        for (int step = 0; step < 3; step++) {
                            var budget = new io.github.loongin.omniresonance.transfer.TransferWorkBudget(
                                    1, 1000, 1000, () -> 0);
                            f.filters.sampleStep(budget);
                            helper.assertTrue(
                                    budget.calls() == 1,
                                    "Replacement sample was cancelled or lost its bounded native step");
                        }
                        var result = (NetworkTerminalResponse.FullRule)
                                f.notices.getLast().response();
                        helper.assertTrue(
                                f.notices.size() == 2
                                        && result.sequence() == replacementSequence[0]
                                        && result.failure().isEmpty(),
                                "Replacement sample did not complete its own request");
                        var intent = new io.github.loongin.omniresonance.filter.ResourceRuleIntent.Match(
                                io.github.loongin.omniresonance.transfer.ResourceTypes.FLUID,
                                io.github.loongin.omniresonance.filter.ResourceFilterRule.Selector.exact(
                                        net.minecraft.resources.ResourceLocation.parse("minecraft:water")),
                                io.github.loongin.omniresonance.filter.ComponentCondition.Mode.FULL,
                                Set.of(),
                                result.sampleToken());
                        helper.assertTrue(
                                f.terminal.handle(
                                                        owner,
                                                        new NetworkTerminalRequest.SaveResourceRule(
                                                                OWNER_VIEW, OWNER_SESSION, ++sequence[0], intent))
                                                instanceof NetworkTerminalResponse.ViewState
                                        && library.findPreset(preset)
                                                        .orElseThrow()
                                                        .revision()
                                                == 1,
                                "Timeout cancelled the edit or successful retry token lost its snapshot authorization");
                        finished[0] = true;
                        f.close();
                        helper.succeed();
                    }
                } catch (RuntimeException | IOException failure) {
                    finished[0] = true;
                    try {
                        f.close();
                    } catch (IOException cleanup) {
                        failure.addSuppressed(cleanup);
                    }
                    throw new IllegalStateException(failure);
                }
            });
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final Path path;
        private final SavedNetworkRepository repository;
        private final NetworkDirectory directory;
        private final NetworkTopologyService topology;
        private final NetworkSettingsService settings;
        private final NetworkTerminalService terminal;
        private final io.github.loongin.omniresonance.node.NodeMenuService menus;
        private final io.github.loongin.omniresonance.node.NodeManagementService nodeManagement;
        private final io.github.loongin.omniresonance.node.NodeAuthorityService nodeAuthority;
        private final List<io.github.loongin.omniresonance.networking.ManagementTransferMessage> fragments =
                new ArrayList<>();
        private final io.github.loongin.omniresonance.filter.ItemFilterService filters;
        private final List<Notice> notices = new ArrayList<>();
        private long nextPresetId;

        private Fixture(GameTestHelper helper) throws IOException {
            path = Files.createTempDirectory("omniresonance-terminal-settings-test-");
            DimensionDataStorage storage = new DimensionDataStorage(
                    path.toFile(), DataFixers.getDataFixer(), helper.getLevel().registryAccess());
            repository = new SavedNetworkRepository(storage, path);
            NetworkMetadata network =
                    new NetworkMetadata(NETWORK, OWNER, new ManagedName("Network"), 0, Set.of(ADMINISTRATOR));
            NetworkMetadata unrelated =
                    new NetworkMetadata(UNRELATED, OTHER_OWNER, new ManagedName("Unrelated"), 0, Set.of());
            repository.createNetwork(network);
            repository.createNetwork(unrelated);
            directory = new NetworkDirectory(List.of(network, unrelated));
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
            var nodes = new NetworkNodeDirectory(List.of());
            nodeAuthority = new io.github.loongin.omniresonance.node.NodeAuthorityService(
                    helper.getLevel().getServer(), repository, nodes, UUID::randomUUID);
            nodeManagement = new io.github.loongin.omniresonance.node.NodeManagementService(
                    helper.getLevel().getServer(), directory, repository, nodes, nodeAuthority, locks);
            menus = new io.github.loongin.omniresonance.node.NodeMenuService(
                    helper.getLevel().getServer(), nodeManagement, topology, directory, UUID::randomUUID);
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
                    menus,
                    config,
                    () -> sessions.isEmpty() ? UUID.randomUUID() : sessions.removeFirst(),
                    (player, response) -> notices.add(new Notice(player.getUUID(), response)));
            filters = new io.github.loongin.omniresonance.filter.ItemFilterService(
                    helper.getLevel().getServer(),
                    repository,
                    directory,
                    locks,
                    terminal::settingsSnapshot,
                    id -> {},
                    () -> new UUID(874, ++nextPresetId));
            terminal.installFilters(filters);
            terminal.installTransferSender((player, message) -> fragments.add(message));
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
            menus.close();
            nodeManagement.close();
            nodeAuthority.close();
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
