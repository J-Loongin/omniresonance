// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.bootstrap;

import io.github.loongin.omniresonance.config.ServerConfig;
import io.github.loongin.omniresonance.datagen.OmniDataGenerators;
import io.github.loongin.omniresonance.material.DragonBreathDispenseBehavior;
import io.github.loongin.omniresonance.material.DragonBreathPlayerInteraction;
import io.github.loongin.omniresonance.networking.NetworkPayloads;
import io.github.loongin.omniresonance.node.NodeLifecycleEvent;
import io.github.loongin.omniresonance.registry.ModBlockEntities;
import io.github.loongin.omniresonance.registry.ModBlocks;
import io.github.loongin.omniresonance.registry.ModCreativeTabs;
import io.github.loongin.omniresonance.registry.ModItems;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Initializes the mod without registering content from later milestones. */
@Mod(OmniResonanceMod.MOD_ID)
public final class OmniResonanceMod {
    public static final String MOD_ID = "omniresonance";
    private static final Logger LOGGER = LoggerFactory.getLogger(OmniResonanceMod.class);
    private final ServerConfig serverConfig;
    private final NetworkRuntimeRegistry networkRuntime;

    /**
     * Runs during FML construction, without accessing live world state or retaining the container.
     * This constructor performs no resource operations and propagates initialization failures.
     */
    public OmniResonanceMod(IEventBus modBus, ModContainer container) {
        ModItems.register(modBus);
        ModCreativeTabs.register(modBus);
        ModBlocks.register(modBus);
        ModBlockEntities.register(modBus);
        modBus.addListener(OmniDataGenerators::gatherData);
        serverConfig = new ServerConfig();
        container.registerConfig(ModConfig.Type.SERVER, serverConfig.spec(), "omniresonance-server.toml");
        modBus.addListener(serverConfig::onLoading);
        modBus.addListener(serverConfig::onReloading);
        modBus.addListener(serverConfig::onUnloading);
        networkRuntime = new NetworkRuntimeRegistry(serverConfig);
        modBus.addListener((RegisterPayloadHandlersEvent event) -> NetworkPayloads.register(event, networkRuntime));
        modBus.addListener(OmniResonanceMod::onCommonSetup);
        NeoForge.EVENT_BUS.addListener(
                EventPriority.LOWEST,
                false,
                PlayerInteractEvent.RightClickBlock.class,
                DragonBreathPlayerInteraction::onRightClickBlock);
        NeoForge.EVENT_BUS.addListener(networkRuntime::onServerStarted);
        NeoForge.EVENT_BUS.addListener(networkRuntime::onServerStopped);
        NeoForge.EVENT_BUS.addListener(networkRuntime::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(networkRuntime::onPlayerClone);
        NeoForge.EVENT_BUS.addListener(networkRuntime::onServerTick);
        NeoForge.EVENT_BUS.addListener(NodeLifecycleEvent.Loaded.class, networkRuntime::onNodeLifecycle);
        NeoForge.EVENT_BUS.addListener(NodeLifecycleEvent.Removed.class, networkRuntime::onNodeLifecycle);
        NeoForge.EVENT_BUS.addListener(ChunkEvent.Load.class, networkRuntime::onChunkLoad);
        LOGGER.info("Initializing {} {}", MOD_ID, container.getModInfo().getVersion());
    }

    private static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(DragonBreathDispenseBehavior::register);
    }
}
