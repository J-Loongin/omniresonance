// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/** Server-thread one-slot item endpoint for a player's carried stack or explicit inventory slot. No client predictions. */
public final class TerminalItemSlotPort implements ResourceTransferEngine.Handle {
    private final Thread owner = Thread.currentThread();
    private final TerminalCarrierPort.Slot slot;
    private final BooleanSupplier authorized;
    private ItemStack expected;
    private final ResourcePort port;
    private final ResourceTransferEngine.Handle remainder;

    /** Captures a real slot by identity. Simulation only constructs detached remainders; real writes replace owned contents. */
    public TerminalItemSlotPort(
            TerminalCarrierPort.Slot slot, int slotLimit, HolderLookup.Provider provider, BooleanSupplier authorized) {
        this.slot = Objects.requireNonNull(slot);
        this.authorized = Objects.requireNonNull(authorized);
        if (slotLimit < 1) throw new IllegalArgumentException("Invalid player slot limit");
        expected = Objects.requireNonNull(slot.stack());
        port = new ItemResourcePort(
                new IItemHandler() {
                    public int getSlots() {
                        return 1;
                    }

                    public ItemStack getStackInSlot(int index) {
                        require(index);
                        return expected;
                    }

                    public int getSlotLimit(int index) {
                        require(index);
                        return slotLimit;
                    }

                    public boolean isItemValid(int index, ItemStack stack) {
                        require(index);
                        return true;
                    }

                    public ItemStack insertItem(int index, ItemStack offered, boolean simulate) {
                        require(index);
                        if (offered.isEmpty()) return ItemStack.EMPTY;
                        if (!expected.isEmpty() && !ItemStack.isSameItemSameComponents(expected, offered))
                            return offered.copy();
                        int accepted = Math.min(
                                offered.getCount(),
                                Math.max(0, Math.min(slotLimit, offered.getMaxStackSize()) - expected.getCount()));
                        if (!simulate && accepted > 0) write(offered.copyWithCount(expected.getCount() + accepted));
                        return accepted == offered.getCount()
                                ? ItemStack.EMPTY
                                : offered.copyWithCount(offered.getCount() - accepted);
                    }

                    public ItemStack extractItem(int index, int amount, boolean simulate) {
                        require(index);
                        if (amount <= 0 || expected.isEmpty()) return ItemStack.EMPTY;
                        int extracted = Math.min(amount, expected.getCount());
                        ItemStack result = expected.copyWithCount(extracted);
                        if (!simulate)
                            write(
                                    extracted == expected.getCount()
                                            ? ItemStack.EMPTY
                                            : expected.copyWithCount(expected.getCount() - extracted));
                        return result;
                    }
                },
                provider);
        remainder = new ResourceTransferEngine.Handle() {
            public ResourcePort port() {
                return port;
            }

            public Object physicalIdentity() {
                return slot;
            }

            public boolean valid() {
                return owned();
            }
        };
    }

    private boolean owned() {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Player item slot accessed off server thread");
        return slot.current() && slot.stack() == expected;
    }

    private void require(int index) {
        if (index != 0 || !owned()) throw new IllegalStateException("Player slot is no longer owned");
    }

    private void write(ItemStack value) {
        slot.stack(value);
        expected = value;
    }

    public ResourcePort port() {
        return port;
    }

    public Object physicalIdentity() {
        return slot;
    }

    public boolean valid() {
        return owned() && authorized.getAsBoolean();
    }

    public ResourceTransferEngine.Handle remainderTarget() {
        return remainder;
    }
}
