// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.compat.ars;

import com.hollingsworth.arsnouveau.api.source.ISourceCap;
import io.github.loongin.omniresonance.transfer.PipeConnections;
import io.github.loongin.omniresonance.transfer.ResourceAdapterDirectory;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.capabilities.BlockCapability;

/** Optional public capability boundary. No player mana, direct field edits or guessed item carriers. */
public final class ArsResources {
    public static final BlockCapability<ISourceCap, Direction> BLOCK =
            BlockCapability.createSided(SourceVariant.TYPE, ISourceCap.class);

    /** Optional receive-only capability. No Source is exposed for extraction and setters cannot modify storage. */
    public static void capabilities(net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
                BLOCK,
                io.github.loongin.omniresonance.registry.ModBlockEntities.RESONANCE_TRANSFER_NODE.get(),
                (entity, side) ->
                        entity.pipeConnection(side, PipeConnections.Type.SOURCE) && entity.externalInput() != null
                                ? new Input(entity.externalInput(), side)
                                : null);
    }

    private record Input(io.github.loongin.omniresonance.transfer.ExternalDomainInput input, Direction side)
            implements ISourceCap {
        public boolean canAcceptSource(int amount) {
            return amount > 0 && receiveSource(amount, true) > 0;
        }

        public boolean canProvideSource(int amount) {
            return false;
        }

        public int getMaxExtract() {
            return 0;
        }

        public int getMaxReceive() {
            return input.available(side, SourceVariant.TYPE) ? Integer.MAX_VALUE : 0;
        }

        public int getSource() {
            return 0;
        }

        public int getSourceCapacity() {
            return getMaxReceive();
        }

        public void setSource(int amount) {
            throw new UnsupportedOperationException("Delivery endpoint has no mutable inventory");
        }

        public void setMaxSource(int amount) {
            throw new UnsupportedOperationException("Delivery endpoint has no mutable capacity");
        }

        public int receiveSource(int amount, boolean simulate) {
            return amount <= 0 ? 0 : (int) input.insert(side, SourceVariant.INSTANCE, amount, simulate);
        }

        public int extractSource(int amount, boolean simulate) {
            return 0;
        }
    }

    private ArsResources() {}

    public static void register(ResourceAdapterDirectory directory) {
        directory.register(
                new ResourceAdapterDirectory.Descriptor(SourceVariant.TYPE, "Source", 1),
                BLOCK,
                (handler, provider) -> new SourceResourcePort(handler),
                (key, provider) -> SourceVariant.restore(key));
    }
}
