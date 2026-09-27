// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.network;

import io.github.loongin.omniresonance.config.ServerSettings;
import io.github.loongin.omniresonance.networking.TerminalStorageRequest;
import io.github.loongin.omniresonance.networking.TerminalStorageResponse;
import io.github.loongin.omniresonance.networking.TerminalStorageResponse.Status;
import io.github.loongin.omniresonance.recovery.RecoveryBuffer;
import io.github.loongin.omniresonance.storage.DomainLedger;
import io.github.loongin.omniresonance.transfer.TerminalStorageOperation;
import io.github.loongin.omniresonance.transfer.TransferWorkBudget;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Server-thread terminal mutation coordinator. Each subscribed online player owns one state and at most one
 * pending click. IDs are consumed on admission (including rejected clicks), never replayed. Round-robin work
 * shares the scheduler budget. Close/replacement releases pending hints; unknown mutations lock this view.
 */
public final class TerminalStorageService implements AutoCloseable {
    @FunctionalInterface
    public interface Access {
        boolean allowed(ServerPlayer player, UUID session, long generation, UUID network);
    }

    private final Thread owner = Thread.currentThread();
    private final Supplier<ServerSettings> settings;
    private final Access access;
    private final BiConsumer<UUID, io.github.loongin.omniresonance.persistence.AuditEntry> audit;
    private final Function<UUID, @Nullable DomainLedger> ledgers;
    private final Function<UUID, RecoveryBuffer> recovery;
    private final BiConsumer<ServerPlayer, TerminalStorageResponse> sender;
    private final Map<UUID, View> views = new HashMap<>();
    private final ArrayDeque<UUID> pending = new ArrayDeque<>();

    public TerminalStorageService(
            Supplier<ServerSettings> settings,
            Access access,
            Function<UUID, @Nullable DomainLedger> ledgers,
            Function<UUID, RecoveryBuffer> recovery,
            BiConsumer<ServerPlayer, TerminalStorageResponse> sender,
            BiConsumer<UUID, io.github.loongin.omniresonance.persistence.AuditEntry> audit) {
        this.settings = java.util.Objects.requireNonNull(settings);
        this.access = java.util.Objects.requireNonNull(access);
        this.ledgers = java.util.Objects.requireNonNull(ledgers);
        this.recovery = java.util.Objects.requireNonNull(recovery);
        this.sender = java.util.Objects.requireNonNull(sender);
        this.audit = java.util.Objects.requireNonNull(audit);
    }

    /** Opens only after terminal session authentication; snapshot readiness is rechecked before every click. */
    public void open(ServerPlayer player, UUID view, UUID session, long generation, UUID network) {
        check();
        close(player.getUUID());
        View state = new View(player, view, session, generation, network);
        views.put(player.getUUID(), state);
        mode(state);
    }

    /** Captures intent and real inventory snapshots without invoking capabilities or changing resources. */
    public void request(ServerPlayer player, TerminalStorageRequest request) {
        check();
        View view = views.get(player.getUUID());
        if (view == null
                || view.player != player
                || !view.view.equals(request.view())
                || !view.session.equals(request.session())
                || view.generation != request.generation()
                || request.sequence() <= view.sequence) return;
        view.sequence = request.sequence();
        if (!allowed(view)) {
            send(view, request.sequence(), Status.DENIED, 0);
            return;
        }
        if (view.request != null) {
            send(view, request.sequence(), Status.BUSY, 0);
            return;
        }
        if (request.menuState() != player.inventoryMenu.getStateId()) {
            player.inventoryMenu.broadcastFullState();
            send(view, request.sequence(), Status.STALE, 0);
            return;
        }
        io.github.loongin.omniresonance.transfer.ResourceVariantKey bulkKey = null;
        if (request.bulk()) {
            if (view.quickSlot != request.inventorySlot() || view.quickKey == null) {
                send(
                        view,
                        request.sequence(),
                        view.quickSlot == request.inventorySlot() ? Status.COMPLETE : Status.DENIED,
                        0);
                return;
            }
            bulkKey = view.quickKey;
        } else if (request.shift() && request.button() == 0 && request.inventorySlot() >= 0) {
            view.quickSlot = request.inventorySlot();
            view.quickKey = null;
        } else {
            view.quickSlot = -1;
            view.quickKey = null;
        }
        view.menuState = request.menuState();
        view.request = request;
        view.operation = new TerminalStorageOperation(
                request.inventorySlot(), request.resourceId(), request.button(), request.shift(), bulkKey);
        view.cursor = player.inventoryMenu.getCarried().copy();
        view.slot = request.inventorySlot() < 0
                ? ItemStack.EMPTY
                : player.getInventory().getItem(request.inventorySlot()).copy();
        pending.addLast(player.getUUID());
    }

    /** Runs at most one step per queued player, preserving the next player when the shared budget expires. */
    public void tick(TransferWorkBudget budget) {
        check();
        for (View view : views.values()) mode(view);
        int count = pending.size();
        for (int i = 0; i < count && budget.canStart(); i++) {
            UUID player = pending.removeFirst();
            View view = views.get(player);
            var request = view.request;
            Status status;
            long moved = view.operation.movedSoFar();
            if (!allowed(view)) status = Status.DENIED;
            else if (view.player.inventoryMenu.getStateId() != view.menuState
                    || !ItemStack.matches(view.cursor, view.player.inventoryMenu.getCarried())
                    || request.inventorySlot() >= 0
                            && !ItemStack.matches(
                                    view.slot, view.player.getInventory().getItem(request.inventorySlot())))
                status = Status.STALE;
            else {
                try {
                    var ledger = ledgers.apply(view.network);
                    if (ledger == null || !ledger.isAvailable()) status = Status.DENIED;
                    else {
                        var result = view.operation.step(
                                view.player,
                                ledger,
                                recovery.apply(view.network),
                                settings.get(),
                                () -> allowed(view),
                                budget);
                        status = result.status();
                        moved = result.moved();
                        view.touched = true;
                    }
                } catch (RuntimeException failure) {
                    org.slf4j.LoggerFactory.getLogger(TerminalStorageService.class)
                            .error("Terminal storage work failed; view stopped without retry", failure);
                    status = Status.FAILED;
                }
            }
            if (status == Status.PROGRESS) {
                view.player.inventoryMenu.broadcastChanges();
                view.menuState = view.player.inventoryMenu.getStateId();
                view.cursor = view.player.inventoryMenu.getCarried().copy();
                view.slot = request.inventorySlot() < 0
                        ? ItemStack.EMPTY
                        : view.player
                                .getInventory()
                                .getItem(request.inventorySlot())
                                .copy();
                pending.addLast(player);
                send(view, request.sequence(), status, moved);
                continue;
            }
            if (status == Status.WAITING_BUDGET) {
                pending.addLast(player);
                if (!view.waitingSent) {
                    send(view, request.sequence(), status, moved);
                    view.waitingSent = true;
                }
                continue;
            }
            if (!request.bulk() && request.shift() && request.button() == 0 && request.inventorySlot() >= 0)
                view.quickKey = status == Status.COMPLETE ? view.operation.quickMoveKey() : null;
            view.blocked |= status == Status.UNKNOWN || status == Status.FAILED;
            recordConfirmed(view, moved);
            view.request = null;
            view.operation = null;
            view.cursor = view.slot = ItemStack.EMPTY;
            view.waitingSent = false;
            if (status == Status.STALE) view.player.inventoryMenu.broadcastFullState();
            else view.player.inventoryMenu.broadcastChanges();
            send(view, request.sequence(), status, moved);
        }
    }

    private void recordConfirmed(View view, long amount) {
        if (amount > 0)
            audit.accept(
                    view.network,
                    io.github.loongin.omniresonance.persistence.AuditEntry.of(
                            "terminal_storage", view.player, view.network, "confirmed_amount=" + amount));
    }

    private boolean allowed(View view) {
        return writable()
                && !view.blocked
                && view.player.isAlive()
                && !view.player.isSpectator()
                && view.player.containerMenu == view.player.inventoryMenu
                && access.allowed(view.player, view.session, view.generation, view.network);
    }

    private boolean writable() {
        return settings.get().directStorageAccess() == ServerSettings.DirectStorageAccess.READ_WRITE;
    }

    private void mode(View view) {
        boolean writable = writable() && !view.blocked;
        if (view.mode != null && view.mode == writable) return;
        view.mode = writable;
        send(view, 0, Status.MODE, 0);
    }

    private void send(View view, long sequence, Status status, long moved) {
        sender.accept(
                view.player,
                new TerminalStorageResponse(
                        view.session,
                        view.generation,
                        sequence,
                        status,
                        moved,
                        writable() && !view.blocked,
                        view.player.inventoryMenu.getStateId()));
    }

    /** Cancels work; vanilla menu cleanup settles a cursor touched by this view, without further storage access. */
    public void close(UUID player) {
        check();
        View view = views.remove(player);
        if (view == null) return;
        pending.remove(player);
        if (view.operation != null) recordConfirmed(view, view.operation.movedSoFar());
        if (view.touched && view.player.containerMenu == view.player.inventoryMenu) {
            view.player.inventoryMenu.removed(view.player);
            view.player.inventoryMenu.broadcastChanges();
        }
    }

    public void close() {
        check();
        while (!views.isEmpty()) close(views.keySet().iterator().next());
    }

    private void check() {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Terminal storage accessed off server thread");
    }

    private static final class View {
        final ServerPlayer player;
        final UUID view, session, network;
        final long generation;
        long sequence;
        int menuState, quickSlot = -1;

        @Nullable
        io.github.loongin.omniresonance.transfer.ResourceVariantKey quickKey;

        boolean blocked, touched, waitingSent;

        @Nullable
        Boolean mode;

        @Nullable
        TerminalStorageRequest request;

        @Nullable
        TerminalStorageOperation operation;

        ItemStack cursor = ItemStack.EMPTY, slot = ItemStack.EMPTY;

        View(ServerPlayer player, UUID view, UUID session, long generation, UUID network) {
            this.player = player;
            this.view = view;
            this.session = session;
            this.generation = generation;
            this.network = network;
        }
    }
}
