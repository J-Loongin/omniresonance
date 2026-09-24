// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

/** Server-thread in-memory measurements, bounded by active networks × registered types × 20 ticks.
 * Removed with a network/runtime. Reads never age or mutate buckets. Overflow saturates diagnostics, never resources.
 */
public final class TransferTelemetry {
    public record QueueCounts(int due, int backoff) {
        public QueueCounts {
            if (due < 0 || backoff < 0) throw new IllegalArgumentException("Negative queue count");
        }
    }

    public record Movement(ResourceLocation type, long amount, boolean saturated) {
        public Movement {
            ResourceScope.validateResourceTypeId(type);
            if (amount < 0) throw new IllegalArgumentException("Invalid movement amount");
        }
    }

    public record Snapshot(
            long tick,
            long calls,
            long nanos,
            List<Movement> moved,
            int omittedTypes,
            String error,
            long errorTick,
            @org.jetbrains.annotations.Nullable TransferIncident incident) {
        public Snapshot(
                long tick,
                long calls,
                long nanos,
                List<Movement> moved,
                int omittedTypes,
                String error,
                long errorTick) {
            this(tick, calls, nanos, moved, omittedTypes, error, errorTick, null);
        }

        /** Returns a detached copy with enriched metadata; no state, world access or mutation is involved. */
        public Snapshot withIncident(TransferIncident value) {
            return new Snapshot(tick, calls, nanos, moved, omittedTypes, error, errorTick, value);
        }

        public Snapshot {
            moved = List.copyOf(moved);
            if (tick < -1
                    || calls < 0
                    || nanos < 0
                    || moved.size() > 128
                    || omittedTypes < 0
                    || errorTick < -1
                    || incident != null && (error.isEmpty() || errorTick < 0)
                    || !Set.of("", "direct_transfer", "domain_input", "domain_output")
                            .contains(error)) throw new IllegalArgumentException("Invalid telemetry snapshot");
            var seen = new java.util.HashSet<ResourceLocation>();
            for (var value : moved)
                if (!seen.add(value.type())) throw new IllegalArgumentException("Duplicate movement type");
        }
    }

    private static final class Window {
        final long[] ticks = new long[20], amounts = new long[20];
        final boolean[] saturated = new boolean[20];

        Window() {
            java.util.Arrays.fill(ticks, Long.MIN_VALUE);
        }
    }

    private static final class Scope {
        long tick = -1, calls, nanos, errorTick = -1;
        String error = "";

        @org.jetbrains.annotations.Nullable
        TransferIncident incident;

        final Map<ResourceLocation, Window> windows = new HashMap<>();
    }

    private final Thread owner = Thread.currentThread();
    private final List<ResourceLocation> types;
    private final Set<ResourceLocation> allowed;
    private final Map<UUID, Scope> scopes = new HashMap<>();

    public TransferTelemetry(List<ResourceLocation> types) {
        this.types = List.copyOf(types);
        allowed = Set.copyOf(types);
        if (types.size() > ResourceScope.MAXIMUM_RESOURCE_TYPE_IDS || types.size() != allowed.size())
            throw new IllegalArgumentException("Invalid telemetry type catalog");
    }
    /** Records only completed work on the owning thread; no capabilities, authority or persistence are touched. */
    public void work(UUID network, long tick, long calls, long nanos) {
        check();
        if (tick < 0 || calls < 0 || nanos < 0) throw new IllegalArgumentException("Invalid work measurement");
        var scope = scopes.computeIfAbsent(network, ignored -> new Scope());
        if (scope.tick != tick) {
            scope.tick = tick;
            scope.calls = 0;
            scope.nanos = 0;
        }
        scope.calls = add(scope.calls, calls);
        scope.nanos = add(scope.nanos, nanos);
    }
    /** Adds confirmed transferred quantity after commit; diagnostics have no simulation or resource mutation path. */
    public void moved(UUID network, ResourceLocation type, long tick, long amount) {
        check();
        if (tick < 0 || amount < 0 || !allowed.contains(type))
            throw new IllegalArgumentException("Invalid movement measurement");
        if (amount == 0) return;
        var window = scopes.computeIfAbsent(network, ignored -> new Scope())
                .windows
                .computeIfAbsent(type, ignored -> new Window());
        int slot = (int) (tick % 20);
        if (window.ticks[slot] != tick) {
            window.ticks[slot] = tick;
            window.amounts[slot] = 0;
            window.saturated[slot] = false;
        }
        window.saturated[slot] |= amount > Long.MAX_VALUE - window.amounts[slot];
        window.amounts[slot] = add(window.amounts[slot], amount);
    }
    /** Retains one bounded classification, never a throwable, message, stack trace or resource key. */
    public void failure(UUID network, long tick, String classification) {
        failure(network, tick, classification, null);
    }
    /** Owner-thread recording of one immutable incident; replaces prior context and never changes transfer behavior. */
    public void failure(
            UUID network,
            long tick,
            String classification,
            @org.jetbrains.annotations.Nullable TransferIncident incident) {
        check();
        if (!Set.of("direct_transfer", "domain_input", "domain_output").contains(classification) || tick < 0)
            throw new IllegalArgumentException("Invalid diagnostic classification");
        var scope = scopes.computeIfAbsent(network, ignored -> new Scope());
        scope.error = classification;
        scope.errorTick = tick;
        scope.incident = incident;
    }
    /** Returns detached counters for a completed tick; no state is changed by sampling. */
    public Snapshot snapshot(UUID network, long tick) {
        check();
        var scope = scopes.get(network);
        if (scope == null) return new Snapshot(tick, 0, 0, List.of(), 0, "", -1);
        var result = new ArrayList<Movement>();
        int omitted = 0;
        for (var type : types) {
            var window = scope.windows.get(type);
            if (window == null) continue;
            long amount = 0;
            boolean saturated = false;
            for (int i = 0; i < 20; i++) {
                if (window.ticks[i] < 0 || window.ticks[i] > tick || tick - window.ticks[i] >= 20) continue;
                saturated |= window.saturated[i] || window.amounts[i] > Long.MAX_VALUE - amount;
                amount = add(amount, window.amounts[i]);
            }
            if (amount > 0) {
                if (result.size() < 128) result.add(new Movement(type, amount, saturated));
                else omitted++;
            }
        }
        return new Snapshot(
                tick,
                scope.tick == tick ? scope.calls : 0,
                scope.tick == tick ? scope.nanos : 0,
                result,
                omitted,
                scope.error,
                scope.errorTick,
                scope.incident);
    }
    /** Owner-thread lifecycle query, with no mutation or allocation. */
    public boolean contains(UUID network) {
        check();
        return scopes.containsKey(network);
    }

    /** Releases only diagnostics for a removed network on its owner thread. */
    public void remove(UUID network) {
        check();
        scopes.remove(network);
    }
    /** Clears all observations at runtime close; no persistent data is affected. */
    public void clear() {
        check();
        scopes.clear();
    }

    private static long add(long a, long b) {
        return b > Long.MAX_VALUE - a ? Long.MAX_VALUE : a + b;
    }

    private void check() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Telemetry off server thread");
    }
}
