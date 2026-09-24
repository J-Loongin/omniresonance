// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.filter;

import static io.github.loongin.omniresonance.filter.fixtures.FullFilterAssertions.itemIds;

import com.mojang.authlib.GameProfile;
import io.github.loongin.omniresonance.bootstrap.OmniResonanceMod;
import io.github.loongin.omniresonance.config.ServerSettings;
import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.network.NetworkDirectory;
import io.github.loongin.omniresonance.network.NetworkMetadata;
import io.github.loongin.omniresonance.node.NodeForm;
import io.github.loongin.omniresonance.node.NodeMode;
import io.github.loongin.omniresonance.persistence.ManagedSavedDataNames;
import io.github.loongin.omniresonance.persistence.NetworkSavedData;
import io.github.loongin.omniresonance.persistence.OwnerSavedData;
import io.github.loongin.omniresonance.persistence.SavedNetworkRepository;
import io.github.loongin.omniresonance.security.EditLockTable;
import io.github.loongin.omniresonance.transfer.ItemTransferPolicy;
import io.github.loongin.omniresonance.transfer.RedstoneCondition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.common.IOUtilities;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(OmniResonanceMod.MOD_ID)
@PrefixGameTestTemplate(false)
@net.neoforged.fml.common.EventBusSubscriber(modid = "omniresonance")
public final class ItemFilterServiceGameTests {
    private static final UUID OWNER = new UUID(940, 1), ADMIN = new UUID(940, 2), STRANGER = new UUID(940, 3);
    private static final UUID NETWORK = new UUID(941, 1), PRIVATE = new UUID(941, 2), FOREIGN = new UUID(941, 3);
    private static final UUID PRESET = new UUID(942, 1),
            NODE = new UUID(943, 1),
            CHANNEL = new UUID(944, 1),
            TUNNEL = new UUID(945, 1);

    private static Runnable sampleReentry = () -> {};
    private static int nativeSampleTank;

    @net.neoforged.bus.api.SubscribeEvent
    public static void sampleCapabilities(net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent event) {
        event.registerItem(
                net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.ITEM,
                (stack, context) -> stack.getOrDefault(
                                        net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                                        net.minecraft.world.item.component.CustomData.EMPTY)
                                .copyTag()
                                .getBoolean("sample_test")
                        ? new ManyTanks(stack)
                        : null,
                net.minecraft.world.item.Items.PAPER);
    }

    private record ManyTanks(net.minecraft.world.item.ItemStack container)
            implements net.neoforged.neoforge.fluids.capability.IFluidHandlerItem {
        @Override
        public net.minecraft.world.item.ItemStack getContainer() {
            return container;
        }

        @Override
        public int getTanks() {
            return Integer.MAX_VALUE;
        }

        @Override
        public net.neoforged.neoforge.fluids.FluidStack getFluidInTank(int tank) {
            nativeSampleTank = tank;
            sampleReentry.run();
            return new net.neoforged.neoforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER, 1000);
        }

        @Override
        public int getTankCapacity(int tank) {
            throw new AssertionError("Sample scanned capacity");
        }

        @Override
        public boolean isFluidValid(int tank, net.neoforged.neoforge.fluids.FluidStack stack) {
            throw new AssertionError("Sample queried admission");
        }

        @Override
        public int fill(net.neoforged.neoforge.fluids.FluidStack stack, FluidAction action) {
            throw new AssertionError("Sample filled fluid");
        }

        @Override
        public net.neoforged.neoforge.fluids.FluidStack drain(
                net.neoforged.neoforge.fluids.FluidStack stack, FluidAction action) {
            throw new AssertionError("Sample drained fluid");
        }

        @Override
        public net.neoforged.neoforge.fluids.FluidStack drain(int amount, FluidAction action) {
            throw new AssertionError("Sample drained fluid");
        }
    }

    private ItemFilterServiceGameTests() {}

    @GameTest(template = "bootstrap")
    public static void catalogBatchesRetainAuthorityAndInvalidateTheMetadataIndex(GameTestHelper helper)
            throws IOException {
        try (Fixture f = new Fixture(helper, false)) {
            for (int i = 0; i < 260; i++)
                f.library.putPreset(
                        new ResourceFilterPreset(new UUID(950, i + 1), new ManagedName("Catalog " + i), 0, List.of()),
                        f.library.presetLibraryRevision(),
                        -1,
                        -1);
            var first = f.service.page(f.owner, NETWORK, 0, "", -1);
            var second = f.service.page(f.owner, NETWORK, 128, "", first.libraryRevision());
            helper.assertTrue(
                    first.entries().size() == 128 && second.entries().size() == 128,
                    "Catalog pages must remain bounded");
            helper.assertTrue(
                    first.entries().stream()
                            .noneMatch(a -> second.entries().stream().anyMatch(b -> a.id().equals(b.id()))),
                    "Catalog pages overlap");
            var edit = f.service.begin(f.owner, NETWORK, PresetEditOperation.RENAME, PRESET, "");
            f.service.save(f.owner, edit, "Renamed catalog entry");
            var changed = f.service.page(f.owner, NETWORK, 128, "", first.libraryRevision());
            helper.assertTrue(
                    changed.offset() == 0 && changed.libraryRevision() != first.libraryRevision(),
                    "Changed catalog must restart so the client can reject mixed revisions");
            var match = f.service.page(f.owner, NETWORK, 0, "Renamed catalog entry", -1);
            helper.assertTrue(match.totalCount() == 1, "Metadata index retained the old name");
            f.service.page(f.admin, NETWORK, 0, "", -1);
        }
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void privateDomainReferencesRestrictPresetEdits(GameTestHelper helper) throws IOException {
        for (boolean indirect : new boolean[] {false, true}) {
            try (Fixture f = new Fixture(helper, false)) {
                UUID selectedPreset = PRESET;
                if (indirect) {
                    selectedPreset = new UUID(942, 91);
                    f.library.putPreset(
                            new ResourceFilterPreset(
                                    selectedPreset,
                                    new ManagedName("Domain parent"),
                                    0,
                                    List.of(new ResourceFilterRule.Reference(new UUID(943, 91), PRESET))),
                            f.library.presetLibraryRevision(),
                            -1,
                            -1);
                }
                var node = f.privateNetwork.createNode(
                        NODE,
                        new ManagedName("Domain"),
                        GlobalPos.of(Level.OVERWORLD, new BlockPos(11000000, 64, 11000000)),
                        NodeForm.BLOCK,
                        Direction.DOWN);
                node = f.privateNetwork
                        .setNodeMode(NODE, node.revision(), NodeMode.DOMAIN, false)
                        .orElseThrow();
                var policy = new io.github.loongin.omniresonance.transfer.ResourceTransferPolicy.Output(
                        1,
                        io.github.loongin.omniresonance.transfer.ResourceScope.all(),
                        RedstoneCondition.IGNORE,
                        selectedPreset,
                        FilterMode.WHITELIST,
                        java.util.Map.of(),
                        0);
                f.privateNetwork.saveDomainConfiguration(
                        NODE,
                        node.revision(),
                        new io.github.loongin.omniresonance.transfer.StoredResourcePolicy(policy, java.util.Map.of()),
                        io.github.loongin.omniresonance.network.WorkingFaces.explicit(0),
                        false);
                reject(
                        helper,
                        ItemFilterService.Reason.NO_ACCESS,
                        () -> f.service.begin(f.admin, NETWORK, PresetEditOperation.RENAME, PRESET, ""));
                var edit = f.service.begin(f.owner, NETWORK, PresetEditOperation.RENAME, PRESET, "");
                f.service.save(f.owner, edit, "Owner edit");
                helper.assertTrue(
                        f.library.auditEntries().size() == 1
                                && f.library
                                        .auditEntries()
                                        .getFirst()
                                        .action()
                                        .getPath()
                                        .equals("rename_preset"),
                        "Rejected administrator edit must not add an owner audit entry");
                helper.assertTrue(
                        f.library
                                .findPreset(PRESET)
                                .orElseThrow()
                                .name()
                                .value()
                                .equals("Owner edit"),
                        "Owner could not edit a domain-referenced preset");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void ruleReplacementIsOneObservableCommitAtQuotaAndMergesExistingIds(GameTestHelper helper)
            throws IOException {
        try (Fixture f = new Fixture(helper, false)) {
            var old = ResourceLocation.parse("minecraft:iron_ingto");
            var kept = ResourceLocation.parse("minecraft:stone");
            f.library.putPreset(
                    new ItemFilterPreset(PRESET, new ManagedName("Preset"), 1, Set.of(old, kept)),
                    f.library.presetLibraryRevision(),
                    -1,
                    -1);
            f.ruleLimit(2);
            var before = f.library.findPreset(PRESET).orElseThrow();
            var edit = f.service.begin(f.owner, NETWORK, PresetEditOperation.EDIT_RULE, PRESET, old.toString());
            helper.assertTrue(edit.originalRule().equals(old.toString()), "Server did not pin source");
            long libraryRevision = f.library.presetLibraryRevision();
            f.library.setDirty(false);
            for (String invalid : List.of("Bad ID", "stone", "a:" + "b".repeat(65534))) {
                reject(helper, ItemFilterService.Reason.INVALID_REQUEST, () -> f.service.save(f.owner, edit, invalid));
                helper.assertTrue(
                        f.library.findPreset(PRESET).orElseThrow().equals(before)
                                && !f.library.isDirty()
                                && f.library.presetLibraryRevision() == libraryRevision
                                && f.notified.isEmpty(),
                        "Invalid replacement partially committed");
            }
            var optional = ResourceLocation.parse("optional_mod:unregistered_item");
            f.service.save(f.owner, edit, optional.toString());
            var replaced = f.library.findPreset(PRESET).orElseThrow();
            helper.assertTrue(
                    replaced.id().equals(PRESET)
                            && replaced.name().equals(before.name())
                            && replaced.revision() == before.revision() + 1
                            && f.library.presetLibraryRevision() == libraryRevision + 1
                            && itemIds(replaced).equals(Set.of(optional, kept))
                            && f.notified.equals(List.of(OWNER))
                            && f.observed.equals(List.of(replaced)),
                    "Observers saw an intermediate set, duplicate commit, identity loss or quota rejection");
            reject(helper, ItemFilterService.Reason.LOCK_EXPIRED, () -> f.service.save(f.owner, edit, old.toString()));
            var merge = f.service.begin(f.owner, NETWORK, PresetEditOperation.EDIT_RULE, PRESET, optional.toString());
            f.service.save(f.owner, merge, kept.toString());
            helper.assertTrue(
                    itemIds(f.library.findPreset(PRESET).orElseThrow()).equals(Set.of(kept)),
                    "Replacement failed to merge the duplicate target");
            var noop = f.service.begin(f.owner, NETWORK, PresetEditOperation.EDIT_RULE, PRESET, kept.toString());
            long noOpRevision = f.library.findPreset(PRESET).orElseThrow().revision();
            f.service.save(f.owner, noop, kept.toString());
            helper.assertTrue(
                    f.library.findPreset(PRESET).orElseThrow().revision() == noOpRevision + 1
                            && itemIds(f.library.findPreset(PRESET).orElseThrow())
                                    .equals(Set.of(kept)),
                    "Same-ID save violated the existing explicit-save revision contract");
            f.ruleLimit(0);
            var overQuota = f.service.begin(f.owner, NETWORK, PresetEditOperation.EDIT_RULE, PRESET, kept.toString());
            f.service.save(f.owner, overQuota, old.toString());
            helper.assertTrue(
                    itemIds(f.library.findPreset(PRESET).orElseThrow()).equals(Set.of(old)) && f.notified.size() == 4,
                    "Lowered quota blocked a no-growth replacement or repeated notification");
            helper.succeed();
        }
    }

    @GameTest(template = "bootstrap")
    public static void ruleEditRejectsMissingForgedCancelledStaleAndNewlyPrivateSourceWithoutMutation(
            GameTestHelper helper) throws IOException {
        try (Fixture f = new Fixture(helper, false)) {
            String source = "minecraft:iron_ingto";
            f.library.putPreset(
                    new ItemFilterPreset(PRESET, new ManagedName("Preset"), 1, Set.of(ResourceLocation.parse(source))),
                    f.library.presetLibraryRevision(),
                    -1,
                    -1);
            var original = f.library.findPreset(PRESET).orElseThrow();
            long revision = f.library.presetLibraryRevision();
            f.library.setDirty(false);
            for (String missing : List.of("", "minecraft:diamond", "stone"))
                reject(
                        helper,
                        ItemFilterService.Reason.INVALID_REQUEST,
                        () -> f.service.begin(f.owner, NETWORK, PresetEditOperation.EDIT_RULE, PRESET, missing));
            reject(
                    helper,
                    ItemFilterService.Reason.INVALID_REQUEST,
                    () -> f.service.begin(f.owner, NETWORK, PresetEditOperation.EDIT_RULE, PRESET));
            var cancelled = f.service.begin(f.owner, NETWORK, PresetEditOperation.EDIT_RULE, PRESET, source);
            f.service.cancel(f.owner, cancelled);
            reject(
                    helper,
                    ItemFilterService.Reason.LOCK_EXPIRED,
                    () -> f.service.save(f.owner, cancelled, "minecraft:iron_ingot"));
            var admin = f.service.begin(f.admin, NETWORK, PresetEditOperation.EDIT_RULE, PRESET, source);
            reject(
                    helper,
                    ItemFilterService.Reason.LOCK_EXPIRED,
                    () -> f.service.save(f.stranger, admin, "minecraft:iron_ingot"));
            f.bind(f.privateNetwork, false);
            reject(
                    helper,
                    ItemFilterService.Reason.NO_ACCESS,
                    () -> f.service.save(f.admin, admin, "minecraft:iron_ingot"));
            var owner = f.service.begin(f.owner, NETWORK, PresetEditOperation.EDIT_RULE, PRESET, source);
            f.bind(f.network, true);
            reject(
                    helper,
                    ItemFilterService.Reason.STALE_REVISION,
                    () -> f.service.save(f.owner, owner, "minecraft:iron_ingot"));
            var expires = f.service.begin(f.owner, NETWORK, PresetEditOperation.EDIT_RULE, PRESET, source);
            for (int tick = 0; tick < 200; tick++) f.service.tick();
            reject(
                    helper,
                    ItemFilterService.Reason.LOCK_EXPIRED,
                    () -> f.service.save(f.owner, expires, "minecraft:iron_ingot"));
            helper.assertTrue(
                    f.library.findPreset(PRESET).orElseThrow().equals(original)
                            && !f.library.isDirty()
                            && f.library.presetLibraryRevision() == revision
                            && f.notified.isEmpty(),
                    "Rejected or cancelled source changed library authority");
            var stale = f.service.begin(f.owner, NETWORK, PresetEditOperation.EDIT_RULE, PRESET, source);
            var external = new ItemFilterPreset(PRESET, original.name(), 2, Set.of());
            f.library.putPreset(external, revision, -1, -1);
            f.library.setDirty(false);
            reject(
                    helper,
                    ItemFilterService.Reason.STALE_REVISION,
                    () -> f.service.save(f.owner, stale, "minecraft:iron_ingot"));
            helper.assertTrue(
                    f.library
                                    .findPreset(PRESET)
                                    .orElseThrow()
                                    .equals(io.github.loongin.omniresonance.persistence.ResourceFilterPresetNbt.migrate(
                                            external))
                            && !f.library.isDirty()
                            && f.notified.isEmpty(),
                    "Stale edit recreated an absent source");
            helper.succeed();
        }
    }

    @GameTest(template = "bootstrap")
    public static void nameSearchCoversWholeAuthorizedLibraryAndRestartsChangedPages(GameTestHelper helper)
            throws IOException {
        try (Fixture f = new Fixture(helper, false)) {
            for (int index = 2; index <= 260; index++) {
                f.library.putPreset(
                        new ItemFilterPreset(
                                new UUID(942, index),
                                new ManagedName(index == 260 ? "Deep Iron" : "Preset " + index),
                                0,
                                Set.of()),
                        f.library.presetLibraryRevision(),
                        -1,
                        -1);
            }
            f.library.setDirty(false);
            long revision = f.library.presetLibraryRevision();
            var found = f.service.page(f.admin, NETWORK, 0, "iRoN", -1);
            helper.assertTrue(
                    found.totalCount() == 1 && found.entries().getFirst().name().equals("Deep Iron"),
                    "Name search truncated or did not filter across the full owner library");
            var first = f.service.page(f.owner, NETWORK, 0, "", -1);
            helper.assertTrue(
                    first.entries().size() == 128 && first.totalCount() == 260,
                    "Empty search did not restore bounded full library page");
            var second = f.service.page(f.owner, NETWORK, 128, "", first.libraryRevision());
            helper.assertTrue(second.offset() == 128 && second.entries().size() == 128, "Adjacent page failed");
            var restarted = f.service.page(f.owner, NETWORK, 128, "", revision - 1);
            helper.assertTrue(
                    restarted.offset() == 0 && restarted.libraryRevision() == revision,
                    "Changed revision silently skipped library entries");
            var deleted = f.service.page(f.owner, NETWORK, 260, "Iron", revision);
            helper.assertTrue(
                    deleted.offset() == 0 && deleted.totalCount() == 1, "Out of range filtered page did not restart");
            helper.assertTrue(
                    !f.library.isDirty() && f.library.presetLibraryRevision() == revision,
                    "Read-only search changed saved data");
            reject(helper, ItemFilterService.Reason.NO_ACCESS, () -> f.service.page(f.stranger, NETWORK, 0, "", -1));
            helper.succeed();
        }
    }

    @GameTest(template = "bootstrap")
    public static void adminProofIncludesDisabledUnloadedAndDuplicatePersistedNodes(GameTestHelper helper)
            throws IOException {
        try (Fixture f = new Fixture(helper, false)) {
            f.bind(f.network, true);
            f.bind(f.privateNetwork, false);
            var summary = f.service.summary(f.admin, NETWORK, PRESET);
            helper.assertTrue(
                    summary != null && !summary.editable(), "Private disabled binding was excluded from proof");
            reject(
                    helper,
                    ItemFilterService.Reason.NO_ACCESS,
                    () -> f.service.begin(f.admin, NETWORK, PresetEditOperation.RENAME, PRESET));
            reject(
                    helper,
                    ItemFilterService.Reason.NO_ACCESS,
                    () -> f.service.begin(f.admin, NETWORK, PresetEditOperation.DELETE, PRESET));
            var copy = f.service.begin(f.admin, NETWORK, PresetEditOperation.COPY, PRESET);
            UUID copied = f.service.save(f.admin, copy, "Copy");
            helper.assertTrue(!PRESET.equals(copied), "Admin copy reused shared UUID");
            var edit = f.service.begin(f.owner, NETWORK, PresetEditOperation.RENAME, PRESET);
            helper.assertTrue(
                    edit.impact().networkCount() == 2
                            && edit.impact().nodeCount() == 2
                            && edit.impact().bindingCount() == 2,
                    "Persisted conflicting UUID impact was deduplicated across networks");
            f.service.save(f.owner, edit, "Updated");
            helper.assertTrue(
                    f.notified.size() == 2 && f.notified.stream().allMatch(OWNER::equals),
                    "Runtime library invalidation missing");
            helper.succeed();
        }
    }

    @GameTest(template = "bootstrap")
    public static void sharedPresetLeaseRechecksRevisionReferencesSenderAndLatePermissions(GameTestHelper helper)
            throws IOException {
        try (Fixture f = new Fixture(helper, false)) {
            var edit = f.service.begin(f.admin, NETWORK, PresetEditOperation.RENAME, PRESET);
            reject(
                    helper,
                    ItemFilterService.Reason.LOCKED,
                    () -> f.service.begin(f.owner, NETWORK, PresetEditOperation.RENAME, PRESET));
            reject(helper, ItemFilterService.Reason.LOCK_EXPIRED, () -> f.service.save(f.stranger, edit, "Forged"));
            f.bind(f.privateNetwork, false);
            reject(helper, ItemFilterService.Reason.NO_ACCESS, () -> f.service.save(f.admin, edit, "Forbidden"));
            var ownerEdit = f.service.begin(f.owner, NETWORK, PresetEditOperation.RENAME, PRESET);
            f.library.putPreset(
                    new ItemFilterPreset(PRESET, new ManagedName("External"), 1, Set.of()),
                    f.library.presetLibraryRevision(),
                    -1,
                    -1);
            reject(helper, ItemFilterService.Reason.STALE_REVISION, () -> f.service.save(f.owner, ownerEdit, "Stale"));
            var current = f.service.begin(f.owner, NETWORK, PresetEditOperation.RENAME, PRESET);
            f.library.putPreset(
                    new ItemFilterPreset(new UUID(942, 2), new ManagedName("Unrelated"), 0, Set.of()),
                    f.library.presetLibraryRevision(),
                    -1,
                    -1);
            f.service.save(f.owner, current, "Independent");
            helper.assertTrue(
                    f.library.findPreset(PRESET).orElseThrow().name().value().equals("Independent"),
                    "Unrelated library revision invalidated exact preset lease");
            var expires = f.service.begin(f.owner, NETWORK, PresetEditOperation.DELETE, PRESET);
            for (int i = 0; i < 200; i++) f.service.tick();
            reject(helper, ItemFilterService.Reason.LOCK_EXPIRED, () -> f.service.save(f.owner, expires, ""));
            helper.succeed();
        }
    }

    @GameTest(template = "bootstrap")
    public static void unreadableShardsDenyAdminProofWithoutRemovingOwnerAuthority(GameTestHelper helper)
            throws IOException {
        try (Fixture f = new Fixture(helper, true)) {
            helper.assertTrue(
                    f.repository.hasUnreadableNetworkShards(), "Test did not establish failed shard authority");
            reject(
                    helper,
                    ItemFilterService.Reason.NO_ACCESS,
                    () -> f.service.begin(f.admin, NETWORK, PresetEditOperation.DELETE, PRESET));
            var ownerEdit = f.service.begin(f.owner, NETWORK, PresetEditOperation.RENAME, PRESET);
            helper.assertTrue(!ownerEdit.impact().complete(), "Incomplete impact was presented as global certainty");
            f.service.save(f.owner, ownerEdit, "Owner change");
            helper.succeed();
        }
    }

    @GameTest(template = "bootstrap")
    public static void deletionRetainsBothDirectionsAndForeignUuidCannotContaminateImpact(GameTestHelper helper)
            throws IOException {
        try (Fixture f = new Fixture(helper, false)) {
            f.bind(f.network, true);
            f.bind(f.privateNetwork, false);
            f.bind(f.foreign, true);
            f.repository
                    .createOwner(STRANGER, null)
                    .putPreset(new ItemFilterPreset(PRESET, new ManagedName("Other owner"), 0, Set.of()), 0, -1, -1);
            var edit = f.service.begin(f.owner, NETWORK, PresetEditOperation.DELETE, PRESET);
            helper.assertTrue(edit.impact().bindingCount() == 2, "Other owner's colliding UUID contaminated impact");
            f.service.save(f.owner, edit, "");
            for (NetworkSavedData network : List.of(f.network, f.privateNetwork)) {
                var binding = network.directBindings(NODE).getFirst();
                helper.assertTrue(
                        PRESET.equals(binding.policy().filterPresetId()), "Preset delete rewrote saved references");
                helper.assertTrue(
                        !ItemFilterPreset.allows(
                                PRESET, null, binding.policy().filterMode(), ResourceLocation.parse("minecraft:stone")),
                        "Deleted preset allowed transfer");
            }
            helper.assertTrue(
                    f.repository
                            .findOwner(STRANGER)
                            .orElseThrow()
                            .findPreset(PRESET)
                            .isPresent(),
                    "Delete touched other owner's UUID");
            helper.succeed();
        }
    }

    @GameTest(template = "bootstrap")
    public static void variableBytePagesHaveExactBackwardAnchorsAndDoNotTruncateRules(GameTestHelper helper)
            throws IOException {
        try (Fixture f = new Fixture(helper, false)) {
            Set<ResourceLocation> rules = new java.util.HashSet<>();
            for (int i = 0; i < 131; i++)
                rules.add(ResourceLocation.parse(
                        "a:" + String.format(java.util.Locale.ROOT, "%03d", i) + "x".repeat(65530)));
            f.library.putPreset(
                    new ItemFilterPreset(PRESET, new ManagedName("Preset"), 1, rules),
                    f.library.presetLibraryRevision(),
                    -1,
                    -1);
            var first = f.service.rules(f.owner, NETWORK, PRESET, 1, 0);
            var later = f.service.rules(f.owner, NETWORK, PRESET, 1, 126);
            helper.assertTrue(
                    first.entries().size() == 3 && first.entries().getFirst().length() == 65535,
                    "Long ID page was truncated or exceeded byte bound");
            helper.assertTrue(
                    later.previousOffset() == 123 && later.entries().size() == 3,
                    "Byte-bounded backwards page jumped over rules");
            var back = f.service.rules(f.owner, NETWORK, PRESET, 1, later.previousOffset());
            helper.assertTrue(
                    back.offset() + back.entries().size() == later.offset(),
                    "Previous page did not end at current anchor");
            reject(
                    helper,
                    ItemFilterService.Reason.STALE_REVISION,
                    () -> f.service.rules(f.owner, NETWORK, PRESET, 0, 0));
            helper.succeed();
        }
    }

    @GameTest(template = "bootstrap")
    public static void lateNetworkRevocationReleasesExactLeaseAndUnreadableOwnerIsNeverRecreated(GameTestHelper helper)
            throws IOException {
        try (Fixture f = new Fixture(helper, false)) {
            var edit = f.service.begin(f.admin, NETWORK, PresetEditOperation.RENAME, PRESET);
            var change = f.network.prepareAdministratorChange(ADMIN, false, 0, -1);
            var index = f.directory.prepareMetadataReplacement(change.previous(), change.next());
            f.network.commitAdministratorChange(change);
            f.directory.commitMetadataReplacement(index);
            reject(helper, ItemFilterService.Reason.NO_ACCESS, () -> f.service.save(f.admin, edit, "Revoked"));
            var ownerEdit = f.service.begin(f.owner, NETWORK, PresetEditOperation.RENAME, PRESET);
            f.service.cancel(f.owner, ownerEdit);
            Path unreadable = f.path.resolve(ManagedSavedDataNames.owner(STRANGER) + ".dat");
            Files.writeString(unreadable, "invalid");
            reject(helper, ItemFilterService.Reason.UNAVAILABLE, () -> f.service.page(f.stranger, FOREIGN, 0));
            reject(
                    helper,
                    ItemFilterService.Reason.UNAVAILABLE,
                    () -> f.service.begin(f.stranger, FOREIGN, PresetEditOperation.CREATE, null));
            helper.assertTrue(Files.readString(unreadable).equals("invalid"), "Unreadable owner was overwritten");
            helper.succeed();
        }
    }

    @GameTest(template = "bootstrap")
    public static void fullRuleServiceRejectsIndirectAndLatePrivateReferences(GameTestHelper helper)
            throws IOException {
        try (Fixture f = new Fixture(helper, false)) {
            UUID child = new UUID(942, 90);
            f.library.putPreset(new ResourceFilterPreset(child, new ManagedName("Child"), 0, List.of()), 1, -1, -1);
            var edit = f.service.beginRule(f.admin, NETWORK, child, null, false);
            f.bind(f.privateNetwork, true);
            f.library.putPreset(
                    new ResourceFilterPreset(
                            PRESET,
                            new ManagedName("Preset"),
                            1,
                            List.of(new ResourceFilterRule.Reference(new UUID(99, 1), child))),
                    2,
                    -1,
                    -1);
            f.library.setDirty(false);
            var intent = new ResourceRuleIntent.Match(
                    io.github.loongin.omniresonance.transfer.ResourceTypes.FLUID,
                    ResourceFilterRule.Selector.glob("minecraft:*"),
                    ComponentCondition.Mode.ID_ONLY,
                    Set.of(),
                    null);
            reject(helper, ItemFilterService.Reason.NO_ACCESS, () -> f.service.saveRule(f.admin, edit, intent));
            reject(
                    helper,
                    ItemFilterService.Reason.NO_ACCESS,
                    () -> f.service.beginRule(f.admin, NETWORK, child, null, false));
            helper.assertTrue(
                    !f.library.isDirty()
                            && f.library.findPreset(child).orElseThrow().revision() == 0,
                    "Denied indirect edit changed authority");
            var copy = f.service.begin(f.admin, NETWORK, PresetEditOperation.COPY, child);
            UUID copied = f.service.save(f.admin, copy, "Independent copy");
            helper.assertTrue(copied != null && !copied.equals(child), "Denied actor could not explicitly copy");
            helper.succeed();
        }
    }

    @GameTest(template = "bootstrap")
    public static void fullRuleServiceKeepsSelectorsComponentsAndRejectsCycles(GameTestHelper helper)
            throws IOException {
        try (Fixture f = new Fixture(helper, false)) {
            var add = f.service.beginRule(f.owner, NETWORK, PRESET, null, false);
            f.service.saveRule(
                    f.owner,
                    add,
                    new ResourceRuleIntent.Match(
                            io.github.loongin.omniresonance.transfer.ResourceTypes.FLUID,
                            ResourceFilterRule.Selector.tag(ResourceLocation.parse("c:water")),
                            ComponentCondition.Mode.ID_ONLY,
                            Set.of(),
                            null));
            var saved = f.library.findPreset(PRESET).orElseThrow();
            var rule = (ResourceFilterRule.Match) saved.rules().getFirst();
            helper.assertTrue(
                    rule.selector() instanceof ResourceFilterRule.TagSelector
                            && rule.resourceTypeId()
                                    .equals(io.github.loongin.omniresonance.transfer.ResourceTypes.FLUID),
                    "Full selector lost");
            var edit = f.service.beginRule(f.owner, NETWORK, PRESET, rule.id(), false);
            f.library.setDirty(false);
            try {
                f.service.saveRule(f.owner, edit, new ResourceRuleIntent.Reference(PRESET));
                helper.fail("Self reference accepted");
            } catch (IllegalArgumentException expected) {
                helper.assertTrue(
                        !f.library.isDirty()
                                && f.library.findPreset(PRESET).orElseThrow().equals(saved),
                        "Failed graph changed authority");
            }
            f.service.saveRule(
                    f.owner,
                    edit,
                    new ResourceRuleIntent.Match(
                            io.github.loongin.omniresonance.transfer.ResourceTypes.ENERGY,
                            ResourceFilterRule.Selector.wholeType(),
                            ComponentCondition.Mode.ID_ONLY,
                            Set.of(),
                            null));
            helper.assertTrue(
                    f.library
                            .findPreset(PRESET)
                            .orElseThrow()
                            .rules()
                            .getFirst()
                            .id()
                            .equals(rule.id()),
                    "Ordinary full rule edit changed UUID");
            helper.succeed();
        }
    }

    @GameTest(template = "bootstrap")
    public static void nativeInventorySamplesAreNonConsumingBudgetedAndLeaseBound(GameTestHelper helper)
            throws IOException {
        try (Fixture f = new Fixture(helper, false)) {
            var edit = f.service.beginRule(f.owner, NETWORK, PRESET, null, false);
            var stack = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WATER_BUCKET, 1);
            f.owner.getInventory().setItem(0, stack);
            List<ItemFilterService.SampleResult> received = new ArrayList<>();
            UUID token = f.service.requestSample(
                    f.owner,
                    edit,
                    io.github.loongin.omniresonance.transfer.ResourceTypes.FLUID,
                    0,
                    0,
                    () -> true,
                    received::add);
            for (int step = 0; step < 3; step++) {
                var budget = new io.github.loongin.omniresonance.transfer.TransferWorkBudget(1, 1000, 1000, () -> 0);
                f.service.sampleStep(budget);
                helper.assertTrue(budget.calls() == 1, "Fluid sample escaped shared call budget");
                if (step < 2) helper.assertTrue(received.isEmpty(), "Unfinished native sample published");
            }
            helper.assertTrue(
                    received.size() == 1
                            && received.getFirst().failure().isEmpty()
                            && net.minecraft.world.item.ItemStack.matches(
                                    stack, f.owner.getInventory().getItem(0)),
                    "Sample consumed inventory or failed");
            f.service.saveRule(
                    f.owner,
                    edit,
                    new ResourceRuleIntent.Match(
                            io.github.loongin.omniresonance.transfer.ResourceTypes.FLUID,
                            ResourceFilterRule.Selector.exact(ResourceLocation.parse("minecraft:water")),
                            ComponentCondition.Mode.FULL,
                            Set.of(),
                            token));
            var saved = (ResourceFilterRule.Match)
                    f.library.findPreset(PRESET).orElseThrow().rules().getFirst();
            helper.assertTrue(
                    saved.components().persistenceSnapshot().mode() == ComponentCondition.Mode.FULL,
                    "Native components lost");
            var next = f.service.beginRule(f.owner, NETWORK, PRESET, saved.id(), false);
            reject(
                    helper,
                    ItemFilterService.Reason.INVALID_REQUEST,
                    () -> f.service.saveRule(
                            f.owner,
                            next,
                            new ResourceRuleIntent.Match(
                                    saved.resourceTypeId(),
                                    saved.selector(),
                                    ComponentCondition.Mode.FULL,
                                    Set.of(),
                                    token)));
            f.service.cancel(f.owner, next);
            helper.succeed();
        }
    }

    @GameTest(template = "bootstrap")
    public static void largeNativeTankIndexAndReentrantReplacementNeverPublishOldSample(GameTestHelper helper)
            throws IOException {
        try (Fixture f = new Fixture(helper, false)) {
            var edit = f.service.beginRule(f.owner, NETWORK, PRESET, null, false);
            var paper = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PAPER);
            var marker = new net.minecraft.nbt.CompoundTag();
            marker.putBoolean("sample_test", true);
            paper.set(
                    net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                    net.minecraft.world.item.component.CustomData.of(marker));
            f.owner.getInventory().setItem(0, paper);
            var results = new ArrayList<ItemFilterService.SampleResult>();
            UUID old = f.service.requestSample(
                    f.owner,
                    edit,
                    io.github.loongin.omniresonance.transfer.ResourceTypes.FLUID,
                    0,
                    Integer.MAX_VALUE - 1,
                    () -> true,
                    results::add);
            UUID[] replacement = new UUID[1];
            sampleReentry = () -> {
                sampleReentry = () -> {};
                replacement[0] = f.service.requestSample(
                        f.owner,
                        edit,
                        io.github.loongin.omniresonance.transfer.ResourceTypes.FLUID,
                        0,
                        Integer.MAX_VALUE - 1,
                        () -> true,
                        results::add);
            };
            for (int step = 0; step < 6; step++) {
                var budget = new io.github.loongin.omniresonance.transfer.TransferWorkBudget(1, 1000, 1000, () -> 0);
                f.service.sampleStep(budget);
                helper.assertTrue(budget.calls() == 1, "Native tank selection scanned or escaped budget");
            }
            helper.assertTrue(
                    results.size() == 1
                            && results.getFirst().token().equals(replacement[0])
                            && !results.getFirst().token().equals(old)
                            && results.getFirst().failure().isEmpty()
                            && nativeSampleTank == Integer.MAX_VALUE - 1
                            && results.getFirst().tankCount() == Integer.MAX_VALUE,
                    "Large scalar tank index rejected or reentrant old task published");
            helper.assertTrue(
                    net.minecraft.world.item.ItemStack.matches(
                            paper, f.owner.getInventory().getItem(0)),
                    "Native query changed source inventory");
            helper.succeed();
        } finally {
            sampleReentry = () -> {};
        }
    }

    @GameTest(template = "bootstrap")
    public static void exactSampleCancellationCannotCancelReplacementOrSuccessfulRetryToken(GameTestHelper helper)
            throws IOException {
        try (Fixture f = new Fixture(helper, false)) {
            var edit = f.service.beginRule(f.owner, NETWORK, PRESET, null, false);
            f.owner
                    .getInventory()
                    .setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WATER_BUCKET));
            var results = new ArrayList<ItemFilterService.SampleResult>();
            UUID expired = f.service.requestSample(
                    f.owner,
                    edit,
                    io.github.loongin.omniresonance.transfer.ResourceTypes.FLUID,
                    0,
                    0,
                    () -> true,
                    results::add);
            var discover = new io.github.loongin.omniresonance.transfer.TransferWorkBudget(1, 1000, 1000, () -> 0);
            f.service.sampleStep(discover);
            helper.assertTrue(
                    discover.calls() == 1 && f.service.cancelSample(f.owner, edit, expired),
                    "Exact cancellation did not release partially discovered work");
            var empty = new io.github.loongin.omniresonance.transfer.TransferWorkBudget(1, 1000, 1000, () -> 0);
            f.service.sampleStep(empty);
            helper.assertTrue(empty.calls() == 0 && results.isEmpty(), "Cancelled capability state remained queued");
            UUID replacement = f.service.requestSample(
                    f.owner,
                    edit,
                    io.github.loongin.omniresonance.transfer.ResourceTypes.FLUID,
                    0,
                    0,
                    () -> true,
                    results::add);
            helper.assertTrue(
                    !f.service.cancelSample(f.owner, edit, expired),
                    "Old timeout cancelled a new request in the same edit");
            for (int step = 0; step < 3; step++)
                f.service.sampleStep(
                        new io.github.loongin.omniresonance.transfer.TransferWorkBudget(1, 1000, 1000, () -> 0));
            helper.assertTrue(
                    results.size() == 1
                            && results.getFirst().token().equals(replacement)
                            && !f.service.cancelSample(f.owner, edit, expired),
                    "Old cancellation invalidated the successful retry snapshot");
            f.service.saveRule(
                    f.owner,
                    edit,
                    new ResourceRuleIntent.Match(
                            io.github.loongin.omniresonance.transfer.ResourceTypes.FLUID,
                            ResourceFilterRule.Selector.exact(
                                    net.minecraft.resources.ResourceLocation.parse("minecraft:water")),
                            ComponentCondition.Mode.FULL,
                            Set.of(),
                            replacement));
            helper.assertTrue(
                    f.library.findPreset(PRESET).orElseThrow().revision() == 1,
                    "Cancellation released the user's edit lease");
            helper.succeed();
        }
    }

    private static void reject(GameTestHelper helper, ItemFilterService.Reason reason, Runnable operation) {
        try {
            operation.run();
            helper.fail("Expected filter rejection " + reason);
        } catch (ItemFilterService.Rejected failure) {
            helper.assertTrue(failure.reason() == reason, "Unexpected filter rejection " + failure.reason());
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final Path path;
        private final SavedNetworkRepository repository;
        private final NetworkDirectory directory;
        private final NetworkSavedData network, privateNetwork, foreign;
        private final OwnerSavedData library;
        private final ServerPlayer owner, admin, stranger;
        private final ItemFilterService service;
        private final List<UUID> notified = new ArrayList<>();
        private long nextPresetId;
        private ServerSettings settings = ServerSettings.defaults();
        private final List<ResourceFilterPreset> observed = new ArrayList<>();

        private Fixture(GameTestHelper helper, boolean unreadable) throws IOException {
            path = Files.createTempDirectory("omni-filter-test-");
            if (unreadable)
                Files.writeString(path.resolve(ManagedSavedDataNames.network(new UUID(946, 1)) + ".dat"), "unreadable");
            repository = new SavedNetworkRepository(
                    new DimensionDataStorage(
                            path.toFile(),
                            DataFixers.getDataFixer(),
                            helper.getLevel().registryAccess()),
                    path);
            repository.loadNetworkData();
            NetworkMetadata one = new NetworkMetadata(NETWORK, OWNER, new ManagedName("Public"), 0, Set.of(ADMIN));
            NetworkMetadata two = new NetworkMetadata(PRIVATE, OWNER, new ManagedName("Private"), 1, Set.of());
            NetworkMetadata three = new NetworkMetadata(FOREIGN, STRANGER, new ManagedName("Foreign"), 0, Set.of());
            for (NetworkMetadata metadata : List.of(one, two, three)) repository.createNetwork(metadata);
            network = repository.findLoadedNetwork(NETWORK).orElseThrow();
            privateNetwork = repository.findLoadedNetwork(PRIVATE).orElseThrow();
            foreign = repository.findLoadedNetwork(FOREIGN).orElseThrow();
            library = repository.createOwner(OWNER, null);
            library.putPreset(new ItemFilterPreset(PRESET, new ManagedName("Preset"), 0, Set.of()), 0, -1, -1);
            owner = new FakePlayer(helper.getLevel(), new GameProfile(OWNER, "Owner"));
            admin = new FakePlayer(helper.getLevel(), new GameProfile(ADMIN, "Admin"));
            stranger = new FakePlayer(helper.getLevel(), new GameProfile(STRANGER, "Other"));
            directory = new NetworkDirectory(List.of(one, two, three));
            service = new ItemFilterService(
                    helper.getLevel().getServer(),
                    repository,
                    directory,
                    new EditLockTable(),
                    () -> settings,
                    ownerId -> {
                        notified.add(ownerId);
                        library.findPreset(PRESET).ifPresent(observed::add);
                    },
                    () -> new UUID(947, ++nextPresetId));
        }

        private void ruleLimit(int limit) {
            settings = new ServerSettings(
                    settings.networksPerOwner(),
                    settings.tunnelsPerNetwork(),
                    settings.channelsPerTunnel(),
                    settings.channelBindingsPerDirectNode(),
                    settings.administratorsPerNetwork(),
                    settings.scheduler(),
                    new ServerSettings.FilterLimits(-1, limit),
                    settings.recoveryLimits());
        }

        private void bind(NetworkSavedData data, boolean input) {
            data.createTunnel(TUNNEL, new ManagedName("Tunnel"), CHANNEL, new ManagedName("Channel"), -1);
            var node = data.createNode(
                    NODE,
                    new ManagedName("Node"),
                    GlobalPos.of(Level.OVERWORLD, new BlockPos(10000000, 64, 10000000)),
                    NodeForm.BLOCK,
                    Direction.UP);
            node = data.setNodeMode(NODE, node.revision(), NodeMode.DIRECT, false)
                    .orElseThrow();
            ItemTransferPolicy policy = input
                    ? new ItemTransferPolicy.Input(1, 64, RedstoneCondition.IGNORE, PRESET, FilterMode.WHITELIST, 0)
                    : new ItemTransferPolicy.Output(1, 64, RedstoneCondition.IGNORE, PRESET, FilterMode.BLACKLIST, 0);
            node = data.setDirectBinding(NODE, node.revision(), CHANNEL, policy, false, -1);
            data.setNodeEnabled(NODE, node.revision(), false).orElseThrow();
            data.setTunnelEnabled(TUNNEL, 0, false).orElseThrow();
        }

        @Override
        public void close() throws IOException {
            IOUtilities.waitUntilIOWorkerComplete();
            try (var files = Files.walk(path)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
    }
}
