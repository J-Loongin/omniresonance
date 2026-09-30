// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import io.github.loongin.omniresonance.bootstrap.OmniResonanceMod;
import io.github.loongin.omniresonance.config.ServerSettings;
import io.github.loongin.omniresonance.filter.FilterMode;
import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.network.NetworkMetadata;
import io.github.loongin.omniresonance.network.TransferDirection;
import io.github.loongin.omniresonance.network.WorkingFaces;
import io.github.loongin.omniresonance.node.AbstractResonanceNodeBlock;
import io.github.loongin.omniresonance.node.NetworkNodeDirectory;
import io.github.loongin.omniresonance.node.NetworkNodeRecord;
import io.github.loongin.omniresonance.node.NodeForm;
import io.github.loongin.omniresonance.node.NodeMode;
import io.github.loongin.omniresonance.node.NodePersistentState;
import io.github.loongin.omniresonance.node.ResonanceNodeBlockEntity;
import io.github.loongin.omniresonance.persistence.NetworkSavedData;
import io.github.loongin.omniresonance.persistence.SavedNetworkRepository;
import io.github.loongin.omniresonance.registry.ModBlocks;
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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** M2 saved-binding assertions migrated to ResourceDirectRuntime, with retained standalone legacy-cache coverage. */
@GameTestHolder(OmniResonanceMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ItemDirectTransferGameTests {
    private ItemDirectTransferGameTests() {}

    @GameTest(template = "bootstrap", timeoutTicks = 160)
    public static void realMekanismItemPipeDeliversDirectlyToDomainInput(GameTestHelper helper) throws Exception {
        if (!net.neoforged.fml.ModList.get().isLoaded("mekanism")) {
            helper.succeed();
            return;
        }
        var f = new Fixture(helper);
        UUID id = f.node(2, TransferDirection.INPUT);
        var n = f.data.findNode(id).orElseThrow();
        n = f.data.setNodeMode(id, n.revision(), NodeMode.DOMAIN, true).orElseThrow();
        f.data.saveDomainConfiguration(
                id,
                n.revision(),
                new StoredResourcePolicy(ResourceTransferPolicy.defaults(TransferDirection.INPUT), java.util.Map.of()),
                WorkingFaces.attachedFace(),
                true);
        f.sync();
        helper.runAfterDelay(5, () -> {
            var entity = (ResonanceNodeBlockEntity) helper.getLevel().getBlockEntity(f.pos(2));
            var tag = new CompoundTag();
            NodePersistentState.linked(id).writeOwnedFields(tag);
            entity.loadCustomOnly(tag, helper.getLevel().registryAccess());
            f.start();
            f.tick(0);
            NativeDeliveryPipe.place(helper, f.pos(2).below());
            helper.runAfterDelay(80, () -> {
                try {
                    var ledger = f.repository
                            .domainStorage(f.network)
                            .activatedLedger()
                            .orElseThrow();
                    var iron = ItemVariant.from(
                            new ItemStack(Items.IRON_INGOT), helper.getLevel().registryAccess());
                    helper.assertTrue(ledger.amount(iron.key()) == 1, "Native item pipe did not deliver to the domain");
                    var handler = helper.getLevel()
                            .getCapability(
                                    net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
                                    f.pos(2),
                                    Direction.DOWN);
                    helper.assertTrue(handler.extractItem(0, 64, false).isEmpty(), "Delivery slot exposed extraction");
                    helper.succeed();
                } finally {
                    helper.getLevel().setBlockAndUpdate(f.pos(2).below().east(), Blocks.AIR.defaultBlockState());
                    try {
                        f.close();
                    } catch (Exception error) {
                        throw new IllegalStateException(error);
                    }
                }
            });
        });
    }

    private static final class NativeDeliveryPipe {
        static void place(GameTestHelper helper, BlockPos pos) {
            var level = helper.getLevel();
            level.setBlockAndUpdate(
                    pos,
                    mekanism.common.registries.MekanismBlocks.BASIC_LOGISTICAL_TRANSPORTER
                            .get()
                            .defaultBlockState());
            level.setBlockAndUpdate(pos.east(), Blocks.CHEST.defaultBlockState());
            ((ChestBlockEntity) level.getBlockEntity(pos.east())).setItem(0, new ItemStack(Items.IRON_INGOT));
            var pipe = ((mekanism.common.tile.transmitter.TileEntityLogisticalTransporter) level.getBlockEntity(pos))
                    .getTransmitter();
            pipe.setConnectionTypeRaw(Direction.EAST, mekanism.common.lib.transmitter.ConnectionType.PULL);
            pipe.refreshConnections();
        }
    }

    @GameTest(template = "bootstrap")
    public static void externalDeliveryHonorsFiltersAndRejectsIncompleteMatching(GameTestHelper helper)
            throws Exception {
        try (var f = new Fixture(helper)) {
            UUID node = f.node(2, TransferDirection.INPUT);
            UUID preset = new UUID(781, 1);
            var owner = f.repository.createOwner(f.owner, null);
            var rules = new java.util.ArrayList<io.github.loongin.omniresonance.filter.ResourceFilterRule>();
            for (int i = 0; i < 5000; i++)
                rules.add(new io.github.loongin.omniresonance.filter.ResourceFilterRule.Match(
                        new UUID(782, i + 1),
                        ResourceTypes.ITEM,
                        io.github.loongin.omniresonance.filter.ResourceFilterRule.Selector.exact(
                                net.minecraft.resources.ResourceLocation.parse("minecraft:stone")),
                        io.github.loongin.omniresonance.filter.ComponentCondition.idOnly()));
            rules.add(new io.github.loongin.omniresonance.filter.ResourceFilterRule.Match(
                    new UUID(782, 5001),
                    ResourceTypes.ITEM,
                    io.github.loongin.omniresonance.filter.ResourceFilterRule.Selector.wholeType(),
                    io.github.loongin.omniresonance.filter.ComponentCondition.idOnly()));
            owner.putPreset(
                    new io.github.loongin.omniresonance.filter.ResourceFilterPreset(
                            preset, new ManagedName("Delivery"), 0, rules),
                    0,
                    -1,
                    -1);
            var n = f.data.findNode(node).orElseThrow();
            n = f.data.setNodeMode(node, n.revision(), NodeMode.DOMAIN, true).orElseThrow();
            f.data.saveDomainConfiguration(
                    node,
                    n.revision(),
                    new StoredResourcePolicy(
                            new ResourceTransferPolicy.Input(
                                    1,
                                    ResourceScope.all(),
                                    RedstoneCondition.IGNORE,
                                    preset,
                                    FilterMode.WHITELIST,
                                    java.util.Map.of(),
                                    0),
                            java.util.Map.of()),
                    WorkingFaces.attachedFace(),
                    true);
            f.sync();
            f.start();
            f.tick(0);
            var handler = helper.getLevel()
                    .getCapability(
                            net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
                            f.pos(2),
                            Direction.DOWN);
            var iron = new ItemStack(Items.IRON_INGOT, 16);
            var ledger = f.repository.domainStorage(f.network).activatedLedger().orElseThrow();
            long revision = ledger.revision();
            owner.setDirty(false);
            f.data.setDirty(false);
            for (int i = 0; i < 20; i++)
                helper.assertTrue(
                        handler.insertItem(0, iron, true).getCount() == 16, "Simulation advanced unready filter work");
            helper.assertTrue(
                    ledger.revision() == revision && !owner.isDirty() && !f.data.isDirty(),
                    "Simulation dirtied authority");
            for (int tick = 1; tick < 300; tick++) f.tick(tick);
            helper.assertTrue(
                    handler.insertItem(0, iron, false).getCount() == 16 && ledger.revision() == revision,
                    "Over-budget candidate was partially committed");
            owner.putPreset(
                    new io.github.loongin.omniresonance.filter.ItemFilterPreset(
                            preset,
                            new ManagedName("Delivery"),
                            1,
                            java.util.Set.of(net.minecraft.resources.ResourceLocation.parse("minecraft:iron_ingot"))),
                    owner.presetLibraryRevision(),
                    -1,
                    -1);
            f.runtime.ownerLibraryChanged(f.owner);
            helper.assertTrue(
                    handler.insertItem(0, iron, true).getCount() == 16, "Stale filter accepted before preparation");
            for (int tick = 300; tick < 304; tick++) f.tick(tick);
            helper.assertTrue(
                    handler.insertItem(0, new ItemStack(Items.GOLD_INGOT, 16), false)
                                    .getCount()
                            == 16,
                    "Filter admitted forbidden resource");
            helper.assertTrue(
                    handler.insertItem(0, iron, true).isEmpty() && ledger.revision() == revision,
                    "Prepared matching rejected or dirtied a valid resource");
            helper.assertTrue(
                    handler.insertItem(0, iron, false).isEmpty()
                            && ledger.amount(ItemVariant.from(
                                                    iron, helper.getLevel().registryAccess())
                                            .key())
                                    == 16,
                    "Prepared valid delivery failed");
        }
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void domainInputAcceptsExternalItemsWithoutExposingItsInventory(GameTestHelper helper)
            throws Exception {
        try (var f = new Fixture(helper, io.github.loongin.omniresonance.bootstrap.ResourceAdapters.create())) {
            UUID id = f.node(2, TransferDirection.INPUT);
            var n = f.data.findNode(id).orElseThrow();
            n = f.data.setNodeMode(id, n.revision(), NodeMode.DOMAIN, true).orElseThrow();
            var policy = new ResourceTransferPolicy.Input(
                    100,
                    ResourceScope.all(),
                    RedstoneCondition.IGNORE,
                    null,
                    io.github.loongin.omniresonance.filter.FilterMode.WHITELIST,
                    java.util.Map.of(
                            ResourceTypes.ITEM,
                            new ResourceTransferPolicy.InputOverride(1, ResourceTransferPolicy.BatchMode.EXACT, 64)),
                    100);
            f.data.saveDomainConfiguration(
                    id,
                    n.revision(),
                    new StoredResourcePolicy(policy, java.util.Map.of()),
                    WorkingFaces.attachedFace(),
                    true);
            f.sync();
            f.start();
            f.tick(0);
            var handler = helper.getLevel()
                    .getCapability(
                            net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
                            f.pos(2),
                            Direction.DOWN);
            helper.assertTrue(handler != null && handler.getSlots() > 0, "Input node has no external delivery slot");
            var stack = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_INGOT, 32);
            var key =
                    ItemVariant.from(stack, helper.getLevel().registryAccess()).key();
            var ledger = f.repository.domainStorage(f.network).activatedLedger().orElseThrow();
            long revision = ledger.revision();
            helper.assertTrue(
                    handler.insertItem(0, stack, true).isEmpty()
                            && ledger.amount(key) == 0
                            && ledger.revision() == revision,
                    "Delivery simulation mutated storage or applied active quota");
            helper.assertTrue(
                    handler.insertItem(0, stack, false).isEmpty() && stack.getCount() == 32 && ledger.amount(key) == 32,
                    "Delivery failed to conserve items independently of active batch/rate/retention");
            helper.assertTrue(
                    handler.getStackInSlot(0).isEmpty()
                            && handler.extractItem(0, 64, false).isEmpty()
                            && ledger.amount(key) == 32,
                    "External handler exposed domain inventory");
            io.github.loongin.omniresonance.transfer.ExternalResourceDeliveryChecks.verify(helper, f.pos(2), ledger);
            var physical = (ResonanceNodeBlockEntity) helper.getLevel().getBlockEntity(f.pos(2));
            var restored = new CompoundTag();
            NodePersistentState.linked(id).writeOwnedFields(restored);
            physical.loadCustomOnly(restored, helper.getLevel().registryAccess());
            helper.assertTrue(
                    handler.insertItem(0, stack, false).getCount() == 32,
                    "Old delivery lease survived identity reload");
            f.runtime.nodeChanged(id);
            f.tick(1);
            var oldHandler = handler;
            handler = helper.getLevel()
                    .getCapability(
                            net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
                            f.pos(2),
                            Direction.DOWN);
            helper.assertTrue(
                    handler != null
                            && handler.insertItem(0, stack, true).isEmpty()
                            && oldHandler.insertItem(0, stack, true).getCount() == 32,
                    "New lifecycle revived an old external capability");
            n = f.data.findNode(id).orElseThrow();
            f.data.setNodeEnabled(id, n.revision(), false);
            f.sync();
            helper.assertTrue(
                    handler.insertItem(0, stack, true).getCount() == 32
                            && handler.insertItem(0, stack, false).getCount() == 32
                            && ledger.amount(key) == 32,
                    "Stale external handler bypassed disabled authority before reconciliation");
            n = f.data.findNode(id).orElseThrow();
            n = f.data.setNodeEnabled(id, n.revision(), true).orElseThrow();
            f.data.saveDomainConfiguration(
                    id,
                    n.revision(),
                    new StoredResourcePolicy(
                            new ResourceTransferPolicy.Input(
                                    1,
                                    ResourceScope.all(),
                                    RedstoneCondition.SIGNAL,
                                    null,
                                    FilterMode.WHITELIST,
                                    java.util.Map.of(),
                                    0),
                            java.util.Map.of()),
                    WorkingFaces.attachedFace(),
                    true);
            f.sync();
            f.runtime.networkChanged(f.network);
            f.tick(1);
            var redstoneHandler = helper.getLevel()
                    .getCapability(
                            net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
                            f.pos(2),
                            Direction.DOWN);
            helper.assertTrue(
                    redstoneHandler.insertItem(0, stack, true).getCount() == 32,
                    "Inactive redstone gate accepted input");
            helper.getLevel().setBlockAndUpdate(f.pos(2).east(), Blocks.REDSTONE_BLOCK.defaultBlockState());
            try {
                helper.assertTrue(
                        redstoneHandler.insertItem(0, stack, true).isEmpty() && ledger.amount(key) == 32,
                        "Live redstone gate was ignored or simulation mutated storage");
            } finally {
                helper.getLevel().setBlockAndUpdate(f.pos(2).east(), Blocks.AIR.defaultBlockState());
            }
            n = f.data.findNode(id).orElseThrow();
            f.data.saveDomainConfiguration(
                    id,
                    n.revision(),
                    new StoredResourcePolicy(
                            ResourceTransferPolicy.defaults(TransferDirection.OUTPUT), java.util.Map.of()),
                    WorkingFaces.attachedFace(),
                    true);
            f.sync();
            helper.assertTrue(
                    redstoneHandler.insertItem(0, stack, false).getCount() == 32 && ledger.amount(key) == 32,
                    "Stale input lease accepted after direction changed to output");
        }
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void connectionMarkersFollowConfiguredFacesWithoutExposingInventory(GameTestHelper helper)
            throws Exception {
        try (var f = new Fixture(helper)) {
            UUID node = f.node(2, TransferDirection.OUTPUT);
            f.start();
            f.tick(0);
            var level = helper.getLevel();
            var pos = f.pos(2);
            var items = level.getCapability(
                    net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, pos, Direction.DOWN);
            helper.assertTrue(
                    items != null && items.getSlots() == 0, "Configured item face has no empty connection marker");
            helper.assertTrue(
                    level.getCapability(
                                    net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
                                    pos,
                                    Direction.UP)
                            == null,
                    "Unselected face exposed an interface");
            helper.assertTrue(
                    level.getCapability(
                                    net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK,
                                    pos,
                                    Direction.DOWN)
                            == null,
                    "Item-only policy exposed energy");
            var current = f.data.findNode(node).orElseThrow();
            f.data.setNodeEnabled(node, current.revision(), false);
            f.sync();
            f.runtime.networkChanged(f.network);
            f.tick(1);
            helper.assertTrue(
                    level.getCapability(
                                    net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
                                    pos,
                                    Direction.DOWN)
                            == null,
                    "Disabled node kept its connection marker");
            current = f.data.findNode(node).orElseThrow();
            current = f.data.setNodeEnabled(node, current.revision(), true).orElseThrow();
            current = f.data.setNodeMode(node, current.revision(), NodeMode.DOMAIN, true)
                    .orElseThrow();
            var policy = new ResourceTransferPolicy.Input(
                    1,
                    ResourceScope.customSet(java.util.Set.of(ResourceTypes.ENERGY)),
                    RedstoneCondition.IGNORE,
                    null,
                    io.github.loongin.omniresonance.filter.FilterMode.WHITELIST,
                    java.util.Map.of(),
                    0);
            f.data.saveDomainConfiguration(
                    node,
                    current.revision(),
                    new StoredResourcePolicy(policy, java.util.Map.of()),
                    WorkingFaces.explicit(1 << Direction.EAST.get3DDataValue()),
                    true);
            f.sync();
            f.runtime.networkChanged(f.network);
            f.tick(2);
            helper.assertTrue(
                    level.getCapability(
                                    net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK,
                                    pos,
                                    Direction.EAST)
                            != null,
                    "Domain energy face did not publish its connection marker");
            helper.assertTrue(
                    level.getCapability(
                                    net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
                                    pos,
                                    Direction.DOWN)
                            == null,
                    "Domain mode switch retained a stale item marker");
            var entity = (ResonanceNodeBlockEntity) level.getBlockEntity(pos);
            helper.assertTrue(!entity.pipeConnection(Direction.EAST, 3), "Energy-only configuration exposed chemicals");
            current = f.data.findNode(node).orElseThrow();
            var chemical = new ResourceTransferPolicy.Input(
                    1,
                    ResourceScope.customSet(java.util.Set.of(ResourceTypes.CHEMICAL)),
                    RedstoneCondition.IGNORE,
                    null,
                    io.github.loongin.omniresonance.filter.FilterMode.WHITELIST,
                    java.util.Map.of(),
                    0);
            f.data.saveDomainConfiguration(
                    node,
                    current.revision(),
                    new StoredResourcePolicy(chemical, java.util.Map.of()),
                    WorkingFaces.explicit(1 << Direction.UP.get3DDataValue()),
                    true);
            f.sync();
            f.runtime.networkChanged(f.network);
            f.tick(3);
            helper.assertTrue(
                    entity.pipeConnection(Direction.UP, 3)
                            && !entity.pipeConnection(Direction.EAST, 3)
                            && !entity.pipeConnection(Direction.EAST, 2),
                    "Chemical-only configuration did not replace the previous resource and face selection");
            f.runtime.close();
            helper.assertTrue(
                    !entity.pipeConnection(Direction.UP, 3), "Closed runtime kept chemical connection metadata");
            helper.assertTrue(
                    level.getCapability(
                                    net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK,
                                    pos,
                                    Direction.EAST)
                            == null,
                    "Closing runtime retained connection metadata");
        }
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void loadedNodeDoesNotLoadUnselectedOrSelectedDistantChunk(GameTestHelper helper) throws Exception {
        BlockPos pos = new BlockPos(29999007, 80, 29999000);
        var level = helper.getLevel();
        level.getChunk(pos);
        level.setBlock(
                pos,
                ModBlocks.RESONANCE_TRANSFER_NODE
                        .get()
                        .defaultBlockState()
                        .setValue(AbstractResonanceNodeBlock.FACING, Direction.EAST),
                18);
        try {
            var entity = (ResonanceNodeBlockEntity) level.getBlockEntity(pos);
            UUID id = entity.state().orElseThrow().nodeId();
            CompoundTag tag = new CompoundTag();
            NodePersistentState.linked(id).writeOwnedFields(tag);
            entity.loadCustomOnly(tag, level.registryAccess());
            NetworkNodeRecord node = NetworkNodeRecord.fresh(
                    id,
                    1,
                    new ManagedName("Boundary"),
                    GlobalPos.of(level.dimension(), pos),
                    NodeForm.BLOCK,
                    Direction.EAST);
            helper.assertTrue(!level.isLoaded(pos.east()), "Fixture adjacent target already loaded");
            try (ItemEndpointCache cache = new ItemEndpointCache(level.getServer(), ignored -> {})) {
                TransferWorkBudget budget = new TransferWorkBudget(100, 1000, 1000, () -> 0);
                helper.assertTrue(
                        cache.physical(node), "Node validation incorrectly depends on attached target loading");
                helper.assertTrue(
                        cache.resolve(node, Direction.EAST, budget) == null && budget.calls() == 0,
                        "Unloaded selected target discovered capability");
                helper.assertTrue(!level.isLoaded(pos.east()), "Selected target lookup loaded a chunk");
                cache.resolve(node, Direction.WEST, budget);
                helper.assertTrue(budget.calls() == 1, "Loaded alternate face blocked by unloaded attachment");
                helper.assertTrue(!level.isLoaded(pos.east()), "Alternate lookup loaded unselected chunk");
            }
        } finally {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 18);
        }
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void explicitFacesUseSeparateCachesAndOneQuota(GameTestHelper helper) throws Exception {
        try (Fixture f = new Fixture(helper)) {
            UUID in = f.node(1, TransferDirection.INPUT), out = f.node(7, TransferDirection.OUTPUT);
            var inputPolicy =
                    new ItemTransferPolicy.Input(20, 64, RedstoneCondition.IGNORE, null, FilterMode.WHITELIST, 16);
            var outputPolicy =
                    new ItemTransferPolicy.Output(20, 64, RedstoneCondition.IGNORE, null, FilterMode.WHITELIST, 0);
            var n = f.data.findNode(in).orElseThrow();
            f.data.setDirectBinding(in, n.revision(), f.channel, inputPolicy, WorkingFaces.explicit(0), false, -1);
            f.sync();
            f.start();
            f.tick(0);
            helper.assertTrue(f.runtime.cachedEndpoints() == 0, "Empty selection discovered capability");
            for (int x : new int[] {1, 7}) {
                for (Direction face : new Direction[] {Direction.WEST, Direction.EAST}) {
                    BlockPos target = f.pos(x).relative(face);
                    f.positions.add(target);
                    helper.getLevel().setBlock(target, Blocks.CHEST.defaultBlockState(), 3);
                    ChestBlockEntity chest =
                            (ChestBlockEntity) helper.getLevel().getBlockEntity(target);
                    if (x == 1) chest.setItem(0, new ItemStack(Items.IRON_INGOT, 48));
                    else {
                        for (int slot = 0; slot < chest.getContainerSize(); slot++)
                            chest.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
                        chest.setItem(0, new ItemStack(Items.IRON_INGOT, 32));
                    }
                }
            }
            f.chest(1).setItem(0, new ItemStack(Items.IRON_INGOT, 64));
            n = f.data.findNode(in).orElseThrow();
            f.data.setDirectBinding(in, n.revision(), f.channel, inputPolicy, WorkingFaces.explicit(48), false, -1);
            n = f.data.findNode(out).orElseThrow();
            f.data.setDirectBinding(out, n.revision(), f.channel, outputPolicy, WorkingFaces.explicit(48), false, -1);
            f.sync();
            f.tick(20);
            for (Direction face : new Direction[] {Direction.WEST, Direction.EAST}) {
                ChestBlockEntity source = (ChestBlockEntity)
                        helper.getLevel().getBlockEntity(f.pos(1).relative(face));
                ChestBlockEntity target = (ChestBlockEntity)
                        helper.getLevel().getBlockEntity(f.pos(7).relative(face));
                helper.assertTrue(source.getItem(0).getCount() == 16, "Each selected source must retain sixteen");
                helper.assertTrue(target.getItem(0).getCount() == 64, "Output faces did not share quota");
            }
            helper.assertTrue(f.chest(1).getItem(0).getCount() == 64, "Unselected attached source was touched");
            helper.assertTrue(f.runtime.cachedEndpoints() == 4, "Selected endpoints were not cached separately");
        }
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void savedDefaultBindingsRunAndStopCleanly(GameTestHelper helper) throws Exception {
        try (Fixture f = new Fixture(helper)) {
            UUID in = f.node(1, TransferDirection.INPUT), out = f.node(4, TransferDirection.OUTPUT);
            f.chest(1).setItem(0, new ItemStack(Items.IRON_INGOT, 64));
            f.start();
            for (int tick = 0; tick < 20; tick++) f.tick(tick);
            helper.assertTrue(f.chest(1).getItem(0).isEmpty(), "Saved default input did not extract");
            helper.assertTrue(f.chest(4).getItem(0).getCount() == 64, "Runtime did not route saved default binding");
            helper.assertTrue(
                    f.runtime.cachedEndpoints() == 2, "Runtime did not retain exactly source and target handles");
            f.runtime.close();
            helper.assertTrue(f.runtime.cachedEndpoints() == 0, "Runtime stop retained capability handles");
        }
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void sharedQuotaRetentionAndChannelSeparation(GameTestHelper helper) throws Exception {
        try (Fixture f = new Fixture(helper)) {
            UUID a = f.node(1, TransferDirection.INPUT),
                    b = f.node(4, TransferDirection.INPUT),
                    out = f.node(7, TransferDirection.OUTPUT);
            f.policy(a, new ItemTransferPolicy.Input(20, 64, RedstoneCondition.IGNORE, null, FilterMode.WHITELIST, 16));
            f.policy(
                    out,
                    new ItemTransferPolicy.Output(20, 64, RedstoneCondition.IGNORE, null, FilterMode.WHITELIST, 0));
            f.chest(1).setItem(0, new ItemStack(Items.IRON_INGOT, 48));
            f.chest(4).setItem(0, new ItemStack(Items.IRON_INGOT, 32));
            f.start();
            f.tick(100);
            helper.assertTrue(f.chest(1).getItem(0).getCount() == 16, "Retention was violated");
            helper.assertTrue(f.chest(7).getItem(0).getCount() == 64, "Inputs did not share the output quota");
            var stats = f.runtime.telemetrySnapshot(f.data.metadata().id());
            helper.assertTrue(
                    stats.tick() == 100
                            && stats.calls() > 0
                            && stats.moved().stream()
                                    .anyMatch(value -> value.type().equals(ResourceTypes.ITEM) && value.amount() == 64),
                    "Real direct transfers did not publish telemetry");
            helper.assertTrue(
                    stats.equals(f.runtime.telemetrySnapshot(f.data.metadata().id())),
                    "Reading telemetry changed its counters");
            f.chest(4).setItem(0, new ItemStack(Items.IRON_INGOT, 32));
            f.runtime.nodeChanged(b);
            f.tick(105);
            helper.assertTrue(f.chest(7).getItem(0).getCount() == 64, "Wake granted duplicate output quota");
            f.tick(120);
            helper.assertTrue(f.chest(7).getItem(1).getCount() == 32, "Expired output quota did not renew");
        }
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void redstoneNodeAndTunnelGatesApplyBeforeCapability(GameTestHelper helper) throws Exception {
        try (Fixture f = new Fixture(helper)) {
            UUID in = f.node(1, TransferDirection.INPUT);
            f.node(4, TransferDirection.OUTPUT);
            f.policy(in, new ItemTransferPolicy.Input(1, 64, RedstoneCondition.SIGNAL, null, FilterMode.WHITELIST, 0));
            f.chest(1).setItem(0, new ItemStack(Items.IRON_INGOT, 64));
            f.start();
            f.tick(0);
            helper.assertTrue(f.runtime.cachedEndpoints() == 0, "Redstone refusal discovered capability");
            helper.getLevel().setBlock(f.pos(1).above(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
            f.runtime.nodeChanged(in);
            f.tick(1);
            helper.assertTrue(f.chest(4).getItem(0).getCount() == 64, "Signal did not enable transfer");
            f.chest(1).setItem(0, new ItemStack(Items.IRON_INGOT, 64));
            NetworkNodeRecord n = f.data.findNode(in).orElseThrow();
            f.data.setNodeEnabled(in, n.revision(), false);
            f.sync();
            f.tick(2);
            helper.assertTrue(f.chest(1).getItem(0).getCount() == 64, "Disabled node transferred");
            n = f.data.findNode(in).orElseThrow();
            f.data.setNodeEnabled(in, n.revision(), true);
            f.sync();
            var tunnel = f.data.findTunnel(f.tunnel).orElseThrow();
            f.data.setTunnelEnabled(f.tunnel, tunnel.revision(), false);
            f.tick(3);
            helper.assertTrue(f.chest(1).getItem(0).getCount() == 64, "Disabled tunnel transferred");
        }
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void onlyAttachedFurnaceSideIsQueried(GameTestHelper helper) throws Exception {
        try (Fixture f = new Fixture(helper)) {
            f.node(1, TransferDirection.INPUT);
            UUID out = f.node(4, TransferDirection.OUTPUT);
            f.chest(1).setItem(0, new ItemStack(Items.IRON_ORE, 64));
            BlockPos p = f.pos(4);
            helper.getLevel().setBlock(p.above(), Blocks.FURNACE.defaultBlockState(), 3);
            helper.getLevel()
                    .setBlock(
                            p,
                            helper.getLevel()
                                    .getBlockState(p)
                                    .setValue(AbstractResonanceNodeBlock.FACING, Direction.UP),
                            3);
            f.data.updateNodePhysicalSnapshot(
                    out, GlobalPos.of(helper.getLevel().dimension(), p), NodeForm.BLOCK, Direction.UP);
            f.sync();
            f.start();
            f.tick(0);
            helper.assertTrue(
                    f.chest(1).getItem(0).getCount() == 64,
                    "Bottom-only furnace endpoint scanned an unexposed insertion face");
            helper.getLevel().setBlock(p.above(), Blocks.AIR.defaultBlockState(), 3);
            helper.getLevel().setBlock(p.below(), Blocks.FURNACE.defaultBlockState(), 3);
            helper.getLevel()
                    .setBlock(
                            p,
                            helper.getLevel()
                                    .getBlockState(p)
                                    .setValue(AbstractResonanceNodeBlock.FACING, Direction.DOWN),
                            3);
            f.data.updateNodePhysicalSnapshot(
                    out, GlobalPos.of(helper.getLevel().dimension(), p), NodeForm.BLOCK, Direction.DOWN);
            f.sync();
            f.tick(1);
            var furnace = (net.minecraft.world.level.block.entity.FurnaceBlockEntity)
                    helper.getLevel().getBlockEntity(p.below());
            helper.assertTrue(furnace.getItem(0).getCount() == 64, "Top furnace input face did not accept items");
        }
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void missingPhysicalIdentityAndUnloadedTargetNeverDiscover(GameTestHelper helper) throws Exception {
        try (Fixture f = new Fixture(helper)) {
            UUID in = f.node(1, TransferDirection.INPUT);
            f.node(4, TransferDirection.OUTPUT);
            f.chest(1).setItem(0, new ItemStack(Items.IRON_INGOT, 64));
            f.start();
            ResonanceNodeBlockEntity entity =
                    (ResonanceNodeBlockEntity) helper.getLevel().getBlockEntity(f.pos(1));
            CompoundTag replacement = new CompoundTag();
            NodePersistentState.linked(UUID.randomUUID()).writeOwnedFields(replacement);
            entity.loadCustomOnly(replacement, helper.getLevel().registryAccess());
            f.tick(0);
            helper.assertTrue(f.runtime.cachedEndpoints() == 0, "Mismatched physical UUID discovered capability");
            helper.assertTrue(f.chest(1).getItem(0).getCount() == 64, "Mismatched source extracted");
            // Keep this non-loading probe separate from the far-chunk hydration fixture.
            BlockPos unloaded = new BlockPos(-29998000, 80, 29998000);
            helper.assertTrue(!helper.getLevel().isLoaded(unloaded), "Fixture far target was already loaded");
            NetworkNodeRecord distant = NetworkNodeRecord.fresh(
                    UUID.randomUUID(),
                    1,
                    new ManagedName("Unloaded"),
                    GlobalPos.of(helper.getLevel().dimension(), unloaded),
                    NodeForm.BLOCK,
                    Direction.EAST);
            try (ItemEndpointCache cache =
                    new ItemEndpointCache(helper.getLevel().getServer(), id -> {})) {
                TransferWorkBudget budget = new TransferWorkBudget(1, 100, 100, () -> 0);
                helper.assertTrue(
                        cache.resolve(distant, budget) == null && budget.calls() == 0,
                        "Unloaded endpoint discovered a capability");
            }
            helper.assertTrue(!helper.getLevel().isLoaded(unloaded), "Endpoint lookup loaded a chunk");
        }
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void capabilityInvalidationAndUnloadRetireOldHandles(GameTestHelper helper) throws Exception {
        try (Fixture f = new Fixture(helper)) {
            UUID in = f.node(1, TransferDirection.INPUT);
            NetworkNodeRecord node = f.data.findNode(in).orElseThrow();
            java.util.concurrent.atomic.AtomicInteger wakes = new java.util.concurrent.atomic.AtomicInteger();
            try (ItemEndpointCache cache =
                    new ItemEndpointCache(helper.getLevel().getServer(), id -> wakes.incrementAndGet())) {
                TransferWorkBudget budget = new TransferWorkBudget(100, 1000, 1000, () -> 0);
                var old = cache.resolve(node, budget);
                helper.assertTrue(old != null && old.valid(), "Loaded capability missing");
                helper.getLevel().invalidateCapabilities(f.pos(1).below());
                helper.assertTrue(wakes.get() == 1 && !old.valid(), "Invalidation did not retire exact handle");
                var renewed = cache.resolve(node, budget);
                helper.assertTrue(renewed != null && renewed.valid(), "Capability did not rediscover after callback");
                cache.unload(helper.getLevel().dimension(), new net.minecraft.world.level.ChunkPos(f.pos(1)));
                helper.assertTrue(cache.size() == 0 && !renewed.valid(), "Chunk unload retained live handler");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void channelIsolationAndOwnerLibraryCreationInvalidateMissingPreset(GameTestHelper helper)
            throws Exception {
        try (Fixture f = new Fixture(helper)) {
            UUID in = f.node(1, TransferDirection.INPUT), out = f.node(4, TransferDirection.OUTPUT);
            UUID other = UUID.randomUUID(), presetId = UUID.randomUUID();
            var tunnel = f.data.findTunnel(f.tunnel).orElseThrow();
            f.data.createChannel(f.tunnel, tunnel.revision(), other, new ManagedName("Other"), -1);
            NetworkNodeRecord n = f.data.findNode(out).orElseThrow();
            f.data.removeDirectBinding(out, n.revision(), f.channel);
            n = f.data.findNode(out).orElseThrow();
            f.data.setDirectBinding(
                    out,
                    n.revision(),
                    other,
                    ItemTransferPolicy.defaults(TransferDirection.OUTPUT),
                    WorkingFaces.attachedFace(),
                    false,
                    -1);
            f.sync();
            f.chest(1).setItem(0, new ItemStack(Items.IRON_INGOT, 64));
            f.start();
            f.tick(0);
            helper.assertTrue(f.chest(4).getItem(0).isEmpty(), "Different channel received items");
            n = f.data.findNode(out).orElseThrow();
            f.data.removeDirectBinding(out, n.revision(), other);
            n = f.data.findNode(out).orElseThrow();
            f.data.setDirectBinding(
                    out,
                    n.revision(),
                    f.channel,
                    ItemTransferPolicy.defaults(TransferDirection.OUTPUT),
                    WorkingFaces.attachedFace(),
                    false,
                    -1);
            f.sync();
            f.policy(
                    in,
                    new ItemTransferPolicy.Input(1, 64, RedstoneCondition.IGNORE, presetId, FilterMode.WHITELIST, 0));
            f.tick(1);
            helper.assertTrue(f.chest(4).getItem(0).isEmpty(), "Missing preset was ignored");
            var owner = f.repository.createOwner(f.owner, null);
            owner.putPreset(
                    new io.github.loongin.omniresonance.filter.ItemFilterPreset(
                            presetId,
                            new ManagedName("Iron"),
                            0,
                            Set.of(net.minecraft.resources.ResourceLocation.parse("minecraft:iron_ingot"))),
                    0,
                    -1,
                    -1);
            f.runtime.ownerLibraryChanged(f.owner);
            f.tick(2);
            helper.assertTrue(
                    f.chest(4).getItem(0).getCount() == 64, "Created owner library remained negatively cached");
            owner.removePreset(presetId, owner.presetLibraryRevision());
            f.chest(1).setItem(0, new ItemStack(Items.IRON_INGOT, 64));
            f.runtime.ownerLibraryChanged(f.owner);
            f.tick(3);
            helper.assertTrue(f.chest(1).getItem(0).getCount() == 64, "Deleted preset object remained allowed");
        }
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void knownRemainderSurvivesNativeSaveAndReload(GameTestHelper helper) throws Exception {
        for (boolean domainAvailable : new boolean[] {true, false}) {
            try (Fixture f = new Fixture(helper)) {
                if (!domainAvailable) f.data.markStorageBuckets(1L);
                var source = new io.github.loongin.omniresonance.transfer.fixtures.FakeItemHandler(1);
                var target = new io.github.loongin.omniresonance.transfer.fixtures.FakeItemHandler(1);
                source.stacks[0] = new ItemStack(Items.IRON_INGOT, 64);
                source.refuseReturn = true;
                target.actualInsertLimit = 60;
                f.node(1, TransferDirection.INPUT);
                f.node(4, TransferDirection.OUTPUT);
                var nativeSource =
                        ResourceEndpointGameTests.place(helper, f.pos(1).below());
                var nativeTarget =
                        ResourceEndpointGameTests.place(helper, f.pos(4).below());
                nativeSource.items = source;
                nativeTarget.items = target;
                ItemVariant variant =
                        ItemVariant.from(source.stacks[0], helper.getLevel().registryAccess());
                f.start();
                f.tick(0);
                helper.assertTrue(
                        nativeSource.itemCalls == 1 && nativeTarget.itemCalls == 1,
                        "Recovery did not use native resource runtime endpoints");
                helper.assertTrue(
                        nativeSource.fluidCalls == 0 && nativeSource.energyCalls == 0,
                        "Legacy binding was incorrectly published as ALL");
                helper.assertTrue(target.stacks[0].getCount() == 60, "Unavailable domain stopped direct transfer");
                helper.assertTrue(
                        f.data.recovery().amount(variant.key()) == (domainAvailable ? 0 : 4),
                        "Known remainder did not follow original domain then recovery order");
                if (domainAvailable)
                    helper.assertTrue(
                            f.repository
                                            .domainStorage(f.network)
                                            .activate()
                                            .orElseThrow()
                                            .amount(variant.key())
                                    == 4,
                            "Known remainder was not placed in the original domain");
                f.storage.save();
                net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
                SavedNetworkRepository reloaded = new SavedNetworkRepository(
                        new DimensionDataStorage(
                                f.path.toFile(),
                                DataFixers.getDataFixer(),
                                helper.getLevel().registryAccess()),
                        f.path);
                reloaded.loadNetworkData();
                helper.assertTrue(
                        reloaded.findLoadedNetwork(f.network)
                                        .orElseThrow()
                                        .recovery()
                                        .amount(variant.key())
                                == (domainAvailable ? 0 : 4),
                        "Native reload lost actual buffered remainder");
                if (domainAvailable)
                    helper.assertTrue(
                            reloaded.domainStorage(f.network)
                                            .activate()
                                            .orElseThrow()
                                            .amount(variant.key())
                                    == 4,
                            "Native bucket reload lost known unreturned remainder");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void resourceRuntimeInvalidationRemovalAndUnselectedOwnerCleanup(GameTestHelper helper)
            throws Exception {
        try (Fixture f = new Fixture(helper)) {
            UUID in = f.node(1, TransferDirection.INPUT), out = f.node(4, TransferDirection.OUTPUT);
            var source = ResourceEndpointGameTests.place(helper, f.pos(1).below());
            var target = ResourceEndpointGameTests.place(helper, f.pos(4).below());
            var items = new net.neoforged.neoforge.items.ItemStackHandler(1);
            items.setStackInSlot(0, new ItemStack(Items.IRON_INGOT, 64));
            source.items = items;
            f.start();
            f.tick(0);
            helper.assertTrue(target.items.getStackInSlot(0).getCount() == 64, "New runtime did not transfer");
            helper.assertTrue(
                    f.runtime.cachedFilterOwners() == 0 && f.runtime.cachedFilterRoots() == 0,
                    "No-preset bindings loaded an owner library");
            helper.assertTrue(
                    source.itemCalls == 1 && target.itemCalls == 1 && source.fluidCalls == 0 && source.energyCalls == 0,
                    "Legacy type selection discovered unrelated capabilities");
            items.setStackInSlot(0, new ItemStack(Items.IRON_INGOT, 64));
            ((net.neoforged.neoforge.items.ItemStackHandler) target.items).setStackInSlot(0, ItemStack.EMPTY);
            helper.getLevel().invalidateCapabilities(f.pos(1).below());
            helper.getLevel().invalidateCapabilities(f.pos(1).below());
            helper.assertTrue(source.itemCalls == 1, "Native callback reentered discovery");
            f.tick(20);
            helper.assertTrue(
                    source.itemCalls == 2
                            && target.itemCalls == 1
                            && target.items.getStackInSlot(0).getCount() == 64,
                    "Server tick did not drain invalidation with endpoint reuse");
            f.data.removeNode(in, f.data.findNode(in).orElseThrow().position());
            f.runtime.nodeChanged(in);
            f.tick(40);
            helper.assertTrue(f.runtime.cachedEndpoints() == 0, "Node removal retained old endpoint indices");
            f.runtime.close();
            helper.getLevel().invalidateCapabilities(f.pos(1).below());
            helper.assertTrue(
                    f.runtime.cachedEndpoints() == 0
                            && f.runtime.cachedFilterRoots() == 0
                            && f.runtime.cachedFilterOwners() == 0
                            && f.runtime.cachedFilterTags() == 0,
                    "Closed runtime retained native or filter state");
        }
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void resourceRuntimeSharedPresetRetiresAfterFinalBinding(GameTestHelper helper) throws Exception {
        try (Fixture f = new Fixture(helper)) {
            UUID in = f.node(1, TransferDirection.INPUT), out = f.node(4, TransferDirection.OUTPUT);
            UUID presetId = new UUID(40, 1);
            var owner = f.repository.createOwner(f.owner, null);
            owner.putPreset(
                    new io.github.loongin.omniresonance.filter.ItemFilterPreset(
                            presetId,
                            new ManagedName("Shared iron"),
                            0,
                            Set.of(net.minecraft.resources.ResourceLocation.parse("minecraft:iron_ingot"))),
                    0,
                    -1,
                    -1);
            f.policy(
                    in,
                    new ItemTransferPolicy.Input(20, 64, RedstoneCondition.IGNORE, presetId, FilterMode.WHITELIST, 0));
            f.policy(
                    out,
                    new ItemTransferPolicy.Output(20, 64, RedstoneCondition.IGNORE, presetId, FilterMode.WHITELIST, 0));
            f.chest(1).setItem(0, new ItemStack(Items.IRON_INGOT, 64));
            f.start();
            f.tick(0);
            helper.assertTrue(
                    f.chest(4).getItem(0).getCount() == 64
                            && f.runtime.cachedFilterRoots() == 1
                            && f.runtime.cachedFilterOwners() == 1,
                    "Bindings did not share a single prepared owner/root");
            f.data.removeDirectBinding(in, f.data.findNode(in).orElseThrow().revision(), f.channel);
            f.sync();
            f.tick(20);
            helper.assertTrue(f.runtime.cachedFilterRoots() == 1, "First removal retired still-referenced root");
            f.data.removeDirectBinding(out, f.data.findNode(out).orElseThrow().revision(), f.channel);
            f.sync();
            f.tick(21);
            helper.assertTrue(
                    f.runtime.cachedFilterRoots() == 0 && f.runtime.cachedFilterOwners() == 0,
                    "Last binding retained root or owner");
        }
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void authoritativeAllPolicyTransfersAllThreeNativeTypes(GameTestHelper helper) throws Exception {
        try (Fixture f = new Fixture(helper)) {
            UUID input = f.node(1, TransferDirection.INPUT), output = f.node(4, TransferDirection.OUTPUT);
            var source = ResourceEndpointGameTests.place(helper, f.pos(1).below());
            var target = ResourceEndpointGameTests.place(helper, f.pos(4).below());
            source.items.insertItem(0, new ItemStack(Items.IRON_INGOT, 64), false);
            source.fluid.fill(
                    new net.neoforged.neoforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER, 2000),
                    net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
            source.energy.receiveEnergy(20000, false);
            for (UUID id : List.of(input, output)) {
                var node = f.data.findNode(id).orElseThrow();
                var policy = ResourceTransferPolicy.defaults(
                        id.equals(input) ? TransferDirection.INPUT : TransferDirection.OUTPUT);
                f.data.setDirectBinding(
                        id,
                        node.revision(),
                        f.channel,
                        new StoredResourcePolicy(policy, java.util.Map.of()),
                        WorkingFaces.explicit(1),
                        false,
                        -1);
            }
            f.sync();
            f.start();
            for (int tick = 0; tick < 10; tick++) f.tick(tick);
            helper.assertTrue(
                    target.items.getStackInSlot(0).getCount() == 64
                            && target.fluid.getFluidAmount() == 2000
                            && target.energy.getEnergyStored() == 20000,
                    "ALL policy did not drive every native type");
            helper.assertTrue(
                    source.items.getStackInSlot(0).isEmpty()
                            && source.fluid.isEmpty()
                            && source.energy.getEnergyStored() == 0,
                    "ALL transfer did not conserve source amounts");
            CompoundTag saved = f.data.save(new CompoundTag(), helper.getLevel().registryAccess());
            var restored = NetworkSavedData.load(f.network, saved, f.repository.registeredResourceTypes());
            helper.assertTrue(
                    restored.findDirectBinding(input, f.channel)
                                    .orElseThrow()
                                    .policy()
                                    .scope()
                                    .kind()
                            == ResourceScope.Kind.ALL,
                    "ALL scope changed on real shard reload");
        }
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void resourcePolicyKeepsIndependentExactRatesAndExcludedType(GameTestHelper helper) throws Exception {
        var directory = new ResourceAdapterDirectory(4);
        ResourceAdapterDirectory.registerNative(directory);
        var externalType = net.minecraft.resources.ResourceLocation.parse("addon:excluded");
        directory.register(
                new ResourceAdapterDirectory.Descriptor(externalType, "unit", 1),
                net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK,
                (handler, registries) -> {
                    throw new AssertionError("Excluded external adapter factory invoked");
                });
        directory.freeze();
        try (Fixture f = new Fixture(helper, directory)) {
            helper.assertTrue(
                    f.repository.resourceAdapters() == directory
                            && f.repository.registeredResourceTypes().contains(externalType),
                    "Runtime and repository did not share the frozen external registration");
            UUID input = f.node(1, TransferDirection.INPUT), output = f.node(4, TransferDirection.OUTPUT);
            var source = ResourceEndpointGameTests.place(helper, f.pos(1).below());
            var target = ResourceEndpointGameTests.place(helper, f.pos(4).below());
            source.items.insertItem(0, new ItemStack(Items.IRON_INGOT, 64), false);
            source.fluid.fill(
                    new net.neoforged.neoforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER, 2000),
                    net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
            source.energy.receiveEnergy(20000, false);
            var scope = ResourceScope.customSet(Set.of(ResourceTypes.ITEM, ResourceTypes.FLUID));
            var policy = new ResourceTransferPolicy.Input(
                    20,
                    scope,
                    RedstoneCondition.IGNORE,
                    null,
                    FilterMode.WHITELIST,
                    java.util.Map.of(
                            ResourceTypes.ITEM,
                            new ResourceTransferPolicy.InputOverride(32, ResourceTransferPolicy.BatchMode.EXACT, 16),
                            ResourceTypes.FLUID,
                            new ResourceTransferPolicy.InputOverride(
                                    1000, ResourceTransferPolicy.BatchMode.EXACT, 500)),
                    16);
            var node = f.data.findNode(input).orElseThrow();
            f.data.setDirectBinding(
                    input,
                    node.revision(),
                    f.channel,
                    new StoredResourcePolicy(policy, java.util.Map.of()),
                    WorkingFaces.explicit(1),
                    false,
                    -1);
            node = f.data.findNode(output).orElseThrow();
            f.data.setDirectBinding(
                    output,
                    node.revision(),
                    f.channel,
                    new StoredResourcePolicy(
                            ResourceTransferPolicy.defaults(TransferDirection.OUTPUT), java.util.Map.of()),
                    WorkingFaces.explicit(1),
                    false,
                    -1);
            f.sync();
            f.start();
            for (int tick = 0; tick < 10; tick++) f.tick(tick);
            helper.assertTrue(
                    target.items.getStackInSlot(0).getCount() == 32 && target.fluid.getFluidAmount() == 1000,
                    "Per-type exact rate windows were not independent");
            helper.assertTrue(
                    source.items.getStackInSlot(0).getCount() == 32
                            && source.fluid.getFluidAmount() == 1000
                            && source.energy.getEnergyStored() == 20000
                            && target.energy.getEnergyStored() == 0,
                    "Excluded energy or retained amounts changed");
            helper.assertTrue(source.energyCalls == 0, "Custom-excluded type discovered a capability");
        }
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void legacySavedItemBindingNeverEnablesFluidOrEnergy(GameTestHelper helper) throws Exception {
        try (Fixture f = new Fixture(helper)) {
            f.node(1, TransferDirection.INPUT);
            f.node(4, TransferDirection.OUTPUT);
            var source = ResourceEndpointGameTests.place(helper, f.pos(1).below());
            var target = ResourceEndpointGameTests.place(helper, f.pos(4).below());
            source.items.insertItem(0, new ItemStack(Items.IRON_INGOT, 64), false);
            source.fluid.fill(
                    new net.neoforged.neoforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER, 1000),
                    net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
            source.energy.receiveEnergy(10000, false);
            f.start();
            for (int tick = 0; tick < 5; tick++) f.tick(tick);
            helper.assertTrue(
                    target.items.getStackInSlot(0).getCount() == 64
                            && target.fluid.isEmpty()
                            && target.energy.getEnergyStored() == 0
                            && source.fluidCalls == 0
                            && source.energyCalls == 0,
                    "Legacy item policy enabled non-item resources");
        }
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void nativeSamplingAndLogisticsBothProgressWithOneSharedCallPerTick(GameTestHelper helper)
            throws Exception {
        try (Fixture f = new Fixture(helper)) {
            f.node(1, TransferDirection.INPUT);
            f.node(4, TransferDirection.OUTPUT);
            f.chest(1).setItem(0, new ItemStack(Items.IRON_INGOT, 8));
            var defaults = ServerSettings.defaults();
            var scheduler = defaults.scheduler();
            var tiny = new ServerSettings(
                    defaults.networksPerOwner(),
                    defaults.tunnelsPerNetwork(),
                    defaults.channelsPerTunnel(),
                    defaults.channelBindingsPerDirectNode(),
                    defaults.administratorsPerNetwork(),
                    new ServerSettings.Scheduler(
                            scheduler.cpuBudgetMillisPerTick(),
                            1,
                            scheduler.emptyChecksBeforeSleep(),
                            scheduler.idleBackoffTicks(),
                            scheduler.failureThreshold(),
                            scheduler.breakerBackoffTicks(),
                            scheduler.slowCallThresholdMillis()),
                    defaults.filterLimits(),
                    defaults.recoveryLimits());
            UUID preset = new UUID(999, 1);
            var owner = f.repository.createOwner(f.owner, null);
            owner.putPreset(
                    new io.github.loongin.omniresonance.filter.ItemFilterPreset(
                            preset, new ManagedName("Sample"), 0, Set.of()),
                    0,
                    -1,
                    -1);
            var player = new net.neoforged.neoforge.common.util.FakePlayer(
                    helper.getLevel(), new com.mojang.authlib.GameProfile(f.owner, "Sampler"));
            player.getInventory().setItem(0, new ItemStack(Items.WATER_BUCKET));
            var service = new io.github.loongin.omniresonance.filter.ItemFilterService(
                    helper.getLevel().getServer(),
                    f.repository,
                    new io.github.loongin.omniresonance.network.NetworkDirectory(List.of(f.data.metadata())),
                    new io.github.loongin.omniresonance.security.EditLockTable(),
                    () -> tiny,
                    ignored -> {},
                    UUID::randomUUID);
            var edit = service.beginRule(player, f.network, preset, null, false);
            var samples = new ArrayList<io.github.loongin.omniresonance.filter.ItemFilterService.SampleResult>();
            service.requestSample(player, edit, ResourceTypes.FLUID, 0, 0, () -> true, samples::add);
            f.start();
            f.runtime.installSampleWork(service::sampleStep);
            for (int tick = 0; tick < 120; tick++) {
                f.runtime.tick(tick, tiny);
                if (tick == 5)
                    helper.assertTrue(
                            samples.size() == 1,
                            "Three sample calls did not receive alternating one-call opportunities");
            }
            helper.assertTrue(
                    samples.size() == 1 && samples.getFirst().failure().isEmpty(),
                    "One-call logistics starved native sample");
            helper.assertTrue(
                    f.chest(4).getItem(0).getCount() == 8
                            && f.chest(1).getItem(0).isEmpty(),
                    "Native sample starved one-call logistics");
            helper.assertTrue(
                    player.getInventory().getItem(0).is(Items.WATER_BUCKET), "Shared-budget sample consumed bucket");
        }
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void fullOwnerRulesDriveNativeTypesAndMissingReferenceBlocksUntilRestored(GameTestHelper helper)
            throws Exception {
        try (Fixture f = new Fixture(helper)) {
            UUID input = f.node(1, TransferDirection.INPUT), output = f.node(4, TransferDirection.OUTPUT);
            var source = ResourceEndpointGameTests.place(helper, f.pos(1).below());
            var target = ResourceEndpointGameTests.place(helper, f.pos(4).below());
            source.items = new net.neoforged.neoforge.items.ItemStackHandler(2);
            ItemStack named = new ItemStack(Items.IRON_INGOT, 8);
            named.set(
                    net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                    net.minecraft.network.chat.Component.literal("Selected"));
            source.items.insertItem(0, named, false);
            source.items.insertItem(1, new ItemStack(Items.IRON_INGOT, 8), false);
            source.fluid.fill(
                    new net.neoforged.neoforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER, 1000),
                    net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
            source.energy.receiveEnergy(1000, false);
            UUID root = new UUID(999, 11), child = new UUID(999, 12);
            var library = f.repository.createOwner(f.owner, null);
            var sample = ItemVariant.from(named, helper.getLevel().registryAccess());
            library.putPreset(
                    new io.github.loongin.omniresonance.filter.ResourceFilterPreset(
                            root,
                            new ManagedName("Full root"),
                            0,
                            List.of(
                                    new io.github.loongin.omniresonance.filter.ResourceFilterRule.Reference(
                                            new UUID(999, 13), child),
                                    new io.github.loongin.omniresonance.filter.ResourceFilterRule.Match(
                                            new UUID(999, 14),
                                            ResourceTypes.ENERGY,
                                            io.github.loongin.omniresonance.filter.ResourceFilterRule.Selector
                                                    .wholeType(),
                                            io.github.loongin.omniresonance.filter.ComponentCondition.idOnly()),
                                    new io.github.loongin.omniresonance.filter.ResourceFilterRule.Match(
                                            new UUID(999, 15),
                                            ResourceTypes.ITEM,
                                            io.github.loongin.omniresonance.filter.ResourceFilterRule.Selector.exact(
                                                    net.minecraft.resources.ResourceLocation.parse(
                                                            "minecraft:iron_ingot")),
                                            io.github.loongin.omniresonance.filter.ComponentCondition.full(sample)),
                                    new io.github.loongin.omniresonance.filter.ResourceFilterRule.Match(
                                            new UUID(999, 16),
                                            ResourceTypes.ITEM,
                                            io.github.loongin.omniresonance.filter.ResourceFilterRule.Selector.glob(
                                                    "minecraft:iron*"),
                                            io.github.loongin.omniresonance.filter.ComponentCondition.selected(
                                                    sample,
                                                    Set.of(net.minecraft.resources.ResourceLocation.parse(
                                                            "minecraft:custom_name")))))),
                    0,
                    -1,
                    -1);
            for (UUID nodeId : List.of(input, output)) {
                var node = f.data.findNode(nodeId).orElseThrow();
                ResourceTransferPolicy policy = nodeId.equals(input)
                        ? new ResourceTransferPolicy.Input(
                                1,
                                ResourceScope.all(),
                                RedstoneCondition.IGNORE,
                                root,
                                FilterMode.WHITELIST,
                                java.util.Map.of(),
                                0)
                        : ResourceTransferPolicy.defaults(TransferDirection.OUTPUT);
                f.data.setDirectBinding(
                        nodeId,
                        node.revision(),
                        f.channel,
                        new StoredResourcePolicy(policy, java.util.Map.of()),
                        WorkingFaces.explicit(1),
                        false,
                        -1);
            }
            f.sync();
            f.start();
            for (int tick = 0; tick < 8; tick++) f.tick(tick);
            helper.assertTrue(
                    target.items.getStackInSlot(0).isEmpty()
                            && target.fluid.isEmpty()
                            && target.energy.getEnergyStored() == 0,
                    "Missing indirect reference did not fail closed for every native type");
            var childPreset = new io.github.loongin.omniresonance.filter.ResourceFilterPreset(
                    child,
                    new ManagedName("Fluid tags"),
                    0,
                    List.of(new io.github.loongin.omniresonance.filter.ResourceFilterRule.Match(
                            new UUID(999, 17),
                            ResourceTypes.FLUID,
                            io.github.loongin.omniresonance.filter.ResourceFilterRule.Selector.tag(
                                    net.minecraft.resources.ResourceLocation.parse("minecraft:water")),
                            io.github.loongin.omniresonance.filter.ComponentCondition.idOnly())));
            library.putPreset(childPreset, 1, -1, -1);
            f.runtime.ownerLibraryChanged(f.owner);
            for (int tick = 8; tick < 30; tick++) f.tick(tick);
            helper.assertTrue(
                    target.items.getStackInSlot(0).getCount() == 8
                            && source.items.getStackInSlot(1).getCount() == 8
                            && target.fluid.getFluidAmount() == 1000
                            && target.energy.getEnergyStored() == 1000,
                    "Full component/glob/tag/FE owner library did not drive actual runtime");
            library.removePreset(child, 2);
            f.runtime.ownerLibraryChanged(f.owner);
            source.energy.receiveEnergy(1000, false);
            for (int tick = 30; tick < 36; tick++) f.tick(tick);
            helper.assertTrue(
                    target.energy.getEnergyStored() == 1000, "Deleting indirect preset broadened running filter");
            library.putPreset(childPreset, 3, -1, -1);
            f.runtime.ownerLibraryChanged(f.owner);
            for (int tick = 36; tick < 55; tick++) f.tick(tick);
            helper.assertTrue(
                    target.energy.getEnergyStored() == 2000, "Restored reference did not wake native runtime");
        }
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void configuredDomainInputRunsAllNativeTypesAndLegacyPendingDoesNotRun(GameTestHelper helper)
            throws Exception {
        try (Fixture f = new Fixture(helper)) {
            UUID input = f.node(1, TransferDirection.INPUT);
            var node = f.data.findNode(input).orElseThrow();
            node = f.data.setNodeMode(input, node.revision(), NodeMode.DOMAIN, true)
                    .orElseThrow();
            node = f.data.setDomainConfiguration(input, node.revision(), TransferDirection.INPUT, false);
            var source = ResourceEndpointGameTests.place(helper, f.pos(1).below());
            var items = new io.github.loongin.omniresonance.transfer.fixtures.FakeItemHandler(1);
            items.stacks[0] = new ItemStack(Items.IRON_INGOT, 64);
            source.items = items;
            source.fluid.setFluid(new net.neoforged.neoforge.fluids.FluidStack(
                    net.minecraft.world.level.material.Fluids.WATER, 3000));
            source.energy.receiveEnergy(50000, false);
            var iron = ItemVariant.from(items.stacks[0], helper.getLevel().registryAccess());
            var water =
                    FluidVariant.from(source.fluid.getFluid(), helper.getLevel().registryAccess());
            f.sync();
            f.start();
            f.tick(0);
            helper.assertTrue(
                    items.stacks[0].getCount() == 64 && source.itemCalls == 0,
                    "Pending legacy domain input ran before full save");
            helper.assertTrue(
                    f.repository.domainStorage(f.network).state()
                            == io.github.loongin.omniresonance.persistence.DomainStorage.State.NOT_LOADED,
                    "Pending legacy domain activated storage");
            f.data.saveDomainConfiguration(
                    input,
                    node.revision(),
                    new StoredResourcePolicy(
                            ResourceTransferPolicy.defaults(TransferDirection.INPUT), java.util.Map.of()),
                    WorkingFaces.explicit(1),
                    false);
            f.sync();
            f.tick(1);
            var ledger = f.repository.domainStorage(f.network).activate().orElseThrow();
            helper.assertTrue(
                    ledger.amount(iron.key()) == 64
                            && ledger.amount(water.key()) == 3000
                            && ledger.amount(EnergyVariant.INSTANCE.key()) == 50000,
                    "Live domain input did not transfer all native resources");
            helper.assertTrue(
                    items.stacks[0].isEmpty() && source.fluid.isEmpty() && source.energy.getEnergyStored() == 0,
                    "Live domain input did not conserve source quantities");
        }
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void directInputPrecedesDomainFallbackAtTheSamePhysicalSource(GameTestHelper helper)
            throws Exception {
        for (boolean crossNetwork : new boolean[] {false, true}) {
            try (Fixture f = new Fixture(helper)) {
                UUID domainNetwork = f.network;
                NetworkSavedData domainData = f.data;
                if (crossNetwork) {
                    domainNetwork = new UUID(713, 1);
                    f.repository.createNetwork(new NetworkMetadata(
                            domainNetwork, f.owner, new ManagedName("Domain fallback"), 1, Set.of()));
                    domainData = f.repository.findLoadedNetwork(domainNetwork).orElseThrow();
                }
                UUID direct = f.node(1, TransferDirection.INPUT);
                f.node(4, TransferDirection.OUTPUT);
                f.policy(
                        direct,
                        new ItemTransferPolicy.Input(1, 64, RedstoneCondition.IGNORE, null, FilterMode.WHITELIST, 0));
                BlockPos sourcePosition = f.pos(1).below();
                f.chest(1).setItem(0, new ItemStack(Items.IRON_INGOT, 64));
                f.chest(1).setItem(1, new ItemStack(Items.IRON_INGOT, 36));
                var iron = ItemVariant.from(
                        f.chest(1).getItem(0), helper.getLevel().registryAccess());
                BlockPos domainPosition = sourcePosition.east();
                f.positions.add(domainPosition);
                helper.getLevel()
                        .setBlock(
                                domainPosition,
                                ModBlocks.RESONANCE_TRANSFER_NODE
                                        .get()
                                        .defaultBlockState()
                                        .setValue(AbstractResonanceNodeBlock.FACING, Direction.WEST),
                                3);
                var entity = (ResonanceNodeBlockEntity) helper.getLevel().getBlockEntity(domainPosition);
                UUID id = entity.state().orElseThrow().nodeId();
                CompoundTag tag = new CompoundTag();
                NodePersistentState.linked(id).writeOwnedFields(tag);
                entity.loadCustomOnly(tag, helper.getLevel().registryAccess());
                var node = domainData.createNode(
                        id,
                        new ManagedName("Domain input"),
                        GlobalPos.of(helper.getLevel().dimension(), domainPosition),
                        NodeForm.BLOCK,
                        Direction.WEST);
                node = domainData
                        .setNodeMode(id, node.revision(), NodeMode.DOMAIN, false)
                        .orElseThrow();
                node = domainData.saveDomainConfiguration(
                        id,
                        node.revision(),
                        new StoredResourcePolicy(
                                ResourceTransferPolicy.defaults(TransferDirection.INPUT), java.util.Map.of()),
                        WorkingFaces.explicit(1 << Direction.WEST.get3DDataValue()),
                        false);
                f.nodes.add(new NetworkNodeDirectory.Entry(domainNetwork, node));
                f.start();
                f.tick(0);
                var ledger =
                        f.repository.domainStorage(domainNetwork).activate().orElseThrow();
                helper.assertTrue(
                        f.chest(4).getItem(0).getCount() == 64, "Domain input stole direct input's due allowance");
                helper.assertTrue(
                        ledger.amount(iron.key()) == 36
                                && f.chest(1).getItem(0).isEmpty()
                                && f.chest(1).getItem(1).isEmpty(),
                        "Domain did not receive the remaining source resources");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void domainInputFilterBlocksThenWakesAfterOwnerLibraryCreation(GameTestHelper helper)
            throws Exception {
        try (Fixture f = new Fixture(helper)) {
            UUID id = f.node(1, TransferDirection.INPUT);
            var node = f.data.findNode(id).orElseThrow();
            node = f.data.setNodeMode(id, node.revision(), NodeMode.DOMAIN, true)
                    .orElseThrow();
            UUID preset = new UUID(710, 1);
            var policy = new ResourceTransferPolicy.Input(
                    1,
                    ResourceScope.all(),
                    RedstoneCondition.IGNORE,
                    preset,
                    FilterMode.WHITELIST,
                    java.util.Map.of(),
                    0);
            f.data.saveDomainConfiguration(
                    id,
                    node.revision(),
                    new StoredResourcePolicy(policy, java.util.Map.of()),
                    WorkingFaces.explicit(1),
                    false);
            f.chest(1).setItem(0, new ItemStack(Items.IRON_INGOT, 64));
            var key = ItemVariant.from(f.chest(1).getItem(0), helper.getLevel().registryAccess())
                    .key();
            f.sync();
            f.start();
            for (int tick = 0; tick < 5; tick++) f.tick(tick);
            helper.assertTrue(f.chest(1).getItem(0).getCount() == 64, "Missing domain filter was ignored");
            var library = f.repository.createOwner(f.owner, null);
            library.putPreset(
                    new io.github.loongin.omniresonance.filter.ItemFilterPreset(
                            preset,
                            new ManagedName("Iron"),
                            0,
                            Set.of(net.minecraft.resources.ResourceLocation.parse("minecraft:iron_ingot"))),
                    0,
                    -1,
                    -1);
            f.runtime.ownerLibraryChanged(f.owner);
            for (int tick = 5; tick < 15; tick++) f.tick(tick);
            helper.assertTrue(
                    f.chest(1).getItem(0).isEmpty()
                            && f.repository
                                            .domainStorage(f.network)
                                            .activate()
                                            .orElseThrow()
                                            .amount(key)
                                    == 64,
                    "Domain filter did not resume after valid owner preset appeared");
        }
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void liveDomainOutputsAllocateByPriorityAndTransferAllNativeTypes(GameTestHelper helper)
            throws Exception {
        try (Fixture f = new Fixture(helper)) {
            UUID high = f.node(1, TransferDirection.OUTPUT), low = f.node(4, TransferDirection.OUTPUT);
            UUID preset = new UUID(720, 1);
            var owner = f.repository.createOwner(f.owner, null);
            owner.putPreset(
                    new io.github.loongin.omniresonance.filter.ItemFilterPreset(
                            preset,
                            new ManagedName("Iron"),
                            0,
                            Set.of(net.minecraft.resources.ResourceLocation.parse("minecraft:iron_ingot"))),
                    0,
                    -1,
                    -1);
            for (UUID id : List.of(high, low)) {
                var node = f.data.findNode(id).orElseThrow();
                node = f.data.setNodeMode(id, node.revision(), NodeMode.DOMAIN, true)
                        .orElseThrow();
                var policy = new ResourceTransferPolicy.Output(
                        1,
                        ResourceScope.customSet(Set.of(ResourceTypes.ITEM)),
                        RedstoneCondition.IGNORE,
                        preset,
                        FilterMode.WHITELIST,
                        java.util.Map.of(ResourceTypes.ITEM, new ResourceTransferPolicy.OutputOverride(100)),
                        id.equals(high) ? 10 : 0);
                f.data.saveDomainConfiguration(
                        id,
                        node.revision(),
                        new StoredResourcePolicy(policy, java.util.Map.of()),
                        WorkingFaces.explicit(1),
                        false);
            }
            var highTarget = ResourceEndpointGameTests.place(helper, f.pos(1).below());
            var lowTarget = ResourceEndpointGameTests.place(helper, f.pos(4).below());
            var highItems = new io.github.loongin.omniresonance.transfer.fixtures.FakeItemHandler(1);
            var lowItems = new io.github.loongin.omniresonance.transfer.fixtures.FakeItemHandler(1);
            highItems.capacity = 60;
            highTarget.items = highItems;
            lowTarget.items = lowItems;
            var iron = ItemVariant.from(
                    new ItemStack(Items.IRON_INGOT), helper.getLevel().registryAccess());
            var ledger = f.repository.domainStorage(f.network).activate().orElseThrow();
            try (var deposit = ledger.reserveDeposit(iron.key(), 100, -1).orElseThrow()) {
                deposit.commit(100);
            }
            f.sync();
            f.start();
            for (int tick = 0; tick < 10; tick++) f.tick(tick);
            helper.assertTrue(
                    highItems.stacks[0].getCount() == 60 && lowItems.stacks[0].getCount() == 40,
                    "Live domain output priority did not allocate 60/40");
            helper.assertTrue(
                    ledger.amount(iron.key()) == 0 && f.data.recovery().isEmpty(),
                    "Live domain output lost known quantities");
        }
        try (Fixture f = new Fixture(helper)) {
            UUID id = f.node(1, TransferDirection.OUTPUT), preset = new UUID(720, 2);
            var node = f.data.findNode(id).orElseThrow();
            node = f.data.setNodeMode(id, node.revision(), NodeMode.DOMAIN, true)
                    .orElseThrow();
            var rules = new java.util.ArrayList<io.github.loongin.omniresonance.filter.ResourceFilterRule>();
            for (net.minecraft.resources.ResourceLocation type :
                    List.of(ResourceTypes.ITEM, ResourceTypes.FLUID, ResourceTypes.ENERGY))
                rules.add(new io.github.loongin.omniresonance.filter.ResourceFilterRule.Match(
                        new UUID(721, rules.size()),
                        type,
                        io.github.loongin.omniresonance.filter.ResourceFilterRule.Selector.wholeType(),
                        io.github.loongin.omniresonance.filter.ComponentCondition.idOnly()));
            f.repository
                    .createOwner(f.owner, null)
                    .putPreset(
                            new io.github.loongin.omniresonance.filter.ResourceFilterPreset(
                                    preset, new ManagedName("All resources"), 0, rules),
                            0,
                            -1,
                            -1);
            f.data.saveDomainConfiguration(
                    id,
                    node.revision(),
                    new StoredResourcePolicy(
                            new ResourceTransferPolicy.Output(
                                    1,
                                    ResourceScope.all(),
                                    RedstoneCondition.IGNORE,
                                    preset,
                                    FilterMode.WHITELIST,
                                    java.util.Map.of(),
                                    0),
                            java.util.Map.of()),
                    WorkingFaces.explicit(1),
                    false);
            var target = ResourceEndpointGameTests.place(helper, f.pos(1).below());
            var iron = ItemVariant.from(
                    new ItemStack(Items.IRON_INGOT), helper.getLevel().registryAccess());
            var water = FluidVariant.from(
                    new net.neoforged.neoforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER, 1),
                    helper.getLevel().registryAccess());
            var ledger = f.repository.domainStorage(f.network).activate().orElseThrow();
            ResourceVariant[] variants = {iron, water, EnergyVariant.INSTANCE};
            long[] amounts = {64, 3000, 50000};
            for (int i = 0; i < variants.length; i++)
                try (var deposit =
                        ledger.reserveDeposit(variants[i].key(), amounts[i], -1).orElseThrow()) {
                    deposit.commit(amounts[i]);
                }
            f.sync();
            f.start();
            for (int tick = 0; tick < 10; tick++) f.tick(tick);
            helper.assertTrue(
                    target.items.getStackInSlot(0).getCount() == 64
                            && target.fluid.getFluidAmount() == 3000
                            && target.energy.getEnergyStored() == 50000
                            && ledger.variantCount() == 0,
                    "Live output did not transfer all native types");
        }
        helper.succeed();
    }

    private static final class Fixture implements AutoCloseable {
        final GameTestHelper helper;
        final Path path;
        final SavedNetworkRepository repository;
        final NetworkSavedData data;
        final DimensionDataStorage storage;
        final UUID network = UUID.randomUUID(),
                owner = UUID.randomUUID(),
                tunnel = UUID.randomUUID(),
                channel = UUID.randomUUID();
        final NetworkNodeDirectory nodes = new NetworkNodeDirectory(List.of());
        final List<BlockPos> positions = new ArrayList<>();
        ResourceDirectRuntime runtime;

        Fixture(GameTestHelper helper) throws Exception {
            this(helper, ResourceAdapterDirectory.nativeDefaults());
        }

        Fixture(GameTestHelper helper, ResourceAdapterDirectory directory) throws Exception {
            this.helper = helper;
            path = Files.createTempDirectory("omniresonance-item-direct-");
            storage = new DimensionDataStorage(
                    path.toFile(), DataFixers.getDataFixer(), helper.getLevel().registryAccess());
            repository = new SavedNetworkRepository(storage, path, directory);
            repository.createNetwork(
                    new NetworkMetadata(network, owner, new ManagedName("Transfer test"), 0, Set.of()));
            data = repository.findLoadedNetwork(network).orElseThrow();
            data.createTunnel(tunnel, new ManagedName("Main"), channel, new ManagedName("Items"), -1);
        }

        BlockPos pos(int x) {
            return helper.absolutePos(new BlockPos(x, 3, 2));
        }

        UUID node(int x, TransferDirection direction) {
            BlockPos pos = pos(x);
            positions.add(pos);
            helper.getLevel().setBlock(pos.below(), Blocks.CHEST.defaultBlockState(), 3);
            helper.getLevel()
                    .setBlock(
                            pos,
                            ModBlocks.RESONANCE_TRANSFER_NODE
                                    .get()
                                    .defaultBlockState()
                                    .setValue(AbstractResonanceNodeBlock.FACING, Direction.DOWN),
                            3);
            ResonanceNodeBlockEntity entity =
                    (ResonanceNodeBlockEntity) helper.getLevel().getBlockEntity(pos);
            UUID id = entity.state().orElseThrow().nodeId();
            CompoundTag tag = new CompoundTag();
            NodePersistentState.linked(id).writeOwnedFields(tag);
            entity.loadCustomOnly(tag, helper.getLevel().registryAccess());
            NetworkNodeRecord n = data.createNode(
                    id,
                    new ManagedName("Node " + x),
                    GlobalPos.of(helper.getLevel().dimension(), pos),
                    NodeForm.BLOCK,
                    Direction.DOWN);
            n = data.setNodeMode(id, n.revision(), NodeMode.DIRECT, false).orElseThrow();
            n = data.setDirectBinding(
                    id,
                    n.revision(),
                    channel,
                    ItemTransferPolicy.defaults(direction),
                    WorkingFaces.attachedFace(),
                    false,
                    -1);
            nodes.add(new NetworkNodeDirectory.Entry(network, n));
            return id;
        }

        void policy(UUID id, ItemTransferPolicy policy) {
            NetworkNodeRecord n = data.findNode(id).orElseThrow();
            data.setDirectBinding(id, n.revision(), channel, policy, false, -1);
            sync();
        }

        void sync() {
            for (NetworkNodeRecord n : data.nodes()) {
                var previous = nodes.byId(n.nodeId()).entry().orElseThrow();
                if (!previous.record().equals(n)) nodes.update(previous, new NetworkNodeDirectory.Entry(network, n));
            }
        }

        ChestBlockEntity chest(int x) {
            return (ChestBlockEntity) helper.getLevel().getBlockEntity(pos(x).below());
        }

        void start() {
            runtime = new ResourceDirectRuntime(
                    helper.getLevel().getServer(), repository, nodes, ServerSettings.defaults(), () -> 0);
        }

        void tick(long tick) {
            runtime.tick(tick, ServerSettings.defaults());
        }

        public void close() throws Exception {
            if (runtime != null) runtime.close();
            for (BlockPos pos : positions) {
                helper.getLevel().setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                helper.getLevel().setBlock(pos.below(), Blocks.AIR.defaultBlockState(), 3);
            }
            try (var files = Files.walk(path)) {
                for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
    }
}
