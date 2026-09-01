// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

/** Bounded immutable lifecycle-work queue behavior independent of a live world. */
final class NodeReconciliationQueueTest {
    private static final GlobalPos FIRST = GlobalPos.of(Level.OVERWORLD, new BlockPos(1, 64, 1));
    private static final GlobalPos SECOND = GlobalPos.of(Level.NETHER, new BlockPos(2, 70, 2));

    @Test
    void sharedCapacityDeduplicatesPositionAndChunkKeysInFifoOrder() {
        NodeReconciliationQueue queue = new NodeReconciliationQueue(3);

        assertEquals(NodeReconciliationQueue.OfferResult.ADDED, queue.offerPosition(FIRST));
        assertEquals(NodeReconciliationQueue.OfferResult.DUPLICATE, queue.offerPosition(FIRST));
        assertEquals(NodeReconciliationQueue.OfferResult.ADDED, queue.offerChunk(Level.OVERWORLD, 4, -2));
        assertEquals(NodeReconciliationQueue.OfferResult.ADDED, queue.offerPosition(SECOND));
        assertEquals(NodeReconciliationQueue.OfferResult.OVERFLOW, queue.offerChunk(Level.END, 0, 0));
        assertEquals(3, queue.size());
        assertTrue(queue.hasOverflowed());

        NodeReconciliationQueue.Position first = assertInstanceOf(
                NodeReconciliationQueue.Position.class, queue.poll().orElseThrow());
        assertEquals(FIRST, first.position());
        NodeReconciliationQueue.Chunk second = assertInstanceOf(
                NodeReconciliationQueue.Chunk.class, queue.poll().orElseThrow());
        assertEquals(Level.OVERWORLD, second.dimension());
        assertEquals(4, second.x());
        assertEquals(-2, second.z());
        assertEquals(
                SECOND,
                assertInstanceOf(
                                NodeReconciliationQueue.Position.class,
                                queue.poll().orElseThrow())
                        .position());
        assertTrue(queue.poll().isEmpty());

        assertEquals(NodeReconciliationQueue.OfferResult.ADDED, queue.offerPosition(FIRST));
        assertEquals(1, queue.size());
        queue.clear();
        assertEquals(0, queue.size());
        assertFalse(queue.hasOverflowed());
        assertTrue(queue.poll().isEmpty());
    }

    @Test
    void invalidInputsAndWrongThreadOperationsFailBeforeMutation() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new NodeReconciliationQueue(0));
        NodeReconciliationQueue queue = new NodeReconciliationQueue(2);
        assertThrows(NullPointerException.class, () -> queue.offerPosition(null));
        assertThrows(NullPointerException.class, () -> queue.offerChunk(null, 0, 0));
        assertEquals(0, queue.size());

        try (var executor = Executors.newSingleThreadExecutor()) {
            for (Runnable operation : List.<Runnable>of(
                    () -> queue.offerPosition(FIRST),
                    () -> queue.offerChunk(Level.OVERWORLD, 0, 0),
                    queue::poll,
                    queue::size,
                    queue::hasOverflowed,
                    queue::clear)) {
                ExecutionException failure = assertThrows(
                        ExecutionException.class,
                        () -> executor.submit(operation).get());
                assertTrue(failure.getCause() instanceof IllegalStateException);
            }
        }
        assertEquals(0, queue.size());
    }
}
