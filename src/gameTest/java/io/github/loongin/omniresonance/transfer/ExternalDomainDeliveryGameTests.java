// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import io.github.loongin.omniresonance.bootstrap.OmniResonanceMod;
import io.github.loongin.omniresonance.filter.FilterMode;
import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.network.TransferDirection;
import io.github.loongin.omniresonance.network.WorkingFaces;
import io.github.loongin.omniresonance.node.NodeMode;
import io.github.loongin.omniresonance.node.NodePersistentState;
import io.github.loongin.omniresonance.node.ResonanceNodeBlockEntity;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** World scenarios for ExternalDomainDeliveryGameTests. */
@GameTestHolder(OmniResonanceMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ExternalDomainDeliveryGameTests {
    private ExternalDomainDeliveryGameTests() {}

    @GameTest(template = "bootstrap")
    public static void externalDeliveryHonorsFiltersAndRejectsIncompleteMatching(GameTestHelper helper)
            throws Exception {
        try (var f = new TransferWorldFixture(helper)) {
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
        try (var f =
                new TransferWorldFixture(helper, io.github.loongin.omniresonance.bootstrap.ResourceAdapters.create())) {
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
}
