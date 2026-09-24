// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.compat.emi;

import io.github.loongin.omniresonance.client.RecipeTagCopy;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/** Client-only guard: absent EMI never resolves the adapter or its external types. */
@EventBusSubscriber(modid = "omniresonance", value = Dist.CLIENT)
public final class EmiClientCompat {
    private EmiClientCompat() {}

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            if (ModList.get().isLoaded("emi")) RecipeTagCopy.install(RecipeTagCopy.Source.EMI, new EmiTagProvider());
        });
    }
}
