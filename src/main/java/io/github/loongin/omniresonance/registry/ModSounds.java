// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.registry;

import io.github.loongin.omniresonance.bootstrap.OmniResonanceMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Common sound identities; registration never loads client playback classes. */
public final class ModSounds {
    public static final ResourceLocation UI_CLICK_ID =
            ResourceLocation.fromNamespaceAndPath(OmniResonanceMod.MOD_ID, "ui_click");
    private static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(BuiltInRegistries.SOUND_EVENT, OmniResonanceMod.MOD_ID);
    public static final DeferredHolder<SoundEvent, SoundEvent> UI_CLICK =
            SOUNDS.register("ui_click", () -> SoundEvent.createVariableRangeEvent(UI_CLICK_ID));

    private ModSounds() {}

    /** Attaches deferred sound registrations to the mod bus without reading or changing live world state. */
    public static void register(IEventBus modBus) {
        SOUNDS.register(modBus);
    }
}
