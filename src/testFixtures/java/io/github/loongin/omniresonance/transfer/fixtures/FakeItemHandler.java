// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer.fixtures;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/** Counted extended-capacity handler; native stack limits are explicitly optional. */
public final class FakeItemHandler implements IItemHandler {
    public final ItemStack[] stacks;
    public int calls, extractionCalls, insertionCalls, maximumRequest;
    public int capacity = Integer.MAX_VALUE, extractionLimit = Integer.MAX_VALUE, actualInsertLimit = Integer.MAX_VALUE;
    public boolean refuseSimulation, refuseReturn, throwExtract, throwInsert, wrongExtraction;
    public Runnable afterExtract = () -> {};
    public Runnable afterSlots = () -> {};
    public Runnable afterSimulatedExtract = () -> {};
    public int slotQueries;

    public FakeItemHandler(int slots) {
        stacks = new ItemStack[slots];
        java.util.Arrays.fill(stacks, ItemStack.EMPTY);
    }

    @Override
    public int getSlots() {
        calls++;
        slotQueries++;
        afterSlots.run();
        return stacks.length;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        calls++;
        return stacks[slot];
    }

    @Override
    public int getSlotLimit(int slot) {
        calls++;
        return capacity;
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        calls++;
        return true;
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        calls++;
        maximumRequest = Math.max(maximumRequest, amount);
        if (!simulate) {
            extractionCalls++;
            if (throwExtract) throw new IllegalStateException("Unknown extraction");
        }
        ItemStack stored = stacks[slot];
        if (stored.isEmpty()) return ItemStack.EMPTY;
        int count = Math.min(amount, Math.min(extractionLimit, stored.getCount()));
        ItemStack result = stored.copyWithCount(count);
        if (!simulate) {
            stacks[slot] = stored.copyWithCount(stored.getCount() - count);
            afterExtract.run();
            if (wrongExtraction) return new ItemStack(net.minecraft.world.item.Items.GOLD_INGOT, count);
        }
        if (simulate) afterSimulatedExtract.run();
        return result;
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        calls++;
        maximumRequest = Math.max(maximumRequest, stack.getCount());
        if (!simulate) {
            insertionCalls++;
            if (throwInsert) throw new IllegalStateException("Unknown insertion");
        }
        if (simulate && refuseSimulation || !simulate && refuseReturn) return stack.copy();
        ItemStack stored = stacks[slot];
        if (!stored.isEmpty() && !ItemStack.isSameItemSameComponents(stored, stack)) return stack.copy();
        int accepted = Math.min(stack.getCount(), Math.max(0, capacity - stored.getCount()));
        if (!simulate) {
            accepted = Math.min(accepted, actualInsertLimit);
            stacks[slot] = stack.copyWithCount(stored.getCount() + accepted);
        }
        return stack.copyWithCount(stack.getCount() - accepted);
    }
}
