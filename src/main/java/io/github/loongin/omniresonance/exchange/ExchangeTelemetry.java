// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.exchange;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Server-thread runtime-owned diagnostics: at most capacity network scopes, each with 20 tick buckets and one
 * incident. Writes refresh activity order; oldest activity is evicted, counts become lower bounds for 20 ticks.
 * Reads neither age nor reorder scopes. No resource authority, world access, exception text or persistence.
 */
public final class ExchangeTelemetry {
    public enum Stage {
        FILTER,
        SOURCE_STORAGE,
        TARGET_STORAGE,
        VALIDATION,
        DECODE,
        TRANSFER
    }

    public enum Reason {
        FILTER_FAILED,
        STORAGE_FAILED,
        VALIDATION_FAILED,
        DECODE_FAILED,
        TRANSFER_FAILED,
        UNCERTAIN_TRANSFER,
        ARITHMETIC_FAILED,
        STORAGE_UNAVAILABLE
    }

    public record Incident(
            UUID channel,
            UUID source,
            UUID target,
            @Nullable ResourceLocation type,
            long tick,
            Stage stage,
            Reason reason,
            String channelName,
            String sourceName,
            String targetName) {
        public Incident {
            Objects.requireNonNull(channel);
            Objects.requireNonNull(source);
            Objects.requireNonNull(target);
            Objects.requireNonNull(stage);
            Objects.requireNonNull(reason);
            if (source.equals(target)
                    || tick < 0
                    || type != null && type.toString().length() > 128)
                throw new IllegalArgumentException("Invalid exchange incident");
            name(channelName);
            name(sourceName);
            name(targetName);
        }

        private static void name(String value) {
            Objects.requireNonNull(value);
            if (!value.isEmpty()
                    && !new io.github.loongin.omniresonance.network.ManagedName(value)
                            .value()
                            .equals(value)) throw new IllegalArgumentException("Invalid diagnostic name");
        }
    }

    public record Snapshot(
            boolean available,
            boolean observed,
            long sent,
            long received,
            boolean lowerBound,
            @Nullable Incident incident) {
        public Snapshot {
            if (sent < 0 || received < 0 || !observed && (sent != 0 || received != 0 || incident != null))
                throw new IllegalArgumentException("Invalid exchange counters");
        }

        public Snapshot withAvailability(boolean value) {
            return new Snapshot(value, observed, sent, received, lowerBound, incident);
        }

        public Snapshot withIncident(Incident value) {
            return new Snapshot(available, observed, sent, received, lowerBound, value);
        }
    }

    private static final class Scope {
        final long[] ticks = new long[20], sent = new long[20], received = new long[20];

        @Nullable
        Incident incident;

        final UUID network;
        @Nullable
        Scope previous, next;

        Scope(UUID network) {
            this.network = network;
            Arrays.fill(ticks, -1);
        }
    }

    private final Thread owner = Thread.currentThread();
    private final int capacity;
    private final HashMap<UUID, Scope> scopes = new HashMap<>();
    private @Nullable Scope first, last;
    private long evictionTick = -1;

    public ExchangeTelemetry(int capacity) {
        if (capacity < 2 || capacity > 262144) throw new IllegalArgumentException("Invalid diagnostic capacity");
        this.capacity = capacity;
    }

    public void moved(UUID source, UUID target, long tick) {
        check();
        if (tick < 0 || source.equals(target)) throw new IllegalArgumentException("Invalid movement context");
        add(scope(source, tick), tick, true);
        add(scope(target, tick), tick, false);
    }

    private static void add(Scope s, long tick, boolean send) {
        int i = (int) (tick % 20);
        if (s.ticks[i] != tick) {
            s.ticks[i] = tick;
            s.sent[i] = 0;
            s.received[i] = 0;
        }
        if (send) s.sent[i] = sum(s.sent[i], 1);
        else s.received[i] = sum(s.received[i], 1);
    }

    public void failure(Incident value) {
        check();
        record(scope(value.source(), value.tick()), value);
        record(scope(value.target(), value.tick()), value);
    }

    private static void record(Scope scope, Incident value) {
        var old = scope.incident;
        if (old != null
                && value.reason() == Reason.STORAGE_UNAVAILABLE
                && old.channel().equals(value.channel())
                && old.source().equals(value.source())
                && old.target().equals(value.target())
                && Objects.equals(old.type(), value.type())
                && old.stage() == value.stage()
                && old.reason() == value.reason()) return;
        scope.incident = value;
    }

    public Snapshot snapshot(UUID network, long tick) {
        check();
        if (tick < 0) throw new IllegalArgumentException("Negative diagnostic tick");
        var scope = scopes.get(network);
        if (scope == null) return new Snapshot(true, false, 0, 0, false, null);
        long sent = 0, received = 0;
        boolean lower = evictionTick >= 0 && tick >= evictionTick && tick - evictionTick < 20;
        for (int i = 0; i < 20; i++)
            if (scope.ticks[i] >= 0 && scope.ticks[i] <= tick && tick - scope.ticks[i] < 20) {
                lower |= scope.sent[i] > Long.MAX_VALUE - sent
                        || scope.received[i] > Long.MAX_VALUE - received
                        || scope.sent[i] == Long.MAX_VALUE
                        || scope.received[i] == Long.MAX_VALUE;
                sent = sum(sent, scope.sent[i]);
                received = sum(received, scope.received[i]);
            }
        return new Snapshot(true, true, sent, received, lower, scope.incident);
    }

    private Scope scope(UUID id, long tick) {
        Objects.requireNonNull(id);
        var s = scopes.get(id);
        if (s == null) {
            s = new Scope(id);
            scopes.put(id, s);
        } else unlink(s);
        s.previous = last;
        if (last != null) last.next = s;
        else first = s;
        last = s;
        if (scopes.size() > capacity) {
            var oldest = Objects.requireNonNull(first);
            unlink(oldest);
            scopes.remove(oldest.network);
            evictionTick = tick;
        }
        return s;
    }

    private void unlink(Scope s) {
        if (s.previous != null) s.previous.next = s.next;
        else first = s.next;
        if (s.next != null) s.next.previous = s.previous;
        else last = s.previous;
        s.previous = null;
        s.next = null;
    }

    public int scopeCount() {
        check();
        return scopes.size();
    }

    public void clear() {
        check();
        scopes.clear();
        first = null;
        last = null;
        evictionTick = -1;
    }

    private void check() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Exchange telemetry off owner thread");
    }

    private static long sum(long a, long b) {
        return b > Long.MAX_VALUE - a ? Long.MAX_VALUE : a + b;
    }
}
