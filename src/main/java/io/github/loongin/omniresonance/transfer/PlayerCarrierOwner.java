// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Server-thread ownership of one carrier from a cursor stack. Unprocessed carriers stay on the cursor; processed
 * containers go to cursor, player inventory, then a normal player drop. At most the cursor, 36 inventory slots and one world
 * item are tracked, independently of resource quantity. Simulations do not consume a carrier or place overflow.
 * Known return calls can replace only the originally placed portion, after checking every captured location.
 */
public final class PlayerCarrierOwner implements TerminalCarrierPort.ContainerOwner {
    private final ServerPlayer player;
    private final ItemStack original, originalSnapshot;
    private final List<Piece> pieces = new ArrayList<>();
    private ItemStack working;
    private ItemStack emptyAnchor;
    private boolean settled;

    /** Captures one detached item for capability discovery, rejecting absent cursors or another open container. */
    public PlayerCarrierOwner(ServerPlayer player) {
        this.player = player;
        if (!player.server.isSameThread() || player.containerMenu != player.inventoryMenu)
            throw new IllegalStateException("Player inventory is unavailable");
        original = player.inventoryMenu.getCarried();
        if (original.isEmpty()) throw new IllegalArgumentException("No carried container");
        originalSnapshot = original.copy();
        working = original.copyWithCount(1);
        emptyAnchor = original;
    }

    public ItemStack stack() {
        return working;
    }

    public Object identity() {
        return player.inventoryMenu;
    }

    public int maximumSettlementCalls() {
        return 2;
    }

    public boolean current() {
        if (!player.server.isSameThread()) throw new IllegalStateException("Carrier accessed off server thread");
        if (player.containerMenu != player.inventoryMenu || !player.isAlive()) return false;
        if (!settled)
            return player.inventoryMenu.getCarried() == original && ItemStack.matches(original, originalSnapshot);
        if (pieces.isEmpty()) return player.inventoryMenu.getCarried() == emptyAnchor;
        for (Piece piece : pieces) if (!piece.current()) return false;
        return true;
    }

    public void settle(ItemStack value, TransferWorkBudget budget) {
        if (!current()) throw new IllegalStateException("Carrier placement no longer owned");
        try {
            if (!settled) {
                player.inventoryMenu.setCarried(
                        original.getCount() == 1 ? ItemStack.EMPTY : original.copyWithCount(original.getCount() - 1));
                settled = true;
            } else {
                for (Piece piece : pieces) piece.remove(budget);
            }
            pieces.clear();
            working = value;
            place(value.copy(), budget);
            emptyAnchor = player.inventoryMenu.getCarried();
        } finally {
            player.getInventory().setChanged();
        }
    }

    private void place(ItemStack value, TransferWorkBudget budget) {
        if (value.isEmpty()) return;
        ItemStack carried = player.inventoryMenu.getCarried();
        int room = carried.isEmpty()
                ? value.getMaxStackSize()
                : ItemStack.isSameItemSameComponents(carried, value)
                        ? Math.max(0, value.getMaxStackSize() - carried.getCount())
                        : 0;
        if (room > 0) {
            int count = Math.min(room, value.getCount());
            var result = value.copyWithCount(carried.getCount() + count);
            player.inventoryMenu.setCarried(result);
            pieces.add(new Piece(-1, null, result, count));
            value.shrink(count);
        }
        for (int pass = 0; pass < 2 && !value.isEmpty(); pass++)
            for (int slot = 0; slot < 36 && !value.isEmpty(); slot++) {
                ItemStack current = player.getInventory().getItem(slot);
                if ((pass == 0 && current.isEmpty()) || (pass == 1 && !current.isEmpty())) continue;
                if (!current.isEmpty() && !ItemStack.isSameItemSameComponents(current, value)) continue;
                int maximum = player.inventoryMenu
                        .getSlot(slot < 9 ? slot + 36 : slot)
                        .getMaxStackSize(value);
                int count = Math.min(value.getCount(), Math.max(0, maximum - current.getCount()));
                if (count == 0) continue;
                var result = value.copyWithCount(current.getCount() + count);
                player.getInventory().setItem(slot, result);
                pieces.add(new Piece(slot, null, result, count));
                value.shrink(count);
            }
        if (!value.isEmpty()) {
            ItemEntity dropped;
            ItemStack snapshot = value.copy();
            budget.beforeCall();
            try {
                dropped = player.drop(value, false);
            } finally {
                budget.afterCall();
            }
            if (dropped == null || !dropped.isAlive() || !ItemStack.matches(snapshot, dropped.getItem()))
                throw new IllegalStateException("Carrier overflow placement failed");
            pieces.add(new Piece(-2, dropped, dropped.getItem(), snapshot.getCount()));
        }
    }

    private final class Piece {
        private final int slot, owned;
        private final @Nullable ItemEntity entity;
        private final ItemStack expected, snapshot;

        Piece(int slot, @Nullable ItemEntity entity, ItemStack expected, int owned) {
            this.slot = slot;
            this.entity = entity;
            this.expected = expected;
            this.snapshot = expected.copy();
            this.owned = owned;
        }

        ItemStack actual() {
            return entity != null
                    ? entity.getItem()
                    : slot == -1
                            ? player.inventoryMenu.getCarried()
                            : player.getInventory().getItem(slot);
        }

        boolean current() {
            return (entity == null || entity.isAlive())
                    && actual() == expected
                    && ItemStack.matches(expected, snapshot);
        }

        void remove(TransferWorkBudget budget) {
            if (!current()) throw new IllegalStateException("Carrier remainder location changed");
            ItemStack remaining = expected.getCount() == owned
                    ? ItemStack.EMPTY
                    : expected.copyWithCount(expected.getCount() - owned);
            if (entity != null) {
                budget.beforeCall();
                try {
                    if (remaining.isEmpty()) entity.discard();
                    else entity.setItem(remaining);
                } finally {
                    budget.afterCall();
                }
            } else if (slot == -1) player.inventoryMenu.setCarried(remaining);
            else player.getInventory().setItem(slot, remaining);
        }
    }
}
