// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.network;

import io.github.loongin.omniresonance.networking.DomainInventoryFrame;
import io.github.loongin.omniresonance.storage.DomainLedger;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

/**
 * Server-thread send coordinator. Authorized online players each own at most one queued/live view; callers cancel
 * it on departure, close, or permission loss. Network publishers exist only while receivers reference them.
 * Work and byte limits bound each tick; the round-robin cursor continues at the next player without deficit or
 * percentages. The sink must enqueue synchronously on the same reliable connection and never mutate inventory.
 */
public final class DomainInventorySync implements AutoCloseable {
    public record Limits(long bytesPerPlayer, long bytesServer, int concurrentFull, int pendingEntries) {
        public Limits {
            if (bytesPerPlayer < 1
                    || bytesServer < bytesPerPlayer
                    || concurrentFull < 1
                    || concurrentFull > 128
                    || pendingEntries < 1
                    || pendingEntries > 65536) throw new IllegalArgumentException("Invalid inventory sync limits");
        }
    }

    private final Thread owner = Thread.currentThread();
    private final Function<UUID, @Nullable DomainLedger> source;
    private final BiConsumer<UUID, DomainInventoryFrame> sink;
    private final Map<UUID, View> views = new HashMap<>();
    private final Map<UUID, DomainInventoryPublisher> publishers = new HashMap<>();
    private final ArrayDeque<UUID> order = new ArrayDeque<>();
    private final ArrayDeque<UUID> waiting = new ArrayDeque<>();
    private int activeFull;
    private int lastPendingLimit;

    public DomainInventorySync(
            Function<UUID, @Nullable DomainLedger> source, BiConsumer<UUID, DomainInventoryFrame> sink) {
        this.source = Objects.requireNonNull(source);
        this.sink = Objects.requireNonNull(sink);
    }

    /** Replaces an authorized player's prior view; only admission activates a ledger, never a queued request. */
    public void request(UUID player, UUID network, UUID session, long generation) {
        check();
        Objects.requireNonNull(player);
        Objects.requireNonNull(network);
        Objects.requireNonNull(session);
        if (generation <= 0) throw new IllegalArgumentException("Invalid inventory generation");
        cancel(player);
        views.put(player, new View(network, session, generation));
        waiting.addLast(player);
    }

    /** Runs bounded work units while the shared soft CPU budget permits; returns exact emitted payload/envelope bytes. */
    public long tick(Limits limits, int maximumWork, BooleanSupplier workAvailable) {
        check();
        Objects.requireNonNull(limits);
        Objects.requireNonNull(workAvailable);
        if (maximumWork < 0) throw new IllegalArgumentException("Negative inventory work budget");
        if (lastPendingLimit != limits.pendingEntries()) {
            for (var publisher : publishers.values()) publisher.limit(limits.pendingEntries());
            lastPendingLimit = limits.pendingEntries();
        }
        var used = new HashMap<UUID, Long>();
        long remaining = limits.bytesServer();
        int idle = 0;
        for (int step = 0;
                step < maximumWork
                        && (!order.isEmpty() || !waiting.isEmpty() && activeFull < limits.concurrentFull())
                        && workAvailable.getAsBoolean()
                        && remaining >= DomainInventoryFrame.HEADER_BYTES + 1;
                step++) {
            boolean worked = false;
            // Waiting requests do not consume active receiver turns. Admission still costs one bounded turn.
            if (!waiting.isEmpty() && activeFull < limits.concurrentFull()) {
                UUID admitted = waiting.removeFirst();
                admit(admitted, views.get(admitted), limits.pendingEntries());
                order.addLast(admitted);
                worked = true;
            }
            UUID player = order.removeFirst();
            order.addLast(player);
            View view = views.get(player);
            if (view.receiver != null) {
                worked |= view.publisher.work();
                reconcile(view);
            }
            long available = Math.min(remaining, limits.bytesPerPlayer() - used.getOrDefault(player, 0L));
            DomainInventoryFrame frame = view.receiver != null
                    ? view.receiver.poll(available)
                    : view.failure != null && available >= DomainInventoryFrame.HEADER_BYTES + 1
                            ? new DomainInventoryFrame.Failed(view.session, view.generation, 0, view.failure)
                            : null;
            reconcile(view);
            if (frame != null) {
                if (frame.wireSize() > available)
                    throw new IllegalStateException("Inventory frame exceeded available bytes");
                remaining -= frame.wireSize();
                used.merge(player, (long) frame.wireSize(), Long::sum);
                try {
                    sink.accept(player, frame);
                } catch (RuntimeException failedSend) {
                    org.slf4j.LoggerFactory.getLogger(DomainInventorySync.class)
                            .error("Inventory send failed; cancelling view without retry", failedSend);
                    cancel(player);
                    continue;
                }
                worked = true;
                if (frame instanceof DomainInventoryFrame.Failed) cancel(player);
            }
            idle = worked ? 0 : idle + 1;
            if (idle >= order.size() && (waiting.isEmpty() || activeFull >= limits.concurrentFull())) break;
        }
        return limits.bytesServer() - remaining;
    }

    private void admit(UUID player, View view, int limit) {
        try {
            var publisher = publishers.get(view.network);
            if (publisher == null) {
                DomainLedger ledger = source.apply(view.network);
                if (ledger == null || !ledger.isAvailable()) {
                    view.failure = DomainInventoryFrame.Reason.UNAVAILABLE;
                    return;
                }
                publisher = new DomainInventoryPublisher(ledger, limit);
                publishers.put(view.network, publisher);
            }
            view.publisher = publisher;
            view.receiver = publisher.subscribe(player, view.session, view.generation);
            view.countedFull = true;
            activeFull++;
        } catch (RuntimeException unavailable) {
            view.failure = DomainInventoryFrame.Reason.UNAVAILABLE;
            org.slf4j.LoggerFactory.getLogger(DomainInventorySync.class)
                    .error("Inventory stream admission failed", unavailable);
            if (view.publisher != null && view.publisher.receiverCount() == 0) {
                publishers.remove(view.network, view.publisher);
                view.publisher.close();
                view.publisher = null;
            }
        }
    }

    private void reconcile(View view) {
        if (view.countedFull && view.receiver != null && !view.receiver.fullSync()) {
            view.countedFull = false;
            activeFull--;
        }
    }

    /** Nonmutating owner-thread check for the exact subscribed generation after its successful End send. */
    public boolean ready(UUID player, UUID session, long generation) {
        check();
        View view = views.get(player);
        return view != null
                && view.session.equals(session)
                && view.generation == generation
                && view.failure == null
                && view.receiver != null
                && view.receiver.ready();
    }

    /** Stops data immediately and queues an ordered failure for an invalidated authorized view. */
    public void fail(UUID player, DomainInventoryFrame.Reason reason) {
        check();
        var view = views.get(player);
        if (view == null) return;
        if (waiting.remove(player)) order.addLast(player);
        if (view.receiver != null) {
            view.receiver.abort(reason);
            reconcile(view);
        } else view.failure = reason;
    }

    /** Cancels queued or active work immediately, dropping the last network snapshot/publisher when unused. */
    public void cancel(UUID player) {
        check();
        View view = views.remove(player);
        if (view == null) return;
        order.remove(player);
        waiting.remove(player);
        if (view.countedFull) activeFull--;
        if (view.receiver != null) view.receiver.close();
        if (view.publisher != null && view.publisher.receiverCount() == 0) {
            publishers.remove(view.network, view.publisher);
            view.publisher.close();
        }
    }

    /** Counts pending full/delta work from admitted view metadata only; never activates a publisher. */
    public int pendingTasks(UUID network) {
        check();
        int count = 0;
        for (var view : views.values())
            if (view.network.equals(network)
                    && (view.receiver == null || view.receiver.fullSync() || view.receiver.pendingCount() > 0)) count++;
        return count;
    }

    /** Owner-thread aggregate diagnostics, not player-visible authority. */
    public int waitingCount() {
        check();
        return waiting.size();
    }

    public int publisherCount() {
        check();
        return publishers.size();
    }

    public int activeFullCount() {
        check();
        return activeFull;
    }

    @Override
    public void close() {
        check();
        while (!order.isEmpty()) cancel(order.peekFirst());
        while (!waiting.isEmpty()) cancel(waiting.peekFirst());
    }

    private void check() {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Inventory sync accessed off server thread");
    }

    private static final class View {
        final UUID network, session;
        final long generation;

        @Nullable
        DomainInventoryPublisher publisher;

        @Nullable
        DomainInventoryPublisher.Receiver receiver;

        @Nullable
        DomainInventoryFrame.Reason failure;

        boolean countedFull;

        View(UUID network, UUID session, long generation) {
            this.network = network;
            this.session = session;
            this.generation = generation;
        }
    }
}
