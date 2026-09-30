// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.compat.mekanism;

import io.github.loongin.omniresonance.transfer.PipeConnections;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("omniresonance")
@PrefixGameTestTemplate(false)
public final class PipeConnectionGameTests {
    private PipeConnectionGameTests() {}

    @GameTest(template = "bootstrap", timeoutTicks = 120)
    public static void emptyConnectionMarkersPermitNativeEnergyAndFluidInsertion(GameTestHelper h) {
        if (!ModList.get().isLoaded("mekanism")) {
            h.succeed();
            return;
        }
        Installed.run(h);
    }

    @GameTest(template = "bootstrap", timeoutTicks = 100)
    public static void chemicalPipeConnectionsRespectFacesAndNeverExposeDomainStock(GameTestHelper h) {
        if (!ModList.get().isLoaded("mekanism")) {
            h.succeed();
            return;
        }
        Installed.chemical(h);
    }

    private static final class Installed {
        static void chemical(GameTestHelper h) {
            var level = h.getLevel();
            var pos = h.absolutePos(new BlockPos(3, 3, 3));
            level.setBlockAndUpdate(
                    pos.west(),
                    io.github.loongin.omniresonance.registry.ModBlocks.RESONANCE_TRANSFER_NODE
                            .get()
                            .defaultBlockState());
            level.setBlockAndUpdate(
                    pos,
                    mekanism.common.registries.MekanismBlocks.BASIC_PRESSURIZED_TUBE
                            .get()
                            .defaultBlockState());
            h.runAfterDelay(5, () -> {
                var node = (io.github.loongin.omniresonance.node.ResonanceNodeBlockEntity)
                        level.getBlockEntity(pos.west());
                var tag = new net.minecraft.nbt.CompoundTag();
                io.github.loongin.omniresonance.node.NodePersistentState.linked(
                                node.state().orElseThrow().nodeId())
                        .writeOwnedFields(tag);
                node.loadCustomOnly(tag, level.registryAccess());
                node.publishPipeConnections(PipeConnections.only(PipeConnections.Type.CHEMICAL, Direction.EAST));
            });
            h.runAfterDelay(10, () -> {
                var marker = level.getCapability(MekanismResources.BLOCK, pos.west(), Direction.EAST);
                h.assertTrue(marker != null, "Configured chemical connection is missing");
                h.assertTrue(
                        level.getCapability(MekanismResources.BLOCK, pos.west(), Direction.WEST) == null,
                        "Unselected chemical face is exposed");
                var hydrogen = new mekanism.api.chemical.ChemicalStack(
                        mekanism.common.registries.MekanismChemicals.HYDROGEN, 100);
                h.assertTrue(
                        marker.insertChemical(hydrogen.copy(), mekanism.api.Action.SIMULATE)
                                                .getAmount()
                                        == 100
                                && marker.insertChemical(hydrogen.copy(), mekanism.api.Action.EXECUTE)
                                                .getAmount()
                                        == 100
                                && marker.extractChemical(Long.MAX_VALUE, mekanism.api.Action.EXECUTE)
                                        .isEmpty(),
                        "Connection interface exposed a resource inventory");
                var pipe = level.getCapability(MekanismResources.BLOCK, pos, Direction.WEST);
                h.assertTrue(
                        pipe != null
                                && pipe.insertChemical(hydrogen.copy(), mekanism.api.Action.SIMULATE)
                                        .isEmpty(),
                        "Chemical pipe rejected simulated insertion");
                h.assertTrue(
                        pipe.extractChemical(100, mekanism.api.Action.SIMULATE).isEmpty(),
                        "Simulation added chemicals");
                h.assertTrue(
                        pipe.insertChemical(hydrogen.copy(), mekanism.api.Action.EXECUTE)
                                .isEmpty(),
                        "Chemical pipe rejected actual insertion");
                h.assertTrue(
                        pipe.extractChemical(40, mekanism.api.Action.SIMULATE).getAmount() == 40
                                && pipe.extractChemical(40, mekanism.api.Action.EXECUTE)
                                                .getAmount()
                                        == 40
                                && pipe.extractChemical(100, mekanism.api.Action.SIMULATE)
                                                .getAmount()
                                        == 60,
                        "Chemical pipe extraction failed conservation");
                var tube = ((mekanism.common.tile.transmitter.TileEntityPressurizedTube) level.getBlockEntity(pos))
                        .getTransmitter();
                tube.setConnectionTypeRaw(Direction.WEST, mekanism.common.lib.transmitter.ConnectionType.NONE);
                tube.refreshConnections();
                h.assertTrue(
                        pipe.insertChemical(hydrogen.copy(), mekanism.api.Action.SIMULATE)
                                                .getAmount()
                                        == 100
                                && pipe.extractChemical(100, mekanism.api.Action.SIMULATE)
                                        .isEmpty(),
                        "Disabled chemical face allowed transfer");
                var node = (io.github.loongin.omniresonance.node.ResonanceNodeBlockEntity)
                        level.getBlockEntity(pos.west());
                node.publishPipeConnections(PipeConnections.NONE);
                h.assertTrue(
                        level.getCapability(MekanismResources.BLOCK, pos.west(), Direction.EAST) == null
                                && marker.extractChemical(100, mekanism.api.Action.EXECUTE)
                                        .isEmpty(),
                        "Revoked chemical marker leaked stock");
                h.succeed();
            });
        }

        static void run(GameTestHelper h) {
            var level = h.getLevel();
            var energy = h.absolutePos(new BlockPos(2, 3, 2));
            var fluid = energy.offset(0, 0, 3);
            var item = energy.offset(0, 0, 6);
            for (var pos : java.util.List.of(energy, fluid, item)) {
                var nodePos = pos.west();
                level.setBlockAndUpdate(
                        nodePos,
                        io.github.loongin.omniresonance.registry.ModBlocks.RESONANCE_TRANSFER_NODE
                                .get()
                                .defaultBlockState());
            }
            level.setBlockAndUpdate(
                    energy,
                    mekanism.common.registries.MekanismBlocks.BASIC_UNIVERSAL_CABLE
                            .get()
                            .defaultBlockState());
            level.setBlockAndUpdate(
                    fluid,
                    mekanism.common.registries.MekanismBlocks.BASIC_MECHANICAL_PIPE
                            .get()
                            .defaultBlockState());
            level.setBlockAndUpdate(
                    item,
                    mekanism.common.registries.MekanismBlocks.BASIC_LOGISTICAL_TRANSPORTER
                            .get()
                            .defaultBlockState());
            level.setBlockAndUpdate(item.east(), Blocks.CHEST.defaultBlockState());
            h.runAfterDelay(5, () -> {
                for (var pos : java.util.List.of(energy, fluid, item)) {
                    var nodePos = pos.west();
                    var node = (io.github.loongin.omniresonance.node.ResonanceNodeBlockEntity)
                            level.getBlockEntity(nodePos);
                    var tag = new net.minecraft.nbt.CompoundTag();
                    io.github.loongin.omniresonance.node.NodePersistentState.linked(
                                    node.state().orElseThrow().nodeId())
                            .writeOwnedFields(tag);
                    node.loadCustomOnly(tag, level.registryAccess());
                    node.publishPipeConnections(PipeConnections.only(PipeConnections.Type.ITEM, Direction.EAST)
                            .union(PipeConnections.only(PipeConnections.Type.FLUID, Direction.EAST))
                            .union(PipeConnections.only(PipeConnections.Type.ENERGY, Direction.EAST)));
                }
            });
            h.runAfterDelay(10, () -> {
                var sourceNode = (io.github.loongin.omniresonance.node.ResonanceNodeBlockEntity)
                        level.getBlockEntity(energy.west());
                h.assertTrue(
                        level.getCapability(Capabilities.EnergyStorage.BLOCK, energy.west(), Direction.EAST) != null,
                        "Node connection marker missing: " + sourceNode.state());
                var e = level.getCapability(Capabilities.EnergyStorage.BLOCK, energy, Direction.WEST);
                var f = level.getCapability(Capabilities.FluidHandler.BLOCK, fluid, Direction.WEST);
                var i = level.getCapability(Capabilities.ItemHandler.BLOCK, item, Direction.WEST);
                h.assertTrue(e != null && e.receiveEnergy(100, true) == 100, "Empty marker failed to connect energy");
                h.assertTrue(e.receiveEnergy(100, false) == 100, "Energy commit rejected");
                var water = new net.neoforged.neoforge.fluids.FluidStack(
                        net.minecraft.world.level.material.Fluids.WATER, 100);
                h.assertTrue(
                        f != null
                                && f.fill(
                                                water,
                                                net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction
                                                        .SIMULATE)
                                        == 100,
                        "Empty marker failed to connect fluid");
                h.assertTrue(
                        f.fill(water, net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE)
                                == 100,
                        "Fluid commit rejected");
                h.assertTrue(
                        e.extractEnergy(50, true) == 50 && e.extractEnergy(50, false) == 50,
                        "Native cable extraction failed");
                h.assertTrue(
                        f.drain(50, net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.SIMULATE)
                                                .getAmount()
                                        == 50
                                && f.drain(
                                                        50,
                                                        net.neoforged.neoforge.fluids.capability.IFluidHandler
                                                                .FluidAction.EXECUTE)
                                                .getAmount()
                                        == 50,
                        "Native pipe extraction failed");
                var marker = level.getCapability(Capabilities.EnergyStorage.BLOCK, energy.west(), Direction.EAST);
                h.assertTrue(
                        marker != null
                                && marker.receiveEnergy(100, true) == 0
                                && marker.receiveEnergy(100, false) == 0
                                && marker.extractEnergy(100, false) == 0
                                && marker.getEnergyStored() == 0,
                        "Connection marker exposed energy inventory");
                var stack = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_INGOT, 1);
                h.assertTrue(
                        i != null && i.insertItem(0, stack.copy(), true).isEmpty(),
                        "Item insertion simulation rejected route");
                h.assertTrue(i.insertItem(0, stack.copy(), false).isEmpty(), "Item insertion commit rejected route");
                h.assertTrue(i.extractItem(0, 1, false).isEmpty(), "Transporter unexpectedly exposed in-flight stock");
                var ec = ((mekanism.common.tile.transmitter.TileEntityUniversalCable) level.getBlockEntity(energy))
                        .getTransmitter();
                var fc = ((mekanism.common.tile.transmitter.TileEntityMechanicalPipe) level.getBlockEntity(fluid))
                        .getTransmitter();
                ec.setConnectionTypeRaw(Direction.WEST, mekanism.common.lib.transmitter.ConnectionType.NONE);
                fc.setConnectionTypeRaw(Direction.WEST, mekanism.common.lib.transmitter.ConnectionType.NONE);
                ec.refreshConnections();
                fc.refreshConnections();
                h.assertTrue(e.receiveEnergy(100, true) == 0, "Disabled cable face still accepted energy");
                h.assertTrue(
                        f.fill(water, net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.SIMULATE) == 0,
                        "Disabled pipe face still accepted fluid");
                h.runAfterDelay(40, () -> {
                    var chest = level.getCapability(Capabilities.ItemHandler.BLOCK, item.east(), Direction.WEST);
                    int count = 0;
                    for (int slot = 0; slot < chest.getSlots(); slot++)
                        count += chest.getStackInSlot(slot).getCount();
                    h.assertTrue(count == 1, "Transported item did not arrive exactly once");
                    h.succeed();
                });
            });
        }
    }
}
