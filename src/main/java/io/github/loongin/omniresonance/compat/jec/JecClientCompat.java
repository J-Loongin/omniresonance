// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.compat.jec;

import io.github.loongin.omniresonance.bootstrap.OmniResonanceMod;
import io.github.loongin.omniresonance.client.ClientTextSearch;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Client-only bootstrap; missing JEC never resolves its adapter or classes on either logical side. */
@EventBusSubscriber(modid = OmniResonanceMod.MOD_ID, value = Dist.CLIENT)
public final class JecClientCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger(JecClientCompat.class);

    private JecClientCompat() {}

    /** Installs the optional matcher on the client thread after mod construction; no server state is touched. */
    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ClientTextSearch.usePlain();
            if (ModList.get().isLoaded("jecharacters")) {
                ClientTextSearch.install(new JecSearchMatcher());
                LOGGER.info("Enabled optional JEC client text search");
            }
        });
    }
}
