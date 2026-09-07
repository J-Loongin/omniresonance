// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.registry;

import io.github.loongin.omniresonance.bootstrap.OmniResonanceMod;
import io.github.loongin.omniresonance.node.ResonanceNodeMenu;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Owns common MenuType registrations without loading client Screen classes. */
public final class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(BuiltInRegistries.MENU, OmniResonanceMod.MOD_ID);
    public static final DeferredHolder<MenuType<?>, MenuType<ResonanceNodeMenu>> RESONANCE_NODE =
            MENUS.register("resonance_node", () -> IMenuTypeExtension.create(ResonanceNodeMenu::createClient));

    private ModMenus() {}

    /** Installs Menu types on the mod bus without accessing a player, world or client Screen. */
    public static void register(IEventBus modBus) {
        MENUS.register(modBus);
    }
}
