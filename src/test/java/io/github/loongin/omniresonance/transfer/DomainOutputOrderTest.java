// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

final class DomainOutputOrderTest {
    private static final ResourceVariant IRON = () -> new ResourceVariantKey(ResourceTypes.ITEM, new byte[] {1});
    private static final ResourceVariant WATER = () -> new ResourceVariantKey(ResourceTypes.FLUID, new byte[] {2});

    @Test
    void higherPriorityReceivesSixtyBeforeLowerReceivesForty() {
        long[] stock = {100};
        Sink high = new Sink(1, 10, 60, stock), low = new Sink(2, 0, 100, stock);
        DomainOutputOrder order = new DomainOutputOrder(List.of(low, high));
        var cursor = order.cursor(IRON.key());
        assertEquals(high.id(), order.step(IRON, cursor, budget(20)).outputId());
        assertEquals(60, high.received);
        assertEquals(low.id(), order.step(IRON, cursor, budget(20)).outputId());
        assertEquals(40, low.received);
        assertEquals(0, stock[0]);
    }

    @Test
    void highCanConsumeEverythingAndIneligibleHighDoesNotBlockAnotherVariant() {
        long[] stock = {100};
        Sink high = new Sink(1, 10, 100, stock), low = new Sink(2, 0, 100, stock);
        high.onlyType = ResourceTypes.ITEM;
        DomainOutputOrder order = new DomainOutputOrder(List.of(low, high));
        order.step(IRON, order.cursor(IRON.key()), budget(20));
        assertEquals(100, high.received);
        assertEquals(0, low.received);
        stock[0] = 100;
        order.step(WATER, order.cursor(WATER.key()), budget(20));
        assertEquals(100, low.received);
    }

    @Test
    void budgetResumeRechecksHigherCapacityRatherThanRememberingAnOldRefusal() {
        long[] stock = {100};
        Sink high = new Sink(1, 10, 0, stock), low = new Sink(2, 0, 100, stock);
        DomainOutputOrder order = new DomainOutputOrder(List.of(low, high));
        var cursor = order.cursor(IRON.key());
        assertEquals(
                DomainOutputOrder.State.WAITING_BUDGET,
                order.step(IRON, cursor, budget(1)).attempt().state());
        assertEquals(100, stock[0]);
        high.capacity = 100;
        order.step(IRON, cursor, budget(20));
        assertEquals(100, high.received);
        assertEquals(0, low.received);
    }

    @Test
    void equalPriorityRotatesSeparatelyForEachFullVariant() {
        long[] stock = {1};
        Sink a = new Sink(1, 0, 100, stock), b = new Sink(2, 0, 100, stock);
        DomainOutputOrder order = new DomainOutputOrder(List.of(b, a));
        var iron = order.cursor(IRON.key());
        var water = order.cursor(WATER.key());
        assertEquals(a.id(), order.step(IRON, iron, budget(10)).outputId());
        stock[0] = 1;
        assertEquals(a.id(), order.step(WATER, water, budget(10)).outputId());
        stock[0] = 1;
        assertEquals(b.id(), order.step(IRON, iron, budget(10)).outputId());
        assertThrows(IllegalArgumentException.class, () -> order.step(WATER, iron, budget(10)));
        ResourceVariant otherIron = () -> new ResourceVariantKey(ResourceTypes.ITEM, new byte[] {9});
        assertThrows(IllegalArgumentException.class, () -> order.step(otherIron, iron, budget(10)));
        DomainOutputOrder replacement = new DomainOutputOrder(List.of(a, b));
        assertThrows(IllegalArgumentException.class, () -> replacement.step(IRON, iron, budget(10)));
    }

    private static TransferWorkBudget budget(int calls) {
        return new TransferWorkBudget(calls, Long.MAX_VALUE, Long.MAX_VALUE, () -> 0);
    }

    @Test
    void thousandOutputsReadMetadataOnlyDuringPublicationAndEachProbeIsBudgeted() {
        java.util.concurrent.atomic.AtomicInteger metadataReads = new java.util.concurrent.atomic.AtomicInteger();
        java.util.List<DomainOutputOrder.Output> outputs = new java.util.ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            int index = i;
            outputs.add(new DomainOutputOrder.Output() {
                public UUID id() {
                    metadataReads.incrementAndGet();
                    return new UUID(0, index);
                }

                public int priority() {
                    metadataReads.incrementAndGet();
                    return index % 10;
                }

                public DomainOutputOrder.Attempt attempt(ResourceVariant variant, TransferWorkBudget budget) {
                    budget.beforeCall();
                    return new DomainOutputOrder.Attempt(DomainOutputOrder.State.NO_CAPACITY, 0);
                }
            });
        }
        var order = new DomainOutputOrder(outputs);
        assertEquals(2000, metadataReads.get());
        var cursor = order.cursor(IRON.key());
        var full = budget(1001);
        assertEquals(
                DomainOutputOrder.State.NO_CAPACITY,
                order.step(IRON, cursor, full).attempt().state());
        assertEquals(1000, full.calls());
        var limited = budget(7);
        assertEquals(
                DomainOutputOrder.State.WAITING_BUDGET,
                order.step(IRON, cursor, limited).attempt().state());
        assertEquals(7, limited.calls());
        assertEquals(2000, metadataReads.get());
    }

    @Test
    void failedHigherOutputStopsTheAllocationWithoutTryingLower() {
        var high = new DomainOutputOrder.Output() {
            public UUID id() {
                return new UUID(0, 1);
            }

            public int priority() {
                return 10;
            }

            public DomainOutputOrder.Attempt attempt(ResourceVariant variant, TransferWorkBudget budget) {
                return new DomainOutputOrder.Attempt(DomainOutputOrder.State.FAILED, 0);
            }
        };
        long[] stock = {100};
        Sink low = new Sink(2, 0, 100, stock);
        var order = new DomainOutputOrder(List.of(low, high));
        assertEquals(
                DomainOutputOrder.State.FAILED,
                order.step(IRON, order.cursor(IRON.key()), budget(10)).attempt().state());
        assertEquals(0, low.received);
        assertEquals(100, stock[0]);
    }

    @Test
    void freshCursorsAfterResourceRetirementDoNotAlwaysRestartAtTheFirstOutput() {
        long[] stock = {1};
        Sink a = new Sink(1, 0, 100, stock), b = new Sink(2, 0, 100, stock);
        var order = new DomainOutputOrder(List.of(a, b));
        assertEquals(
                a.id(), order.step(IRON, order.cursor(IRON.key()), budget(10)).outputId());
        stock[0] = 1;
        assertEquals(
                b.id(), order.step(IRON, order.cursor(IRON.key()), budget(10)).outputId());
    }

    private static final class Sink implements DomainOutputOrder.Output {
        private final UUID id;
        private final int priority;
        private final long[] stock;
        private long capacity, received;
        private ResourceLocation onlyType;

        private Sink(int id, int priority, long capacity, long[] stock) {
            this.id = new UUID(0, id);
            this.priority = priority;
            this.capacity = capacity;
            this.stock = stock;
        }

        public UUID id() {
            return id;
        }

        public int priority() {
            return priority;
        }

        public DomainOutputOrder.Attempt attempt(ResourceVariant variant, TransferWorkBudget budget) {
            if (onlyType != null && !onlyType.equals(variant.key().typeId()))
                return new DomainOutputOrder.Attempt(DomainOutputOrder.State.NO_CAPACITY, 0);
            budget.beforeCall();
            budget.afterCall();
            long amount = Math.min(stock[0], capacity - received);
            if (amount == 0) return new DomainOutputOrder.Attempt(DomainOutputOrder.State.NO_CAPACITY, 0);
            stock[0] -= amount;
            received += amount;
            return new DomainOutputOrder.Attempt(DomainOutputOrder.State.COMMITTED, amount);
        }
    }
}
