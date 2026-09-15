// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import io.github.loongin.omniresonance.storage.DomainLedger;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.Nullable;

/** One borrowed empty bucket moved to the cursor; known rejection restores it, unknown carrier mutation never guesses a refund. */
final class TerminalBucketLease implements AutoCloseable {
    private final ServerPlayer player;
    private final ItemStack bucket, remainder, remainderSnapshot;
    private final int inventorySlot;
    private final @Nullable DomainLedger.Withdrawal withdrawal;

    private TerminalBucketLease(
            ServerPlayer player, ItemStack bucket, int slot, @Nullable DomainLedger.Withdrawal withdrawal) {
        this.player = player;
        this.bucket = bucket.copyWithCount(1);
        this.inventorySlot = slot;
        this.withdrawal = withdrawal;
        remainder = slot < 0 ? ItemStack.EMPTY : bucket.copyWithCount(bucket.getCount() - 1);
        remainderSnapshot = remainder.copy();
        if (slot >= 0) player.getInventory().setItem(slot, remainder);
        player.inventoryMenu.setCarried(this.bucket.copy());
    }

    /** Discovery owns only finite sequence hints across ticks; decoded variants and native handlers never escape a step. */
    static final class Search {
        private long cursor, ceiling = -1;
        private boolean exhausted;
        private final ResourceAdapterDirectory adapters = ResourceAdapterDirectory.nativeDefaults();

        boolean exhausted() {
            return exhausted;
        }

        @Nullable
        TerminalBucketLease acquire(
                ServerPlayer player,
                DomainLedger ledger,
                FluidVariant fluid,
                BooleanSupplier authorized,
                TransferWorkBudget budget) {
            if (ledger.amount(fluid.key()) < 1000
                    || !player.inventoryMenu.getCarried().isEmpty()) {
                exhausted = true;
                return null;
            }
            for (int slot = 0; slot < 36; slot++) {
                var stack = player.getInventory().getItem(slot);
                if (!stack.is(Items.BUCKET)) continue;
                var snapshot = stack.copy();
                if (!accepts(snapshot, fluid, budget)) continue;
                if (!authorized.getAsBoolean()
                        || !player.inventoryMenu.getCarried().isEmpty()
                        || player.getInventory().getItem(slot) != stack
                        || !ItemStack.matches(stack, snapshot)
                        || ledger.amount(fluid.key()) < 1000) {
                    exhausted = true;
                    return null;
                }
                return new TerminalBucketLease(player, stack, slot, null);
            }
            var ordinary = ItemVariant.from(new ItemStack(Items.BUCKET), player.registryAccess());
            if (ledger.amount(ordinary.key()) > 0)
                return takeDomain(player, ledger, ordinary, fluid, authorized, budget);
            if (ceiling < 0) ceiling = ledger.sequenceCeiling();
            for (int step = 0; step < 128 && (step == 0 || budget.canFit(0)); step++) {
                var entry = ledger.nextWithin(cursor, ceiling).orElse(null);
                if (entry == null) {
                    exhausted = true;
                    return null;
                }
                cursor = entry.sequence();
                if (!entry.key().typeId().equals(ResourceTypes.ITEM)) continue;
                var variant =
                        adapters.decode(entry.key(), player.registryAccess()).orElse(null);
                if (variant instanceof ItemVariant item && item.stack(1).is(Items.BUCKET))
                    return takeDomain(player, ledger, item, fluid, authorized, budget);
            }
            return null;
        }

        private @Nullable TerminalBucketLease takeDomain(
                ServerPlayer player,
                DomainLedger ledger,
                ItemVariant item,
                FluidVariant fluid,
                BooleanSupplier authorized,
                TransferWorkBudget budget) {
            var stack = item.stack(1);
            if (!accepts(stack, fluid, budget)
                    || !authorized.getAsBoolean()
                    || !player.inventoryMenu.getCarried().isEmpty()
                    || ledger.amount(fluid.key()) < 1000) {
                exhausted = true;
                return null;
            }
            var withdrawal = ledger.withdraw(item.key(), 1).orElse(null);
            if (withdrawal == null) {
                exhausted = true;
                return null;
            }
            return new TerminalBucketLease(player, stack, -1, withdrawal);
        }

        private boolean accepts(ItemStack bucket, FluidVariant fluid, TransferWorkBudget budget) {
            net.neoforged.neoforge.fluids.capability.IFluidHandlerItem handler;
            budget.beforeCall();
            try {
                handler = bucket.copyWithCount(1).getCapability(Capabilities.FluidHandler.ITEM);
            } finally {
                budget.afterCall();
            }
            if (handler == null) return false;
            budget.beforeCall();
            try {
                return handler.fill(fluid.stack(1000), IFluidHandler.FluidAction.SIMULATE) == 1000;
            } finally {
                budget.afterCall();
            }
        }
    }

    /** Moves only the still-present unchanged empty bucket, independent of permission to start new work. */
    void restore(TransferWorkBudget budget) {
        var carried = player.inventoryMenu.getCarried();
        if (!ItemStack.matches(carried, bucket)) return;
        if (withdrawal != null) {
            withdrawal.returnRemainder(1);
            player.inventoryMenu.setCarried(ItemStack.EMPTY);
        } else if (player.getInventory().getItem(inventorySlot) == remainder
                && ItemStack.matches(remainder, remainderSnapshot)) {
            player.getInventory().setItem(inventorySlot, bucket.copyWithCount(remainder.getCount() + 1));
            player.inventoryMenu.setCarried(ItemStack.EMPTY);
        } else {
            player.inventoryMenu.setCarried(ItemStack.EMPTY);
            budget.beforeCall();
            try {
                player.getInventory().placeItemBackInInventory(carried);
            } finally {
                budget.afterCall();
            }
        }
    }

    public void close() {
        if (withdrawal != null) withdrawal.close();
    }
}
