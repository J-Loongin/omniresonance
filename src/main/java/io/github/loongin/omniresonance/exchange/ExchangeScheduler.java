// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.exchange;

import io.github.loongin.omniresonance.filter.ResourceFilterCompiler;
import io.github.loongin.omniresonance.transfer.DomainTransferWindow;
import io.github.loongin.omniresonance.transfer.ResourceScope;
import io.github.loongin.omniresonance.transfer.TransferWorkBudget;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Server-thread two-level fair exchange dispatch. One bounded protocol map and one retained window per published
 * protocol/type; removed scope types retain only their windows until protocol removal or scheduler close. Queues
 * contain exact live entries, never lazy tombstones. Publication is management work, not a tick-time whole-map copy.
 * Dispatch performs O(log protocols + log types) queue work per charged microstep, including blocked probes.
 * No native capability calls, player-online checks, forced saves or implicit world activation occur here.
 */
public final class ExchangeScheduler implements AutoCloseable {
    public record Progress(int used, int transfers) {}

    private static final Comparator<Slot> SLOT_ORDER =
            Comparator.comparingLong((Slot slot) -> slot.nextTick).thenComparingLong(slot -> slot.order);
    private static final Comparator<Protocol> PROTOCOL_ORDER = Comparator.comparingLong(
                    (Protocol protocol) -> protocol.ready.element().nextTick)
            .thenComparingLong(protocol -> protocol.order);
    private final ExchangeTelemetry telemetry = new ExchangeTelemetry(4096);

    public ExchangeTelemetry.Snapshot telemetrySnapshot(UUID network, long tick) {
        checkThread();
        return telemetry.snapshot(network, tick);
    }

    void storageFailure(ExchangeAgreement a, UUID network, long tick) {
        checkThread();
        var c = a.consent();
        telemetry.failure(new ExchangeTelemetry.Incident(
                a.id(),
                c.sourceNetwork(),
                c.targetNetwork(),
                null,
                tick,
                network.equals(c.sourceNetwork())
                        ? ExchangeTelemetry.Stage.SOURCE_STORAGE
                        : ExchangeTelemetry.Stage.TARGET_STORAGE,
                ExchangeTelemetry.Reason.STORAGE_UNAVAILABLE,
                "",
                "",
                ""));
    }

    private final Thread owner = Thread.currentThread();
    private final ExchangeTypeWork.Environment environment;
    private final List<ResourceLocation> types;
    private final int maximumProtocols, maximumWindows;
    private final Map<UUID, Protocol> protocols = new HashMap<>();
    private final PriorityQueue<Protocol> ready = new PriorityQueue<>(PROTOCOL_ORDER);
    private int windowCount;
    private long order;
    private boolean busy, closed;

    /** Explicit bounded capacities with no product defaults. Types must be the frozen registered resource directory. */
    public ExchangeScheduler(
            ExchangeTypeWork.Environment environment,
            List<ResourceLocation> types,
            int maximumProtocols,
            int maximumWindows) {
        this.environment = Objects.requireNonNull(environment);
        this.types = List.copyOf(types);
        if (maximumProtocols < 1
                || maximumProtocols > 262144
                || maximumWindows < 1
                || maximumWindows > 262144
                || this.types.size() > ResourceScope.MAXIMUM_RESOURCE_TYPE_IDS
                || new HashSet<>(this.types).size() != this.types.size())
            throw new IllegalArgumentException("Invalid exchange scheduler bounds");
        for (ResourceLocation type : this.types)
            if (type.toString().length() > ResourceScope.MAXIMUM_RESOURCE_TYPE_ID_BYTES)
                throw new IllegalArgumentException("Exchange type ID too long");
        this.maximumProtocols = maximumProtocols;
        this.maximumWindows = maximumWindows;
    }

    /**
     * Publishes validated current authority outside dispatch, preserving every prior type window for this protocol.
     * Stale/conflicting revisions, identity changes or admission overflow reject before replacing existing work.
     * Preparation may copy filter snapshots, so the caller must admit publication separately from tick work.
     */
    public void publish(ExchangeAgreement agreement, ResourceFilterCompiler.Tags tags, long tick) {
        Objects.requireNonNull(tags);
        publish(agreement, () -> ExchangeFilterWork.prepared(agreement, tags), tick);
    }

    /** Publishes native iterator-backed tag preparation shared by every resource type of this protocol. */
    public void publishLive(
            ExchangeAgreement agreement,
            io.github.loongin.omniresonance.filter.ResourceFilterCache.TagSource source,
            long tick) {
        Objects.requireNonNull(source);
        publish(agreement, () -> new ExchangeFilterWork(agreement, source), tick);
    }

    private void publish(
            ExchangeAgreement agreement, java.util.function.Supplier<ExchangeFilterWork> filters, long tick) {
        requireManagement();
        Objects.requireNonNull(agreement);
        if (tick < 0) throw new IllegalArgumentException("Negative publication tick");
        Protocol old = protocols.get(agreement.id());
        if (old != null) {
            if (old.agreement == agreement) return;
            ExchangeConsent before = old.agreement.consent(), after = agreement.consent();
            if (after.revision() <= before.revision()) throw new IllegalStateException("Stale exchange publication");
            boolean same = before.sourceNetwork().equals(after.sourceNetwork())
                    && before.targetNetwork().equals(after.targetNetwork())
                    && before.sourceOwner().equals(after.sourceOwner())
                    && before.targetOwner().equals(after.targetOwner());
            boolean reversed = before.sourceNetwork().equals(after.targetNetwork())
                    && before.targetNetwork().equals(after.sourceNetwork())
                    && before.sourceOwner().equals(after.targetOwner())
                    && before.targetOwner().equals(after.sourceOwner())
                    && after.termsRevision() > before.termsRevision();
            if ((!same && !reversed) || !before.invitationId().equals(after.invitationId()))
                throw new IllegalArgumentException("Exchange identity changed");
        }
        if (agreement.consent().revoked()) {
            remove(agreement.id());
            return;
        }
        if (old == null && protocols.size() == maximumProtocols)
            throw new IllegalStateException("Exchange protocol capacity reached");
        Map<ResourceLocation, DomainTransferWindow> windows =
                old == null ? new HashMap<>() : new HashMap<>(old.windows);
        boolean active = agreement.consent().approvedByBoth()
                && !agreement.consent().source().paused()
                && !agreement.consent().target().paused();
        int additional = 0;
        boolean selected = false;
        if (active)
            for (ResourceLocation type : types)
                if (agreement.terms().scope().includes(type)) {
                    selected = true;
                    if (!windows.containsKey(type)) additional++;
                }
        int count = windowCount + additional;
        if (count > maximumWindows) throw new IllegalStateException("Exchange window capacity reached");
        if (active)
            for (ResourceLocation type : types)
                if (agreement.terms().scope().includes(type))
                    windows.computeIfAbsent(type, ignored -> new DomainTransferWindow());
        long nextOrder = Math.incrementExact(order);
        Protocol next = new Protocol(agreement, windows, nextOrder, selected ? filters.get() : null);
        if (active) {
            for (ResourceLocation type : types) {
                if (!agreement.terms().scope().includes(type)) continue;
                DomainTransferWindow window = windows.get(type);
                Slot slot = new Slot(
                        new ExchangeTypeWork(agreement, type, window, environment, Objects.requireNonNull(next.filter)),
                        window,
                        Math.max(tick, window.nextRunTick()),
                        ++next.turn);
                next.slots.put(type, slot);
                next.ready.add(slot);
            }
        }
        if (old != null) {
            ready.remove(old);
            old.close();
        }
        protocols.put(agreement.id(), next);
        if (!next.ready.isEmpty()) ready.add(next);
        windowCount = count;
        order = nextOrder;
    }

    /** Server-thread read of the current publication; never prepares filters or loads domain storage. */
    public ExchangeFilterStatus filterStatus(ExchangeAgreement agreement) {
        checkThread();
        var protocol = protocols.get(agreement.id());
        return ExchangeFilterStatus.of(
                agreement.terms(),
                protocol == null || protocol.agreement != agreement || protocol.filter == null
                        ? null
                        : protocol.filter.view().compiled());
    }

    /** Physically removes all queue entries and quota windows for one protocol; absent removal is a no-op. */
    public void remove(UUID id) {
        requireManagement();
        Protocol old = protocols.remove(Objects.requireNonNull(id));
        if (old == null) return;
        ready.remove(old);
        windowCount -= old.windows.size();
        old.close();
    }

    /** Invalidates one protocol's tag-derived work without replenishing its quota or waking failed work. */
    public void invalidateTags(UUID id, ResourceFilterCompiler.Tags tags, long tick) {
        Objects.requireNonNull(tags);
        invalidateFilter(id, filter -> filter.replaceTags(tags), tick);
    }

    /** Invalidates the current native iterator source after a registry tag reload, without waking failed work. */
    public void invalidateLiveTags(UUID id, long tick) {
        invalidateFilter(id, ExchangeFilterWork::tagsChanged, tick);
    }

    private void invalidateFilter(UUID id, java.util.function.Consumer<ExchangeFilterWork> change, long tick) {
        requireManagement();
        if (tick < 0) throw new IllegalArgumentException("Negative invalidation tick");
        Protocol protocol = protocols.get(id);
        if (protocol == null) return;
        ready.remove(protocol);
        protocol.ready.clear();
        if (protocol.filter != null) change.accept(protocol.filter);
        for (Slot slot : protocol.slots.values()) {
            if (slot.work.failure() != null) continue;
            slot.nextTick = Math.max(tick, slot.window.nextRunTick());
            protocol.ready.add(slot);
        }
        if (!protocol.ready.isEmpty()) ready.add(protocol);
    }

    /**
     * Dispatches one microstep per protocol turn under shared CPU and explicit work bounds. Sleeping queues return
     * immediately; blocked probes charge one unit and retry no earlier than next tick. Failure evidence remains
     * queryable and failed work is not automatically requeued. Returned transfers counts commits, not mixed units.
     */
    public Progress run(long tick, int workUnits, TransferWorkBudget budget, long variantLimit) {
        requireManagement();
        Objects.requireNonNull(budget);
        if (tick < 0 || workUnits < 0 || variantLimit < -1)
            throw new IllegalArgumentException("Invalid exchange dispatch bounds");
        long retryTick = Math.addExact(tick, 1);
        int used = 0, transfers = 0;
        busy = true;
        try {
            while (used < workUnits && budget.canFit(0) && !ready.isEmpty()) {
                Protocol protocol = ready.peek();
                if (protocol.ready.element().nextTick > tick) break;
                long protocolOrder = Math.incrementExact(order), typeOrder = Math.incrementExact(protocol.turn);
                Math.addExact(tick, protocol.agreement.terms().intervalTicks());
                ready.remove();
                Slot slot = protocol.ready.remove();
                try {
                    ExchangeTypeWork.Progress result = slot.work.step(tick, 1, budget, variantLimit);
                    used++;
                    if (result.moved() > 0) {
                        transfers++;
                        telemetry.moved(
                                protocol.agreement.consent().sourceNetwork(),
                                protocol.agreement.consent().targetNetwork(),
                                tick);
                    }
                    if (result.status() == ExchangeTypeWork.Status.FAILED) {
                        var failure = slot.work.failure();
                        var stage = slot.work.failureStage();
                        var reason = failure
                                        instanceof
                                        io.github.loongin.omniresonance.storage.DomainLedger.UncertainTransfer
                                ? ExchangeTelemetry.Reason.UNCERTAIN_TRANSFER
                                : failure instanceof ArithmeticException
                                        ? ExchangeTelemetry.Reason.ARITHMETIC_FAILED
                                        : switch (stage) {
                                            case FILTER -> ExchangeTelemetry.Reason.FILTER_FAILED;
                                            case SOURCE_STORAGE, TARGET_STORAGE ->
                                                ExchangeTelemetry.Reason.STORAGE_FAILED;
                                            case VALIDATION -> ExchangeTelemetry.Reason.VALIDATION_FAILED;
                                            case DECODE -> ExchangeTelemetry.Reason.DECODE_FAILED;
                                            case TRANSFER -> ExchangeTelemetry.Reason.TRANSFER_FAILED;
                                        };
                        var c = protocol.agreement.consent();
                        telemetry.failure(new ExchangeTelemetry.Incident(
                                protocol.agreement.id(),
                                c.sourceNetwork(),
                                c.targetNetwork(),
                                slot.work.resourceType(),
                                tick,
                                stage,
                                reason,
                                "",
                                "",
                                ""));
                    }
                    slot.order = typeOrder;
                    protocol.turn = typeOrder;
                    if (result.status() != ExchangeTypeWork.Status.FAILED) {
                        slot.nextTick = switch (result.status()) {
                            case BLOCKED -> retryTick;
                            case ROUND_FINISHED, WAITING_INTERVAL -> slot.window.nextRunTick();
                            default -> tick;
                        };
                        protocol.ready.add(slot);
                    }
                } finally {
                    protocol.order = protocolOrder;
                    order = protocolOrder;
                    if (!protocol.ready.isEmpty()) ready.add(protocol);
                }
            }
            return new Progress(used, transfers);
        } finally {
            busy = false;
        }
    }

    /** Internal retained evidence for one stopped type, without reactivating or exposing it to players. */
    public @Nullable RuntimeException failure(UUID id, ResourceLocation type) {
        checkThread();
        Protocol protocol = protocols.get(id);
        Slot slot = protocol == null ? null : protocol.slots.get(type);
        return slot == null ? null : slot.work.failure();
    }

    public int protocolCount() {
        checkThread();
        return protocols.size();
    }

    public int windowCount() {
        checkThread();
        return windowCount;
    }

    private void requireManagement() {
        checkThread();
        if (busy) throw new IllegalStateException("Reentrant exchange scheduler mutation");
    }

    private void checkThread() {
        if (Thread.currentThread() != owner || closed)
            throw new IllegalStateException("Closed or off-thread exchange scheduler");
    }

    @Override
    public void close() {
        requireManagement();
        for (Protocol protocol : protocols.values()) protocol.close();
        ready.clear();
        protocols.clear();
        telemetry.clear();
        windowCount = 0;
        closed = true;
    }

    private static final class Slot {
        final ExchangeTypeWork work;
        final DomainTransferWindow window;
        long nextTick, order;

        Slot(ExchangeTypeWork work, DomainTransferWindow window, long nextTick, long order) {
            this.work = work;
            this.window = window;
            this.nextTick = nextTick;
            this.order = order;
        }
    }

    private static final class Protocol {
        final ExchangeAgreement agreement;
        final @Nullable ExchangeFilterWork filter;
        final Map<ResourceLocation, DomainTransferWindow> windows;
        final Map<ResourceLocation, Slot> slots = new HashMap<>();
        final PriorityQueue<Slot> ready = new PriorityQueue<>(SLOT_ORDER);
        long order, turn;

        Protocol(
                ExchangeAgreement agreement,
                Map<ResourceLocation, DomainTransferWindow> windows,
                long order,
                @Nullable ExchangeFilterWork filter) {
            this.agreement = agreement;
            this.filter = filter;
            this.windows = windows;
            this.order = order;
        }

        void close() {
            for (Slot slot : slots.values()) slot.work.close();
            if (filter != null) filter.close();
            ready.clear();
            slots.clear();
        }
    }
}
