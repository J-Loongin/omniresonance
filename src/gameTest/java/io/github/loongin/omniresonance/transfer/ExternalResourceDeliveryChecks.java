// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import io.github.loongin.omniresonance.storage.DomainLedger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.Capabilities;

/** Real capability checks used by the authoritative runtime fixture; optional types are isolated by class. */
public final class ExternalResourceDeliveryChecks {
    private ExternalResourceDeliveryChecks() {}

    public static void verify(GameTestHelper h, BlockPos pos, DomainLedger ledger) {
        var energy = h.getLevel().getCapability(Capabilities.EnergyStorage.BLOCK, pos, Direction.DOWN);
        long revision = ledger.revision();
        h.assertTrue(
                energy != null
                        && energy.receiveEnergy(Integer.MAX_VALUE, true) == Integer.MAX_VALUE
                        && ledger.revision() == revision,
                "Energy simulation rejected or mutated storage");
        h.assertTrue(
                energy.receiveEnergy(Integer.MAX_VALUE, false) == Integer.MAX_VALUE
                        && energy.receiveEnergy(Integer.MAX_VALUE, false) == Integer.MAX_VALUE
                        && ledger.amount(EnergyVariant.INSTANCE.key()) == 2L * Integer.MAX_VALUE
                        && !energy.canExtract()
                        && energy.extractEnergy(Integer.MAX_VALUE, false) == 0
                        && energy.getEnergyStored() == 0,
                "Energy delivery used active rate or exposed stored inventory");
        var fluids = h.getLevel().getCapability(Capabilities.FluidHandler.BLOCK, pos, Direction.DOWN);
        var water = new net.neoforged.neoforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER, 1000);
        var key = FluidVariant.from(water, h.getLevel().registryAccess()).key();
        revision = ledger.revision();
        h.assertTrue(
                fluids != null
                        && fluids.fill(
                                        water,
                                        net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.SIMULATE)
                                == 1000
                        && ledger.revision() == revision,
                "Fluid simulation changed storage");
        h.assertTrue(
                fluids.fill(water, net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE) == 1000
                        && ledger.amount(key) == 1000
                        && water.getAmount() == 1000
                        && fluids.drain(
                                        1000,
                                        net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE)
                                .isEmpty(),
                "Fluid delivery lost conservation or allowed extraction");
        if (ModList.get().isLoaded("mekanism")) Chemicals.verify(h, pos, ledger);
        if (ModList.get().isLoaded("ars_nouveau")) Source.verify(h, pos, ledger);
        if (ModList.get().isLoaded("industrialforegoingsouls")) Souls.verify(h, pos, ledger);
    }

    private static final class Chemicals {
        static void verify(GameTestHelper h, BlockPos pos, DomainLedger ledger) {
            var handler = h.getLevel()
                    .getCapability(
                            io.github.loongin.omniresonance.compat.mekanism.MekanismResources.BLOCK,
                            pos,
                            Direction.DOWN);
            var stack = new mekanism.api.chemical.ChemicalStack(
                    mekanism.common.registries.MekanismChemicals.HYDROGEN, Long.MAX_VALUE);
            var key = io.github.loongin.omniresonance.compat.mekanism.ChemicalVariant.from(stack)
                    .key();
            long revision = ledger.revision();
            h.assertTrue(
                    handler != null
                            && handler.insertChemical(stack, mekanism.api.Action.SIMULATE)
                                    .isEmpty()
                            && ledger.revision() == revision,
                    "Chemical delivery simulation rejected or changed storage");
            h.assertTrue(
                    handler.insertChemical(stack, mekanism.api.Action.EXECUTE).isEmpty()
                            && ledger.amount(key) == Long.MAX_VALUE
                            && stack.getAmount() == Long.MAX_VALUE
                            && handler.extractChemical(Long.MAX_VALUE, mekanism.api.Action.EXECUTE)
                                    .isEmpty(),
                    "Chemical long delivery lost stock or exposed extraction");
            h.assertTrue(
                    handler.insertChemical(stack, mekanism.api.Action.SIMULATE).getAmount() == Long.MAX_VALUE,
                    "Chemical overflow was accepted");
        }
    }

    private static final class Source {
        static void verify(GameTestHelper h, BlockPos pos, DomainLedger ledger) {
            var handler = h.getLevel()
                    .getCapability(io.github.loongin.omniresonance.compat.ars.ArsResources.BLOCK, pos, Direction.DOWN);
            var key = io.github.loongin.omniresonance.compat.ars.SourceVariant.INSTANCE.key();
            long revision = ledger.revision();
            h.assertTrue(
                    handler != null && handler.receiveSource(1000, true) == 1000 && ledger.revision() == revision,
                    "Source simulation changed storage");
            h.assertTrue(
                    handler.receiveSource(1000, false) == 1000
                            && ledger.amount(key) == 1000
                            && handler.extractSource(1000, false) == 0
                            && handler.getSource() == 0,
                    "Source delivery lost stock or exposed extraction");
        }
    }

    private static final class Souls {
        static void verify(GameTestHelper h, BlockPos pos, DomainLedger ledger) {
            var handler = h.getLevel()
                    .getCapability(
                            com.buuz135.industrialforegoingsouls.capabilities.SoulCapabilities.BLOCK,
                            pos,
                            Direction.DOWN);
            var key = io.github.loongin.omniresonance.compat.souls.SoulVariant.INSTANCE.key();
            long revision = ledger.revision();
            h.assertTrue(
                    handler != null
                            && handler.fill(
                                            1000,
                                            com.buuz135.industrialforegoingsouls.capabilities.ISoulHandler.Action
                                                    .SIMULATE)
                                    == 1000
                            && ledger.revision() == revision,
                    "Soul simulation changed storage");
            h.assertTrue(
                    handler.fill(1000, com.buuz135.industrialforegoingsouls.capabilities.ISoulHandler.Action.EXECUTE)
                                    == 1000
                            && ledger.amount(key) == 1000
                            && handler.drain(
                                            1000,
                                            com.buuz135.industrialforegoingsouls.capabilities.ISoulHandler.Action
                                                    .EXECUTE)
                                    == 0,
                    "Soul delivery lost stock or exposed extraction");
        }
    }
}
