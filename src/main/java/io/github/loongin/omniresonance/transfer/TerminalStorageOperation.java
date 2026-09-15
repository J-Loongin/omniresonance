// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import io.github.loongin.omniresonance.config.ServerSettings;
import io.github.loongin.omniresonance.networking.TerminalStorageResponse.Status;
import io.github.loongin.omniresonance.recovery.RecoveryBuffer;
import io.github.loongin.omniresonance.storage.DomainLedger;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import org.jetbrains.annotations.Nullable;

/**
 * One server-thread terminal click. Only discovery indices, an immutable server-selected key, and confirmed movement totals survive suspension;
 * no native handler, uncommitted quantity, or simulation promise survives. The caller serializes player inventory, verifies menu revisions,
 * rechecks session permissions, and stops after every terminal outcome (including unknown mutations).
 */
public final class TerminalStorageOperation {
    public record Outcome(Status status, long moved) {}

    private static final ResourceAdapterDirectory ADAPTERS = ResourceAdapterDirectory.nativeDefaults();
    private final int inventorySlot, button;
    private final long resourceId;
    private final boolean shift;
    private final @Nullable ResourceVariantKey bulkKey;
    private int bulkSlot;
    private long bulkMoved;
    private @Nullable ResourceVariantKey quickMoveKey;
    private int tank;
    private boolean fluidScanned;
    private final TerminalBucketLease.Search buckets = new TerminalBucketLease.Search();
    private final DomainTransferEngine engine = new DomainTransferEngine();

    public TerminalStorageOperation(int inventorySlot, long resourceId, int button, boolean shift) {
        this(inventorySlot, resourceId, button, shift, null);
    }

    /** The optional bulk key is server-derived from the preceding authorized quick move, never from a client stack. */
    public TerminalStorageOperation(
            int inventorySlot, long resourceId, int button, boolean shift, @Nullable ResourceVariantKey bulkKey) {
        if (inventorySlot < -1
                || inventorySlot > 35
                || resourceId < 0
                || button < 0
                || button > 1
                || inventorySlot >= 0 && resourceId != 0) throw new IllegalArgumentException("Invalid terminal click");
        this.inventorySlot = inventorySlot;
        this.resourceId = resourceId;
        this.button = button;
        this.shift = shift;
        if (bulkKey != null
                && (!shift
                        || button != 0
                        || inventorySlot < 0
                        || !bulkKey.typeId().equals(ResourceTypes.ITEM)))
            throw new IllegalArgumentException("Invalid bulk item move");
        this.bulkKey = bulkKey;
    }

    /** Performs bounded units while budget permits. WAITING_BUDGET means no extraction in this step; PROGRESS retains confirmed partial work. */
    public Outcome step(
            ServerPlayer player,
            DomainLedger ledger,
            RecoveryBuffer recovery,
            ServerSettings settings,
            BooleanSupplier authorized,
            TransferWorkBudget budget) {
        if (!player.server.isSameThread()) throw new IllegalStateException("Terminal click off server thread");
        if (!current(player) || !authorized.getAsBoolean() || !ledger.isAvailable()) return outcome(Status.DENIED);
        if (!budget.canStart()) return outcome(Status.WAITING_BUDGET);
        try {
            if (bulkKey != null) return bulkDeposit(player, ledger, recovery, settings, authorized, budget);
            if (inventorySlot >= 0 && !shift) {
                player.inventoryMenu.clicked(
                        inventorySlot < 9 ? inventorySlot + 36 : inventorySlot, button, ClickType.PICKUP, player);
                return outcome(Status.COMPLETE);
            }
            ItemStack held = inventorySlot >= 0
                    ? player.getInventory().getItem(inventorySlot)
                    : player.inventoryMenu.getCarried();
            if (inventorySlot < 0 && button == 1 && !held.isEmpty()) {
                Outcome carrier = emptyCarrier(player, ledger, recovery, settings, authorized, budget);
                if (carrier != null) return carrier;
            }
            var entry = resourceId == 0 ? null : ledger.findSequence(resourceId).orElse(null);
            if (resourceId > 0 && entry == null) return outcome(Status.STALE);
            ResourceVariant variant = entry == null
                    ? null
                    : ADAPTERS.decode(entry.key(), player.registryAccess()).orElse(null);
            if (inventorySlot < 0 && shift && button == 0 && variant instanceof ItemVariant item)
                return quickTake(player, ledger, recovery, settings, authorized, budget, item);
            if (inventorySlot < 0 && button == 0 && variant != null && !(variant instanceof ItemVariant)) {
                TerminalBucketLease lease = null;
                if (held.isEmpty()) {
                    if (!(variant instanceof FluidVariant fluid)) return outcome(Status.NO_CARRIER);
                    if (ledger.amount(fluid.key()) < 1000) return outcome(Status.INSUFFICIENT_FLUID);
                    lease = buckets.acquire(player, ledger, fluid, authorized, budget);
                    if (lease == null) return outcome(buckets.exhausted() ? Status.NO_BUCKET : Status.WAITING_BUDGET);
                }
                try (var acquired = lease) {
                    var owner = new PlayerCarrierOwner(player);
                    TerminalCarrierPort port = carrier(player, owner, variant, authorized, budget);
                    if (port == null) {
                        if (acquired != null) acquired.restore(budget);
                        return outcome(Status.NO_CARRIER);
                    }
                    var transferred = engine.withdrawGreedy(
                            ledger,
                            port,
                            0,
                            variant,
                            acquired == null ? carrierAmount(variant) : 1000,
                            authorized,
                            recovery,
                            settings.recoveryLimits(),
                            budget,
                            true);
                    if (acquired != null
                            && transferred.moved() == 0
                            && transferred.failure() != ResourceTransferEngine.Failure.UNKNOWN_MUTATION)
                        acquired.restore(budget);
                    return result(transferred);
                }
            }
            var slot = slot(player, inventorySlot);
            var port = new TerminalItemSlotPort(slot, Integer.MAX_VALUE, player.registryAccess(), authorized);
            if (!held.isEmpty()) {
                variant = ItemVariant.from(held, player.registryAccess());
                if (inventorySlot >= 0 && shift && button == 0) quickMoveKey = variant.key();
                long amount = inventorySlot >= 0 || button == 0 ? held.getCount() : 1;
                return result(engine.depositGreedy(
                        port,
                        0,
                        ledger,
                        variant,
                        amount,
                        settings.storageVariantLimitPerNetwork(),
                        authorized,
                        recovery,
                        settings.recoveryLimits(),
                        budget,
                        true));
            }
            if (!(variant instanceof ItemVariant item))
                return outcome(resourceId == 0 ? Status.COMPLETE : Status.REFUSED);
            long amount = Math.min(entry.amount(), item.stack(1).getMaxStackSize());
            if (button == 1) amount = (amount + 1) / 2;
            return result(engine.withdrawGreedy(
                    ledger, port, 0, item, amount, authorized, recovery, settings.recoveryLimits(), budget, true));
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(TerminalStorageOperation.class)
                    .error("Terminal click preparation failed; stopping without retry", failure);
            return new Outcome(Status.FAILED, bulkMoved);
        } finally {
            player.getInventory().setChanged();
        }
    }

    /** Confirmed quantity already moved by this logical operation, for cancellation after budget suspension. */
    public @Nullable ResourceVariantKey quickMoveKey() {
        return quickMoveKey;
    }

    public long movedSoFar() {
        return bulkMoved;
    }

    private Outcome bulkDeposit(
            ServerPlayer player,
            DomainLedger ledger,
            RecoveryBuffer recovery,
            ServerSettings settings,
            BooleanSupplier authorized,
            TransferWorkBudget budget) {
        var decoded = ADAPTERS.decode(bulkKey, player.registryAccess()).orElse(null);
        if (!(decoded instanceof ItemVariant item)) return new Outcome(Status.REFUSED, bulkMoved);
        var probe = item.stack(1);
        boolean firstUnit = true;
        while (bulkSlot < 36) {
            if (!firstUnit && !budget.canStart()) return new Outcome(Status.PROGRESS, bulkMoved);
            if (!authorized.getAsBoolean()) return new Outcome(Status.DENIED, bulkMoved);
            firstUnit = false;
            int slotIndex = bulkSlot++;
            var stack = player.getInventory().getItem(slotIndex);
            if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, probe)) continue;
            var source = new TerminalItemSlotPort(
                    slot(player, slotIndex), Integer.MAX_VALUE, player.registryAccess(), authorized);
            var transferred = engine.depositGreedy(
                    source,
                    0,
                    ledger,
                    item,
                    stack.getCount(),
                    settings.storageVariantLimitPerNetwork(),
                    authorized,
                    recovery,
                    settings.recoveryLimits(),
                    budget,
                    true);
            bulkMoved = Math.addExact(bulkMoved, transferred.moved());
            var outcome = result(transferred);
            if (outcome.status() != Status.COMPLETE) return new Outcome(outcome.status(), bulkMoved);
        }
        return new Outcome(Status.COMPLETE, bulkMoved);
    }

    /** Mirrors AE2 quick extraction: prefer a compatible partial stack, otherwise the first empty player slot. */
    private Outcome quickTake(
            ServerPlayer player,
            DomainLedger ledger,
            RecoveryBuffer recovery,
            ServerSettings settings,
            BooleanSupplier authorized,
            TransferWorkBudget budget,
            ItemVariant item) {
        ItemStack probe = item.stack(1);
        for (int pass = 0; pass < 2; pass++)
            for (int offset = 0; offset < 36; offset++) {
                int index = (offset + 9) % 36;
                ItemStack target = player.getInventory().getItem(index);
                if (pass == 0
                        && (target.isEmpty()
                                || !ItemStack.isSameItemSameComponents(target, probe)
                                || target.getCount() >= probe.getMaxStackSize())) continue;
                if (pass == 1 && !target.isEmpty()) continue;
                var port = new TerminalItemSlotPort(
                        slot(player, index), Integer.MAX_VALUE, player.registryAccess(), authorized);
                return result(engine.withdrawGreedy(
                        ledger,
                        port,
                        0,
                        item,
                        probe.getMaxStackSize(),
                        authorized,
                        recovery,
                        settings.recoveryLimits(),
                        budget,
                        true));
            }
        return outcome(Status.NO_SPACE);
    }

    private @Nullable Outcome emptyCarrier(
            ServerPlayer player,
            DomainLedger ledger,
            RecoveryBuffer recovery,
            ServerSettings settings,
            BooleanSupplier authorized,
            TransferWorkBudget budget) {
        var owner = new PlayerCarrierOwner(player);
        if (!fluidScanned) {
            var port = carrier(player, owner, FluidVariant.class, authorized, budget);
            if (port != null) {
                int count = port.sourceViews(budget);
                if (tank < count) {
                    var candidate = port.peek(tank, budget).orElse(null);
                    if (candidate != null)
                        return result(engine.depositGreedy(
                                port,
                                tank,
                                ledger,
                                candidate.variant(),
                                carrierAmount(candidate.variant()),
                                settings.storageVariantLimitPerNetwork(),
                                authorized,
                                recovery,
                                settings.recoveryLimits(),
                                budget,
                                true));
                    tank++;
                    if (tank < count) return outcome(Status.WAITING_BUDGET);
                }
            }
            fluidScanned = true;
        }
        if (!budget.canStart()) return outcome(Status.WAITING_BUDGET);
        var port = carrier(player, owner, EnergyVariant.class, authorized, budget);
        if (port == null) return null;
        port.sourceViews(budget);
        var candidate = port.peek(0, budget).orElse(null);
        if (candidate == null) return null;
        return result(engine.depositGreedy(
                port,
                0,
                ledger,
                candidate.variant(),
                carrierAmount(candidate.variant()),
                settings.storageVariantLimitPerNetwork(),
                authorized,
                recovery,
                settings.recoveryLimits(),
                budget,
                true));
    }

    private long carrierAmount(ResourceVariant variant) {
        return shift
                ? Integer.MAX_VALUE
                : ADAPTERS.find(variant.key().typeId()).orElseThrow().defaultBatchSize();
    }

    private static @Nullable TerminalCarrierPort carrier(
            ServerPlayer player,
            PlayerCarrierOwner owner,
            ResourceVariant variant,
            BooleanSupplier authorized,
            TransferWorkBudget budget) {
        return carrier(player, owner, variant.getClass(), authorized, budget);
    }

    private static @Nullable TerminalCarrierPort carrier(
            ServerPlayer player,
            PlayerCarrierOwner owner,
            Class<?> type,
            BooleanSupplier authorized,
            TransferWorkBudget budget) {
        budget.beforeCall();
        try {
            if (type == FluidVariant.class) {
                var handler = owner.stack().getCapability(Capabilities.FluidHandler.ITEM);
                return handler == null
                        ? null
                        : TerminalCarrierPort.fluid(owner, owner.stack(), handler, player.registryAccess(), authorized);
            }
            if (type == EnergyVariant.class) {
                var handler = owner.stack().getCapability(Capabilities.EnergyStorage.ITEM);
                return handler == null ? null : TerminalCarrierPort.energy(owner, owner.stack(), handler, authorized);
            }
            return null;
        } finally {
            budget.afterCall();
        }
    }

    private static TerminalCarrierPort.Slot slot(ServerPlayer player, int index) {
        return new TerminalCarrierPort.Slot() {
            public ItemStack stack() {
                return index < 0
                        ? player.inventoryMenu.getCarried()
                        : player.getInventory().getItem(index);
            }

            public void stack(ItemStack stack) {
                if (index < 0) player.inventoryMenu.setCarried(stack);
                else player.getInventory().setItem(index, stack);
            }

            public boolean current() {
                return TerminalStorageOperation.current(player);
            }
        };
    }

    private static boolean current(ServerPlayer player) {
        return player.containerMenu == player.inventoryMenu && player.isAlive() && !player.isSpectator();
    }

    private static Outcome outcome(Status status) {
        return new Outcome(status, 0);
    }

    private static Outcome result(ResourceTransferEngine.Result result) {
        if (result.cause() != null)
            org.slf4j.LoggerFactory.getLogger(TerminalStorageOperation.class)
                    .error(
                            "Terminal resource operation stopped with {} at {} (requested {})",
                            result.failure(),
                            result.unknownStage(),
                            result.unknownRequested(),
                            result.cause());
        return new Outcome(
                switch (result.failure()) {
                    case NONE -> Status.COMPLETE;
                    case REFUSED -> Status.REFUSED;
                    case RECOVERY_FULL -> Status.RECOVERY_FULL;
                    case INVALID_ENDPOINT -> Status.DENIED;
                    case WAITING_BUDGET -> Status.WAITING_BUDGET;
                    case UNKNOWN_MUTATION -> Status.UNKNOWN;
                    case INCONSISTENT, EXCEPTION -> Status.FAILED;
                },
                result.moved());
    }
}
