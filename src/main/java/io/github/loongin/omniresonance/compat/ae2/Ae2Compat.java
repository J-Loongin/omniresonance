// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.compat.ae2;

import appeng.api.AECapabilities;
import appeng.api.networking.GridHelper;
import io.github.loongin.omniresonance.registry.ModBlockEntities;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;

/** Optional bootstrap is reached only behind the ae2 loaded-mod guard. */
public final class Ae2Compat {
    private Ae2Compat() {}

    public static void register(IEventBus bus) {
        Ae2InterfaceContent.register(bus);
        bus.addListener(ResonanceKeys::register);
        bus.addListener((net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent event) -> event.enqueueWork(() -> {
            ResonanceCarrierStrategy.register();
        }));
        bus.addListener(Ae2InterfacePayloads::register);
        bus.addListener((net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent event) ->
                event.registerBlockEntity(
                        AECapabilities.IN_WORLD_GRID_NODE_HOST,
                        ModBlockEntities.RESONANCE_TRANSFER_NODE.get(),
                        (entity, unused) -> {
                            if (!(entity.getLevel() instanceof ServerLevel level)
                                    || !(entity.getBlockState().getBlock() instanceof Ae2InterfaceBlock)) return null;
                            var runtime = Ae2InterfaceRuntime.find(level.getServer());
                            return runtime == null ? null : runtime.host(entity);
                        }));
        NeoForge.EVENT_BUS.addListener((io.github.loongin.omniresonance.node.NodeLifecycleEvent.Loaded event) -> {
            if (event.entity().getBlockState().getBlock() instanceof Ae2InterfaceBlock)
                GridHelper.onFirstTick(event.entity(), entity -> {
                    var runtime = Ae2InterfaceRuntime.find(event.level().getServer());
                    if (runtime != null) runtime.loaded(entity);
                });
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.level.ChunkEvent.Unload event) -> {
            if (event.getLevel() instanceof ServerLevel level) {
                var runtime = Ae2InterfaceRuntime.find(level.getServer());
                if (runtime != null)
                    runtime.unload(level.dimension(), event.getChunk().getPos());
            }
        });
        NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) -> {
                    if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
                        var runtime = Ae2InterfaceRuntime.find(player.server);
                        if (runtime != null) runtime.logout(player);
                    }
                });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppingEvent event) -> {
            var runtime = Ae2InterfaceRuntime.find(event.getServer());
            if (runtime != null) runtime.close();
        });
    }
}
