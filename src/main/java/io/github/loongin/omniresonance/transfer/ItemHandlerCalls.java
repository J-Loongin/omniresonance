// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/** Counted synchronous API boundary. All supplied stacks are owned copies, including simulation inputs. */
final class ItemHandlerCalls {
    private ItemHandlerCalls() {}

    static int slots(IItemHandler handler, TransferWorkBudget b) {
        b.beforeCall();
        try {
            int slots = handler.getSlots();
            if (slots < 0) throw new IllegalArgumentException("Negative slot count");
            return slots;
        } finally {
            b.afterCall();
        }
    }

    static ItemStack peek(IItemHandler handler, int slot, TransferWorkBudget b) {
        b.beforeCall();
        try {
            return handler.getStackInSlot(slot).copy();
        } finally {
            b.afterCall();
        }
    }

    static ItemStack extract(IItemHandler handler, int slot, int amount, boolean simulate, TransferWorkBudget b) {
        b.beforeCall();
        try {
            return handler.extractItem(slot, amount, simulate).copy();
        } finally {
            b.afterCall();
        }
    }

    static ItemStack insert(IItemHandler handler, int slot, ItemStack stack, boolean simulate, TransferWorkBudget b) {
        b.beforeCall();
        try {
            return handler.insertItem(slot, stack.copy(), simulate).copy();
        } finally {
            b.afterCall();
        }
    }

    static boolean validAmount(ItemStack stack, ItemStack identity, int maximum) {
        return stack.isEmpty()
                || stack.getCount() > 0
                        && stack.getCount() <= maximum
                        && ItemStack.isSameItemSameComponents(stack, identity);
    }
}
