// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.jetbrains.annotations.Nullable;

/** Optional development-only client-thread measurement for one opening. Retains scalar counters only,
 * reports once when the first complete result can be displayed, and never records resource identities. */
final class InventoryOpenTiming {
    record Sample(
            long totalNanos,
            long firstFrameNanos,
            long snapshotNanos,
            long preparationNanos,
            long metadataNanos,
            int preparationTicks,
            int metadataEntries,
            int variants,
            int frames,
            long wireBytes) {}

    private final LongSupplier clock;
    private final Consumer<Sample> reporter;
    private long startedNanos, firstFrameNanos, snapshotNanos, preparationNanos, metadataNanos, wireBytes;
    private int preparationTicks, metadataEntries, frames;
    private boolean active;

    InventoryOpenTiming(LongSupplier clock, Consumer<Sample> reporter) {
        this.clock = clock;
        this.reporter = reporter;
    }

    static @Nullable InventoryOpenTiming development() {
        if (net.neoforged.fml.loading.FMLEnvironment.production
                || !Boolean.getBoolean("omniresonance.profileInventory")) return null;
        return new InventoryOpenTiming(
                System::nanoTime,
                sample -> org.slf4j.LoggerFactory.getLogger(InventoryOpenTiming.class)
                        .info("Inventory open timing (nanoseconds): {}", sample));
    }

    void begin() {
        startedNanos = clock.getAsLong();
        firstFrameNanos = snapshotNanos = -1;
        preparationNanos = metadataNanos = wireBytes = 0;
        preparationTicks = metadataEntries = frames = 0;
        active = true;
    }

    boolean active() {
        return active;
    }

    void frame(int bytes, boolean snapshotReady) {
        if (!active) return;
        long elapsed = clock.getAsLong() - startedNanos;
        if (firstFrameNanos < 0) firstFrameNanos = elapsed;
        if (snapshotReady && snapshotNanos < 0) snapshotNanos = elapsed;
        frames++;
        wireBytes += bytes;
    }

    void preparation(long elapsedNanos) {
        if (!active) return;
        preparationTicks++;
        preparationNanos += elapsedNanos;
    }

    void metadata(long elapsedNanos) {
        if (!active) return;
        metadataEntries++;
        metadataNanos += elapsedNanos;
    }

    void ready(int variants) {
        if (!active || snapshotNanos < 0) return;
        active = false;
        reporter.accept(new Sample(
                clock.getAsLong() - startedNanos,
                firstFrameNanos,
                snapshotNanos,
                preparationNanos,
                metadataNanos,
                preparationTicks,
                metadataEntries,
                variants,
                frames,
                wireBytes));
    }

    void cancel() {
        active = false;
    }
}
