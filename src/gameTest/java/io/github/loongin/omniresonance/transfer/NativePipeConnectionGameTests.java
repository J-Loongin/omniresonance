// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import io.github.loongin.omniresonance.bootstrap.OmniResonanceMod;
import io.github.loongin.omniresonance.network.TransferDirection;
import io.github.loongin.omniresonance.network.WorkingFaces;
import io.github.loongin.omniresonance.node.NodeMode;
import io.github.loongin.omniresonance.node.NodePersistentState;
import io.github.loongin.omniresonance.node.ResonanceNodeBlockEntity;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** World scenarios for NativePipeConnectionGameTests. */
@GameTestHolder(OmniResonanceMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NativePipeConnectionGameTests {
    private NativePipeConnectionGameTests() {}

    @GameTest(template = "bootstrap", timeoutTicks = 160)
    public static void realMekanismItemPipeDeliversDirectlyToDomainInput(GameTestHelper helper) throws Exception {
        if (!net.neoforged.fml.ModList.get().isLoaded("mekanism")) {
            helper.succeed();
            return;
        }
        var f = new TransferWorldFixture(helper);
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
    public static void connectionMarkersFollowConfiguredFacesWithoutExposingInventory(GameTestHelper helper)
            throws Exception {
        try (var f = new TransferWorldFixture(helper)) {
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
            helper.assertTrue(
                    !entity.pipeConnection(Direction.EAST, PipeConnections.Type.CHEMICAL),
                    "Energy-only configuration exposed chemicals");
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
                    entity.pipeConnection(Direction.UP, PipeConnections.Type.CHEMICAL)
                            && !entity.pipeConnection(Direction.EAST, PipeConnections.Type.CHEMICAL)
                            && !entity.pipeConnection(Direction.EAST, PipeConnections.Type.ENERGY),
                    "Chemical-only configuration did not replace the previous resource and face selection");
            f.runtime.close();
            helper.assertTrue(
                    !entity.pipeConnection(Direction.UP, PipeConnections.Type.CHEMICAL),
                    "Closed runtime kept chemical connection metadata");
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
}
