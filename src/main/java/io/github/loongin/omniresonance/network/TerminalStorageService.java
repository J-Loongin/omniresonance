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
 * Server-thread terminal mutation coordinator. Each subscribed online player owns one state and one
 * executing click plus a bounded FIFO. IDs are consumed on admission (including rejected clicks), never replayed. Round-robin work
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

    /** Admits a bounded, ordered intent without trusting client quantities or menu-wide revision hints. */
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
            cancelWork(view);
            view.mode = false;
            send(view, request.sequence(), Status.DENIED, 0);
            return;
        }
        if (view.queued.size() + (view.request == null ? 0 : 1) >= TerminalStorageRequest.MAX_PENDING) {
            send(view, request.sequence(), Status.BUSY, 0);
            return;
        }
        if (view.request == null) activate(view, request);
        else view.queued.addLast(request);
        schedule(view);
    }

    private void activate(View view, TerminalStorageRequest request) {
        view.request = request;
        view.operation = null;
        view.waitingSent = false;
        view.cursor = view.slot = ItemStack.EMPTY;
    }

    private void snapshot(View view) {
        view.cursor = view.player.inventoryMenu.getCarried().copy();
        view.slot = view.request.inventorySlot() < 0
                ? ItemStack.EMPTY
                : view.player
                        .getInventory()
                        .getItem(view.request.inventorySlot())
                        .copy();
    }

    private void schedule(View view) {
        if (view.request != null && !view.scheduled) {
            view.scheduled = true;
            pending.addLast(view.player.getUUID());
        }
    }

    /** Fair bounded rounds share the existing CPU/native-call budget; suspended work yields for this tick. */
    public void tick(TransferWorkBudget budget) {
        check();
        for (View view : views.values()) {
            view.yielded = false;
            mode(view);
        }
        for (int round = 0; round < TerminalStorageRequest.MAX_PENDING && budget.canStart(); round++) {
            int count = pending.size();
            boolean advanced = false;
            for (int i = 0; i < count && budget.canStart(); i++) {
                View view = views.get(pending.removeFirst());
                if (view == null) continue;
                view.scheduled = false;
                if (view.yielded) {
                    schedule(view);
                    continue;
                }
                var request = view.request;
                var result = execute(view, budget);
                Status status = result.status();
                long moved = result.moved();
                if (status == Status.PROGRESS || status == Status.WAITING_BUDGET) {
                    view.yielded = true;
                    if (status == Status.PROGRESS) {
                        view.player.inventoryMenu.broadcastChanges();
                        snapshot(view);
                    }
                    if (status == Status.PROGRESS || !view.waitingSent) {
                        send(view, request.sequence(), status, moved);
                        view.waitingSent = true;
                    }
                } else {
                    advanced = true;
                    if (!request.bulk() && request.shift() && request.button() == 0 && request.inventorySlot() >= 0)
                        view.quickKey = status == Status.COMPLETE && view.operation != null
                                ? view.operation.quickMoveKey()
                                : null;
                    view.blocked |= status == Status.UNKNOWN || status == Status.FAILED;
                    recordConfirmed(view, moved);
                    view.request = null;
                    view.operation = null;
                    view.cursor = view.slot = ItemStack.EMPTY;
                    view.waitingSent = false;
                    if (status == Status.STALE) view.player.inventoryMenu.broadcastFullState();
                    else view.player.inventoryMenu.broadcastChanges();
                    send(view, request.sequence(), status, moved);
                    if (!allowed(view)) view.queued.clear();
                    else if (!view.queued.isEmpty()) activate(view, view.queued.removeFirst());
                }
                schedule(view);
            }
            if (!advanced) break;
        }
    }

    private TerminalStorageOperation.Outcome execute(View view, TransferWorkBudget budget) {
        long moved = view.operation == null ? 0 : view.operation.movedSoFar();
        if (!allowed(view)) return new TerminalStorageOperation.Outcome(Status.DENIED, moved);
        var request = view.request;
        if (view.operation != null
                && (!ItemStack.matches(view.cursor, view.player.inventoryMenu.getCarried())
                        || request.inventorySlot() >= 0
                                && !ItemStack.matches(
                                        view.slot, view.player.getInventory().getItem(request.inventorySlot()))))
            return new TerminalStorageOperation.Outcome(Status.STALE, moved);
        try {
            if (view.operation == null) {
                snapshot(view);
                io.github.loongin.omniresonance.transfer.ResourceVariantKey bulkKey = null;
                if (request.bulk()) {
                    if (view.quickSlot != request.inventorySlot() || view.quickKey == null)
                        return new TerminalStorageOperation.Outcome(
                                view.quickSlot == request.inventorySlot() ? Status.COMPLETE : Status.DENIED, 0);
                    bulkKey = view.quickKey;
                } else if (request.shift() && request.button() == 0 && request.inventorySlot() >= 0) {
                    view.quickSlot = request.inventorySlot();
                    view.quickKey = null;
                } else {
                    view.quickSlot = -1;
                    view.quickKey = null;
                }
                view.operation = new TerminalStorageOperation(
                        request.inventorySlot(), request.resourceId(), request.button(), request.shift(), bulkKey);
            }
            var ledger = ledgers.apply(view.network);
            if (ledger == null || !ledger.isAvailable())
                return new TerminalStorageOperation.Outcome(Status.DENIED, moved);
            var result = view.operation.step(
                    view.player, ledger, recovery.apply(view.network), settings.get(), () -> allowed(view), budget);
            view.touched = true;
            return result;
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(TerminalStorageService.class)
                    .error("Terminal storage work failed; view stopped without retry", failure);
            return new TerminalStorageOperation.Outcome(Status.FAILED, moved);
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
        boolean writable = allowed(view);
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
                        allowed(view),
                        view.player.inventoryMenu.getStateId()));
    }

    /** Cancels work; vanilla menu cleanup settles a cursor touched by this view, without further storage access. */
    public void close(UUID player) {
        check();
        View view = views.remove(player);
        if (view == null) return;
        cancelWork(view);
        if (view.touched && view.player.containerMenu == view.player.inventoryMenu) {
            view.player.inventoryMenu.removed(view.player);
            view.player.inventoryMenu.broadcastChanges();
        }
    }

    private void cancelWork(View view) {
        pending.remove(view.player.getUUID());
        view.scheduled = false;
        if (view.operation != null) recordConfirmed(view, view.operation.movedSoFar());
        view.queued.clear();
        view.quickSlot = -1;
        view.quickKey = null;
        view.request = null;
        view.operation = null;
        view.cursor = view.slot = ItemStack.EMPTY;
        view.waitingSent = false;
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
        int quickSlot = -1;
        final ArrayDeque<TerminalStorageRequest> queued = new ArrayDeque<>();

        @Nullable
        io.github.loongin.omniresonance.transfer.ResourceVariantKey quickKey;

        boolean blocked, touched, waitingSent, scheduled, yielded;

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
