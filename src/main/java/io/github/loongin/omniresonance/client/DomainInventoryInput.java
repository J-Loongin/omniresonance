// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/** Client-thread input and native inventory access; owns no inventory, prediction, cache or network state. */
interface DomainInventoryInput {
    boolean shiftDown();

    long millis();

    @Nullable
    Inventory inventory();

    ItemStack carried();

    int menuState();

    @Nullable
    HolderLookup.Provider registries();

    void clickFeedback();

    DomainInventoryInput MINECRAFT = new DomainInventoryInput() {
        public boolean shiftDown() {
            return Screen.hasShiftDown();
        }

        public long millis() {
            return net.minecraft.Util.getMillis();
        }

        public @Nullable Inventory inventory() {
            var client = Minecraft.getInstance();
            return client == null || client.player == null ? null : client.player.getInventory();
        }

        public ItemStack carried() {
            var player = Minecraft.getInstance().player;
            return player == null ? ItemStack.EMPTY : player.inventoryMenu.getCarried();
        }

        public int menuState() {
            return Minecraft.getInstance().player.inventoryMenu.getStateId();
        }

        public @Nullable HolderLookup.Provider registries() {
            var client = Minecraft.getInstance();
            return client == null || client.level == null ? null : client.level.registryAccess();
        }

        public void clickFeedback() {
            Minecraft.getInstance()
                    .getSoundManager()
                    .play(TerminalClickButton.clickFeedback().createSound());
        }
    };
}
