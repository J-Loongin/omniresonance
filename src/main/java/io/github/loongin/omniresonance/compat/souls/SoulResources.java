// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.compat.souls;

import com.buuz135.industrialforegoingsouls.block_network.SoulNetwork;
import com.buuz135.industrialforegoingsouls.capabilities.ISoulHandler;
import com.buuz135.industrialforegoingsouls.capabilities.SoulCapabilities;
import com.hrznstudio.titanium.block_network.NetworkManager;
import io.github.loongin.omniresonance.transfer.ResourceAdapterDirectory;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import org.jetbrains.annotations.Nullable;

/** Optional Souls 1.10.7 bridge through public pipe-network methods. Uses its own capability namespace so native
 * pipes cannot discover and exchange resources with their own network through this adapter. */
public final class SoulResources {
    public static final BlockCapability<ISoulHandler, Direction> BLOCK =
            BlockCapability.createSided(ResourceLocation.parse("omniresonance:soul_endpoint"), ISoulHandler.class);

    private SoulResources() {}

    public static void register(ResourceAdapterDirectory directory) {
        directory.register(
                new ResourceAdapterDirectory.Descriptor(SoulVariant.TYPE, "Soul", 1),
                BLOCK,
                (handler, registries) -> new SoulResourcePort(handler),
                (key, registries) -> SoulVariant.restore(key));
    }

    public static void capabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
                SoulCapabilities.BLOCK,
                io.github.loongin.omniresonance.registry.ModBlockEntities.RESONANCE_TRANSFER_NODE.get(),
                (entity, side) -> entity.pipeConnection(side, 5) && entity.externalInput() != null
                        ? new Input(entity.externalInput(), side)
                        : null);
        event.registerBlock(
                BLOCK,
                (level, pos, state, entity, side) -> level instanceof ServerLevel server && entity != null
                        ? new PipeHandler(server, entity, NetworkManager.get(server))
                        : null,
                BuiltInRegistries.BLOCK.get(ResourceLocation.parse("industrialforegoingsouls:soul_network_pipe")));
        event.registerBlock(
                BLOCK,
                (level, pos, state, entity, side) ->
                        level.getCapability(SoulCapabilities.BLOCK, pos, state, entity, side),
                BuiltInRegistries.BLOCK.get(ResourceLocation.parse("industrialforegoingsouls:soul_laser_base")));
    }

    private record Input(io.github.loongin.omniresonance.transfer.ExternalDomainInput input, Direction side)
            implements ISoulHandler {
        public int getSoulTanks() {
            return 1;
        }

        public int getSoulInTank(int tank) {
            return 0;
        }

        public int getTankCapacity(int tank) {
            return input.available(side, SoulVariant.TYPE) ? Integer.MAX_VALUE : 0;
        }

        public int fill(int amount, Action action) {
            return amount <= 0 ? 0 : (int) input.insert(side, SoulVariant.INSTANCE, amount, action.simulate());
        }

        public int drain(int amount, Action action) {
            return 0;
        }
    }

    private record PipeHandler(ServerLevel level, BlockEntity entity, NetworkManager manager) implements ISoulHandler {
        private @Nullable SoulNetwork network() {
            if (!level.getServer().isSameThread())
                throw new IllegalStateException("Soul network accessed off server thread");
            var pos = entity.getBlockPos();
            if (!level.isLoaded(pos) || entity.isRemoved() || level.getBlockEntity(pos) != entity) return null;
            var element = manager.getElement(pos);
            return element != null && element.getNetwork() instanceof SoulNetwork souls ? souls : null;
        }

        private static int stored(SoulNetwork network) {
            int amount = network.getSoulAmount();
            if (amount < 0) throw new IllegalStateException("Negative native soul network balance");
            return amount;
        }

        private static int capacity(SoulNetwork network) {
            int capacity = network.getMaxSouls();
            if (capacity < 0) throw new IllegalStateException("Native soul network capacity overflow");
            return capacity;
        }

        private static void view(int tank) {
            if (tank != 0) throw new IllegalArgumentException("Soul network has one logical view");
        }

        public int getSoulTanks() {
            return 1;
        }

        public int getSoulInTank(int tank) {
            view(tank);
            var network = network();
            return network == null ? 0 : stored(network);
        }

        public int getTankCapacity(int tank) {
            view(tank);
            var network = network();
            return network == null ? 0 : capacity(network);
        }

        public int fill(int amount, Action action) {
            if (amount <= 0) return 0;
            var network = network();
            if (network == null) return 0;
            int accepted = (int) Math.min(amount, Math.max(0L, (long) capacity(network) - stored(network)));
            return action.simulate() || accepted == 0 ? accepted : network.addSouls(level, accepted);
        }

        public int drain(int amount, Action action) {
            if (amount <= 0) return 0;
            var network = network();
            if (network == null) return 0;
            int removed = Math.min(amount, stored(network));
            return action.simulate() || removed == 0 ? removed : network.drainSouls(level, removed);
        }
    }
}
