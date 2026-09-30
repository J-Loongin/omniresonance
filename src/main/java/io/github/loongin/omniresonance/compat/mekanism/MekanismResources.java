// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.compat.mekanism;

import io.github.loongin.omniresonance.transfer.PipeConnections;
import io.github.loongin.omniresonance.transfer.ResourceAdapterDirectory;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.IChemicalHandler;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.ItemCapability;

/** Explicit optional bootstrap; only public API types and capability identities, no implementation classes. */
public final class MekanismResources {
    public static final BlockCapability<IChemicalHandler, Direction> BLOCK =
            BlockCapability.createSided(ResourceLocation.parse("mekanism:chemical_handler"), IChemicalHandler.class);
    public static final ItemCapability<IChemicalHandler, Void> ITEM =
            ItemCapability.createVoid(ResourceLocation.parse("mekanism:chemical_handler"), IChemicalHandler.class);

    private static final IChemicalHandler CONNECTION = new IChemicalHandler() {
        public int getChemicalTanks() {
            return 0;
        }

        public mekanism.api.chemical.ChemicalStack getChemicalInTank(int tank) {
            return mekanism.api.chemical.ChemicalStack.EMPTY;
        }

        public void setChemicalInTank(int tank, mekanism.api.chemical.ChemicalStack stack) {
            throw new IndexOutOfBoundsException("Connection marker has no chemical tanks");
        }

        public long getChemicalTankCapacity(int tank) {
            return 0;
        }

        public boolean isValid(int tank, mekanism.api.chemical.ChemicalStack stack) {
            return false;
        }

        public mekanism.api.chemical.ChemicalStack insertChemical(
                int tank, mekanism.api.chemical.ChemicalStack stack, mekanism.api.Action action) {
            return stack;
        }

        public mekanism.api.chemical.ChemicalStack extractChemical(int tank, long amount, mekanism.api.Action action) {
            return mekanism.api.chemical.ChemicalStack.EMPTY;
        }
    };

    /** Optional server-thread registration. Domain inputs use receive-only leases; other configured chemical
     * faces expose empty markers. Simulation never changes inventory; extraction always returns empty. */
    public static void registerNodeConnections(net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
                BLOCK,
                io.github.loongin.omniresonance.registry.ModBlockEntities.RESONANCE_TRANSFER_NODE.get(),
                (entity, side) -> !entity.pipeConnection(side, PipeConnections.Type.CHEMICAL)
                        ? null
                        : entity.externalInput() == null
                                ? CONNECTION
                                : new ChemicalInput(entity.externalInput(), side));
    }

    private record ChemicalInput(io.github.loongin.omniresonance.transfer.ExternalDomainInput input, Direction side)
            implements IChemicalHandler {
        public int getChemicalTanks() {
            return 1;
        }

        public mekanism.api.chemical.ChemicalStack getChemicalInTank(int tank) {
            return mekanism.api.chemical.ChemicalStack.EMPTY;
        }

        public void setChemicalInTank(int tank, mekanism.api.chemical.ChemicalStack stack) {
            throw new UnsupportedOperationException("Delivery endpoint has no mutable inventory");
        }

        public long getChemicalTankCapacity(int tank) {
            return Long.MAX_VALUE;
        }

        public boolean isValid(int tank, mekanism.api.chemical.ChemicalStack stack) {
            return tank == 0 && !stack.isEmpty() && input.available(side, ChemicalVariant.TYPE);
        }

        public mekanism.api.chemical.ChemicalStack insertChemical(
                int tank, mekanism.api.chemical.ChemicalStack stack, mekanism.api.Action action) {
            if (!isValid(tank, stack)) return stack;
            long accepted = input.insert(side, ChemicalVariant.from(stack), stack.getAmount(), action.simulate());
            return accepted == stack.getAmount()
                    ? mekanism.api.chemical.ChemicalStack.EMPTY
                    : stack.copyWithAmount(stack.getAmount() - accepted);
        }

        public mekanism.api.chemical.ChemicalStack extractChemical(int tank, long amount, mekanism.api.Action action) {
            return mekanism.api.chemical.ChemicalStack.EMPTY;
        }
    }

    private MekanismResources() {}

    public static @org.jetbrains.annotations.Nullable ChemicalVariant recipeVariant(Object ingredient) {
        return ingredient instanceof mekanism.api.chemical.ChemicalStack stack && !stack.isEmpty()
                ? ChemicalVariant.from(stack)
                : null;
    }

    /** Stable registry identity for AE indexing; read on the owning game thread without capability access. */
    public static @org.jetbrains.annotations.Nullable Object primaryIdentity(ResourceLocation id) {
        return MekanismAPI.CHEMICAL_REGISTRY.containsKey(id) ? MekanismAPI.CHEMICAL_REGISTRY.get(id) : null;
    }

    public static void register(ResourceAdapterDirectory directory) {
        directory.register(
                new ResourceAdapterDirectory.Descriptor(ChemicalVariant.TYPE, "mB", 1000),
                BLOCK,
                (handler, provider) -> new ChemicalResourcePort(handler),
                (key, provider) -> ChemicalVariant.restore(key));
        directory.registerTagRegistry(ChemicalVariant.TYPE, MekanismAPI.CHEMICAL_REGISTRY_NAME);
        directory.registerCarrier(
                ChemicalVariant.TYPE, ITEM, (handler, provider) -> new ChemicalResourcePort(handler, true));
    }
}
