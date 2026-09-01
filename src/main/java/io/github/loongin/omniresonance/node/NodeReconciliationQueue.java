// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.node;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.ApiStatus;

/**
 * Server-thread-owned FIFO work set with one combined hard cap for immutable position/chunk keys.
 *
 * <p>Offers deduplicate without changing order. Overflow is a single retained summary bit, not a retry queue or
 * gameplay quota. Poll/clear retain no history, world, chunk, block-entity or server references. Every operation
 * rejects cross-thread access before reading or mutating state; no operation performs simulation or I/O.
 */
@ApiStatus.Internal
public final class NodeReconciliationQueue {
    public static final int PRODUCTION_MAXIMUM = 262144;
    private final Thread owningThread = Thread.currentThread();
    private final int maximum;
    private final ArrayDeque<Work> queue = new ArrayDeque<>();
    private final Set<Work> queued = new HashSet<>();
    private boolean overflowed;

    public enum OfferResult {
        ADDED,
        DUPLICATE,
        OVERFLOW
    }

    public sealed interface Work permits Position, Chunk {}

    public record Position(GlobalPos position) implements Work {
        public Position {
            Objects.requireNonNull(position, "position");
        }
    }

    public record Chunk(ResourceKey<Level> dimension, int x, int z) implements Work {
        public Chunk {
            Objects.requireNonNull(dimension, "dimension");
        }
    }

    NodeReconciliationQueue(int maximum) {
        if (maximum < 1) {
            throw new IllegalArgumentException("Reconciliation queue maximum must be positive");
        }
        this.maximum = maximum;
    }

    public static NodeReconciliationQueue production() {
        return new NodeReconciliationQueue(PRODUCTION_MAXIMUM);
    }

    public OfferResult offerPosition(GlobalPos position) {
        requireOwningThread();
        return offer(new Position(position));
    }

    public OfferResult offerChunk(ResourceKey<Level> dimension, int x, int z) {
        requireOwningThread();
        return offer(new Chunk(dimension, x, z));
    }

    public Optional<Work> poll() {
        requireOwningThread();
        Work work = queue.pollFirst();
        if (work != null) {
            queued.remove(work);
        }
        return Optional.ofNullable(work);
    }

    public int size() {
        requireOwningThread();
        return queue.size();
    }

    public boolean hasOverflowed() {
        requireOwningThread();
        return overflowed;
    }

    public void clear() {
        requireOwningThread();
        queue.clear();
        queued.clear();
        overflowed = false;
    }

    private OfferResult offer(Work work) {
        if (queued.contains(work)) {
            return OfferResult.DUPLICATE;
        }
        if (queue.size() >= maximum) {
            overflowed = true;
            return OfferResult.OVERFLOW;
        }
        queued.add(work);
        queue.addLast(work);
        return OfferResult.ADDED;
    }

    private void requireOwningThread() {
        if (Thread.currentThread() != owningThread) {
            throw new IllegalStateException("Reconciliation queue accessed outside its owning server thread");
        }
    }
}
